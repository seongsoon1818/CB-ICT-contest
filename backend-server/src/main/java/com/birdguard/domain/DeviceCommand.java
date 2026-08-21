package com.birdguard.domain;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeviceCommandStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public DeviceCommand(
            String commandId,
            DetectionEvent event,
            String deviceId,
            String commandType,
            int durationMs,
            DeviceCommandStatus status
    ) {
        this.commandId = commandId;
        this.event = event;
        this.deviceId = deviceId;
        this.commandType = commandType;
        this.durationMs = durationMs;
        this.status = status;
        this.createdAt = Instant.now();
    }
}
