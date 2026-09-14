package com.animalguard.exception;

public class ManualCommandConflictException extends RuntimeException {

    public ManualCommandConflictException() {
        super("requestId is already bound to a different command");
    }
}
