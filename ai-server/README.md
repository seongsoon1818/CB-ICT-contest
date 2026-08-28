# AnimalGuard AI Server

Python 3.11과 FastAPI 기반의 AI Server입니다. JPEG 한 프레임을 검증해 독립적인 RGB 픽셀 이미지로 디코딩한 뒤, 선택된 inference engine의 결과와 metadata를 AnimalGuard Detection Event v1으로 만들어 Spring Backend에 전달합니다.

기본 Mock engine과 모델 번들 검증·lifecycle 경계를 제공하며, `ultralytics-8.4.125` runtime과 `ultralytics-yolo-detect-v1` output adapter 조합의 detector-only 모델을 지원합니다. 위험도 계산, 이미지 저장, 재시도는 수행하지 않습니다.

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

inference mode 기본값은 `mock`입니다.

```bash
export INFERENCE_MODE=mock
```

Mock 결과를 비우려면 선택 환경변수를 설정합니다. 기본값은 `detected`입니다.

```bash
export MOCK_RESULT=empty
```

`model` mode는 시작 시 bundle을 검증하며 `MODEL_BUNDLE_DIR`가 필수입니다.

```bash
export INFERENCE_MODE=model
export MODEL_BUNDLE_DIR=/opt/animalguard/models/current
```

지원 조합은 manifest의 `runtime=ultralytics-8.4.125`, `outputAdapter=ultralytics-yolo-detect-v1`입니다. 둘 중 하나라도 다르거나 bundle·checkpoint 검증 또는 모델 로딩이 실패하면 ready/analyze를 503으로 유지하며 Mock으로 fallback하지 않습니다. 제공된 11-class checkpoint의 추적 가능한 metadata와 bundle 조립 절차는 `models/README.md`에 있습니다.

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

inference engine이 준비되고 `BACKEND_BASE_URL`이 설정돼 있으면 `200 OK`를 반환합니다. Readiness 검사에서 Backend 네트워크 요청은 하지 않습니다. Backend URL이 없거나 model bundle/runtime 로딩이 실패하면 내부 경로나 exception을 노출하지 않는 `503 Service Unavailable`입니다. `/health/live`는 model 로딩 실패와 무관하게 프로세스가 동작하면 `200 OK`를 유지합니다.

```json
{
  "status": "READY",
  "inference": "mock",
  "runtime": "mock",
  "bundleVersion": null,
  "detectorVersion": "mock-animal-detector-v1",
  "classifierVersion": null
}
```

실제 Wildlife bundle이 준비된 model mode에서는 다음 metadata가 노출됩니다.

```json
{
  "status": "READY",
  "inference": "model",
  "runtime": "ultralytics-8.4.125",
  "bundleVersion": "2026-08-24.7e4f5549",
  "detectorVersion": "sha256:7e4f5549f40b844f2156c31739894e1a8cbbd33f4ef59d6d3f0b25e555fc4572",
  "classifierVersion": null
}
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

AI Server는 매 요청마다 UUID v4 `eventId`를 생성하고, engine metadata의 `detectorVersion`·`classifierVersion`과 추론 결과를 사용합니다. 이미지 바이너리 없이 Detection Event v1 JSON만 다음 endpoint로 한 번 전송합니다. 디코딩된 RGB 이미지는 요청 처리가 끝나면 명시적으로 닫습니다.

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
| 503 | `BACKEND_BASE_URL`/Backend client가 없거나 inference engine이 준비되지 않음 |

Content-Type 비교 시 대소문자와 `;` 뒤 media type parameter는 정규화합니다. 비표준 `image/jpg`와 `application/octet-stream`은 지원하지 않습니다.

## Mock 동작

- `MOCK_RESULT=detected`: 실제 이미지 크기를 기준으로 중앙 bbox를 계산하고 `MAGPIE` 탐지 한 개를 생성합니다.
- `MOCK_RESULT=empty`: `detections: []`를 생성합니다.
- detector version은 `mock-animal-detector-v1`입니다.
- 전체 디코딩 전에 40,000,000 픽셀 상한을 적용합니다. 8K UHD 이미지는 허용 범위에 포함됩니다.

Mock engine에는 YOLO/PyTorch 모델이 포함되지 않습니다. Model engine은 detector 결과만 Detection Event로 변환하며 classifier, Raspberry Pi 카메라, MQTT/GPIO, 이미지 저장, queue 또는 위험도 판단을 포함하지 않습니다. 위험도와 DeviceCommand는 Backend가 결정합니다.

AI팀 전달물 중 별도 FastAPI API, SQLite history, ROI 필터와 `risk_rules.json`의 OFF/WEAK/MEDIUM/STRONG 선택 로직은 연결하지 않습니다. 기존 AI Server HTTP 계약을 유지하고, 물리 동작 정책의 단일 소스는 계속 Backend입니다.

Ultralytics가 반환한 post-NMS box 중 confidence 내림차순 상위 100개만 Detection Event에 포함합니다. bbox는 JPEG 경계로 clamp하고 면적이 0이면 제외합니다. class map에 없는 runtime class ID, malformed result, 비정상 confidence 또는 predict 실패는 빈 탐지나 `UNKNOWN`으로 바꾸지 않고 inference 오류로 처리합니다.

## 모델 로딩 lifecycle

- engine 생성과 model bundle 검증은 FastAPI lifespan 시작 시 한 번만 실행합니다.
- 요청마다 manifest, class map 또는 model file을 다시 읽지 않습니다.
- `INFERENCE_MODE=model` 로딩 실패 시 오류를 로그에 기록하고 ready/analyze를 503으로 유지합니다.
- 로딩 실패를 숨기기 위한 Mock fallback은 하지 않습니다.
- 프로세스 종료 시 engine의 `close()`를 호출합니다.
- 실행 중 hot reload와 file watcher는 없습니다. 모델 교체는 `models/README.md`의 bundle symlink 변경 후 AI Server를 재시작합니다.

## 테스트

```bash
pytest
python -m pytest
python -c "from app.main import app; print(app.title)"
```

저장소 Mock E2E는 `MOCK_RESULT=detected`와 `MOCK_RESULT=empty`인 AI Server 두 개를 격리 port에 기동해 같은 `/api/v1/analyze` multipart 계약으로 positive/empty observation을 만듭니다.

```bash
bash scripts/e2e/animalguard_mock_e2e.sh
```

이 경로는 JPEG decode, MockInference, Backend 전달과 후속 MQTT 상태 전이를 검증하지만 실제 model bundle이나 추론 품질을 검증하지 않습니다. Model adapter 단위 테스트와 외부 `.pt`를 사용한 model-mode smoke는 별도 증거이며, synthetic JPEG smoke만으로 실제 야생동물 정확도나 작동 정책을 승인하지 않습니다. 남은 입력은 `models/README.md`와 `docs/SOFTWARE_READY_CHECKLIST.md`에서 확인합니다.
