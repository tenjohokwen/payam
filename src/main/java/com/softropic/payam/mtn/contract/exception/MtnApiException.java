package com.softropic.payam.mtn.contract.exception;

public class MtnApiException extends RuntimeException {
    public MtnApiException(String message) { super(message); }
    public MtnApiException(String message, Throwable cause) { super(message, cause); }
}
