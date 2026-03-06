package com.softropic.payam.security.exposed.exception;



import com.softropic.payam.common.exception.ApplicationException;
import com.softropic.payam.common.exception.ErrorCode;

import java.util.Map;

public class SecException extends ApplicationException {
    public SecException(String msg) {
        super(msg);
    }

    public SecException(String msg, Map<String, Object> logContext) {
        super(msg, logContext);
    }

    public SecException(String msg, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, logContext, errorCode);
    }

    public SecException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public SecException(String msg, Throwable cause, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, cause, logContext, errorCode);
    }
}
