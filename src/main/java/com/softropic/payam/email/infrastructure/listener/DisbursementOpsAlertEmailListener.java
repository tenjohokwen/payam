package com.softropic.payam.email.infrastructure.listener;

import com.softropic.payam.common.ClockProvider;
import com.softropic.payam.disbursement.contract.event.DisbursementAdminApprovalRequiredEvent;
import com.softropic.payam.disbursement.contract.event.InsufficientFundsAlertEvent;
import com.softropic.payam.email.contract.EmailTemplate;
import com.softropic.payam.email.contract.Envelope;
import com.softropic.payam.email.contract.Recipient;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Spring @EventListener bean that delivers ops notifications for the two
 * disbursement-side ops events:
 *
 * <ul>
 *   <li>{@link DisbursementAdminApprovalRequiredEvent} (ADMIN-02) — published by
 *       DisbursementOrchestrator when a disbursement is gated for admin approval.
 *       Sends a notification email to Platform Ops using
 *       {@link EmailTemplate#DISBURSEMENT_ADMIN_APPROVAL_REQUIRED}.</li>
 *   <li>{@link InsufficientFundsAlertEvent} (ALERT-01) — published by
 *       DisbursementCallbackTransitionService when a provider returns Insufficient Funds.
 *       Sends a high-priority alert email using
 *       {@link EmailTemplate#DISBURSEMENT_INSUFFICIENT_FUNDS_ALERT}, identifying which
 *       provider account needs liquidity.</li>
 * </ul>
 *
 * <p>Both events are best-effort. The listener does not throw on failure — the
 * MailManager pipeline downstream owns retry/scheduling/AFTER_COMMIT behavior.
 *
 * <p>Pattern mirrors {@link PlatformConfigEmailListener}.
 */
@Slf4j
@Component
public class DisbursementOpsAlertEmailListener {

    private final ApplicationEventPublisher publisher;
    private final String notificationEmail;

    public DisbursementOpsAlertEmailListener(
            ApplicationEventPublisher publisher,
            @Value("${payam.platform.notification-email}") String notificationEmail) {
        this.publisher = publisher;
        this.notificationEmail = notificationEmail;
    }

    /**
     * ADMIN-02: dispatch ops email when a disbursement is gated for admin approval.
     */
    @Transactional
    @EventListener
    public void onAdminApprovalRequired(DisbursementAdminApprovalRequiredEvent event) {
        log.info("Sending disbursement admin-approval notification email",
                kv("operation", "dsb_admin_approval_email_dispatched"),
                kv("disbursementId", event.disbursementId()),
                kv("tenantId", event.tenantId()),
                kv("amount", event.amount()));

        final Recipient recipient = new Recipient();
        recipient.setEmail(notificationEmail);
        recipient.setLangKey("en");

        final Map<String, Object> data = new HashMap<>();
        data.put("disbursementId", event.disbursementId());
        data.put("tenantId", event.tenantId());
        data.put("amount", event.amount());
        data.put("currency", event.currency());
        data.put("recipientMsisdn", event.recipientMsisdn());
        data.put("reference", event.reference() != null ? event.reference() : "");
        data.put("adminNote", event.adminNote() != null ? event.adminNote() : "");
        data.put("submittedAt", event.submittedAt() != null ? event.submittedAt().toString() : "");

        final Envelope envelope = new Envelope(
                List.of(recipient),
                EmailTemplate.DISBURSEMENT_ADMIN_APPROVAL_REQUIRED,
                Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
                data,
                UUID.randomUUID().toString()
        );

        publisher.publishEvent(envelope);
    }

    /**
     * ALERT-01: dispatch high-priority ops alert when provider returns Insufficient Funds.
     */
    @Transactional
    @EventListener
    public void onInsufficientFunds(InsufficientFundsAlertEvent event) {
        log.warn("Sending disbursement insufficient-funds alert email",
                kv("operation", "dsb_insufficient_funds_email_dispatched"),
                kv("disbursementId", event.disbursementId()),
                kv("provider", event.provider()),
                kv("amount", event.amount()),
                kv("errorCode", event.providerErrorCode()));

        final Recipient recipient = new Recipient();
        recipient.setEmail(notificationEmail);
        recipient.setLangKey("en");

        final Map<String, Object> data = new HashMap<>();
        data.put("disbursementId", event.disbursementId());
        data.put("tenantId", event.tenantId());
        data.put("provider", event.provider() != null ? event.provider().name() : "");
        data.put("amount", event.amount());
        data.put("currency", event.currency());
        data.put("providerErrorCode", event.providerErrorCode() != null ? event.providerErrorCode() : "");
        data.put("providerMessage", event.providerMessage() != null ? event.providerMessage() : "");
        data.put("failedAt", event.failedAt() != null ? event.failedAt().toString() : "");

        final Envelope envelope = new Envelope(
                List.of(recipient),
                EmailTemplate.DISBURSEMENT_INSUFFICIENT_FUNDS_ALERT,
                Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
                data,
                UUID.randomUUID().toString()
        );

        publisher.publishEvent(envelope);
    }
}
