package com.animalguard.config;

import com.animalguard.service.ActuationTransportReadiness;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DeviceControlProperties.class, ActuationProperties.class})
public class DeviceControlConfiguration {

    @Bean
    Clock deviceControlClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(ActuationTransportReadiness.class)
    ActuationTransportReadiness unavailableActuationTransportReadiness() {
        return () -> false;
    }
}
