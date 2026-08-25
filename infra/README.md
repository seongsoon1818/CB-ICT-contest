# Infrastructure

로컬 개발용 PostgreSQL과 Raspberry Pi MQTT Simulator 검증용 Mosquitto를 Docker Compose로 제공합니다.

```bash
docker compose -f infra/docker-compose.yml up -d postgres
docker compose -f infra/docker-compose.yml up -d mosquitto
docker compose -f infra/docker-compose.yml config
```

Mosquitto는 `127.0.0.1:1883`에만 바인딩되는 비운영 구성입니다. Backend MQTT Publisher, AI Server와 Raspberry Pi 서비스는 Compose에 포함하지 않습니다.

이 디렉터리는 향후 로컬 실행과 배포에 필요한 인프라 구성의 경계입니다.
