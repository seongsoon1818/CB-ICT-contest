package com.animalguard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record DetectionEventRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        String eventId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$") String cameraId,
        @NotNull Instant capturedAt,
        @NotNull @Valid Image image,
        @NotNull @Valid Model model,
        @NotNull List<@NotNull @Valid Detection> detections
) {

    public record Image(
            @NotNull @Positive Integer width,
            @NotNull @Positive Integer height
    ) {
    }

    public record Model(
            @NotBlank @Size(max = 128) String detectorVersion,
            @Size(max = 128) @Pattern(regexp = ".*\\S.*") String classifierVersion
    ) {
    }

    public record Detection(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$") String detectionId,
            @PositiveOrZero Long trackId,
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
