package com.softropic.payam.platform.security.contract.exception;

public class MissingClientIdException extends AuthorizationException {

    public MissingClientIdException(final String msg) {
        super(msg, SecurityError.MISSING_CLIENT_ID);
    }
}
