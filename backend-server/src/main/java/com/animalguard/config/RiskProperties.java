package com.animalguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "animalguard.risk")
public record RiskProperties(
        Map<String, Integer> classScores,
        int countThreshold,
        int countScore,
        double confidenceThreshold,
        int confidenceScore
) {
    public RiskProperties {
        classScores = classScores == null ? Map.of() : Map.copyOf(classScores);
    }
}
