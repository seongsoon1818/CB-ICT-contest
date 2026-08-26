package com.animalguard.config;

import com.animalguard.domain.RiskLevel;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

@Validated
@ConfigurationProperties(prefix = "animalguard.response-policy")
public record ResponsePolicyProperties(
        boolean enabled,
        Set<
                @NotBlank @Size(max = 64)
                @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String
                > allowedClassCodes,
        @DecimalMin("0.0") @DecimalMax("1.0") double minimumDetectionConfidence,
        @DecimalMin("0.0") @DecimalMax("1.0") Double minimumClassificationConfidence,
        RiskLevel minimumRiskLevel
) {
    public ResponsePolicyProperties {
        allowedClassCodes = allowedClassCodes == null
                ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(allowedClassCodes));
    }

    @AssertTrue(message = "allowedClassCodes must not be empty when response policy is enabled")
    public boolean isAllowlistConfiguredWhenEnabled() {
        return !enabled || !allowedClassCodes.isEmpty();
    }
}
