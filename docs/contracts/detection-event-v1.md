# AnimalGuard Detection Event v1 Contract

## Endpoint

AI Server는 다음 Backend endpoint로 이미지 단위 탐지 결과를 보냅니다.

```text
POST /api/v1/detection/events
```

JSON Schema: `detection-event-v1.schema.json`

## Request example

~~~json
{
  "eventId": "15356786-9588-4db4-a0fe-f8acd6300868",
  "cameraId": "cam-001",
  "capturedAt": "2026-08-24T08:00:00Z",
  "image": {
    "width": 1280,
    "height": 720
  },
  "model": {
    "detectorVersion": "animal-detector-v1",
    "classifierVersion": null
  },
  "detections": [
    {
      "detectionId": "det-001",
      "trackId": null,
      "classCode": "WILD_BOAR",
      "detectionConfidence": 0.96,
      "classificationConfidence": null,
      "bbox": {
        "x": 100,
        "y": 180,
        "width": 260,
        "height": 190
      }
    }
  ]
}
~~~

## Field contract

| Field | Contract |
| --- | --- |
| eventId | UUID. Backend의 중복 수신 방지 키입니다. |
| cameraId | 영문·숫자로 시작하고 영문·숫자·점·underscore·hyphen을 사용하는 안정적인 카메라 식별자입니다. |
| capturedAt | timezone을 명시한 ISO 8601 / RFC 3339 date-time입니다. |
| image.width, image.height | 양의 이미지 픽셀 크기입니다. |
| model | 필수 모델 메타데이터 객체입니다. |
| model.detectorVersion | 필수 detector 모델 버전입니다. |
| model.classifierVersion | 키는 필수이고 값은 nullable인 classifier 모델 버전입니다. detector만 사용하면 null입니다. |
| detections | 0개 이상, 최대 100개의 유해동물 탐지 배열입니다. 결과가 없으면 `detections: []`입니다. |
| detectionId | 이벤트 안에서 고유해야 하는 탐지 식별자입니다. |
| trackId | 키는 필수이고 값은 MVP에서 nullable인 비음수 추적 ID입니다. |
| classCode | 대문자·숫자·underscore 코드입니다. 알 수 없는 대상은 `UNKNOWN`입니다. |
| detectionConfidence | 0 이상 1 이하인 detector confidence입니다. |
| classificationConfidence | 키는 필수이고 값은 nullable인 classifier confidence입니다. |
| bbox | 이미지 픽셀 기준의 필수 x, y, width, height 사각형입니다. |
| bbox.x, bbox.y | 좌상단 원점을 사용하는 비음수 픽셀 좌표입니다. |
| bbox.width, bbox.height | 양의 픽셀 크기입니다. |

구조 검증 예시 classCode는 `MAGPIE`, `SPARROW`, `WILD_BOAR`, `WATER_DEER`, `RODENT`, `UNKNOWN`입니다. 이 목록은 실제 운영 대상 클래스나 위험 점수를 확정하지 않습니다.

`classificationConfidence`는 detector+classifier pipeline에서만 제공됩니다.
`classifierVersion`이 null이면 `classificationConfidence`도 null입니다.
`classifierVersion`이 존재하면 `classificationConfidence`는 0 이상 1 이하의 값이어야 합니다.

## Backend-owned decisions

- AI는 `insideField`를 보내지 않으며 현재 Backend도 ROI를 평가하지 않습니다.
- 이벤트별 class score는 탐지들의 설정 점수 중 최대값을 한 번만 적용합니다.
- confidence score는 detector-only에서는 detectionConfidence가 설정 threshold 이상인 탐지가 있으면 한 번 적용하고, detector+classifier에서는 동일 탐지의 detectionConfidence와 classificationConfidence가 모두 threshold 이상일 때 한 번 적용합니다.
- 이벤트 하나에는 탐지를 최대 100개까지 포함할 수 있고 detectionId는 이벤트 안에서 중복될 수 없습니다.
- 설정에 없는 classCode는 0점이며 `UNKNOWN`의 기본 점수도 0점입니다.
- trackId는 nullable이고 단일 이미지 MVP에서 체류 시간을 계산하지 않습니다.
- 이벤트에는 메타데이터와 탐지 결과만 포함하며 모델 파일, 이미지 바이너리, Base64 이미지를 넣지 않습니다.
- eventId 중복 검사는 두 번째 이벤트나 탐지를 저장하기 전에 적용하며 중복 요청은 `409 Conflict`입니다.

## Time and coordinate rules

- 모든 시간 값은 timezone을 포함한 ISO 8601 / RFC 3339 JSON date-time입니다.
- bbox는 좌상단 원점의 정수 이미지 픽셀 좌표입니다.
- 네 bbox 필드는 모두 필수이며 width와 height는 1 이상입니다.
- image width와 height는 양수이고 x와 y는 비음수입니다.
- bbox 전체는 image width와 height 경계 안에 있어야 합니다.

## Empty event behavior

탐지 결과가 없는 유효한 이미지는 정상 이벤트이며 `detections: []`를 사용합니다. Backend는 이를 저장하고 LOW 위험도로 판단합니다.

## Compatibility and validation

Detection Event v1은 현재 producer가 없으므로 직접 일반화했습니다. 구버전 `birds`와 `speciesCode` payload는 지원하지 않으며 별도의 v2나 호환 adapter를 제공하지 않습니다. JSON Schema는 필수 구조와 primitive 제약뿐 아니라 classifierVersion과 classificationConfidence의 연동 규칙을 표현하는 machine-readable source이며 producer와 consumer는 v1 변경 시 함께 갱신해야 합니다.

## Response contract

정상 요청은 `201 Created`와 다음 구조를 반환합니다.

~~~json
{
  "eventId": "15356786-9588-4db4-a0fe-f8acd6300868",
  "riskScore": 50,
  "riskLevel": "MEDIUM",
  "commandOutcome": "NOT_REQUESTED",
  "commandBlockers": []
}
~~~

`commandOutcome`과 `commandBlockers`는 모든 정상 응답에 포함됩니다. 현재 Backend에는 동물 관찰 state machine과 automatic response policy가 없으므로 위험도와 관계없이 Detection Event와 RiskDecision까지만 저장하며, 이 endpoint는 항상 `NOT_REQUESTED`와 빈 `commandBlockers`를 반환합니다. HIGH만 보고 `SOUND_ALERT` 또는 `DETERRENT_FULL`을 임의로 선택하지 않습니다.

`CREATED`와 `SUPPRESSED`는 향후 명시적인 automatic response policy가 `DeviceCommandCreationService`에 semantic command type과 reason을 전달할 때 사용할 예약 응답 형태입니다. 현재 endpoint가 생성하는 값은 아닙니다.

| commandOutcome | 의미 | commandId | commandBlockers |
| --- | --- | --- | --- |
| `NOT_REQUESTED` | 현재 policy가 command type을 선택하지 않음 | 생략 | 빈 배열 |
| `CREATED` | 향후 policy가 요청한 DeviceCommand가 DB에 생성됨 | 포함 | 빈 배열 |
| `SUPPRESSED` | 향후 policy의 요청이 운영 조건으로 억제됨 | 생략 | 비어 있지 않은 배열 |

향후 `SUPPRESSED` blocker는 `ACTUATION_DISABLED`, `RISK_POLICY_UNCONFIRMED`, `CAMERA_DEVICE_MAPPING_EMPTY`, `MQTT_PUBLISHER_NOT_READY`, `CAMERA_UNMAPPED`, `COOLDOWN_ACTIVE`입니다. Global preflight blocker가 여러 개면 고정된 순서로 모두 포함합니다. DeviceCommand가 생성되면 기존 호환 필드인 `commandId`가 포함됩니다. 명령이 생성되지 않으면 `commandId` 필드는 null이 아니라 응답에서 생략됩니다. suppression 판정은 응답과 Backend 구조화 로그에만 노출하며 DB에는 저장하지 않습니다.

## Error contract

| HTTP status | code | Meaning |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | JSON 구조는 읽었지만 필드 또는 객체 검증에 실패했습니다. |
| 400 | INVALID_JSON | JSON을 읽을 수 없거나 필수 nullable 키가 누락됐습니다. |
| 409 | DUPLICATE_EVENT | 같은 eventId가 이미 저장돼 중복 요청을 거절했습니다. |

검증 실패는 rejected value를 노출하지 않고 field/global violation을 제공합니다.

~~~json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "violations": [
    {
      "field": "detections[0].classCode",
      "message": "must match \"^[A-Z][A-Z0-9_]*$\""
    }
  ]
}
~~~

`INVALID_JSON`과 `DUPLICATE_EVENT`는 `code`와 `message`만 반환합니다.
