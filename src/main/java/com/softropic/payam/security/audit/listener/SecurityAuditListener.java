package com.softropic.payam.security.audit.listener;



import com.softropic.payam.common.ClockProvider;
import com.softropic.payam.common.exception.ApplicationException;
import com.softropic.payam.security.audit.api.AuditTrail;
import com.softropic.payam.security.audit.service.TrailService;
import com.softropic.payam.security.audit.shared.event.SecurityAlertEvent;
import com.softropic.payam.security.common.event.AuthEvent;
import com.softropic.payam.security.exposed.Principal;
import com.softropic.payam.security.exposed.util.RequestMetadata;
import com.softropic.payam.security.exposed.util.RequestMetadataProvider;
import com.softropic.payam.security.exposed.util.SecurityUtil;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class SecurityAuditListener {

    private final TrailService trailService;

    private final SecurityUtil securityUtil;

    public SecurityAuditListener(TrailService trailService, SecurityUtil securityUtil) {this.trailService = trailService;
        this.securityUtil = securityUtil;
    }

    @EventListener
    public void handleAlert(AuthEvent authEvent) {
        final AuditTrail auditTrail = new AuditTrail();
        boolean logged = false;
        try {
            final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();

            auditTrail.setEventTimestamp(ClockProvider.getClock().instant());
            auditTrail.setMsg("EVENT: %s".formatted(authEvent.getAction()));
            auditTrail.setLogin(clientInfo.getUserName());
            auditTrail.setAuthenticated(authEvent.getAuthentication().isAuthenticated());
            auditTrail.setRelevantProperties(extractPrincipalContext(authEvent.getAuthentication()));
            auditTrail.setUserAgent(clientInfo.getUserAgent());
            auditTrail.setIpAddress(clientInfo.getIpAddress());
            auditTrail.setClientId(clientInfo.getClientIdentifier());
            auditTrail.setBrowserCookie(clientInfo.getBrowserCookie());
            auditTrail.setUrl(clientInfo.getReqUrl());
            auditTrail.setLogId(authEvent.getEventId());
            auditTrail.setSessionId(clientInfo.getSessionId());
            auditTrail.setRequestId(clientInfo.getRequestId()); //setting this value so that it shows in logs. If not set here it will not show in logs but will show in the DB because of the pre-persist event listener
            trailService.recordTrail(auditTrail);
        }
        catch (Exception e) {
            log.error("Could not save trail in db. AUDIT_TRAIL: {} LOG_ID/EVENT_ID: {}", auditTrail, authEvent.getEventId(), e);
            logged = true;
        }
        finally {
            if(!logged) {
                log.info("AUDIT_TRAIL: {} LOG_ID/EVENT_ID: {}", auditTrail, authEvent.getEventId());
            }
        }
    }

    private Map<String, Object> extractPrincipalContext(Authentication authentication) {
        final Map<String, Object> context = new HashMap<>();
        if(authentication != null && authentication.getPrincipal() instanceof final Principal principal) {
            context.put("USER_ID", principal.getBusinessId());
        }
        return context;
    }

    @EventListener
    public void handleAlert(SecurityAlertEvent securityAlertEvent) {
        //Either BadCredentialsException , InvalidBearerTokenException or UsernameNotFoundException
        final AuditTrail auditTrail = new AuditTrail();
        boolean logged = false;
        try {
            final Exception exception = securityAlertEvent.getException();
            final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();

            auditTrail.setEventTimestamp(ClockProvider.getClock().instant());
            auditTrail.setMsg(extractMessage(exception));
            auditTrail.setLogin(clientInfo.getUserName());
            auditTrail.setAuthenticated(securityUtil.isAuthenticated());
            auditTrail.setRelevantProperties(extractContext(exception));
            auditTrail.setUserAgent(clientInfo.getUserAgent());
            auditTrail.setIpAddress(clientInfo.getIpAddress());
            auditTrail.setClientId(clientInfo.getClientIdentifier());
            auditTrail.setBrowserCookie(clientInfo.getBrowserCookie());
            auditTrail.setUrl(clientInfo.getReqUrl());
            auditTrail.setLogId(securityAlertEvent.getHelpCode());
            auditTrail.setSessionId(clientInfo.getSessionId());
            auditTrail.setRequestId(clientInfo.getRequestId());
            trailService.recordTrail(auditTrail);
        }
        catch (Exception e) {
            log.error("Could not save trail in db. AUDIT_TRAIL: {}", auditTrail, e);
            logged = true;
        }
        finally {
            if(!logged) {
                log.info("AUDIT_TRAIL: {}", auditTrail);
            }
        }
    }

    private Map<String, Object> extractContext(Exception exception) {
        final Map<String, Object> context = new HashMap<>();
        if(exception != null) {
            if(exception instanceof final ApplicationException appException) {
                final Map<String, Object> logContext = appException.getLogContext();
                context.putAll(logContext);
                context.put("ERROR_CODE", appException.getErrorCode());
                context.put("SUPPORT_ID", appException.getSupportId());
            }
            context.put("EXCEPTION_NAME", exception.getClass().getSimpleName());
        }
        return context;
    }

    private String extractMessage(Exception exception) {
        if(exception != null) {
            return exception.getMessage();
        }
        return null;
    }

}
