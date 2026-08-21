# Infrastructure

이번 Backend Sprint 1에서는 로컬 개발용 PostgreSQL만 Docker Compose로 제공합니다.

```bash
docker compose -f infra/docker-compose.yml up -d postgres
docker compose -f infra/docker-compose.yml config
```

Mosquitto, MQTT Publisher, AI Server, Raspberry Pi 서비스는 이번 단계에 포함하지 않습니다.
