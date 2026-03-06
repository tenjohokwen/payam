package com.softropic.payam.security.listener;



import com.softropic.payam.security.common.event.AuthenticationAction;
import com.softropic.payam.security.common.event.AuthEvent;
import com.softropic.payam.security.exposed.util.RequestMetadata;
import com.softropic.payam.security.exposed.util.RequestMetadataProvider;
import com.softropic.payam.security.manager.LoginAttemptConsumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class AuthenticationSuccessListener {

    private final LoginAttemptConsumer<RequestMetadata> loginAttemptConsumer;

    @Autowired
    public AuthenticationSuccessListener(@Qualifier("loginAttemptService") final LoginAttemptConsumer<RequestMetadata> loginAttemptConsumer) {
        this.loginAttemptConsumer = loginAttemptConsumer;
    }

    @EventListener
    public void AuthEvent(final AuthEvent authEvent) {
        final AuthenticationAction action = authEvent.getAction();
        switch (action) {
            case SUCCESSFUL_AUTHENTICATION,  SUCCESSFUL_2FA -> {
                RequestMetadataProvider.setUserName(authEvent.getAuthentication().getName());
                final RequestMetadata requestMetadata = RequestMetadataProvider.getClientInfo();
                loginAttemptConsumer.loginSucceeded(requestMetadata);
            }
        }
    }
}
