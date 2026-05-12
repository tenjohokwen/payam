package com.softropic.payam.platform.security.contract.exception;



import com.softropic.payam.infrastructure.exception.ErrorCode;

import java.util.Map;

public class InvalidJWTDataException extends AuthorizationException {
    public InvalidJWTDataException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public InvalidJWTDataException(String msg, Throwable throwable, ErrorCode errorCode) {
        super(msg, throwable, errorCode);
    }

    public InvalidJWTDataException(String msg, Throwable throwable, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, throwable, logContext, errorCode);
    }
}
