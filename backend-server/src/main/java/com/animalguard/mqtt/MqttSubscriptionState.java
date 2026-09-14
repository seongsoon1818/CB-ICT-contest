package com.animalguard.mqtt;

import java.util.concurrent.atomic.AtomicBoolean;

public final class MqttSubscriptionState {

    private final AtomicBoolean ackActive = new AtomicBoolean();
    private final AtomicBoolean statusActive = new AtomicBoolean();

    public boolean isAckActive() {
        return ackActive.get();
    }

    public boolean isStatusActive() {
        return statusActive.get();
    }

    public boolean areRequiredSubscriptionsActive() {
        return isAckActive() && isStatusActive();
    }

    public void markAckActive() {
        ackActive.set(true);
    }

    public void markStatusActive() {
        statusActive.set(true);
    }

    public void reset() {
        ackActive.set(false);
        statusActive.set(false);
    }
}
