package com.animalguard.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MqttInboundMessageRouter {

    private final MqttAckParser ackParser;
    private final MqttDeviceStatusParser statusParser;
    private final DeviceCommandAckHandler ackHandler;
    private final DeviceStatusReportService statusService;

    public void route(String topic, byte[] payload, int qos, boolean retained) {
        try {
            if (topic != null && topic.endsWith("/acks")) {
                routeAck(topic, payload);
            } else if (topic != null && topic.endsWith("/status")) {
                routeStatus(topic, payload);
            } else {
                log.warn("MQTT inbound topic rejected: topic={}", topic);
            }
        } catch (MqttInboundContractException exception) {
            log.warn("MQTT inbound contract rejected: topic={}, reason={}", topic, exception.getMessage());
        } catch (DataAccessException exception) {
            log.error("MQTT inbound database failure: topic={}", topic, exception);
        } catch (RuntimeException exception) {
            log.error("MQTT inbound programming invariant failure: topic={}", topic, exception);
        }
    }

    private void routeAck(String topic, byte[] payload) {
        String topicDeviceId = MqttTopicCodec.deviceIdFromAckTopic(topic);
        MqttAckMessage message = ackParser.parse(payload);
        AckHandlingResult result = ackHandler.handle(message, topicDeviceId);
        switch (result.outcome()) {
            case APPLIED -> log.info(
                    "MQTT ACK applied: commandId={}, ackStatus={}, currentStatus={}, retried={}",
                    message.commandId(), message.status(), result.currentStatus(), result.retriedAfterOptimisticLock()
            );
            case IDEMPOTENT, ADVANCED_IGNORED -> log.info(
                    "MQTT ACK ignored idempotently: commandId={}, ackStatus={}, outcome={}, currentStatus={}, retried={}",
                    message.commandId(), message.status(), result.outcome(), result.currentStatus(),
                    result.retriedAfterOptimisticLock()
            );
            case UNKNOWN_COMMAND, TOPIC_PAYLOAD_DEVICE_MISMATCH, DATABASE_DEVICE_MISMATCH,
                    ORDER_VIOLATION, TERMINAL_CONFLICT -> log.warn(
                    "MQTT ACK rejected without state change: commandId={}, ackStatus={}, outcome={}, currentStatus={}, retried={}",
                    message.commandId(), message.status(), result.outcome(), result.currentStatus(),
                    result.retriedAfterOptimisticLock()
            );
            case OPTIMISTIC_LOCK_ABORTED -> log.error(
                    "MQTT ACK optimistic-lock retry exhausted: commandId={}, ackStatus={}",
                    message.commandId(), message.status()
            );
        }
    }

    private void routeStatus(String topic, byte[] payload) {
        String topicDeviceId = MqttTopicCodec.deviceIdFromStatusTopic(topic);
        MqttDeviceStatusMessage message = statusParser.parse(payload);
        DeviceStatusHandlingOutcome outcome = statusService.record(message, topicDeviceId);
        switch (outcome) {
            case STORED -> log.info(
                    "MQTT device status stored: deviceId={}, operationalStatus={}",
                    message.deviceId(), message.status()
            );
            case TOPIC_PAYLOAD_DEVICE_MISMATCH -> log.warn(
                    "MQTT device status rejected for topic/payload mismatch: topicDeviceId={}, payloadDeviceId={}",
                    topicDeviceId, message.deviceId()
            );
        }
    }
}
