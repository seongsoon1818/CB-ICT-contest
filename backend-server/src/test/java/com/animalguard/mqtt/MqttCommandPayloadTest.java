package com.animalguard.mqtt;

import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttCommandPayloadTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-26T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-26T00:00:10Z");

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void serializesExactAutomaticCommandPayload() throws Exception {
        DeviceCommand command = new DeviceCommand(
                "command-001",
                event("15356786-9588-4db4-a0fe-f8acd6300868"),
                "pi-001",
                DeviceCommandSource.AUTOMATIC,
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "FIRST_ANIMAL_DETECTION",
                ISSUED_AT,
                EXPIRES_AT
        );

        String json = objectMapper.writeValueAsString(MqttCommandPayload.from(command));

        assertThat(json).isEqualTo("""
                {"commandId":"command-001","eventId":"15356786-9588-4db4-a0fe-f8acd6300868","deviceId":"pi-001","source":"AUTOMATIC","command":"SOUND_ALERT","durationMs":2000,"issuedAt":"2026-08-26T00:00:00Z","expiresAt":"2026-08-26T00:00:10Z","reason":"FIRST_ANIMAL_DETECTION"}""");
    }

    @Test
    void includesNullEventAndDurationForManualRotation() throws Exception {
        DeviceCommand command = new DeviceCommand(
                "manual-15356786-9588-4db4-a0fe-f8acd6300868",
                null,
                "pi-001",
                DeviceCommandSource.MANUAL,
                DeviceCommandType.ROTATE_CAMERA_LEFT,
                null,
                "USER_REQUEST",
                ISSUED_AT,
                EXPIRES_AT
        );

        String json = objectMapper.writeValueAsString(MqttCommandPayload.from(command));

        assertThat(json).contains("\"eventId\":null");
        assertThat(json).contains("\"durationMs\":null");
        assertThat(json).contains("\"source\":\"MANUAL\"");
        assertThat(json).contains("\"command\":\"ROTATE_CAMERA_LEFT\"");
    }

    @Test
    void rejectsNonUuidAutomaticEventBeforePublish() {
        DeviceCommand command = new DeviceCommand(
                "command-invalid-event",
                event("not-a-uuid"),
                "pi-001",
                DeviceCommandSource.AUTOMATIC,
                DeviceCommandType.STOP_DETERRENT,
                null,
                "ANIMAL_DISAPPEARED",
                ISSUED_AT,
                EXPIRES_AT
        );

        assertThatThrownBy(() -> MqttCommandPayload.from(command))
                .isInstanceOf(MqttPayloadContractException.class)
                .hasMessageContaining("eventId must be a UUID");
    }

    private DetectionEvent event(String eventId) {
        return new DetectionEvent(
                eventId,
                "cam-001",
                ISSUED_AT,
                1280,
                720,
                "animal-detector-v1",
                null
        );
    }
}
