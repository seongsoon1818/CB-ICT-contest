package com.animalguard.mqtt;

public enum MqttAckStatus {
    ACKNOWLEDGED("acknowledgedAt"),
    EXECUTED("executedAt"),
    FAILED("failedAt"),
    EXPIRED("expiredAt");

    private final String timestampField;

    MqttAckStatus(String timestampField) {
        this.timestampField = timestampField;
    }

    public String timestampField() {
        return timestampField;
    }
}
