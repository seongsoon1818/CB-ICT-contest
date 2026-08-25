# AnimalGuard AI Server

Python 3.11과 FastAPI 기반의 Mock AI Server입니다. JPEG 한 프레임을 검증하고 이미지 크기를 추출한 뒤, Detector-only Mock 탐지 결과를 AnimalGuard Detection Event v1으로 만들어 Spring Backend에 전달합니다.

실제 모델 추론, 위험도 계산, 이미지 저장, 재시도는 수행하지 않습니다.

## 실행 준비

```bash
cd ai-server
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
```

테스트까지 실행하는 개발 환경은 테스트 전용 의존성을 설치합니다.

```bash
python -m pip install -r requirements-dev.txt
```

필수 환경변수:

```bash
export BACKEND_BASE_URL=http://localhost:8080
```

Mock 결과를 비우려면 선택 환경변수를 설정합니다. 기본값은 `detected`입니다.

```bash
export MOCK_RESULT=empty
```

서버 실행:

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 상태 확인 API

### `GET /health/live`

프로세스가 요청을 처리할 수 있으면 `200 OK`를 반환합니다.

```json
{"status": "UP"}
```

### `GET /health/ready`

Mock inference가 초기화되고 `BACKEND_BASE_URL`이 설정돼 있으면 `200 OK`를 반환합니다. Readiness 검사에서 Backend 네트워크 요청은 하지 않습니다. 환경변수가 없으면 `503 Service Unavailable`입니다.

```json
{"status": "READY", "inference": "mock"}
```

## 프레임 분석 API

### `POST /api/v1/analyze`

`multipart/form-data`의 다음 필드를 받습니다.

- `frame`: 최대 5 MiB·40,000,000 픽셀의 `image/jpeg` 파일
- `cameraId`: 영문 또는 숫자로 시작하는 최대 64자의 식별자
- `capturedAt`: timezone을 포함한 ISO 8601 / RFC 3339 시간

호출 예시:

```bash
curl -X POST http://localhost:8000/api/v1/analyze \
  -F 'frame=@frame.jpg;type=image/jpeg' \
  -F 'cameraId=cam-001' \
  -F 'capturedAt=2026-08-25T02:00:00+09:00'
```

AI Server는 매 요청마다 UUID v4 `eventId`를 생성하고, 이미지 바이너리 없이 Detection Event v1 JSON만 다음 endpoint로 한 번 전송합니다.

```text
POST {BACKEND_BASE_URL}/api/v1/detection/events
```

Detector-only 계약에 따라 `classifierVersion`, `trackId`, `classificationConfidence`는 key를 유지한 채 `null`로 전송됩니다. Backend가 `201 Created`로 반환한 위험도 응답은 `/api/v1/analyze`의 `200 OK` body로 그대로 전달됩니다.

`/api/v1/analyze`는 호출자 관점에서 분석과 Backend 처리가 끝난 결과를 반환하므로 `200 OK`를 사용합니다. Backend 내부 리소스 생성 status인 `201 Created`를 그대로 전달하지 않습니다.

Backend `409`는 호출자에게 `409 Conflict`로 전달합니다. Backend `400`, `5xx`, 연결 실패 또는 5초 timeout은 내부 상세와 URL을 노출하지 않는 `502 Bad Gateway`로 변환합니다. 자동 재시도는 하지 않습니다.

### 오류 응답

| Status | 의미 |
| --- | --- |
| 400 | 빈 파일, 실제 JPEG가 아닌 바이트, `image/jpeg`가 아닌 media type |
| 413 | 파일이 5 MiB를 초과하거나 이미지가 40,000,000 픽셀을 초과함 |
| 422 | 필수 form field, `cameraId`, timezone 포함 `capturedAt` 검증 실패 |
| 409 | Backend가 중복 `eventId`를 반환함 |
| 502 | Backend 400·5xx, 연결 실패 또는 timeout |
| 503 | `BACKEND_BASE_URL` 또는 Backend client가 설정되지 않음 |

Content-Type 비교 시 대소문자와 `;` 뒤 media type parameter는 정규화합니다. 비표준 `image/jpg`와 `application/octet-stream`은 지원하지 않습니다.

## Mock 동작

- `MOCK_RESULT=detected`: 실제 이미지 크기를 기준으로 중앙 bbox를 계산하고 `MAGPIE` 탐지 한 개를 생성합니다.
- `MOCK_RESULT=empty`: `detections: []`를 생성합니다.
- detector version은 `mock-animal-detector-v1`입니다.
- 전체 디코딩 전에 40,000,000 픽셀 상한을 적용합니다. 8K UHD 이미지는 허용 범위에 포함됩니다.

이 구현에는 YOLO/PyTorch 모델, classifier, Raspberry Pi 카메라, MQTT/GPIO, 이미지 저장, queue 또는 위험도 판단이 포함되지 않습니다. 위험도와 DeviceCommand는 Backend가 결정합니다.

## 테스트

```bash
pytest
python -m pytest
python -c "from app.main import app; print(app.title)"
```
