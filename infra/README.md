# Infrastructure

로컬 개발용 PostgreSQL과 Raspberry Pi MQTT Simulator 검증용 Mosquitto를 Docker Compose로 제공합니다.

```bash
docker compose -f infra/docker-compose.yml up -d postgres
docker compose -f infra/docker-compose.yml up -d mosquitto
docker compose -f infra/docker-compose.yml config
```

PostgreSQL과 Mosquitto는 loopback에만 bind합니다. 기본 host port는 각각 `5432`, `1883`이며 충돌 없는 격리 실행은 `POSTGRES_HOST_PORT`, `MQTT_HOST_PORT`로 바꿀 수 있습니다.

```bash
POSTGRES_HOST_PORT=55432 MQTT_HOST_PORT=51883 \
  docker compose --project-name animalguard-isolated \
  -f infra/docker-compose.yml up -d --wait
```

Compose project 이름이 volume namespace를 결정하므로 기존 개발 DB를 보존해야 하는 검증은 고유 project 이름을 사용합니다. `scripts/e2e/animalguard_mock_e2e.sh`는 이 방식을 사용해 임시 volume을 만들고 종료 시 해당 project의 container·volume만 정리합니다.

Mosquitto는 익명·평문 local test 전용입니다. Backend MQTT Publisher, AI Server와 Raspberry Pi process는 Compose에 포함하지 않으며 host process로 기동합니다. test-only MQTT fault proxy도 production broker 설정이나 runtime artifact가 아닙니다.

이 디렉터리는 향후 로컬 실행과 배포에 필요한 인프라 구성의 경계입니다.
