package com.animalguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "device_commands",
        uniqueConstraints = @UniqueConstraint(name = "uk_device_commands_command_id", columnNames = "command_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// V1/V2 EXPIRED audit rows keep their original legacy type in the database.
@SQLRestriction("command_type NOT IN ('DETERRENT_LEVEL_1', 'DETERRENT_LEVEL_2', 'DETERRENT_LEVEL_3')")
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "command_id", nullable = false, updatable = false, length = 100)
    private String commandId;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "event_id", nullable = true, foreignKey = @ForeignKey(name = "fk_device_commands_event"))
    private DetectionEvent event;

    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_source", nullable = false, length = 20)
    private DeviceCommandSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, length = 100)
    private DeviceCommandType commandType;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeviceCommandStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_reported_at")
    private Instant acknowledgedReportedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "executed_reported_at")
    private Instant executedReportedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "failed_reported_at")
    private Instant failedReportedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "expired_reported_at")
    private Instant expiredReportedAt;

    public DeviceCommand(
            String commandId,
            DetectionEvent event,
            String deviceId,
            DeviceCommandSource source,
            DeviceCommandType commandType,
            Integer durationMs,
            String reason,
            Instant issuedAt,
            Instant expiresAt
    ) {
        this.commandId = requireText(commandId, "commandId");
        this.deviceId = requireText(deviceId, "deviceId");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.commandType = Objects.requireNonNull(commandType, "commandType must not be null");
        validateSourceAndCommandType(source, commandType);
        validateEvent(source, event);
        validateDuration(commandType, durationMs);
        this.event = event;
        this.durationMs = durationMs;
        this.reason = requireText(reason, "reason");
        if (reason.length() > 500) {
            throw new IllegalArgumentException("reason must not exceed 500 characters");
        }
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        this.status = DeviceCommandStatus.CREATED;
        this.createdAt = issuedAt;
    }

    private static void validateSourceAndCommandType(
            DeviceCommandSource source,
            DeviceCommandType commandType
    ) {
        boolean allowed = switch (source) {
            case AUTOMATIC -> switch (commandType) {
                case SOUND_ALERT, DETERRENT_FULL, STOP_DETERRENT -> true;
                case ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT -> false;
            };
            case MANUAL -> switch (commandType) {
                case ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT, STOP_DETERRENT -> true;
                case SOUND_ALERT, DETERRENT_FULL -> false;
            };
        };
        if (!allowed) {
            throw new IllegalArgumentException(
                    "commandType " + commandType + " is not allowed for source " + source
            );
        }
    }

    private static void validateEvent(DeviceCommandSource source, DetectionEvent event) {
        if (source == DeviceCommandSource.AUTOMATIC && event == null) {
            throw new IllegalArgumentException("event must not be null for AUTOMATIC commands");
        }
        if (source == DeviceCommandSource.MANUAL && event != null) {
            throw new IllegalArgumentException("event must be null for MANUAL commands");
        }
    }

    private static void validateDuration(DeviceCommandType commandType, Integer durationMs) {
        String violation = switch (commandType) {
            case SOUND_ALERT, DETERRENT_FULL -> durationMs == null || durationMs <= 0
                    ? "durationMs must be positive for " + commandType
                    : null;
            case ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT, STOP_DETERRENT -> durationMs != null
                    ? "durationMs must be null for " + commandType
                    : null;
        };
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
    }

    public void markPublished(Instant at) {
        at = requireTimestamp(at);
        if (status == DeviceCommandStatus.PUBLISHED) {
            return;
        }
        requireStatus(DeviceCommandStatus.CREATED, DeviceCommandStatus.PUBLISHED);
        requireNotBefore(at, issuedAt);
        status = DeviceCommandStatus.PUBLISHED;
        publishedAt = at;
    }

    public void markAcknowledged(Instant receivedAt, Instant reportedAt) {
        receivedAt = requireTimestamp(receivedAt);
        reportedAt = requireReportedTimestamp(reportedAt);
        if (status == DeviceCommandStatus.ACKNOWLEDGED) {
            return;
        }
        requireStatus(DeviceCommandStatus.PUBLISHED, DeviceCommandStatus.ACKNOWLEDGED);
        requireNotBefore(receivedAt, publishedAt);
        status = DeviceCommandStatus.ACKNOWLEDGED;
        acknowledgedAt = receivedAt;
        acknowledgedReportedAt = reportedAt;
    }

    public void markExecuted(Instant receivedAt, Instant reportedAt) {
        receivedAt = requireTimestamp(receivedAt);
        reportedAt = requireReportedTimestamp(reportedAt);
        if (status == DeviceCommandStatus.EXECUTED) {
            return;
        }
        requireStatus(DeviceCommandStatus.ACKNOWLEDGED, DeviceCommandStatus.EXECUTED);
        requireNotBefore(receivedAt, acknowledgedAt);
        status = DeviceCommandStatus.EXECUTED;
        executedAt = receivedAt;
        executedReportedAt = reportedAt;
    }

    public void markFailed(Instant receivedAt, Instant reportedAt) {
        receivedAt = requireTimestamp(receivedAt);
        if (status == DeviceCommandStatus.FAILED) {
            return;
        }

        Instant previousTimestamp = switch (status) {
            case PUBLISHED -> publishedAt;
            case ACKNOWLEDGED -> acknowledgedAt;
            default -> throw invalidTransition(DeviceCommandStatus.FAILED);
        };
        requireNotBefore(receivedAt, previousTimestamp);
        status = DeviceCommandStatus.FAILED;
        failedAt = receivedAt;
        failedReportedAt = reportedAt;
    }

    public void markExpired(Instant receivedAt, Instant reportedAt) {
        receivedAt = requireTimestamp(receivedAt);
        if (status == DeviceCommandStatus.EXPIRED) {
            return;
        }

        Instant previousTimestamp = switch (status) {
            case CREATED -> issuedAt;
            case PUBLISHED -> publishedAt;
            default -> throw invalidTransition(DeviceCommandStatus.EXPIRED);
        };
        requireNotBefore(receivedAt, previousTimestamp);
        status = DeviceCommandStatus.EXPIRED;
        expiredAt = receivedAt;
        expiredReportedAt = reportedAt;
    }

    private void requireStatus(DeviceCommandStatus expected, DeviceCommandStatus target) {
        if (status != expected) {
            throw invalidTransition(target);
        }
    }

    private IllegalStateException invalidTransition(DeviceCommandStatus target) {
        return new IllegalStateException("Cannot transition DeviceCommand from " + status + " to " + target);
    }

    private static Instant requireTimestamp(Instant at) {
        return Objects.requireNonNull(at, "transition timestamp must not be null");
    }

    private static Instant requireReportedTimestamp(Instant at) {
        return Objects.requireNonNull(at, "device reported timestamp must not be null");
    }

    private static void requireNotBefore(Instant at, Instant lowerBound) {
        if (at.isBefore(lowerBound)) {
            throw new IllegalArgumentException("transition timestamp must not be before the previous timestamp");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
