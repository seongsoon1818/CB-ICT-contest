from __future__ import annotations

import json
import logging
import signal
from datetime import UTC, datetime
from threading import Event
from typing import Any, Mapping

import paho.mqtt.client as mqtt

from .command_handler import CommandHandler
from .contracts import ack_topic, build_status_payload, command_topic, status_topic
from .dedup_store import DedupStore
from .gpio_adapter import GpioZeroAdapter
from .settings import Settings


LOGGER = logging.getLogger(__name__)
QOS = 1
STATUS_RETAINED = True


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )
    settings = Settings.from_env()
    gpio: GpioZeroAdapter | None = None
    store: DedupStore | None = None
    handler: CommandHandler | None = None
    client: mqtt.Client | None = None
    loop_started = False

    try:
        gpio = GpioZeroAdapter(settings)
        store = DedupStore(settings.processed_command_db)
        handler = CommandHandler(
            settings.device_id,
            store,
            gpio,
            settings.max_motor_duration_ms,
            settings.max_sound_duration_ms,
        )
        client = _build_client(settings, handler)
        stop_event = Event()

        def request_stop(signum: int, frame: Any) -> None:
            del frame
            LOGGER.info("shutdown signal received: signal=%d", signum)
            stop_event.set()

        signal.signal(signal.SIGINT, request_stop)
        signal.signal(signal.SIGTERM, request_stop)

        result = client.connect(
            settings.host,
            settings.port,
            settings.keepalive_seconds,
        )
        if result != mqtt.MQTT_ERR_SUCCESS:
            raise RuntimeError(f"MQTT connect request failed: rc={result}")
        client.loop_start()
        loop_started = True
        while not stop_event.wait(1):
            pass
    finally:
        try:
            if handler is not None:
                handler.close()
            elif gpio is not None:
                gpio.close()
        finally:
            try:
                if client is not None and client.is_connected():
                    info = _publish_status_safely(client, settings.device_id, "OFFLINE")
                    if info is not None:
                        _wait_for_publish(info)
                    client.disconnect()
            finally:
                try:
                    if client is not None and loop_started:
                        client.loop_stop()
                finally:
                    if store is not None:
                        store.close()
    return 0


def _build_client(settings: Settings, handler: CommandHandler) -> mqtt.Client:
    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"animalguard-embedded-{settings.device_id}",
        protocol=mqtt.MQTTv311,
    )
    if settings.username:
        client.username_pw_set(settings.username, settings.password)

    offline = build_status_payload(
        settings.device_id,
        "OFFLINE",
        datetime.now(UTC),
    )
    client.will_set(
        status_topic(settings.device_id),
        json.dumps(offline, separators=(",", ":")),
        qos=QOS,
        retain=STATUS_RETAINED,
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
            LOGGER.error("MQTT connection failed: reason=%s", reason_code)
            return
        topic = command_topic(settings.device_id)
        result, _ = connected_client.subscribe(topic, qos=QOS)
        if result != mqtt.MQTT_ERR_SUCCESS:
            LOGGER.error("command subscription failed: topic=%s rc=%s", topic, result)
            return
        LOGGER.info("command topic subscribed: topic=%s qos=%d", topic, QOS)
        _publish_status_safely(connected_client, settings.device_id, "ONLINE")

    def on_message(
        connected_client: mqtt.Client,
        userdata: Any,
        message: mqtt.MQTTMessage,
    ) -> None:
        del userdata

        def publish_ack(ack: Mapping[str, Any]) -> None:
            _publish_json(
                connected_client,
                ack_topic(settings.device_id),
                ack,
                retain=False,
            )

        expected_topic = command_topic(settings.device_id)
        if message.topic != expected_topic or message.qos != QOS or message.retain:
            handler.reject(
                message.payload,
                publish_ack,
                (
                    f"topic={message.topic} qos={message.qos} "
                    f"retain={message.retain}"
                ),
            )
            return
        try:
            handler.handle(message.payload, publish_ack)
        except Exception:
            LOGGER.exception("unexpected command callback failure")

    client.on_connect = on_connect
    client.on_message = on_message
    return client


def _publish_status(
    client: mqtt.Client,
    device_id: str,
    status: str,
) -> mqtt.MQTTMessageInfo:
    return _publish_json(
        client,
        status_topic(device_id),
        build_status_payload(device_id, status, datetime.now(UTC)),
        retain=STATUS_RETAINED,
    )


def _publish_status_safely(
    client: mqtt.Client,
    device_id: str,
    status: str,
) -> mqtt.MQTTMessageInfo | None:
    try:
        return _publish_status(client, device_id, status)
    except RuntimeError as exc:
        LOGGER.warning("MQTT status publish failed: %s", exc)
        return None


def _publish_json(
    client: mqtt.Client,
    topic: str,
    payload: Mapping[str, Any],
    retain: bool,
) -> mqtt.MQTTMessageInfo:
    encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    info = client.publish(topic, encoded, qos=QOS, retain=retain)
    if info.rc != mqtt.MQTT_ERR_SUCCESS:
        raise RuntimeError(f"MQTT publish failed: topic={topic} rc={info.rc}")
    return info


def _wait_for_publish(info: mqtt.MQTTMessageInfo) -> None:
    try:
        info.wait_for_publish(timeout=5)
    except (RuntimeError, ValueError) as exc:
        LOGGER.warning("MQTT publish confirmation failed during shutdown: %s", exc)


if __name__ == "__main__":
    raise SystemExit(main())
