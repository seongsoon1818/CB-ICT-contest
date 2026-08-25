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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "device_commands",
        uniqueConstraints = @UniqueConstraint(name = "uk_device_commands_command_id", columnNames = "command_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false, updatable = false, length = 100)
    private String commandId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, foreignKey = @ForeignKey(name = "fk_device_commands_event"))
    private DetectionEvent event;

    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    @Column(name = "command_type", nullable = false, length = 100)
    private String commandType;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

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

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    public DeviceCommand(
            String commandId,
            DetectionEvent event,
            String deviceId,
            String commandType,
            int durationMs,
            String reason,
            Instant issuedAt,
            Instant expiresAt
    ) {
        this.commandId = requireText(commandId, "commandId");
        this.event = Objects.requireNonNull(event, "event must not be null");
        this.deviceId = requireText(deviceId, "deviceId");
        this.commandType = requireText(commandType, "commandType");
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive");
        }
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

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
