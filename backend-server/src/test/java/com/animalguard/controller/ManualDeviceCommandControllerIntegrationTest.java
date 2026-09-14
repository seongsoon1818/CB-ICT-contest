package com.animalguard.controller;

import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.DeviceCommandRepository;
import com.animalguard.service.ActuationTransportReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "animalguard.operator-api.enabled=true",
        "animalguard.operator-api.token=fake-test-operator-token",
        "animalguard.actuation.enabled=true",
        "animalguard.actuation.risk-policy-confirmed=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ManualDeviceCommandControllerIntegrationTest.TestBeans.class)
@ExtendWith(OutputCaptureExtension.class)
class ManualDeviceCommandControllerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T06:00:00Z");
    private static final String REQUEST_ID = "15356786-9588-4db4-a0fe-f8acd6300868";
    private static final String TOKEN = "fake-test-operator-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeviceCommandRepository repository;

    @Autowired
    private MutableTransportReadiness transportReadiness;

    @BeforeEach
    void cleanDatabase() {
        transportReadiness.setReady(true);
        repository.deleteAll();
    }

    @Test
    void rejectsMissingAndInvalidTokenWithoutPersistingOrLoggingSecrets(CapturedOutput output) throws Exception {
        perform("pi-001", null, REQUEST_ID, "ROTATE_CAMERA_LEFT")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("OPERATOR_AUTHENTICATION_FAILED")));

        perform("pi-001", "fake-test-invalid-token", REQUEST_ID, "ROTATE_CAMERA_LEFT")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("OPERATOR_AUTHENTICATION_FAILED")));

        assertThat(repository.count()).isZero();
        assertThat(output).doesNotContain(TOKEN).doesNotContain("fake-test-invalid-token");
    }

    @Test
    void createsBothRotationCommandsWithManualAuditFields() throws Exception {
        perform("pi-001", TOKEN, REQUEST_ID, "ROTATE_CAMERA_LEFT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandId", is("manual-" + REQUEST_ID)))
                .andExpect(jsonPath("$.commandOutcome", is("CREATED")))
                .andExpect(jsonPath("$.commandBlockers").isEmpty());

        String rightRequestId = "15356786-9588-4db4-a0fe-f8acd6300869";
        perform("pi-002", TOKEN, rightRequestId, "ROTATE_CAMERA_RIGHT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandId", is("manual-" + rightRequestId)));

        assertThat(repository.findAll())
                .extracting(DeviceCommand::getCommandType)
                .containsExactlyInAnyOrder(
                        DeviceCommandType.ROTATE_CAMERA_LEFT,
                        DeviceCommandType.ROTATE_CAMERA_RIGHT
                );
        assertThat(repository.findAll()).allSatisfy(command -> {
            assertThat(command.getSource()).isEqualTo(DeviceCommandSource.MANUAL);
            assertThat(command.getEvent()).isNull();
            assertThat(command.getDurationMs()).isNull();
            assertThat(command.getReason()).isEqualTo("USER_REQUEST");
            assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
            assertThat(command.getIssuedAt()).isEqualTo(NOW);
            assertThat(command.getExpiresAt()).isEqualTo(NOW.plusSeconds(10));
        });
    }

    @Test
    void createsManualStopWithoutRiskPolicyConfirmation() throws Exception {
        perform("pi-001", TOKEN, REQUEST_ID, "STOP_DETERRENT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandOutcome", is("CREATED")));

        assertThat(repository.findAll()).singleElement().satisfies(command -> {
            assertThat(command.getCommandType()).isEqualTo(DeviceCommandType.STOP_DETERRENT);
            assertThat(command.getDurationMs()).isNull();
        });
    }

    @Test
    void rejectsAutomaticOnlyCommands() throws Exception {
        perform("pi-001", TOKEN, REQUEST_ID, "SOUND_ALERT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_MANUAL_COMMAND")));

        perform("pi-001", TOKEN, REQUEST_ID, "DETERRENT_FULL")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_MANUAL_COMMAND")));

        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsUnknownDeviceWithoutCreatingFakeMapping() throws Exception {
        perform("pi-unknown", TOKEN, REQUEST_ID, "ROTATE_CAMERA_LEFT")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("UNKNOWN_DEVICE")));

        assertThat(repository.count()).isZero();
    }

    @Test
    void returnsExistingCommandForIdenticalIdempotentRequest() throws Exception {
        perform("pi-001", TOKEN, REQUEST_ID, "ROTATE_CAMERA_LEFT")
                .andExpect(status().isCreated());

        perform("pi-001", TOKEN, REQUEST_ID, "ROTATE_CAMERA_LEFT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandId", is("manual-" + REQUEST_ID)))
                .andExpect(jsonPath("$.commandOutcome", is("CREATED")));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void rejectsRequestIdReusedForDifferentDeviceOrCommand() throws Exception {
        perform("pi-001", TOKEN, REQUEST_ID, "ROTATE_CAMERA_LEFT")
                .andExpect(status().isCreated());

        perform("pi-002", TOKEN, REQUEST_ID, "ROTATE_CAMERA_LEFT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("MANUAL_COMMAND_CONFLICT")));

        perform("pi-001", TOKEN, REQUEST_ID, "ROTATE_CAMERA_RIGHT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("MANUAL_COMMAND_CONFLICT")));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void suppressesWhenMqttTransportIsNotReady() throws Exception {
        transportReadiness.setReady(false);

        perform("pi-001", TOKEN, REQUEST_ID, "ROTATE_CAMERA_LEFT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").doesNotExist())
                .andExpect(jsonPath("$.commandOutcome", is("SUPPRESSED")))
                .andExpect(jsonPath("$.commandBlockers[0]", is("MQTT_PUBLISHER_NOT_READY")));

        assertThat(repository.count()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            String deviceId,
            String token,
            String requestId,
            String command
    ) throws Exception {
        var request = post("/api/v1/devices/{deviceId}/commands", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","command":"%s"}
                        """.formatted(requestId, command));
        if (token != null) {
            request.header("X-Operator-Token", token);
        }
        return mockMvc.perform(request);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        Clock manualCommandClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        MutableTransportReadiness mutableTransportReadiness() {
            return new MutableTransportReadiness();
        }
    }

    static final class MutableTransportReadiness implements ActuationTransportReadiness {

        private final AtomicBoolean ready = new AtomicBoolean();

        void setReady(boolean ready) {
            this.ready.set(ready);
        }

        @Override
        public boolean isReady() {
            return ready.get();
        }
    }
}
