# Raspberry Pi

이 디렉터리는 AnimalGuard Raspberry Pi 장치 구성요소의 경계입니다. 장치는 MQTT의 의미 기반 command를 GPIO 동작으로 매핑하고 명령 처리 결과와 장치 상태를 발행합니다.

`embedded/`에는 MQTT v1 계약에 맞춘 실제 controller 소스가 있으며, 설정·parser·SQLite dedup·GPIO adapter·MQTT lifecycle을 분리합니다. 로컬 테스트는 FakeGPIO로 검증했지만 실제 Raspberry Pi 배선과 module 동작은 별도 hardware smoke가 필요합니다. 설치·설정·증거 경계는 [`embedded/README.md`](embedded/README.md)를 따릅니다.

- `camera-uploader/`: latest-frame source를 `frame`, `cameraId`, `capturedAt` multipart로 AI Server `/api/v1/analyze`에 전송
- `mqtt-simulator/`: 실제 GPIO 없이 command parser, persistent dedup, ACK/status를 검증
- `embedded/`: 실제 Pi용 gpiozero adapter와 최종 MQTT v1 controller

구 `test_frame_receiver.py`는 어떤 문서·script·component에서도 참조되지 않고 `/api/v1/frames`라는 폐기된 계약을 제공해 제거했습니다. local frame smoke도 production과 같은 `/api/v1/analyze` 계약을 사용해야 하며 receiver 응답을 AI 분석 성공으로 간주하지 않습니다.

전체 Mock E2E는 Simulator를 사용하고 실제 embedded controller를 기동하지 않습니다. 따라서 E2E PASS와 별개로 `HARDWARE_MQTT_SMOKE=NOT_RUN`일 수 있으며, 실제 device 연결 전에는 `docs/SOFTWARE_READY_CHECKLIST.md`의 broker·mapping·GPIO·camera·systemd·30FPS 항목을 확인해야 합니다.

## 운영 관리 포트

- SSH 22/TCP: 기본 원격 터미널·배포·로그 관리 수단
- VNC 5090/TCP: 필요한 경우에만 사용하는 선택적 GUI 관리 수단

SSH와 VNC는 운영 관리용이며 카메라 프레임 또는 MQTT command 전송 경로가 아닙니다. 이번 작업에서는 포트를 열거나 방화벽과 VNC Server를 설정하지 않습니다. VNC 5090의 실제 listen 또는 port mapping 방식은 Raspberry Pi 구성 단계에서 확정합니다.
