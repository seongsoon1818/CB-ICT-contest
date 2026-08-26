package com.animalguard.mqtt;

import com.animalguard.domain.DeviceOperationalStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class MqttDeviceStatusParser {

    private static final Set<String> FIELDS = Set.of(
            "deviceId",
            "status",
            "reportedAt",
            "firmwareVersion"
    );

    private final ObjectMapper objectMapper;

    public MqttDeviceStatusMessage parse(byte[] payload) {
        ObjectNode node = MqttJsonContract.parseObject(objectMapper, payload, "status");
        MqttJsonContract.requireExactFields(node, FIELDS, "status");
        String statusValue = MqttJsonContract.requireText(node, "status", 20);
        DeviceOperationalStatus status;
        try {
            status = DeviceOperationalStatus.valueOf(statusValue);
        } catch (IllegalArgumentException exception) {
            throw new MqttInboundContractException(
                    "unsupported device status: " + statusValue,
                    exception
            );
        }

        return new MqttDeviceStatusMessage(
                MqttJsonContract.requireText(node, "deviceId", 100),
                status,
                MqttJsonContract.requireOffsetTimestamp(node, "reportedAt"),
                MqttJsonContract.requireText(node, "firmwareVersion", 128)
        );
    }
}
