package com.animalguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "animal_detections",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_animal_detections_event_detection",
                columnNames = {"event_id", "detection_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnimalDetection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, foreignKey = @ForeignKey(name = "fk_animal_detections_event"))
    private DetectionEvent event;

    @Column(name = "detection_id", nullable = false, length = 64)
    private String detectionId;

    @Column(name = "track_id")
    private Long trackId;

    @Column(name = "class_code", nullable = false, length = 64)
    private String classCode;

    @Column(name = "detection_confidence", nullable = false)
    private double detectionConfidence;

    @Column(name = "classification_confidence", nullable = false)
    private double classificationConfidence;

    @Column(name = "bbox_x", nullable = false)
    private int bboxX;

    @Column(name = "bbox_y", nullable = false)
    private int bboxY;

    @Column(name = "bbox_width", nullable = false)
    private int bboxWidth;

    @Column(name = "bbox_height", nullable = false)
    private int bboxHeight;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AnimalDetection(
            String detectionId,
            Long trackId,
            String classCode,
            double detectionConfidence,
            double classificationConfidence,
            int bboxX,
            int bboxY,
            int bboxWidth,
            int bboxHeight
    ) {
        this.detectionId = detectionId;
        this.trackId = trackId;
        this.classCode = classCode;
        this.detectionConfidence = detectionConfidence;
        this.classificationConfidence = classificationConfidence;
        this.bboxX = bboxX;
        this.bboxY = bboxY;
        this.bboxWidth = bboxWidth;
        this.bboxHeight = bboxHeight;
        this.createdAt = Instant.now();
    }

    void assignEvent(DetectionEvent event) {
        this.event = event;
    }
}
