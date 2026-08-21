package com.birdguard.exception;

public class DuplicateDetectionEventException extends RuntimeException {

    public DuplicateDetectionEventException(String eventId) {
        super("Detection event already exists: " + eventId);
    }
}
