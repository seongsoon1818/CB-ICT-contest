package com.animalguard.exception;

public class UnsupportedManualCommandException extends RuntimeException {

    public UnsupportedManualCommandException() {
        super("command is not allowed for manual control");
    }
}
