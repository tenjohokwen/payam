package com.softropic.payam.security.exposed.exception;


import static com.softropic.payam.security.exposed.exception.SecurityError.TOKEN_THEFT;

public class JWTTheftException extends AuthorizationException {
    public JWTTheftException(final String msg) {
        super(msg,TOKEN_THEFT);
    }
}
