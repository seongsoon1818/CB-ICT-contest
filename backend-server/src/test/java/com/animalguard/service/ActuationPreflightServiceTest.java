package com.animalguard.service;

import com.animalguard.config.ActuationProperties;
import com.animalguard.config.DeviceControlProperties;
import com.animalguard.domain.ActuationBlocker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActuationPreflightServiceTest {

    @Test
    void returnsEveryCurrentBlockerInDeterministicOrder() {
        ActuationPreflight preflight = service(false, false, Map.of(), false).evaluate();

        assertThat(preflight.enabled()).isFalse();
        assertThat(preflight.ready()).isFalse();
        assertThat(preflight.blockers()).containsExactly(
                ActuationBlocker.ACTUATION_DISABLED,
                ActuationBlocker.RISK_POLICY_UNCONFIRMED,
                ActuationBlocker.CAMERA_DEVICE_MAPPING_EMPTY,
                ActuationBlocker.MQTT_PUBLISHER_NOT_READY
        );
    }

    @Test
    void returnsOnlyActuationDisabledWhenOtherConditionsAreReady() {
        assertThat(service(false, true, mapping(), true).evaluate().blockers())
                .containsExactly(ActuationBlocker.ACTUATION_DISABLED);
    }

    @Test
    void returnsOnlyRiskPolicyUnconfirmedWhenOtherConditionsAreReady() {
        assertThat(service(true, false, mapping(), true).evaluate().blockers())
                .containsExactly(ActuationBlocker.RISK_POLICY_UNCONFIRMED);
    }

    @Test
    void returnsOnlyEmptyMappingWhenOtherConditionsAreReady() {
        assertThat(service(true, true, Map.of(), true).evaluate().blockers())
                .containsExactly(ActuationBlocker.CAMERA_DEVICE_MAPPING_EMPTY);
    }

    @Test
    void returnsOnlyTransportNotReadyWhenOtherConditionsAreReady() {
        assertThat(service(true, true, mapping(), false).evaluate().blockers())
                .containsExactly(ActuationBlocker.MQTT_PUBLISHER_NOT_READY);
    }

    @Test
    void reportsReadyWhenEveryConditionIsSatisfied() {
        ActuationPreflight preflight = service(true, true, mapping(), true).evaluate();

        assertThat(preflight.enabled()).isTrue();
        assertThat(preflight.ready()).isTrue();
        assertThat(preflight.blockers()).isEmpty();
    }

    @Test
    void preflightBlockersAreUnmodifiableAndConsistentWithReady() {
        ActuationPreflight preflight = service(false, true, mapping(), true).evaluate();

        assertThatThrownBy(() -> preflight.blockers().add(ActuationBlocker.COOLDOWN_ACTIVE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ActuationPreflight(true, true, preflight.blockers()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsActuationDisabledBlockerThatContradictsEnabled() {
        assertThatThrownBy(() -> new ActuationPreflight(
                true,
                false,
                List.of(ActuationBlocker.ACTUATION_DISABLED)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("enabled must match absence of ACTUATION_DISABLED blocker");

        assertThatThrownBy(() -> new ActuationPreflight(
                false,
                false,
                List.of(ActuationBlocker.RISK_POLICY_UNCONFIRMED)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("enabled must match absence of ACTUATION_DISABLED blocker");
    }

    private ActuationPreflightService service(
            boolean enabled,
            boolean riskPolicyConfirmed,
            Map<String, String> mappings,
            boolean transportReady
    ) {
        return new ActuationPreflightService(
                new ActuationProperties(enabled, riskPolicyConfirmed),
                new DeviceControlProperties(
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(10),
                        mappings
                ),
                () -> transportReady
        );
    }

    private Map<String, String> mapping() {
        return Map.of("cam-001", "pi-001");
    }
}
