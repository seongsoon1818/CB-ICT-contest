from __future__ import annotations

import json
import logging
import signal
from datetime import UTC, datetime
from threading import Event
from typing import Any

import paho.mqtt.client as mqtt

from .command_handler import CommandHandler
from .contracts import (
    ack_topic,
    build_status_payload,
    command_topic,
    status_topic,
)
from .dedup_store import DedupStore
from .mock_gpio import MockGPIO
from .settings import Settings


LOGGER = logging.getLogger(__name__)
QOS = 1


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )
    settings = Settings.from_env()
    stop_event = Event()
    store = DedupStore(settings.processed_command_db)
    handler = CommandHandler(settings.device_id, store, MockGPIO())
    client = _build_client(settings, handler)

    def request_stop(signum: int, frame: Any) -> None:
        del frame
        LOGGER.info("종료 신호 수신: signal=%d", signum)
        stop_event.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    try:
        result = client.connect(
            settings.host, settings.port, settings.keepalive_seconds
        )
        if result != mqtt.MQTT_ERR_SUCCESS:
            raise RuntimeError(f"MQTT broker 연결 요청 실패: rc={result}")
        client.loop_start()
        while not stop_event.wait(settings.status_interval_seconds):
            _publish_status_safely(client, settings.device_id, "ONLINE")
    finally:
        if client.is_connected():
            info = _publish_status_safely(client, settings.device_id, "OFFLINE")
            if info is not None:
                info.wait_for_publish(timeout=5)
            client.disconnect()
        client.loop_stop()
        store.close()
    return 0


def _build_client(
    settings: Settings, handler: CommandHandler
) -> mqtt.Client:
    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"animalguard-mqtt-simulator-{settings.device_id}",
    )
    offline_payload = build_status_payload(
        settings.device_id, "OFFLINE", datetime.now(UTC)
    )
    client.will_set(
        status_topic(settings.device_id),
        json.dumps(offline_payload, separators=(",", ":"), ensure_ascii=False),
        qos=QOS,
        retain=True,
    )

    def on_connect(
        connected_client: mqtt.Client,
        userdata: Any,
        connect_flags: mqtt.ConnectFlags,
        reason_code: mqtt.ReasonCode,
        properties: mqtt.Properties | None,
    ) -> None:
        del userdata, connect_flags, properties
        if reason_code != 0:
            LOGGER.error("MQTT broker 연결 실패: reason=%s", reason_code)
            return
        topic = command_topic(settings.device_id)
        result, _ = connected_client.subscribe(topic, qos=QOS)
        if result != mqtt.MQTT_ERR_SUCCESS:
            LOGGER.error("command topic 구독 실패: topic=%s rc=%s", topic, result)
            return
        LOGGER.info("command topic 구독: topic=%s qos=%d", topic, QOS)
        _publish_status_safely(connected_client, settings.device_id, "ONLINE")

    def on_message(
        connected_client: mqtt.Client, userdata: Any, message: mqtt.MQTTMessage
    ) -> None:
        del userdata
        LOGGER.info(
            "command 수신: topic=%s qos=%d duplicate=%s",
            message.topic,
            message.qos,
            message.dup,
        )
        publish_ack = lambda ack: _publish_json(
            connected_client,
            ack_topic(settings.device_id),
            ack,
            retain=False,
        )
        if message.qos != QOS or message.retain:
            handler.reject(
                message.payload,
                publish_ack,
                f"qos={message.qos} retain={message.retain}",
            )
            return
        handler.handle(message.payload, publish_ack)

    client.on_connect = on_connect
    client.on_message = on_message
    return client


def _publish_status(
    client: mqtt.Client, device_id: str, status: str
) -> mqtt.MQTTMessageInfo:
    payload = build_status_payload(device_id, status, datetime.now(UTC))
    return _publish_json(client, status_topic(device_id), payload, retain=True)


def _publish_status_safely(
    client: mqtt.Client, device_id: str, status: str
) -> mqtt.MQTTMessageInfo | None:
    try:
        return _publish_status(client, device_id, status)
    except RuntimeError as exc:
        LOGGER.warning("MQTT status 발행 실패, 재연결 대기: %s", exc)
        return None


def _publish_json(
    client: mqtt.Client,
    topic: str,
    payload: dict[str, str],
    retain: bool,
) -> mqtt.MQTTMessageInfo:
    encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    info = client.publish(topic, encoded, qos=QOS, retain=retain)
    if info.rc != mqtt.MQTT_ERR_SUCCESS:
        raise RuntimeError(f"MQTT publish 실패: topic={topic} rc={info.rc}")
    LOGGER.info(
        "MQTT 발행: topic=%s qos=%d retain=%s status=%s",
        topic,
        QOS,
        retain,
        payload.get("status"),
    )
    return info


if __name__ == "__main__":
    raise SystemExit(main())
