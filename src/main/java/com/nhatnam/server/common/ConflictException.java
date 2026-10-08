package com.nhatnam.server.common;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}