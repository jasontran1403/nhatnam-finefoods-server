package com.nhatnam.server.common;

public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
