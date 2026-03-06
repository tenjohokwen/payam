package com.softropic.payam.security.listener;



import com.softropic.payam.security.common.event.BadCredentialsEvent;
import com.softropic.payam.security.exposed.util.RequestMetadata;
import com.softropic.payam.security.exposed.util.RequestMetadataProvider;
import com.softropic.payam.security.manager.LoginAttemptsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Component
public class AuthenticationFailureListener {
    private static final Logger               logger = LoggerFactory.getLogger(AuthenticationFailureListener.class);

    //Todo Document this as one of the things to be replaced (by redis) in order to support multi-node apps [horizontal scaling]
    private final LoginAttemptsService loginAttemptsService;

    @Autowired
    public AuthenticationFailureListener(final LoginAttemptsService loginAttemptsService) {
        this.loginAttemptsService = loginAttemptsService;
    }

    @EventListener
    public void auditEventHappened(final BadCredentialsEvent event) {
        logger.debug("About to register bad credentials event. Message: {}", event.msg());
        final RequestMetadata requestMetadata = RequestMetadataProvider.getClientInfo();
        loginAttemptsService.loginFailed(requestMetadata);
    }

    @EventListener
    public void captureFraudEvent(final FraudEvent fraudEvent) {
        logger.debug("About to register fraud event. Message: {}", fraudEvent.msg());
        final RequestMetadata requestMetadata = RequestMetadataProvider.getClientInfo();
        loginAttemptsService.blacklistClient(requestMetadata);
    }
}
