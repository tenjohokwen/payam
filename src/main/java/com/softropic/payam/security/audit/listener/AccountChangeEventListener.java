package com.softropic.payam.security.audit.listener;

import com.softropic.payam.common.ClockProvider;
import com.softropic.payam.security.audit.api.AuditTrail;
import com.softropic.payam.security.audit.service.TrailService;
import com.softropic.payam.security.exposed.event.AccountChangeEvent;
import com.softropic.payam.security.exposed.util.RequestMetadata;
import com.softropic.payam.security.exposed.util.RequestMetadataProvider;
import com.softropic.payam.security.exposed.util.ShortCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class AccountChangeEventListener {

    private final TrailService trailService;

    public AccountChangeEventListener(TrailService trailService) {
        this.trailService = trailService;
    }

    @EventListener
    public void handleAccountChange(AccountChangeEvent event) {
        log.info("Account change event received: {}", event.getAction());
        recordAuditTrail(event);
    }

    private void recordAuditTrail(AccountChangeEvent event) {
        final AuditTrail auditTrail = new AuditTrail();
        final String logId = ShortCode.shortenInt(UUID.randomUUID().hashCode());
        boolean logged = false;
        try {
            final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();

            auditTrail.setEventTimestamp(ClockProvider.getClock().instant());
            auditTrail.setMsg("PROFILE_CHANGE: %s".formatted(event.getAction()));
            auditTrail.setLogin(event.getUserInfo().email());
            auditTrail.setAuthenticated(true);
            auditTrail.setRelevantProperties(buildRelevantProperties(event));
            auditTrail.setUserAgent(clientInfo.getUserAgent());
            auditTrail.setIpAddress(clientInfo.getIpAddress());
            auditTrail.setClientId(clientInfo.getClientIdentifier());
            auditTrail.setBrowserCookie(clientInfo.getBrowserCookie());
            auditTrail.setUrl(clientInfo.getReqUrl());
            auditTrail.setLogId(logId);
            auditTrail.setSessionId(clientInfo.getSessionId());
            auditTrail.setRequestId(clientInfo.getRequestId()); //setting this value so that it shows in logs. If not set here it will not show in logs but will show in the DB because of the pre-persist event listener
            trailService.recordTrail(auditTrail);
        }
        catch (Exception e) {
            log.error("Could not save trail in db. AUDIT_TRAIL: {} LOG_ID: {}", auditTrail, logId, e);
            logged = true;
        }
        finally {
            if (!logged) {
                log.info("AUDIT_TRAIL: {} LOG_ID: {}", auditTrail, logId);
            }
        }
    }

    private Map<String, Object> buildRelevantProperties(AccountChangeEvent event) {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("action", event.getAction());
        properties.put("oldValue", event.getOldValue() != null ? event.getOldValue() : "");
        properties.put("newValue", event.getNewValue() != null ? event.getNewValue() : "");
        return properties;
    }
}
