package com.softropic.payam.payment.provider.orange.contract.exception;

public class OrangeApiException extends RuntimeException {
    public OrangeApiException(String message) { super(message); }
    public OrangeApiException(String message, Throwable cause) { super(message, cause); }
}
