package com.softropic.payam.security.audit.shared.event;



import com.softropic.payam.common.exception.ApplicationException;

import org.springframework.security.core.AuthenticationException;

public class SecurityAlertEvent extends SecurityEvent<Exception> {

    public SecurityAlertEvent(Exception exception, String logId) {
        super(exception, logId);
    }

    public boolean isAuthenticationException() {
        return exception instanceof AuthenticationException;
    }

    public boolean isApplicationException() {
        return exception instanceof ApplicationException;
    }

}
