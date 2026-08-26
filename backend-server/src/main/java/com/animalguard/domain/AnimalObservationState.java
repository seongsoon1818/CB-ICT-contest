package com.animalguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "animal_observation_states",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_animal_observation_states_camera_id",
                columnNames = "camera_id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnimalObservationState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "camera_id", nullable = false, updatable = false, length = 100)
    private String cameraId;

    @Enumerated(EnumType.STRING)
    @Column(name = "presence_state", nullable = false, length = 20)
    private AnimalPresenceState presenceState;

    @Column(name = "first_detected_at")
    private Instant firstDetectedAt;

    @Column(name = "last_detected_at")
    private Instant lastDetectedAt;

    @Column(name = "absence_started_at")
    private Instant absenceStartedAt;

    @Column(name = "last_processed_captured_at", nullable = false)
    private Instant lastProcessedCapturedAt;

    @Column(name = "sound_alert_command_id", length = 100)
    private String soundAlertCommandId;

    @Column(name = "deterrent_full_command_id", length = 100)
    private String deterrentFullCommandId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static AnimalObservationState initializeIdle(String cameraId, Instant capturedAt, Instant now) {
        AnimalObservationState state = new AnimalObservationState();
        state.cameraId = requireText(cameraId, "cameraId");
        state.presenceState = AnimalPresenceState.IDLE;
        state.lastProcessedCapturedAt = requireTimestamp(capturedAt, "capturedAt");
        state.createdAt = requireTimestamp(now, "now");
        state.updatedAt = now;
        state.validateInvariants();
        return state;
    }

    public static AnimalObservationState initializePresent(String cameraId, Instant capturedAt, Instant now) {
        AnimalObservationState state = new AnimalObservationState();
        state.cameraId = requireText(cameraId, "cameraId");
        state.presenceState = AnimalPresenceState.PRESENT;
        state.firstDetectedAt = requireTimestamp(capturedAt, "capturedAt");
        state.lastDetectedAt = capturedAt;
        state.lastProcessedCapturedAt = capturedAt;
        state.createdAt = requireTimestamp(now, "now");
        state.updatedAt = now;
        state.validateInvariants();
        return state;
    }

    public void startPresence(Instant capturedAt, Instant now) {
        requireState(AnimalPresenceState.IDLE);
        requireAfterProcessed(capturedAt);
        presenceState = AnimalPresenceState.PRESENT;
        firstDetectedAt = capturedAt;
        lastDetectedAt = capturedAt;
        absenceStartedAt = null;
        soundAlertCommandId = null;
        deterrentFullCommandId = null;
        markProcessed(capturedAt, now);
    }

    public void restartPresence(Instant capturedAt, Instant now) {
        requireState(AnimalPresenceState.PRESENT);
        requireAfterProcessed(capturedAt);
        firstDetectedAt = capturedAt;
        lastDetectedAt = capturedAt;
        absenceStartedAt = null;
        soundAlertCommandId = null;
        deterrentFullCommandId = null;
        markProcessed(capturedAt, now);
    }

    public void recordPresent(Instant capturedAt, Instant now) {
        requireState(AnimalPresenceState.PRESENT);
        requireAfterProcessed(capturedAt);
        lastDetectedAt = capturedAt;
        absenceStartedAt = null;
        markProcessed(capturedAt, now);
    }

    public void startAbsence(Instant capturedAt, Instant now) {
        requireState(AnimalPresenceState.PRESENT);
        requireAfterProcessed(capturedAt);
        if (absenceStartedAt == null) {
            absenceStartedAt = capturedAt;
        }
        markProcessed(capturedAt, now);
    }

    public void markIdleProcessed(Instant capturedAt, Instant now) {
        requireState(AnimalPresenceState.IDLE);
        requireAfterProcessed(capturedAt);
        markProcessed(capturedAt, now);
    }

    public void recordSoundAlertCommand(String commandId, Instant now) {
        requireState(AnimalPresenceState.PRESENT);
        if (soundAlertCommandId != null) {
            throw new IllegalStateException("sound alert command already recorded");
        }
        soundAlertCommandId = requireText(commandId, "commandId");
        touch(now);
        validateInvariants();
    }

    public void recordDeterrentFullCommand(String commandId, Instant now) {
        requireState(AnimalPresenceState.PRESENT);
        if (deterrentFullCommandId != null) {
            throw new IllegalStateException("deterrent full command already recorded");
        }
        deterrentFullCommandId = requireText(commandId, "commandId");
        touch(now);
        validateInvariants();
    }

    public boolean clearSoundAlertCommand(String commandId, Instant now) {
        requireText(commandId, "commandId");
        if (!commandId.equals(soundAlertCommandId)) {
            return false;
        }
        soundAlertCommandId = null;
        touch(now);
        validateInvariants();
        return true;
    }

    public boolean clearDeterrentFullCommand(String commandId, Instant now) {
        requireText(commandId, "commandId");
        if (!commandId.equals(deterrentFullCommandId)) {
            return false;
        }
        deterrentFullCommandId = null;
        touch(now);
        validateInvariants();
        return true;
    }

    public void resetToIdle(Instant capturedAt, Instant now) {
        requireState(AnimalPresenceState.PRESENT);
        // processEmpty records this capturedAt through startAbsence before confirming disappearance.
        if (capturedAt.isBefore(lastProcessedCapturedAt)) {
            throw new IllegalArgumentException("capturedAt must not precede last processed capturedAt");
        }
        presenceState = AnimalPresenceState.IDLE;
        firstDetectedAt = null;
        lastDetectedAt = null;
        absenceStartedAt = null;
        soundAlertCommandId = null;
        deterrentFullCommandId = null;
        markProcessed(capturedAt, now);
    }

    public void resetToIdleWithoutEvent(Instant now) {
        requireState(AnimalPresenceState.PRESENT);
        presenceState = AnimalPresenceState.IDLE;
        firstDetectedAt = null;
        lastDetectedAt = null;
        absenceStartedAt = null;
        soundAlertCommandId = null;
        deterrentFullCommandId = null;
        touch(now);
        validateInvariants();
    }

    private void markProcessed(Instant capturedAt, Instant now) {
        lastProcessedCapturedAt = requireTimestamp(capturedAt, "capturedAt");
        touch(now);
        validateInvariants();
    }

    private void touch(Instant now) {
        updatedAt = requireTimestamp(now, "now");
    }

    private void requireAfterProcessed(Instant capturedAt) {
        requireTimestamp(capturedAt, "capturedAt");
        if (!capturedAt.isAfter(lastProcessedCapturedAt)) {
            throw new IllegalArgumentException("capturedAt must be after last processed capturedAt");
        }
    }

    private void requireState(AnimalPresenceState expected) {
        if (presenceState != expected) {
            throw new IllegalStateException("presence state must be " + expected);
        }
    }

    private void validateInvariants() {
        Objects.requireNonNull(presenceState, "presenceState must not be null");
        Objects.requireNonNull(lastProcessedCapturedAt, "lastProcessedCapturedAt must not be null");
        if (presenceState == AnimalPresenceState.IDLE) {
            if (firstDetectedAt != null || lastDetectedAt != null || absenceStartedAt != null
                    || soundAlertCommandId != null || deterrentFullCommandId != null) {
                throw new IllegalStateException("IDLE state must not retain observation session fields");
            }
            return;
        }
        if (firstDetectedAt == null || lastDetectedAt == null
                || firstDetectedAt.isAfter(lastDetectedAt)
                || lastProcessedCapturedAt.isBefore(lastDetectedAt)
                || (absenceStartedAt != null && absenceStartedAt.isAfter(lastProcessedCapturedAt))) {
            throw new IllegalStateException("PRESENT state timestamp invariant violated");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Instant requireTimestamp(Instant value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }
}
