package com.animalguard.mqtt;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttAckParserTest {

    private final MqttAckParser parser = new MqttAckParser(JsonMapper.builder().build());

    @ParameterizedTest
    @CsvSource({
            "ACKNOWLEDGED, acknowledgedAt",
            "EXECUTED, executedAt",
            "FAILED, failedAt",
            "EXPIRED, expiredAt"
    })
    void parsesExactAckForEverySupportedStatus(String status, String timestampField) {
        MqttAckMessage message = parser.parse(bytes("""
                {
                  "commandId": "command-001",
                  "deviceId": "pi-001",
                  "status": "%s",
                  "%s": "2026-08-26T09:00:04+09:00"
                }
                """.formatted(status, timestampField)));

        assertThat(message.commandId()).isEqualTo("command-001");
        assertThat(message.deviceId()).isEqualTo("pi-001");
        assertThat(message.status()).isEqualTo(MqttAckStatus.valueOf(status));
        assertThat(message.reportedAt()).isEqualTo(Instant.parse("2026-08-26T00:00:04Z"));
    }

    @Test
    void rejectsExtraOrWrongTimestampFields() {
        assertThatThrownBy(() -> parser.parse(bytes("""
                {"commandId":"command-001","deviceId":"pi-001","status":"ACKNOWLEDGED",
                 "acknowledgedAt":"2026-08-26T00:00:04Z","extra":true}
                """))).isInstanceOf(MqttInboundContractException.class)
                .hasMessageContaining("exactly");
        assertThatThrownBy(() -> parser.parse(bytes("""
                {"commandId":"command-001","deviceId":"pi-001","status":"ACKNOWLEDGED",
                 "executedAt":"2026-08-26T00:00:04Z"}
                """))).isInstanceOf(MqttInboundContractException.class)
                .hasMessageContaining("exactly");
    }

    @Test
    void rejectsTimestampWithoutTimezoneAndDuplicateFields() {
        assertThatThrownBy(() -> parser.parse(bytes("""
                {"commandId":"command-001","deviceId":"pi-001","status":"FAILED",
                 "failedAt":"2026-08-26T00:00:04"}
                """))).isInstanceOf(MqttInboundContractException.class)
                .hasMessageContaining("timezone");
        assertThatThrownBy(() -> parser.parse(bytes("""
                {"commandId":"command-001","commandId":"command-002","deviceId":"pi-001",
                 "status":"FAILED","failedAt":"2026-08-26T00:00:04Z"}
                """))).isInstanceOf(MqttInboundContractException.class)
                .hasMessageContaining("valid JSON");
    }

    private byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
