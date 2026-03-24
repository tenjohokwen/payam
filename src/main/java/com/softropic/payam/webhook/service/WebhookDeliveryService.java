package com.softropic.payam.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.tenant.repo.Tenant;
import com.softropic.payam.tenant.repo.TenantRepository;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.webhook.contract.OutboundWebhookPayload;
import com.softropic.payam.webhook.repo.WebhookDeliveryLog;
import com.softropic.payam.webhook.repo.WebhookDeliveryLogRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Outbound webhook delivery service.
 *
 * enqueue() — persists a delivery log row for a terminal transaction event.
 * attemptDelivery() — performs the HTTP POST to the tenant's webhookUrl with HMAC-SHA256 signing.
 * findPendingDeliveries() — returns rows eligible for retry (used by WebhookDeliveryJob).
 * getDeliveries() — returns all delivery log rows for a transactionId (used by API resource).
 *
 * HMAC algorithm: javax.crypto.Mac with HmacSHA256 — NOT DigestUtils.sha256Hex.
 * Signature format: "sha256=<64 hex chars>" in X-Payam-Signature header.
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final WebhookDeliveryLogRepository repo;
    private final TenantRepository tenantRepository;
    private final RestTemplate noRetryRestTemplate;
    private final ObjectMapper objectMapper;

    public WebhookDeliveryService(WebhookDeliveryLogRepository repo,
                                   TenantRepository tenantRepository,
                                   @Qualifier("noRetryRestTemplate") RestTemplate noRetryRestTemplate,
                                   ObjectMapper objectMapper) {
        this.repo = repo;
        this.tenantRepository = tenantRepository;
        this.noRetryRestTemplate = noRetryRestTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist a pending delivery log row for the given terminal transaction event.
     * If the tenant has no webhookUrl configured, no row is created and no error is raised.
     *
     * externalReference is stored in the log row and echoed in the outbound payload.
     */
    @Transactional
    public void enqueue(String transactionId, Long tenantId, String eventType,
                        TransactionStatus status, String externalReference) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null || tenant.getWebhookUrl() == null || tenant.getWebhookUrl().isBlank()) {
            log.debug("No webhook URL configured for tenantId={} — skipping delivery", tenantId);
            return;
        }
        WebhookDeliveryLog entry = WebhookDeliveryLog.builder()
            .transactionId(transactionId)
            .tenantId(tenantId)
            .webhookUrl(tenant.getWebhookUrl())
            .eventType(eventType)
            .externalReference(externalReference)
            .nextRetryAt(Instant.now()) // eligible immediately for first attempt
            .build();
        repo.save(entry);
    }

    /**
     * Attempt delivery of a pending webhook. Updates the log row with attempt outcome.
     * On success (2xx): marks delivered=true, nextRetryAt=null.
     * On failure (non-2xx or exception): schedules exponential-backoff retry.
     *
     * HMAC signing is skipped if tenant has no webhookSecret configured (sandbox mode).
     */
    @Transactional
    public void attemptDelivery(WebhookDeliveryLog delivery) {
        Tenant tenant = tenantRepository.findById(delivery.getTenantId()).orElse(null);
        if (tenant == null) {
            log.warn("Tenant not found for delivery tenantId={} — skipping", delivery.getTenantId());
            return;
        }

        OutboundWebhookPayload payload = new OutboundWebhookPayload(
            delivery.getTransactionId(),
            delivery.getEventType().contains("SUCCESS") ? "SUCCESS" : "FAILED",
            delivery.getEventType(),
            Instant.now().toString(),
            delivery.getExternalReference()
        );

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize outbound webhook payload for transactionId={}: {}",
                delivery.getTransactionId(), e.getMessage());
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // HMAC-SHA256 signature — skip if no secret configured (sandbox mode, Pitfall 6)
        // Algorithm: javax.crypto.Mac with HmacSHA256 — NOT DigestUtils.sha256Hex (plain SHA-256)
        if (tenant.getWebhookSecret() != null && !tenant.getWebhookSecret().isBlank()) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(
                    tenant.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
                byte[] hmacBytes = mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
                String signature = "sha256=" +
                    org.apache.commons.codec.binary.Hex.encodeHexString(hmacBytes);
                headers.set("X-Payam-Signature", signature);
            } catch (Exception e) {
                log.error("HMAC signing failed for transactionId={}: {}",
                    delivery.getTransactionId(), e.getMessage());
                // Do not abort delivery — send unsigned; tenant can detect missing header
            }
        }

        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(Instant.now());

        try {
            ResponseEntity<String> response = noRetryRestTemplate.exchange(
                delivery.getWebhookUrl(), HttpMethod.POST,
                new HttpEntity<>(payloadJson, headers), String.class);

            int httpStatus = response.getStatusCode().value();
            delivery.setHttpStatus(httpStatus);

            if (httpStatus >= 200 && httpStatus < 300) {
                delivery.setDelivered(true);
                delivery.setNextRetryAt(null);
                log.info("Webhook delivered: transactionId={}, url={}, status={}",
                    delivery.getTransactionId(), delivery.getWebhookUrl(), httpStatus);
            } else {
                log.warn("Webhook delivery non-2xx: transactionId={}, status={}",
                    delivery.getTransactionId(), httpStatus);
                scheduleRetry(delivery);
            }
        } catch (Exception e) {
            log.warn("Webhook delivery failed (exception): transactionId={}: {}",
                delivery.getTransactionId(), e.getMessage());
            scheduleRetry(delivery);
        }

        repo.save(delivery);
    }

    /**
     * Schedule the next retry with exponential backoff: 2^attemptCount minutes, capped at 60.
     * If MAX_ATTEMPTS is reached, sets nextRetryAt=null (no more retries scheduled).
     */
    private void scheduleRetry(WebhookDeliveryLog delivery) {
        if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {
            log.warn("Max delivery attempts ({}) reached for transactionId={} — giving up",
                MAX_ATTEMPTS, delivery.getTransactionId());
            delivery.setDelivered(false); // stays false — queryable as permanently failed
            delivery.setNextRetryAt(null); // null = no more retries
            return;
        }
        long delayMinutes = Math.min((long) Math.pow(2, delivery.getAttemptCount()), 60);
        delivery.setNextRetryAt(Instant.now().plus(delayMinutes, ChronoUnit.MINUTES));
    }

    /**
     * Find all delivery log rows eligible for retry: not yet delivered, nextRetryAt due, below max attempts.
     */
    public List<WebhookDeliveryLog> findPendingDeliveries() {
        return repo.findPendingForRetry(Instant.now(), MAX_ATTEMPTS);
    }

    /**
     * Return all delivery log rows for a transactionId ordered by creation date ascending.
     */
    public List<WebhookDeliveryLog> getDeliveries(String transactionId) {
        return repo.findByTransactionIdOrderByCreatedDateAsc(transactionId);
    }
}
