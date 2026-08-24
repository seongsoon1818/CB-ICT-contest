package com.animalguard.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "animalguard.risk")
public record RiskProperties(
        Map<
                @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String,
                @NotNull @Min(0) @Max(100) Integer
                > classScores,
        @Positive int countThreshold,
        @Min(0) @Max(100) int countScore,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidenceThreshold,
        @Min(0) @Max(100) int confidenceScore,
        @Min(1) @Max(99) int mediumThreshold,
        @Min(1) @Max(100) int highThreshold
) {
    public RiskProperties {
        classScores = classScores == null ? Map.of() : Map.copyOf(classScores);
    }

    @AssertTrue(message = "mediumThreshold must be less than highThreshold")
    public boolean isThresholdOrderValid() {
        return mediumThreshold < highThreshold;
    }
}
