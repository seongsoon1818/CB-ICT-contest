package com.animalguard.mqtt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class MqttJsonContract {

    private MqttJsonContract() {
    }

    static ObjectNode parseObject(ObjectMapper objectMapper, byte[] payload, String messageType) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            JsonNode root = objectMapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(payload);
            if (root == null || !root.isObject()) {
                throw new MqttInboundContractException(messageType + " payload must be a JSON object");
            }
            return (ObjectNode) root;
        } catch (IOException exception) {
            throw new MqttInboundContractException(messageType + " payload must be valid JSON", exception);
        }
    }

    static void requireExactFields(ObjectNode node, Set<String> expected, String messageType) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new MqttInboundContractException(
                    messageType + " fields must be exactly " + expected
            );
        }
    }

    static String requireText(ObjectNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new MqttInboundContractException(field + " must be a non-blank string");
        }
        if (value.textValue().length() > maxLength) {
            throw new MqttInboundContractException(
                    field + " must not exceed " + maxLength + " characters"
            );
        }
        return value.textValue();
    }

    static Instant requireOffsetTimestamp(ObjectNode node, String field) {
        String value = requireText(node, field, 64);
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            throw new MqttInboundContractException(
                    field + " must be an ISO 8601 date-time with timezone",
                    exception
            );
        }
    }
}
