package com.animalguard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record DetectionEventRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        String eventId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$") String cameraId,
        @NotNull Instant capturedAt,
        @NotNull @Valid Image image,
        @NotNull @Valid Model model,
        @NotNull @Size(max = MAX_DETECTIONS_PER_EVENT) List<@NotNull @Valid Detection> detections
) {

    public static final int MAX_DETECTIONS_PER_EVENT = 100;

    @AssertTrue(message = "detectionId must be unique within an event")
    public boolean isDetectionIdsUnique() {
        if (detections == null) {
            return true;
        }

        Set<String> detectionIds = new HashSet<>();
        return detections.stream()
                .filter(Objects::nonNull)
                .map(Detection::detectionId)
                .filter(Objects::nonNull)
                .allMatch(detectionIds::add);
    }

    @AssertTrue(message = "bbox must be within image bounds")
    public boolean isBoundingBoxesWithinImage() {
        if (image == null || image.width() == null || image.height() == null || detections == null) {
            return true;
        }

        return detections.stream()
                .filter(Objects::nonNull)
                .map(Detection::bbox)
                .filter(Objects::nonNull)
                .filter(bbox -> bbox.x() != null && bbox.y() != null
                        && bbox.width() != null && bbox.height() != null)
                .allMatch(bbox -> (long) bbox.x() + bbox.width() <= image.width()
                        && (long) bbox.y() + bbox.height() <= image.height());
    }

    public record Image(
            @NotNull @Positive Integer width,
            @NotNull @Positive Integer height
    ) {
    }

    public record Model(
            @NotBlank @Size(max = 128) String detectorVersion,
            @JsonProperty(required = true)
            @Size(max = 128) @Pattern(regexp = "(?s).*\\S.*") String classifierVersion
    ) {
    }

    public record Detection(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$") String detectionId,
            @JsonProperty(required = true) @PositiveOrZero Long trackId,
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String classCode,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double detectionConfidence,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double classificationConfidence,
            @NotNull @Valid Bbox bbox
    ) {
    }

    public record Bbox(
            @NotNull @PositiveOrZero Integer x,
            @NotNull @PositiveOrZero Integer y,
            @NotNull @Positive Integer width,
            @NotNull @Positive Integer height
    ) {
    }
}
