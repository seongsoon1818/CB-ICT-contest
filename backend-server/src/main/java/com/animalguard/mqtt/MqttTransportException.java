package com.animalguard.mqtt;

public class MqttTransportException extends RuntimeException {

    public MqttTransportException(String message) {
        super(message);
    }

    public MqttTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
