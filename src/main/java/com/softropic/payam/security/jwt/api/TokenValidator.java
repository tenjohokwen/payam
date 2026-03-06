package com.softropic.payam.security.jwt.api;

import com.softropic.payam.security.exposed.exception.AuthorizationException;
import jakarta.servlet.http.HttpServletRequest;

public interface TokenValidator {
    boolean isTokenFixed(HttpServletRequest request);
    boolean hasDbRefreshTokenExpired(HttpServletRequest request);
    void ensureClientHasPreLoginId();
    void ensureClientHasPostLoginId();
    void ensureAuthTokenPresent(HttpServletRequest request) throws AuthorizationException;
}
