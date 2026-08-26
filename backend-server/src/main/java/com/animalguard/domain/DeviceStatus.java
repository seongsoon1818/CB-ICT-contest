package com.animalguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "device_statuses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "device_id", nullable = false, updatable = false, length = 100)
    private String deviceId;

    @Column(name = "connected", nullable = false)
    private boolean connected;

    @Column(name = "last_seen")
    private Instant lastSeen;

    @Column(name = "temperature")
    private Double temperature;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 20)
    private DeviceOperationalStatus operationalStatus;

    @Column(name = "firmware_version", length = 128)
    private String firmwareVersion;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public DeviceStatus(
            String deviceId,
            DeviceOperationalStatus operationalStatus,
            String firmwareVersion,
            Instant reportedAt,
            Instant receivedAt
    ) {
        this.deviceId = requireText(deviceId, "deviceId", 100);
        recordReport(operationalStatus, firmwareVersion, reportedAt, receivedAt);
    }

    public void recordReport(
            DeviceOperationalStatus operationalStatus,
            String firmwareVersion,
            Instant reportedAt,
            Instant receivedAt
    ) {
        this.operationalStatus = Objects.requireNonNull(
                operationalStatus,
                "operationalStatus must not be null"
        );
        this.firmwareVersion = requireText(firmwareVersion, "firmwareVersion", 128);
        this.reportedAt = Objects.requireNonNull(reportedAt, "reportedAt must not be null");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        connected = operationalStatus.isConnected();
        lastSeen = receivedAt;
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maxLength + " characters");
        }
        return value;
    }
}
