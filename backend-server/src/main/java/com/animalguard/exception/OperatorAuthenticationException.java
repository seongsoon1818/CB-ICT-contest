package com.animalguard.exception;

public class OperatorAuthenticationException extends RuntimeException {

    public OperatorAuthenticationException() {
        super("Operator authentication failed");
    }
}
