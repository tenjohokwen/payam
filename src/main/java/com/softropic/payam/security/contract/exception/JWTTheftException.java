package com.softropic.payam.security.contract.exception;


import static com.softropic.payam.security.contract.exception.SecurityError.TOKEN_THEFT;

public class JWTTheftException extends AuthorizationException {
    public JWTTheftException(final String msg) {
        super(msg,TOKEN_THEFT);
    }
}
