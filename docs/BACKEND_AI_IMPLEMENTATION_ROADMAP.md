# AnimalGuard AI Backend & AI Server Implementation Roadmap

## 1. 프로젝트 개요

### 프로젝트 목표

카메라 기반 유해동물 탐지 및 분류 결과를 활용하여 농작물 피해 위험도를 판단하고,
필요한 경우에만 비살상 대응 장치를 작동시키는 AI 기반 농업 보호 시스템 구축


## 2. 전체 시스템 흐름

Camera

↓

AI Inference Server
(YOLO Detection + Animal Classification)

↓

Detection Result JSON

↓

Backend Server
(Spring Boot)

↓

Risk Decision
Event Storage
MQTT Command

↓

Raspberry Pi

↓

GPIO Control

↓

Speaker / Motor / LED


---

# 3. 역할 정의


## AI 데이터 파트

담당:

- 유해동물 데이터 수집
- 이미지 라벨링
- 객체 탐지 모델 학습
- 유해동물 분류 모델 학습
- 모델 성능 개선

제공 결과:

- animal_detector.pt
- animal_classifier.pt
- classes.json


---

## 내 담당


### AI Inference Server

담당:

- AI 모델 로딩
- 이미지 입력 처리
- YOLO 객체 탐지 실행
- 탐지 영역 Crop
- 유해동물 분류 모델 실행
- 결과 JSON 생성


### Backend Server

담당:

- AI 결과 수신
- 데이터 저장
- 위험도 판단
- 이벤트 관리
- MQTT 명령 생성
- Raspberry Pi 제어
- 전체 시스템 통합


---

# 4. 기술 스택


## AI Server

| 항목             | 기술            |
| ---------------- | --------------- |
| Language         | Python 3.11     |
| Framework        | FastAPI         |
| AI Framework     | PyTorch         |
| Object Detection | YOLOv8 / YOLO11 |
| Image Processing | OpenCV          |
| Server           | Uvicorn         |


## Backend Server

| 항목        | 기술            |
| ----------- | --------------- |
| Language    | Java 17         |
| Framework   | Spring Boot 3   |
| Build       | Gradle          |
| Database    | PostgreSQL      |
| ORM         | Spring Data JPA |
| Messaging   | MQTT            |
| MQTT Broker | Mosquitto       |


---

# 5. 프로젝트 구조


animal-guard-system

    ├── ai-server
    │
    │   ├── FastAPI
    │   ├── YOLO Detector
    │   ├── Animal Classifier
    │   ├── OpenCV Processing
    │   └── Model Files
    │
    ├── backend-server
    │
    │   ├── Spring Boot
    │   ├── Decision Engine
    │   ├── Database
    │   ├── MQTT Client
    │   └── API
    │
    └── docker-compose.yml


---

# 6. 개발 단계 Roadmap


# Phase 0. 인터페이스 설계


## 목표

AI Server와 Backend Server 간 데이터 규격 확정


## AI → Backend API


POST /api/v1/detection/events


Request Example:

```json
{
  "eventId": "15356786-9588-4db4-a0fe-f8acd6300868",
  "cameraId": "cam-001",
  "capturedAt": "2026-08-24T08:00:00Z",
  "image": {"width": 1280, "height": 720},
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
      "classificationConfidence": 0.91,
      "bbox": {"x": 100, "y": 180, "width": 260, "height": 190}
    }
  ]
}
```


필드 설명:

| 필드 | 설명 |
| --- | --- |
| eventId | UUID 중복 방지 키 |
| cameraId | 카메라 ID |
| capturedAt | timezone 포함 촬영 시각 |
| model | detectorVersion 필수, classifierVersion nullable |
| detections | 0개 이상의 탐지 배열 |
| classCode | 대문자·숫자·underscore 대상 코드 |
| detectionConfidence | 객체 탐지 신뢰도 |
| classificationConfidence | 분류 신뢰도 |
| bbox | 픽셀 좌표와 양의 크기 |


---

# Phase 1. Backend 기본 구조 구현


## 목표

AI 결과를 받아 저장할 수 있는 서버 구축


구현:

- Spring Boot 프로젝트 생성
- PostgreSQL 연결
- Entity 생성
- Repository 생성
- Detection API 구현


구조:

Controller

↓

Service

↓

Repository

↓

Database


---

# Database 설계


## AnimalDetection


AI 탐지 결과 저장


필드:

- id
- event_id
- detection_id
- track_id
- class_code
- detection_confidence
- classification_confidence
- bbox_x / bbox_y / bbox_width / bbox_height
- created_at


---

## RiskDecision


백엔드 판단 결과 저장


필드:

- id
- event_id
- risk_score
- risk_level
- reason
- created_at


---

## DeviceCommand


장치 제어 기록


필드:

- id
- command_id
- event_id
- device_id
- command_type
- status
- duration_ms
- created_at


---

## DeviceStatus


장치 상태


필드:

- id
- device_id
- connected
- last_seen
- temperature


---

# Phase 2. AI Inference Server 구현


## 목표

이미지를 입력받아 유해동물 탐지 및 분류 결과 반환


API:

POST /ai/analyze


입력:

image.jpg


처리 과정:


Image

↓

OpenCV Preprocessing

↓

YOLO Detection

↓

Animal Detection Crop

↓

Classification Model

↓

Result JSON


출력:


{
  "classCode": "WILD_BOAR",
  "detectionConfidence": 0.93,
  "classificationConfidence": 0.90,
  "bbox": {
    "x":100,
    "y":200
  }
}


---

# Phase 3. AI Server + Backend 통합


## 목표

실제 AI 결과가 Backend DB까지 저장


흐름:


Camera Image

↓

AI Server

↓

Backend API

↓

PostgreSQL


검증:

- 이미지 입력
- AI 분석
- Backend 수신
- DB 저장 확인


---

# Phase 4. 위험도 판단 엔진 구현


## 역할 분리


AI:

무엇이 보였는가?


Backend:

대응해야 하는가?


---

## Risk Score 계산


현재 MVP 위험도 = 이벤트 내 최대 class score 1회 + 탐지 수 threshold 점수 1회 + 두 confidence threshold 점수 1회


예시:


까치 +30

3마리 이상 +20

Confidence 90% +20


Total = 70

운영 설정은 현재 MAGPIE 30점과 UNKNOWN 0점만 정의합니다. 미설정 classCode는 0점이며 최종 점수는 0~100으로 제한합니다.


---

## 위험 단계


| Level | Score | 현재 Backend 동작 |
| --- | --- | --- |
| LOW | 0 <= score < 40 | DeviceCommand 미생성 |
| MEDIUM | 40 <= score < 70 | DeviceCommand 미생성 |
| HIGH | 70 <= score <= 100 | DeviceCommand 생성 |


---

# Phase 5. MQTT 장치 제어


## 목표

Backend 판단 결과를 Raspberry Pi로 전달


MQTT Topic:


animalguard/devices/{deviceId}/commands


Message Example:


{
 "command":"DETERRENT_LEVEL_2",
 "duration":5000,
 "reason":"HIGH_RISK_MAGPIE"
}


---

## Raspberry Pi Command


Backend는 GPIO 번호를 직접 보내지 않는다.


전송:


DETERRENT_LEVEL_1

DETERRENT_LEVEL_2

STOP


임베디드 처리:


DETERRENT_LEVEL_2

↓

Motor ON

Speaker ON

LED ON


---

# Phase 6. 상태 관리


## 목적

같은 대상 때문에 장치가 반복 작동하는 문제 방지


State Machine:


IDLE

↓

OBSERVING

↓

CONFIRMED

↓

ACTION

↓

COOLDOWN

↓

IDLE


---

# Phase 7. Dashboard API


## 목표

농장 상태 및 이벤트 확인


API:


GET /events/latest

GET /device/status

GET /statistics


표시 정보:

- 현재 탐지된 유해동물
- 대상 classCode
- confidence
- 위험도
- 장치 작동 기록
- 최근 이벤트


---

# Phase 8. Docker 환경 구성


최종 실행:


docker-compose.yml


서비스:


- backend
- postgres
- mosquitto
- ai-server


---

# 9. 개발 우선순위


## Sprint 1

목표:

Backend 기반 완성


구현:

- Spring Boot 생성
- PostgreSQL 연결
- Detection API
- DB 저장


완료 기준:

Mock JSON 입력으로 저장 가능


---

## Sprint 2

목표:

AI Server 구현


구현:

- FastAPI 생성
- YOLO 연결
- Classification 연결
- Image 분석 API


완료 기준:

이미지 → 대상 classCode 반환 가능


---

## Sprint 3

목표:

AI + Backend 통합


구현:

- AI 결과 전달
- 위험도 계산
- 이벤트 저장


완료 기준:

AI 결과 기반 판단 가능


---

## Sprint 4

목표:

IoT 제어 연결


구현:

- MQTT Broker
- Backend Publisher
- Raspberry Pi Subscriber


완료 기준:

AI 판단 → 실제 장치 동작


---

# 10. 확장 기능


## 환경 센서


추가:

- 조도 센서
- 온습도 센서
- 비 감지 센서


목적:

카메라 신뢰도 보정


---

## 음향 기반 탐지


Microphone

↓

Animal Sound Detection

↓

Camera Trigger


목적:

- 안개
- 야간
- 악천후 대응


---

## 추가 센서


- 열화상 카메라
- mmWave Radar
- 다중 카메라


---

# 11. 최종 발표 방향


단순 새 퇴치 시스템이 아니라:


AI 영상 분석 결과를 기반으로 농작물 피해 위험도를 판단하고,
필요한 경우에만 IoT 장치를 작동시키는 지능형 농업 보호 플랫폼


으로 설명한다.


핵심 기술:

- Computer Vision
- AI Model Serving
- Backend Decision Engine
- MQTT IoT Control
- Data Logging


---

# 12. 첫 번째 개발 목표


가장 먼저 구현:


Spring Boot

↓

AI Mock JSON 수신

↓

PostgreSQL 저장

↓

Risk Score 계산

↓

MQTT Command 생성


이 흐름을 먼저 완성한 후 실제 AI 모델을 연결한다.


이 방식이면 AI 모델 개발 일정과 관계없이 Backend 개발을 진행할 수 있다.
