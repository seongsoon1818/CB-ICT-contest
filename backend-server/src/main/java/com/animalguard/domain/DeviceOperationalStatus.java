package com.animalguard.domain;

public enum DeviceOperationalStatus {
    ONLINE(true),
    OFFLINE(false),
    DEGRADED(true),
    MAINTENANCE(true);

    private final boolean connected;

    DeviceOperationalStatus(boolean connected) {
        this.connected = connected;
    }

    public boolean isConnected() {
        return connected;
    }
}
