# AnimalGuard Raspberry Pi 카메라 업로더

Raspberry Pi 카메라의 최신 JPEG 한 프레임을 캡처해 AnimalGuard AI Server의
`POST /api/v1/analyze`로 전송합니다. 기본 주기는 1초이며, 한 요청이 끝나거나
timeout이 발생한 뒤에만 다음 프레임을 캡처합니다.

## 동작 원칙

한 주기는 다음 순서로 동기 실행됩니다.

1. JPEG 한 프레임 캡처
2. timezone을 포함한 UTC `capturedAt` 생성
3. `frame`, `cameraId`, `capturedAt` multipart 전송
4. 응답 또는 timeout 뒤 남은 interval 대기
5. 다음 주기에 새 프레임 캡처

프레임 queue, 동시 요청, retry worker, 디스크 spool을 사용하지 않습니다. 요청이
실패한 프레임은 폐기하며 같은 JPEG를 다시 보내지 않습니다. 요청 시간이 interval을
넘으면 별도로 기다리지 않고 다음 최신 프레임을 캡처합니다.

## 요구 환경

- Python 3.11 이상
- `httpx`
- 실제 카메라 사용 시 Raspberry Pi OS의 Picamera2

Picamera2는 Raspberry Pi OS와 카메라 스택에 결합된 시스템 패키지이므로
`requirements.txt`에 포함하지 않습니다. Raspberry Pi OS에서 다음과 같이 별도로
설치합니다.

```bash
sudo apt update
sudo apt install python3-picamera2 python3-venv
```

Picamera2 시스템 패키지를 가상환경에서 사용할 수 있도록
`--system-site-packages`를 지정합니다.

```bash
python3 -m venv --system-site-packages .venv
.venv/bin/python -m pip install -r requirements.txt
```

## 설정

`.env.example`을 배포 환경용 파일로 복사한 뒤 값을 설정합니다. 프로그램이 `.env`
파일을 직접 읽지는 않으므로 shell에서 export하거나 systemd `EnvironmentFile`로
전달해야 합니다.

| 환경 변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `AI_SERVER_BASE_URL` | 예 | 없음 | AI Server base URL |
| `CAMERA_ID` | 예 | 없음 | `^[A-Za-z0-9][A-Za-z0-9._-]*$`, 최대 64자 |
| `FRAME_INTERVAL_SECONDS` | 아니요 | `1.0` | 0보다 큰 캡처 주기 |
| `HTTP_TIMEOUT_SECONDS` | 아니요 | `10` | 0보다 큰 HTTP timeout |
| `CAMERA_SOURCE` | 아니요 | `picamera2` | `picamera2` 또는 `file` |
| `TEST_FRAME_PATH` | file 사용 시 | 없음 | 로컬 smoke용 JPEG 경로 |
| `FRAME_WIDTH` | 아니요 | `1280` | Picamera2 캡처 폭 |
| `FRAME_HEIGHT` | 아니요 | `720` | Picamera2 캡처 높이 |

실행 예시는 다음과 같습니다.

```bash
set -a
. ./.env
set +a
.venv/bin/python -m animalguard_camera.main
```

## 카메라 없이 로컬 smoke

AI Server가 실행 중이고 로컬에 JPEG 파일이 있을 때 file source를 사용할 수
있습니다. file source는 각 주기마다 파일을 다시 읽으므로 파일이 교체되면 다음
주기에 최신 bytes를 전송합니다.

```bash
export AI_SERVER_BASE_URL=http://127.0.0.1:8000
export CAMERA_ID=cam-local-smoke
export CAMERA_SOURCE=file
export TEST_FRAME_PATH=/tmp/animalguard-frame.jpg
export FRAME_INTERVAL_SECONDS=1.0
.venv/bin/python -m animalguard_camera.main
```

종료는 `Ctrl-C` 또는 `SIGTERM`으로 요청합니다. 종료 시 반복 루프가 멈추고 HTTP
client와 camera source를 닫습니다.

## 오류 처리

- 2xx: 성공을 기록하고 다음 주기로 진행
- 400, 413, 422: status를 기록하고 현재 프레임 폐기
- 401, 403: 설정 오류를 한 번 기록하고 현재 프레임 폐기
- 5xx, timeout, connection error: warning을 기록하고 현재 프레임 폐기

응답 로그에는 status와 JSON key 또는 body 크기 요약만 남깁니다. 이미지 bytes,
비밀번호, 내부 stack trace는 기록하지 않습니다.

## 테스트

실제 카메라나 실제 AI Server 없이 실행됩니다.

```bash
python3 -m venv .venv-test
.venv-test/bin/python -m pip install -r requirements-dev.txt
.venv-test/bin/python -m pytest
.venv-test/bin/python -c "import animalguard_camera.main"
```

Picamera2 경계는 fake 모듈로 검증합니다. 실제 Raspberry Pi 카메라 캡처와 실제
AI Server까지의 hardware smoke test는 이 저장소 테스트에서 실행하지 않았습니다
(`NOT_RUN`).

## systemd 설치 예시

`systemd/animalguard-camera-uploader.service`는 다음 일반 경로를 가정한 예시입니다.

- 애플리케이션: `/opt/animalguard/camera-uploader`
- 환경 파일: `/etc/animalguard/camera-uploader.env`
- 서비스 사용자: `animalguard`

운영 장비의 사용자와 경로에 맞게 unit을 검토한 뒤 관리자가 설치합니다.

```bash
sudo cp systemd/animalguard-camera-uploader.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now animalguard-camera-uploader.service
sudo systemctl status animalguard-camera-uploader.service
```

이 unit은 실패 시 5초 뒤 재시작하며 `SIGTERM`으로 정상 종료를 요청합니다. unit을
실제 장비에 설치하거나 enable하는 작업은 이 구현 범위에 포함하지 않습니다.

SSH `22/TCP`는 배포, 로그 확인, 서비스 재시작 용도입니다. VNC `5090/TCP`는 필요할
때만 사용하는 선택적 GUI 관리 경로이며 업로더 실행에 필요하지 않습니다.
