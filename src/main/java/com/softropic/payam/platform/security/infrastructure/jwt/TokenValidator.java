package com.softropic.payam.platform.security.infrastructure.jwt;

import com.softropic.payam.platform.security.contract.exception.AuthorizationException;
import jakarta.servlet.http.HttpServletRequest;

public interface TokenValidator {
    boolean isTokenFixed(HttpServletRequest request);
    boolean hasDbRefreshTokenExpired(HttpServletRequest request);
    void ensureClientHasPreLoginId();
    void ensureClientHasPostLoginId();
    void ensureAuthTokenPresent(HttpServletRequest request) throws AuthorizationException;
}
