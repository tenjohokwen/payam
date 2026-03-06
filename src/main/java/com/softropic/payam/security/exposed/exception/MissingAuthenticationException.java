package com.softropic.payam.security.exposed.exception;



import com.softropic.payam.common.exception.ErrorCode;

import java.util.Map;

public class MissingAuthenticationException extends AuthorizationException {

    public MissingAuthenticationException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public MissingAuthenticationException(String msg, Throwable cause, ErrorCode errorCode) {
        super(msg, cause, errorCode);
    }

    public MissingAuthenticationException(String msg, Throwable cause, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, cause, logContext, errorCode);
    }

    public MissingAuthenticationException(String msg, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, null, logContext, errorCode);
    }

}
