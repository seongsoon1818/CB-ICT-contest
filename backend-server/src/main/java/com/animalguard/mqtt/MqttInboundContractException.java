package com.animalguard.mqtt;

public class MqttInboundContractException extends RuntimeException {

    public MqttInboundContractException(String message) {
        super(message);
    }

    public MqttInboundContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
