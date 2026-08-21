# Infrastructure

이번 Backend Sprint 1에서는 로컬 개발용 PostgreSQL만 Docker Compose로 제공합니다.

```bash
docker compose -f infra/docker-compose.yml up -d postgres
docker compose -f infra/docker-compose.yml config
```

Mosquitto, MQTT Publisher, AI Server, Raspberry Pi 서비스는 이번 단계에 포함하지 않습니다.

이 디렉터리는 향후 로컬 실행과 배포에 필요한 인프라 구성의 경계입니다.
