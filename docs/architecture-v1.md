# AnimalGuard Architecture v1

## 범위

이 문서는 Detection Event를 시스템의 기준 사실로 삼는 Backend 데이터 구조와 위험도·장치 상태 원칙을 정의합니다. 현재 Spring Boot 수신·저장 vertical slice를 반영하며 PostgreSQL migration, MQTT client, State Machine/cooldown 실행 구현은 포함하지 않습니다.

## 시스템 경계

1. AI Server가 Detection Event v1을 생성합니다.
2. Backend가 eventId를 중복 수신 방지 키로 사용해 이벤트와 개별 유해동물 탐지를 저장합니다.
3. Backend가 event 전체를 기준으로 위험도 점수와 위험도 구간을 계산합니다.
4. 현재 Backend는 HIGH 위험도일 때 device command를 CREATED로 저장합니다.
5. 향후 State Machine과 device/camera별 cooldown 검사를 command 생성 전에 추가합니다.
6. 향후 저장된 명령을 MQTT로 발행하고 Raspberry Pi가 의미 기반 command를 GPIO 동작으로 매핑합니다.

State Machine, cooldown, MQTT 발행과 Raspberry Pi ACK는 아직 구현되지 않았습니다. 향후에도 MQTT는 Backend 판단을 대신하거나 우회하지 않습니다.

## Mermaid ERD

~~~mermaid
erDiagram
    detection_event ||--o{ animal_detection : contains
    detection_event ||--o| risk_decision : evaluates
    detection_event ||--o{ device_command : causes

    detection_event {
        bigint id PK
        uuid event_id UK
        varchar camera_id
        timestamptz captured_at
        int image_width
        int image_height
        varchar detector_version
        varchar classifier_version
        timestamptz created_at
    }

    animal_detection {
        bigint id PK
        bigint event_id FK
        varchar detection_id
        bigint track_id nullable
        varchar class_code
        decimal detection_confidence
        decimal classification_confidence
        int bbox_x
        int bbox_y
        int bbox_width
        int bbox_height
        timestamptz created_at
    }

    risk_decision {
        bigint id PK
        bigint event_id FK UK
        decimal score
        varchar level
        varchar reason
        timestamptz created_at
    }

    device_command {
        bigint id PK
        varchar command_id UK
        bigint event_id FK
        varchar device_id
        varchar command_type
        int duration_ms
        varchar status
        timestamptz created_at
    }

    device_status {
        bigint id PK
        varchar device_id
        boolean connected
        timestamptz last_seen nullable
        decimal temperature nullable
    }
~~~

device_status는 특정 event가 원인이 아닌 장치 단위 상태의 최신값 또는 이력입니다. 따라서 위의 event 중심 관계와 별도로 deviceId를 기준으로 관리하며, cameraId가 장치와 연결된 경우에만 선택적으로 기록합니다.

## 테이블 역할과 핵심 제약

### detection_event

AI가 보낸 이미지 단위 이벤트의 원본 식별·촬영·모델 메타데이터를 저장합니다.

- event_id는 UUID이며 UNIQUE입니다. 동일 event_id가 다시 수신되면 새 이벤트를 생성하지 않습니다.
- camera_id는 이벤트를 발생시킨 카메라의 안정적인 식별자입니다.
- captured_at은 AI가 관측한 시간이고 created_at은 Backend 서버 시간입니다.
- image_width와 image_height는 양의 픽셀 크기입니다.
- detector_version과 classifier_version은 결과를 만든 모델 버전입니다.

### animal_detection

하나의 detection_event에 속한 각 유해동물 탐지를 저장합니다.

- 모든 animal_detection은 정확히 하나의 detection_event를 참조합니다.
- detection_id, class_code, 두 confidence 값과 bbox 전체 필드를 저장합니다.
- track_id는 MVP에서 nullable입니다. 단일 이미지 MVP에서는 체류 시간을 계산하지 않습니다.
- detection_confidence와 classification_confidence는 서로 다른 의미로 저장합니다.
- detection event가 삭제될 때의 cascade 정책은 실제 migration 단계에서 별도로 결정합니다.

### risk_decision

개별 탐지가 아니라 하나의 detection_event 전체를 참조하는 위험도 판단 결과입니다.

- 하나의 event에는 0개 또는 1개의 risk_decision만 허용합니다.
- score는 0 이상 100 이하로 제한합니다.
- level은 LOW, MEDIUM, HIGH 중 하나이며 경계는 겹치지 않습니다.
- 구체적인 종별 점수, 집계 방식, threshold는 향후 Backend 설정으로 관리합니다. 이 문서는 종별 점수나 운영 threshold를 확정하지 않습니다.

### device_command

위험도 판단으로 생성된 장치 명령을 저장합니다.

- command_id는 UNIQUE한 idempotency 키입니다.
- 명령은 원인이 된 event_id와 대상 device_id를 함께 참조합니다.
- 현재 command는 HIGH 판단 시 CREATED로 저장하고 command_type과 duration_ms를 기록합니다.
- PUBLISHED, ACKNOWLEDGED, EXECUTED, FAILED, EXPIRED 전이와 만료 처리는 MQTT 구현 단계에서 추가합니다.
- 향후 동일 command_id의 재수신은 중복 작동 없이 기존 처리 결과를 재사용해야 합니다.

### device_status

장치별 연결·실행 상태를 기록합니다.

- 상태는 전역 단일값이 아니라 device_id 단위로 관리합니다.
- 현재 connected, last_seen, temperature를 저장합니다.
- camera-device mapping은 아직 구현하지 않았습니다.
- 현재 상태를 갱신하는 방식과 이력 보존 여부는 실제 저장소 구현 단계에서 결정합니다.

## 위험도 경계

점수는 0에서 100 사이입니다.

| Level | 범위 |
| --- | --- |
| LOW | 0 <= score < 40 |
| MEDIUM | 40 <= score < 70 |
| HIGH | 70 <= score <= 100 |

구간은 서로 겹치지 않으며 0과 100을 모두 포함합니다. class score와 threshold는 `animalguard.risk` 설정으로 관리하며 운영 기본 class score는 MAGPIE 30점과 UNKNOWN 0점만 둡니다. 이벤트 안에서 가장 높은 class score 하나만 적용하고 미설정 classCode는 0점입니다.

## 상태와 명령 생성 원칙

- 위험도 상태와 cooldown은 전역으로 공유하지 않고 camera 또는 device 단위로 관리합니다.
- 현재 Backend는 HIGH 판단 시 command를 CREATED로 저장하며 cameraId를 deviceId로 임시 사용합니다.
- State Machine, cooldown, MQTT 발행과 후속 상태 전이는 아직 구현하지 않았습니다.
- 향후에는 camera 또는 device 단위 안전 검사를 통과한 뒤 command를 생성하고 MQTT 상태 전이를 적용합니다.
