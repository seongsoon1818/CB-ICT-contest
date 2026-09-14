package com.animalguard.service;

public enum ObservationTrigger {
    NONE,
    FIRST_DETECTION,
    PERSISTENCE_REACHED,
    DISAPPEARANCE_CONFIRMED,
    CONTINUITY_RESTARTED,
    STALE_EVENT_IGNORED
}
