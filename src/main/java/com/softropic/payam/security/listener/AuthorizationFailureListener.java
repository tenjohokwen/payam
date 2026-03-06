package com.softropic.payam.security.listener;



import com.softropic.payam.security.exposed.util.RequestMetadataProvider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.event.AuthorizationFailureEvent;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * Meant to catch cases where an already/not authenticated user attempts to access resources for which he does not have rights.
 * JWTAuthorizationFilter and FilterSecurityInterceptor (to be precise its parent class AbstractSecurityInterceptor) will actually publish this event
 */
@Slf4j
@Component
public class AuthorizationFailureListener {

    @EventListener
    @SuppressWarnings("PMD")
    public void recordFailure(final AuthorizationDeniedEvent event) {
        //TODO test this
        final Object object = event.getObject();
        log.info("The current user does not have permission. Client metadata: '{}'",
                    RequestMetadataProvider.getClientInfo());
    }

}
