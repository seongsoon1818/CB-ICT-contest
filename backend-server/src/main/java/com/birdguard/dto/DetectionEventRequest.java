package com.birdguard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record DetectionEventRequest(
        @NotBlank @Size(max = 100) String eventId,
        @NotBlank @Size(max = 100) String cameraId,
        @NotNull Instant capturedAt,
        @NotNull @Valid Image image,
        @NotNull List<@NotNull @Valid Bird> birds
) {

    public record Image(
            @NotNull @Positive Integer width,
            @NotNull @Positive Integer height
    ) {
    }

    public record Bird(
            @NotBlank @Size(max = 100) String detectionId,
            Long trackId,
            @NotBlank @Size(max = 50) String speciesCode,
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
