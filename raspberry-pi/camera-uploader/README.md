# AnimalGuard Raspberry Pi 카메라 업로더

`raspberry-pi/camera-uploader`는 AnimalGuard의 유일한 정식 카메라 프레임 송신
컴포넌트입니다. Raspberry Pi 카메라에서 JPEG를 계속 캡처하고 AI Server의
`POST /api/v1/analyze`에 multipart field `frame`, `cameraId`, `capturedAt`으로
전송합니다.

## 최신 프레임 정책

캡처와 업로드는 서로 다른 thread에서 동작합니다.

```text
camera capture producer (목표 30 FPS)
                 |
                 v
        LatestFrameSlot (용량 1)
                 |
                 v
         single upload worker
                 |
                 v
      POST /api/v1/analyze
```

- slot은 미전송 프레임을 최대 한 장만 보관합니다.
- 새 프레임은 이전 미전송 프레임을 덮어씁니다.
- upload worker와 HTTP 요청은 각각 하나뿐입니다.
- worker가 읽은 sequence는 결과와 관계없이 소비되며 같은 frame을 자동 retry하지
  않습니다.
- transient 오류 뒤 짧게 backoff하는 동안에도 producer는 계속 캡처합니다. worker는
  backoff가 끝난 시점의 최신 frame을 선택합니다.
- `capturedAt`은 업로드 시작 시각이 아니라 JPEG 캡처가 끝난 직후의 timezone-aware
  UTC 시각입니다.

무제한 queue, 디스크 spool, 여러 동시 HTTP 요청은 사용하지 않습니다.

## 캡처 FPS와 분석 FPS

기본 `CAPTURE_FPS=30`은 카메라 캡처 목표값입니다. AI Server가 초당 10개 요청만
처리할 수 있다면 upload FPS도 약 10이고, 처리 중 들어온 나머지 프레임은 최신
프레임으로 교체됩니다. `overwritten`은 오류가 아니라 과거 프레임 누적을 막는 의도된
backpressure입니다.

Picamera2에는 목표 `FrameRate`를 요청하지만 카메라 모드, 해상도와 Raspberry Pi
성능에 따라 실제 캡처 속도가 낮을 수 있습니다. 주기적 로그의
`effectiveCaptureFps`와 실제 hardware smoke로 확인하기 전에는 실제 30 FPS 달성을
주장하지 않습니다. 실제 분석 FPS는 AI Server와 모델 처리 속도에 의해 결정됩니다.

## 요구 환경

- Python 3.11 이상
- `httpx`
- 실제 카메라 사용 시 Raspberry Pi OS의 Picamera2

Picamera2는 Raspberry Pi OS 카메라 스택에 결합된 시스템 패키지이므로
`requirements.txt`에 포함하지 않습니다.

```bash
sudo apt update
sudo apt install python3-picamera2 python3-venv
python3 -m venv --system-site-packages .venv
.venv/bin/python -m pip install -r requirements.txt
```

저장소에서 USB webcam 운영 경로를 가리키는 문서나 배포 참조가 확인되지 않아
OpenCV source는 포함하지 않습니다. 실제 운영 근거가 생기면 `cv2` lazy import와
Raspberry Pi OS의 `python3-opencv` 설치 방식을 별도 검토해야 합니다.

## 설정

`.env.example`을 배포 환경용 파일로 복사한 뒤 shell export 또는 systemd
`EnvironmentFile`로 전달합니다. 프로그램이 `.env` 파일을 직접 읽지는 않습니다.

| 환경 변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `AI_SERVER_BASE_URL` | 예 | 없음 | AI Server base URL |
| `CAMERA_ID` | 예 | 없음 | `^[A-Za-z0-9][A-Za-z0-9._-]*$`, 최대 64자 |
| `CAPTURE_FPS` | 아니요 | `30` | 0보다 크고 30 이하인 목표 캡처 FPS |
| `HTTP_TIMEOUT_SECONDS` | 아니요 | `10` | 0보다 큰 HTTP timeout |
| `UPLOAD_TRANSIENT_BACKOFF_SECONDS` | 아니요 | `1` | transient 오류 뒤 대기, 0 이상 |
| `STATS_INTERVAL_SECONDS` | 아니요 | `10` | 0보다 큰 집계 로그 주기 |
| `CAMERA_SOURCE` | 아니요 | `picamera2` | `picamera2` 또는 `file` |
| `FRAME_WIDTH` | 아니요 | `1280` | 캡처 폭 |
| `FRAME_HEIGHT` | 아니요 | `720` | 캡처 높이 |
| `TEST_FRAME_PATH` | file 사용 시 | 없음 | 로컬 smoke용 JPEG 경로 |

기존 `FRAME_INTERVAL_SECONDS`가 환경에 남아 있으면 조용히 무시하지 않고
`FRAME_INTERVAL_SECONDS is deprecated; use CAPTURE_FPS` 시작 오류를 반환합니다.
배포할 때는 새 애플리케이션을 재시작하기 전에
`/etc/animalguard/camera-uploader.env`에서 `FRAME_INTERVAL_SECONDS`를 제거하고
`CAPTURE_FPS`로 교체해야 합니다. 환경 파일을 나중에 변경하면 systemd가 먼저
실패 재시작과 start limit에 도달할 수 있습니다.

```bash
set -a
. ./.env
set +a
.venv/bin/python -m animalguard_camera.main
```

## 카메라 없이 로컬 smoke

AI Server 계약과 같은 local endpoint와 JPEG 파일이 있을 때 `file` source를 사용할
수 있습니다. file source는 매 캡처마다 파일을 다시 읽습니다.

```bash
export AI_SERVER_BASE_URL=http://127.0.0.1:8000
export CAMERA_ID=cam-local-smoke
export CAMERA_SOURCE=file
export TEST_FRAME_PATH=/tmp/animalguard-frame.jpg
export CAPTURE_FPS=30
.venv/bin/python -m animalguard_camera.main
```

이 실행은 송신 계약과 backpressure를 확인할 수 있지만 실제 Raspberry Pi 카메라
성능이나 실제 AI 분석 FPS를 증명하지 않습니다.

## 결과와 실패 정책

| 결과 | 현재 frame | 후속 행동 |
| --- | --- | --- |
| `SUCCESS` (2xx) | 소비 | uploaded 증가 후 최신 frame 확인 |
| `CLIENT_ERROR` (400, 409, 413, 422 등) | 폐기 | client error 증가 후 최신 frame 확인 |
| `CONFIGURATION_ERROR` (401, 403) | 폐기 | 전체 서비스 stop, resource 정리, non-zero 종료 |
| `TRANSIENT_ERROR` (timeout, connection, 5xx) | 폐기 | 고정 backoff 후 최신 frame 확인 |
| capture failure | 생성 안 함 | capture error 증가 후 다음 캡처 주기 계속 |

어떤 결과에서도 같은 `CapturedFrame`을 자동 재전송하지 않습니다. 응답 로그에는
status와 JSON key 또는 body 크기 요약만 남기며 JPEG bytes, credential, 전체 Backend
응답은 기록하지 않습니다.

## 집계 로그

frame마다 INFO 성공 로그를 남기지 않고 `STATS_INTERVAL_SECONDS`마다 다음 누적값과
실효 속도를 INFO로 기록합니다.

- `captured`
- `uploaded`
- `overwritten`
- `captureErrors`
- `uploadClientErrors`
- `uploadTransientErrors`
- `effectiveCaptureFps`
- `effectiveUploadFps`
- `latestFrameAgeMs`

`effectiveCaptureFps`와 `effectiveUploadFps`는 프로세스 시작 이후 누적 평균이 아니라
직전 집계 로그 이후 구간의 처리량입니다. 누적 counter는 초기화하지 않습니다.
`latestFrameAgeMs`는 마지막 성공 업로드가 아니라 마지막 캡처의 나이이므로, 업로드
정체는 구간 `effectiveUploadFps`, `uploadTransientErrors`와 함께 판단해야 합니다.

## 종료

`SIGINT` 또는 `SIGTERM`을 받으면 stop event를 설정하고 slot waiter를 깨운 뒤 producer와
worker를 join합니다. 진행 중인 HTTP 요청은 설정된 timeout 안에서 끝나며, thread가
끝난 다음 HTTP client와 camera source를 닫습니다. 종료 요청 뒤 새 upload는 시작하지
않습니다. 제한 시간이 지나도 worker가 살아 있으면 해당 worker가 소유한 HTTP client
또는 camera source를 동시에 닫지 않고 non-zero로 종료합니다. 두 worker는 이 실패
경로에서 프로세스 종료를 막지 않도록 daemon으로 생성됩니다.

## 다음 동물 관찰 State Machine에 미치는 영향

- frame sequence 일부는 의도적으로 분석되지 않으므로 연속 sequence를 가정하면 안
  됩니다.
- 관찰 지속 시간은 업로드 시각이 아니라 `capturedAt`을 기준으로 계산해야 합니다.
- 단일 미탐지 frame보다 최신 처리 frame과 absence grace를 우선해야 합니다.

## 테스트

실제 카메라와 실제 AI Server 없이 실행됩니다.

```bash
python3 -m venv .venv-test
.venv-test/bin/python -m pip install -r requirements-dev.txt
.venv-test/bin/python -m pytest
.venv-test/bin/python -m compileall -q animalguard_camera tests
.venv-test/bin/ruff check animalguard_camera tests
```

bare `pytest`와 `python -m pytest`는 같은 `pyproject.toml` 설정을 사용합니다. Picamera2
경계는 fake module로 검증합니다. 실제 Raspberry Pi 카메라 30초 실행, 실제 AI Server
분석, 실제 systemd 설치는 저장소 테스트에 포함되지 않습니다
(`HARDWARE_30FPS_SMOKE=NOT_RUN`).

## systemd 설치 예시

`systemd/animalguard-camera-uploader.service`는 다음 경로와 사용자를 가정합니다.

- 애플리케이션: `/opt/animalguard/camera-uploader`
- 환경 파일: `/etc/animalguard/camera-uploader.env`
- 서비스 사용자: `animalguard`
- 카메라 supplementary group: `video`

```bash
sudo cp systemd/animalguard-camera-uploader.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now animalguard-camera-uploader.service
sudo systemctl status animalguard-camera-uploader.service
```

unit은 실패 시 5초 뒤 재시작하되 60초 동안 3회로 시작을 제한하고 `SIGTERM`으로
정상 종료를 요청합니다. AI Server가 401/403으로 설정을 거부하면 프로세스가
non-zero로 종료되므로 제한 횟수 재시도 뒤 unit이 failed 상태로 남습니다. 실제
장비에서 사용자와 `/dev/video*`, `/dev/dma_heap/*` 권한을 확인해야 합니다. 실제
설치나 enable은 이 구현 범위에 포함하지 않습니다.
