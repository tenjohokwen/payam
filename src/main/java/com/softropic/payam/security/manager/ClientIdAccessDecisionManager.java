package com.softropic.payam.security.manager;



import com.softropic.payam.security.exposed.util.RequestMetadata;
import com.softropic.payam.security.exposed.util.RequestMetadataProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;


/**
 * AccessDecisionVoter that determines whether access may be granted to a client based on its clientIdentifier and ip.
 */
@Slf4j
@Service
public class ClientIdAccessDecisionManager implements AuthorizationManager<RequestAuthorizationContext> {

    //TODO move to db
    //private final List<String> blacklistedIps ; //NOPMD

    //TODO move to db
    private final List<String> allowedMachineClients;

    @Autowired
    public ClientIdAccessDecisionManager(final List<String> allowedClients) {
        this.allowedMachineClients = allowedClients;
    }

    public boolean isClientIdAllowed() {
        log.info("################# Get info from 'RequestMetadataProvider' and check black lists and client type.");
        final RequestMetadata requestMetadata = RequestMetadataProvider.getClientInfo();
        if(requestMetadata.isMachineClient()) {
            return allowedMachineClients.contains(requestMetadata.getApiKey());
        }
        //TODO eventually add check for blacklistedClients (i.e both blacklisted browser as well as machine clients)
        //TODO add logs so that you can know when a client cannot access endpoints
        return true;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext object) {
        return new AuthorizationDecision(isClientIdAllowed());
    }

    @Override
    public AuthorizationResult authorize(Supplier<Authentication> authentication, RequestAuthorizationContext rac) {
        return new AuthorizationDecision(isClientIdAllowed());
    }
}
