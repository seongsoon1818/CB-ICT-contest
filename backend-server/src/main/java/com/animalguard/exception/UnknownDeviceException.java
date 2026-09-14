package com.animalguard.exception;

public class UnknownDeviceException extends RuntimeException {

    public UnknownDeviceException(String deviceId) {
        super("Unknown deviceId: " + deviceId);
    }
}
