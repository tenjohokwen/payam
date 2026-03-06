package com.softropic.payam.security.jwt.api;


import com.softropic.payam.common.exception.AppSetupException;
import com.softropic.payam.security.jwt.JwtSecretProvider;
import com.softropic.payam.security.secret.SecretService;
import com.softropic.payam.security.secret.repository.Secret;

import org.springframework.stereotype.Service;

import java.util.Optional;

import static com.softropic.payam.security.common.util.SecurityConstants.JWT_BUS_NAME;
import static com.softropic.payam.security.common.util.SecurityConstants.JWT_VERSION;


/**
 * Manages the retrieval of the secret key of the JWT from the db.
 * Provides an endpoint to put the permuted secret key in the thread local for access from JwtUtil
 * When in the ThreadLocal the key is permuted (just an extra step of caution)
 */
@Service
public class JwtSecretService {
    private final SecretService secretService;

    private volatile Secret secret;

    public JwtSecretService(SecretService secretService) {this.secretService = secretService;}


    public void addSecretToThread() {
        if(secret == null) {
            secret = Optional.ofNullable(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
                                 .orElseThrow(() -> new AppSetupException("JWT secret key has not been set in DB"));
        }
        JwtSecretProvider.setSecret(secret);
    }

    public void removeSecretFromThread() {
        JwtSecretProvider.removeFromThread();
    }

}
