package com.animalguard.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnimalObservationStateTest {

    private static final Instant T0 = Instant.parse("2026-08-26T00:00:00Z");

    @Test
    void maintainsIdleAndPresentInvariantsThroughSemanticTransitions() {
        AnimalObservationState state = AnimalObservationState.initializeIdle("cam-001", T0, T0);

        assertThat(state.getPresenceState()).isEqualTo(AnimalPresenceState.IDLE);
        assertThat(state.getFirstDetectedAt()).isNull();
        assertThat(state.getLastDetectedAt()).isNull();

        state.startPresence(T0.plusSeconds(1), T0.plusSeconds(10));
        state.recordSoundAlertCommand("command-sound", T0.plusSeconds(10));
        state.recordPresent(T0.plusSeconds(2), T0.plusSeconds(11));
        state.startAbsence(T0.plusSeconds(3), T0.plusSeconds(12));
        state.recordPresent(T0.plusSeconds(4), T0.plusSeconds(13));
        state.recordDeterrentFullCommand("command-full", T0.plusSeconds(13));

        assertThat(state.getPresenceState()).isEqualTo(AnimalPresenceState.PRESENT);
        assertThat(state.getFirstDetectedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(state.getLastDetectedAt()).isEqualTo(T0.plusSeconds(4));
        assertThat(state.getAbsenceStartedAt()).isNull();
        assertThat(state.getSoundAlertCommandId()).isEqualTo("command-sound");
        assertThat(state.getDeterrentFullCommandId()).isEqualTo("command-full");

        state.resetToIdle(T0.plusSeconds(5), T0.plusSeconds(14));
        assertThat(state.getPresenceState()).isEqualTo(AnimalPresenceState.IDLE);
        assertThat(state.getFirstDetectedAt()).isNull();
        assertThat(state.getLastDetectedAt()).isNull();
        assertThat(state.getAbsenceStartedAt()).isNull();
        assertThat(state.getSoundAlertCommandId()).isNull();
        assertThat(state.getDeterrentFullCommandId()).isNull();
        assertThat(state.getLastProcessedCapturedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void rejectsNonIncreasingCapturedAtAndInvalidMarkerCalls() {
        AnimalObservationState state = AnimalObservationState.initializeIdle("cam-001", T0, T0);
        state.startPresence(T0.plusSeconds(1), T0);

        assertThatThrownBy(() -> state.recordPresent(T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.startPresence(T0.plusSeconds(2), T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.recordSoundAlertCommand(" ", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearsOnlyExactMarkersAndTouchesBackendUpdateTime() {
        AnimalObservationState state = AnimalObservationState.initializePresent("cam-001", T0, T0);
        state.recordSoundAlertCommand("command-sound", T0.plusSeconds(1));
        state.recordDeterrentFullCommand("command-full", T0.plusSeconds(2));

        assertThat(state.clearSoundAlertCommand("other-command", T0.plusSeconds(3))).isFalse();
        assertThat(state.getSoundAlertCommandId()).isEqualTo("command-sound");
        assertThat(state.getUpdatedAt()).isEqualTo(T0.plusSeconds(2));

        assertThat(state.clearSoundAlertCommand("command-sound", T0.plusSeconds(4))).isTrue();
        assertThat(state.clearDeterrentFullCommand("command-full", T0.plusSeconds(5))).isTrue();
        assertThat(state.getSoundAlertCommandId()).isNull();
        assertThat(state.getDeterrentFullCommandId()).isNull();
        assertThat(state.getUpdatedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void resetsIdleWithoutInventingCapturedAt() {
        AnimalObservationState state = AnimalObservationState.initializePresent("cam-001", T0, T0);
        state.recordPresent(T0.plusSeconds(2), T0.plusSeconds(1));
        state.recordDeterrentFullCommand("command-full", T0.plusSeconds(2));

        state.resetToIdleWithoutEvent(T0.plusSeconds(10));

        assertThat(state.getPresenceState()).isEqualTo(AnimalPresenceState.IDLE);
        assertThat(state.getLastProcessedCapturedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(state.getUpdatedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(state.getFirstDetectedAt()).isNull();
        assertThat(state.getLastDetectedAt()).isNull();
        assertThat(state.getSoundAlertCommandId()).isNull();
        assertThat(state.getDeterrentFullCommandId()).isNull();
    }

    @Test
    void exposesNoPublicSetter() {
        assertThat(Arrays.stream(AnimalObservationState.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .filter(name -> !name.equals("setId"))
                .toList()).isEmpty();
        assertThat(Arrays.stream(AnimalObservationState.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .doesNotContain("setPresenceState", "setFirstDetectedAt", "setLastDetectedAt");
    }
}
