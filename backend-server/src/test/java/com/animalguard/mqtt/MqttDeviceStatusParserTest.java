package com.animalguard.mqtt;

import com.animalguard.domain.DeviceOperationalStatus;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttDeviceStatusParserTest {

    private final MqttDeviceStatusParser parser = new MqttDeviceStatusParser(JsonMapper.builder().build());

    @ParameterizedTest
    @ValueSource(strings = {"ONLINE", "OFFLINE", "DEGRADED", "MAINTENANCE"})
    void parsesExactStatusForEverySupportedValue(String status) {
        MqttDeviceStatusMessage message = parser.parse(bytes("""
                {"deviceId":"pi-001","status":"%s","reportedAt":"2026-08-26T00:00:04Z",
                 "firmwareVersion":"mqtt-simulator-v1"}
                """.formatted(status)));

        assertThat(message.deviceId()).isEqualTo("pi-001");
        assertThat(message.status()).isEqualTo(DeviceOperationalStatus.valueOf(status));
        assertThat(message.reportedAt()).isEqualTo(Instant.parse("2026-08-26T00:00:04Z"));
        assertThat(message.firmwareVersion()).isEqualTo("mqtt-simulator-v1");
    }

    @Test
    void rejectsUnknownFieldAndTimezoneLessReportedAt() {
        assertThatThrownBy(() -> parser.parse(bytes("""
                {"deviceId":"pi-001","status":"ONLINE","reportedAt":"2026-08-26T00:00:04Z",
                 "firmwareVersion":"v1","extra":"rejected"}
                """))).isInstanceOf(MqttInboundContractException.class)
                .hasMessageContaining("exactly");
        assertThatThrownBy(() -> parser.parse(bytes("""
                {"deviceId":"pi-001","status":"ONLINE","reportedAt":"2026-08-26T00:00:04",
                 "firmwareVersion":"v1"}
                """))).isInstanceOf(MqttInboundContractException.class)
                .hasMessageContaining("timezone");
    }

    private byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
