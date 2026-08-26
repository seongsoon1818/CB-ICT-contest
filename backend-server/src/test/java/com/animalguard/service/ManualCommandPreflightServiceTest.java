package com.animalguard.service;

import com.animalguard.config.ActuationProperties;
import com.animalguard.config.DeviceControlProperties;
import com.animalguard.config.OperatorApiProperties;
import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.DeviceCommandType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManualCommandPreflightServiceTest {

    @Test
    void rotationRequiresActuationButDoesNotRequireRiskPolicy() {
        ManualCommandPreflightService service = service(true, false, true);

        assertThat(service.blockersForCreation(DeviceCommandType.ROTATE_CAMERA_LEFT))
                .containsExactly(ActuationBlocker.ACTUATION_DISABLED);
    }

    @Test
    void stopBypassesActuationAndRiskPolicyWhenTransportAndMappingAreReady() {
        ManualCommandPreflightService service = service(true, false, true);

        assertThat(service.blockersForCreation(DeviceCommandType.STOP_DETERRENT)).isEmpty();
        assertThat(service.blockersForDispatch(DeviceCommandType.STOP_DETERRENT, "pi-001"))
                .isEmpty();
    }

    @Test
    void dispatchRechecksOperatorActuationMappingAndTransportForRotation() {
        ManualCommandPreflightService service = service(false, false, false);

        assertThat(service.blockersForDispatch(DeviceCommandType.ROTATE_CAMERA_RIGHT, "pi-unknown"))
                .containsExactly(
                        ActuationBlocker.OPERATOR_API_DISABLED,
                        ActuationBlocker.ACTUATION_DISABLED,
                        ActuationBlocker.CAMERA_UNMAPPED,
                        ActuationBlocker.MQTT_PUBLISHER_NOT_READY
                );
    }

    private ManualCommandPreflightService service(
            boolean operatorEnabled,
            boolean actuationEnabled,
            boolean transportReady
    ) {
        return new ManualCommandPreflightService(
                new OperatorApiProperties(operatorEnabled, "fake-test-operator-token"),
                new ActuationProperties(actuationEnabled, false),
                new DeviceControlProperties(
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(10),
                        Map.of("cam-001", "pi-001")
                ),
                () -> transportReady
        );
    }
}
