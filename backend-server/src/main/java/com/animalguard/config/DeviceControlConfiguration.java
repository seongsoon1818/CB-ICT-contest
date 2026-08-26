package com.animalguard.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        DeviceControlProperties.class,
        ActuationProperties.class,
        ReconciliationProperties.class
})
public class DeviceControlConfiguration {

    @Bean
    Clock deviceControlClock() {
        return Clock.systemUTC();
    }
}
