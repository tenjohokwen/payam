package com.softropic.payam.security.infrastructure.jwt;

import com.softropic.payam.security.contract.Principal;
import java.util.Map;

public interface TokenCreator {
    String generateToken(Principal principal, Long dbRefreshToken, boolean isLoggedIn, String seed);
    String generateTokenFromClaims(Map<String, Object> claims);
    Map<String, Object> toClaims(Principal principal, Long dbRefreshToken, boolean isLoggedIn, String seed);
}
