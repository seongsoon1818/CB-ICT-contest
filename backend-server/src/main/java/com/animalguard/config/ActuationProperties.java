package com.animalguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "animalguard.actuation")
public record ActuationProperties(
        boolean enabled,
        boolean riskPolicyConfirmed
) {
}
