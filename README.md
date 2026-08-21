# BirdGuard

BirdGuard는 카메라 기반 조류 감지와 AI 분석 결과를 바탕으로 농작물 피해 위험을 판단하고, 필요한 경우 비살상 방조 장치를 제어하는 농업 보호 시스템입니다.

## 해결하려는 문제

상시 작동하는 방조 장치는 불필요한 전력과 운영을 발생시키고 유익한 생물까지 방해할 수 있습니다. BirdGuard는 감지 결과와 위험도 판단을 바탕으로 필요한 장치 동작만 생성하는 것을 목표로 합니다.

## 시스템 흐름

감지 → AI 분석 → Backend 판단 → MQTT → Raspberry Pi

## 팀 역할

- AI 데이터: 조류 데이터 수집, 라벨링, 탐지·분류 모델 학습
- Backend/서버: AI 결과 수신·저장, 위험도 판단, 이벤트 관리, MQTT 명령 생성
- 임베디드: Raspberry Pi에서 의미 기반 명령을 장치 동작으로 매핑
- 지원: 프로젝트 문서와 통합 지원

## 저장소 디렉터리

- ai-server: AI 추론 서버의 Phase 0 경계와 향후 FastAPI 구성
- backend-server: Backend 서버의 Phase 0 경계와 향후 Spring Boot 구성
- raspberry-pi: Raspberry Pi 장치의 MQTT/GPIO 경계
- models: 향후 모델 산출물 보관 위치. 모델 바이너리는 커밋하지 않음
- infra: 향후 실행·배포 구성
- docs: 로드맵, 아키텍처, API/MQTT 계약

## 현재 구현 상태

현재 저장소는 Phase 0 계약 문서와 디렉터리 구조를 정리하는 단계입니다. 실제 Spring Boot, FastAPI, PostgreSQL migration, Docker 서비스, MQTT 코드, AI 모델 로딩은 아직 구현하지 않았습니다.

## 문서

- [구현 로드맵](docs/BACKEND_AI_IMPLEMENTATION_ROADMAP.md)
- [아키텍처 및 데이터 모델](docs/architecture-v1.md)
- [Detection Event v1](docs/contracts/detection-event-v1.md)
- [Detection Event v1 JSON Schema](docs/contracts/detection-event-v1.schema.json)
- [MQTT v1](docs/contracts/mqtt-v1.md)

## 로컬 실행

로컬 실행 방법과 실행 구성은 아직 구현되지 않았습니다.
