package com.animalguard.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class MqttAckParser {

    private static final Set<String> BASE_FIELDS = Set.of("commandId", "deviceId", "status");

    private final ObjectMapper objectMapper;

    public MqttAckMessage parse(byte[] payload) {
        ObjectNode node = MqttJsonContract.parseObject(objectMapper, payload, "ACK");
        String statusValue = MqttJsonContract.requireText(node, "status", 20);
        MqttAckStatus status;
        try {
            status = MqttAckStatus.valueOf(statusValue);
        } catch (IllegalArgumentException exception) {
            throw new MqttInboundContractException("unsupported ACK status: " + statusValue, exception);
        }

        Set<String> expected = new java.util.HashSet<>(BASE_FIELDS);
        expected.add(status.timestampField());
        MqttJsonContract.requireExactFields(node, expected, "ACK");

        return new MqttAckMessage(
                MqttJsonContract.requireText(node, "commandId", 100),
                MqttJsonContract.requireText(node, "deviceId", 100),
                status,
                MqttJsonContract.requireOffsetTimestamp(node, status.timestampField())
        );
    }
}
