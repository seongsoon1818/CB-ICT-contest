package com.animalguard.config;

import com.animalguard.mqtt.MqttActuationTransportReadiness;
import com.animalguard.mqtt.MqttCommandTransport;
import com.animalguard.mqtt.PahoMqttCommandTransport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfiguration {

    @Bean(destroyMethod = "close")
    MqttCommandTransport mqttCommandTransport(MqttProperties properties) {
        return new PahoMqttCommandTransport(properties);
    }

    @Bean
    MqttActuationTransportReadiness mqttActuationTransportReadiness(
            MqttProperties properties,
            MqttCommandTransport transport
    ) {
        return new MqttActuationTransportReadiness(properties, transport);
    }
}
