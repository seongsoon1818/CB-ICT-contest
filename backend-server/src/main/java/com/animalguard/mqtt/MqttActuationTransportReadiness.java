package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import com.animalguard.service.ActuationTransportReadiness;

import java.util.Objects;

public final class MqttActuationTransportReadiness implements ActuationTransportReadiness {

    private final MqttProperties properties;
    private final MqttCommandTransport transport;

    public MqttActuationTransportReadiness(
            MqttProperties properties,
            MqttCommandTransport transport
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    @Override
    public boolean isReady() {
        return properties.enabled() && transport.isConnected();
    }
}
