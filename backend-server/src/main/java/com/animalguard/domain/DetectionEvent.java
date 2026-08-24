package com.animalguard.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "detection_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_detection_events_event_id", columnNames = "event_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    @Column(name = "camera_id", nullable = false, length = 100)
    private String cameraId;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "image_width", nullable = false)
    private int imageWidth;

    @Column(name = "image_height", nullable = false)
    private int imageHeight;

    @Column(name = "detector_version", nullable = false, length = 128)
    private String detectorVersion;

    @Column(name = "classifier_version", length = 128)
    private String classifierVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AnimalDetection> detections = new ArrayList<>();

    public DetectionEvent(
            String eventId,
            String cameraId,
            Instant capturedAt,
            int imageWidth,
            int imageHeight,
            String detectorVersion,
            String classifierVersion
    ) {
        this.eventId = eventId;
        this.cameraId = cameraId;
        this.capturedAt = capturedAt;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.detectorVersion = detectorVersion;
        this.classifierVersion = classifierVersion;
        this.createdAt = Instant.now();
    }

    public void addDetection(AnimalDetection detection) {
        detections.add(detection);
        detection.assignEvent(this);
    }
}
