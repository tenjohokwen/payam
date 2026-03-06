package com.softropic.payam.security.exposed.exception;

public class MissingClientIdException extends AuthorizationException {

    public MissingClientIdException(final String msg) {
        super(msg, SecurityError.MISSING_CLIENT_ID);
    }
}
