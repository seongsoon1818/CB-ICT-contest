package com.animalguard.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsValidRiskConfiguration() {
        assertThat(validator.validate(validProperties())).isEmpty();
    }

    @Test
    void rejectsNonPositiveCountThreshold() {
        RiskProperties properties = new RiskProperties(
                Map.of("UNKNOWN", 0), 0, 20, 0.90, 20, 40, 70);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsCountThresholdAboveDetectionLimit() {
        RiskProperties properties = new RiskProperties(
                Map.of("UNKNOWN", 0), 101, 20, 0.90, 20, 40, 70);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsConfidenceThresholdOutsideZeroToOne() {
        RiskProperties properties = new RiskProperties(
                Map.of("UNKNOWN", 0), 3, 20, 1.01, 20, 40, 70);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsClassScoreOutsideZeroToOneHundred() {
        RiskProperties properties = new RiskProperties(
                Map.of("UNKNOWN", 101), 3, 20, 0.90, 20, 40, 70);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsNullClassScoreThroughBeanValidation() {
        Map<String, Integer> classScores = new HashMap<>();
        classScores.put("UNKNOWN", null);

        RiskProperties properties = new RiskProperties(
                classScores, 3, 20, 0.90, 20, 40, 70);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsInvalidClassCodeKey() {
        RiskProperties properties = new RiskProperties(
                Map.of("wild-boar", 30), 3, 20, 0.90, 20, 40, 70);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsMediumThresholdThatIsNotBelowHighThreshold() {
        RiskProperties properties = new RiskProperties(
                Map.of("UNKNOWN", 0), 3, 20, 0.90, 20, 70, 40);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    private RiskProperties validProperties() {
        return new RiskProperties(
                Map.of("MAGPIE", 30, "UNKNOWN", 0),
                3,
                20,
                0.90,
                20,
                40,
                70
        );
    }
}
