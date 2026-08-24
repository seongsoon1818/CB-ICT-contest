# Raspberry Pi

이 디렉터리는 AnimalGuard Raspberry Pi 장치 구성요소의 경계입니다. 장치는 향후 MQTT의 의미 기반 command를 GPIO 동작으로 매핑하고 명령 처리 결과와 장치 상태를 발행합니다.

현재는 GPIO 또는 MQTT 실행 코드를 추가하지 않고 `docs/contracts/mqtt-v1.md` 계약만 정의합니다.

## 운영 관리 포트

- SSH 22/TCP: 기본 원격 터미널·배포·로그 관리 수단
- VNC 5090/TCP: 필요한 경우에만 사용하는 선택적 GUI 관리 수단

SSH와 VNC는 운영 관리용이며 카메라 프레임 또는 MQTT command 전송 경로가 아닙니다. 이번 작업에서는 포트를 열거나 방화벽과 VNC Server를 설정하지 않습니다. VNC 5090의 실제 listen 또는 port mapping 방식은 Raspberry Pi 구성 단계에서 확정합니다.
