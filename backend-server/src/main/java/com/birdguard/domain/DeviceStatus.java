package com.birdguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "device_statuses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    @Column(name = "connected", nullable = false)
    private boolean connected;

    @Column(name = "last_seen")
    private Instant lastSeen;

    @Column(name = "temperature")
    private Double temperature;
}
