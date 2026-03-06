package com.softropic.payam.security.audit.listener;

import com.softropic.payam.common.ClockProvider;
import com.softropic.payam.email.api.EmailTemplate;
import com.softropic.payam.email.api.Envelope;
import com.softropic.payam.security.audit.api.AuditTrail;
import com.softropic.payam.security.audit.service.TrailService;
import com.softropic.payam.security.audit.shared.event.AccountChangeEvent;
import com.softropic.payam.security.exposed.util.RequestMetadata;
import com.softropic.payam.security.exposed.util.RequestMetadataProvider;
import com.softropic.payam.security.exposed.util.ShortCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class AccountChangeEventListener {

    //TODO This class knows about the email component. Redesign the account change process and AccountChangeEvent so that the audit package works independently of the email package
    private final TrailService trailService;
    private final ApplicationEventPublisher publisher;
    private final String baseUrl;
    private final String serverPort;

    public AccountChangeEventListener(TrailService trailService,
                                       ApplicationEventPublisher publisher,
                                       @Value("${baseurl}") String baseUrl,
                                       @Value("${server.port}") String serverPort) {
        this.trailService = trailService;
        this.publisher = publisher;
        this.baseUrl = baseUrl;
        this.serverPort = serverPort;
    }

    @EventListener
    public void handleAccountChange(AccountChangeEvent event) {
        log.info("Account change event received: {}", event.getAction());
        recordAuditTrail(event);
        sendNotificationEmail(event);
    }

    private void recordAuditTrail(AccountChangeEvent event) {
        final AuditTrail auditTrail = new AuditTrail();
        final String logId = ShortCode.shortenInt(UUID.randomUUID().hashCode());
        boolean logged = false;
        try {
            final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();

            auditTrail.setEventTimestamp(ClockProvider.getClock().instant());
            auditTrail.setMsg("PROFILE_CHANGE: %s".formatted(event.getAction()));
            auditTrail.setLogin(event.getRecipient().getEmail());
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

    private void sendNotificationEmail(AccountChangeEvent event) {
        final String helpCode = ShortCode.shortenInt(UUID.randomUUID().hashCode());
        final String fullBaseUrl = baseUrl + ":" + serverPort;

        final Map<String, Object> data = new HashMap<>();
        data.put("helpCode", helpCode);
        data.put("baseUrl", fullBaseUrl);
        data.put("action", event.getAction().name());
        data.put("oldValue", event.getOldValue() != null ? event.getOldValue() : "");
        data.put("newValue", event.getNewValue() != null ? event.getNewValue() : "");

        final Envelope envelope = new Envelope(
                List.of(event.getRecipient()),
                EmailTemplate.PROFILE_CHANGE,
                LocalDateTime.now(ClockProvider.getClock()).plusDays(7),
                data,
                helpCode
        );

        publisher.publishEvent(envelope);
    }
}
