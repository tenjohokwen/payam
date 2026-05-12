package com.softropic.payam.platform.security.contract.exception;

import com.softropic.payam.infrastructure.exception.ErrorCode;

import java.util.Map;

public class ProfileActionException extends AuthorizationException {

    public ProfileActionException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public ProfileActionException(String msg, Throwable cause, ErrorCode errorCode) {
        super(msg, cause, errorCode);
    }

    public ProfileActionException(String msg,
                                  Throwable cause,
                                  Map<String, Object> logContext,
                                  ErrorCode errorCode) {
        super(msg, cause, logContext, errorCode);
    }

    public ProfileActionException(String msg, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, logContext, errorCode);
    }
}
