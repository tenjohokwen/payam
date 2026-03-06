package com.softropic.payam.security.exposed.exception;



import com.softropic.payam.common.exception.ErrorCode;

import java.util.Map;

public class JWTExpiredException extends AuthorizationException {
    public JWTExpiredException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public JWTExpiredException(String msg, Throwable throwable, ErrorCode errorCode) {
        super(msg, throwable, errorCode);
    }

    public JWTExpiredException(String msg, Throwable throwable, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, throwable, logContext, errorCode);
    }
}
