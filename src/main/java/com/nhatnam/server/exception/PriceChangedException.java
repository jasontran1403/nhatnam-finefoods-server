package com.nhatnam.server.exception;

public class PriceChangedException extends RuntimeException {
    public PriceChangedException(String message) {
        super(message);
    }
}