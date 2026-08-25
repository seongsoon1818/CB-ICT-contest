# 로컬 Mosquitto

Raspberry Pi MQTT Simulator를 로컬에서 검증하기 위한 비운영 구성입니다.

```bash
docker compose -f infra/docker-compose.yml up -d mosquitto
docker compose -f infra/docker-compose.yml config
```

Broker는 호스트의 `127.0.0.1:1883`에만 바인딩됩니다. 로컬 개발 편의를 위해 익명 접속을 허용하고 메시지를 영속화하지 않습니다.

이 설정을 운영 환경에서 사용하면 안 됩니다. TLS, username/password와 ACL은 제공하지 않습니다.
