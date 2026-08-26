package com.animalguard.mqtt;

public class MqttPayloadContractException extends IllegalArgumentException {

    public MqttPayloadContractException(String message) {
        super(message);
    }

    public MqttPayloadContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
