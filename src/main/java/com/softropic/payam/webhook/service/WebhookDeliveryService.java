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

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
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
     * Persist a delivery log row and immediately attempt the first delivery.
     *
     * If the tenant has no webhookUrl configured, no row is created and no error is raised.
     * externalReference is stored in the log row and echoed in the outbound payload.
     *
     * First attempt is made synchronously here (in the same REQUIRES_NEW transaction from
     * WebhookTransitionService). Failed attempts schedule exponential-backoff retries via
     * the Quartz WebhookDeliveryJob. This design ensures delivery attempt happens promptly
     * without waiting for the next Quartz fire (every 1 minute).
     *
     * The inbound response path is unaffected — this runs in @TransactionalEventListener
     * AFTER_COMMIT, i.e., after the inbound HTTP response has been sent (Pitfall 5).
     */
    @Transactional
    public void enqueue(String transactionId, Long tenantId, String eventType,
                        TransactionStatus status, String externalReference, BigDecimal feeAmount) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null || tenant.getWebhookUrl() == null || tenant.getWebhookUrl().isBlank()) {
            log.warn("No webhook URL configured",
                kv("operation", "webhook_delivery"),
                kv("tenantId", tenantId),
                kv("status", "SKIPPED_NO_URL"));
            return;
        }
        WebhookDeliveryLog entry = WebhookDeliveryLog.builder()
            .transactionId(transactionId)
            .tenantId(tenantId)
            .webhookUrl(tenant.getWebhookUrl())
            .eventType(eventType)
            .externalReference(externalReference)
            // nextRetryAt intentionally null on INSERT — only set after a failed attempt.
            // findPendingForRetry queries WHERE nextRetryAt <= :now, so null rows are skipped
            // by the Quartz job until the inline first attempt sets nextRetryAt on failure.
            .build();
        entry.setFeeAmount(feeAmount != null ? feeAmount : BigDecimal.ZERO);
        repo.save(entry);

        // Attempt delivery immediately — self-invocation bypasses Spring proxy but joins the
        // enclosing @Transactional(REQUIRES_NEW) context directly, which is correct here.
        attemptDeliveryInternal(entry, tenant);
        repo.save(entry);
    }

    /**
     * Attempt delivery of a pending webhook (used by WebhookDeliveryJob for retries).
     * Loads the tenant and delegates to attemptDeliveryInternal.
     */
    @Transactional
    public void attemptDelivery(WebhookDeliveryLog delivery) {
        Tenant tenant = tenantRepository.findById(delivery.getTenantId()).orElse(null);
        if (tenant == null) {
            log.warn("Tenant not found for webhook delivery",
                kv("operation", "webhook_delivery"),
                kv("tenantId", delivery.getTenantId()),
                kv("status", "TENANT_NOT_FOUND"));
            return;
        }
        attemptDeliveryInternal(delivery, tenant);
        repo.save(delivery);
    }

    /**
     * Core delivery logic: builds the signed payload, POSTs to tenant URL, updates log row state.
     * Called from enqueue() (first attempt, tenant already loaded) and attemptDelivery() (retries).
     *
     * HMAC-SHA256 signature — skip if no secret configured (sandbox mode, Pitfall 6).
     * Algorithm: javax.crypto.Mac with HmacSHA256 — NOT DigestUtils.sha256Hex (plain SHA-256).
     * On success (2xx): marks delivered=true, nextRetryAt=null.
     * On failure (non-2xx or exception): schedules exponential-backoff retry.
     */
    private void attemptDeliveryInternal(WebhookDeliveryLog delivery, Tenant tenant) {
        OutboundWebhookPayload payload = new OutboundWebhookPayload(
            delivery.getTransactionId(),
            delivery.getEventType().contains("SUCCESS") ? "SUCCESS" : "FAILED",
            delivery.getEventType(),
            Instant.now().toString(),
            delivery.getExternalReference(),
            delivery.getFeeAmount() != null ? delivery.getFeeAmount() : BigDecimal.ZERO
        );

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize webhook payload",
                kv("operation", "webhook_delivery"),
                kv("transactionId", delivery.getTransactionId()),
                kv("status", "SERIALIZATION_ERROR"),
                e);
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
                log.error("HMAC signing failed",
                    kv("operation", "webhook_delivery"),
                    kv("transactionId", delivery.getTransactionId()),
                    kv("status", "HMAC_ERROR"),
                    e);
                // Do not abort delivery — send unsigned; tenant can detect missing header
            }
        }

        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(Instant.now());

        long deliveryStart = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = noRetryRestTemplate.exchange(
                delivery.getWebhookUrl(), HttpMethod.POST,
                new HttpEntity<>(payloadJson, headers), String.class);

            int httpStatus = response.getStatusCode().value();
            delivery.setHttpStatus(httpStatus);

            if (httpStatus >= 200 && httpStatus < 300) {
                delivery.setDelivered(true);
                delivery.setNextRetryAt(null);
                log.info("Webhook delivery",
                    kv("operation", "webhook_delivery"),
                    kv("transactionId", delivery.getTransactionId()),
                    kv("tenantId", tenant.getTenantRef()),
                    kv("durationMs", System.currentTimeMillis() - deliveryStart),
                    kv("httpStatus", httpStatus),
                    kv("status", "SUCCESS"),
                    kv("retryCount", delivery.getAttemptCount()));
            } else {
                log.warn("Webhook delivery",
                    kv("operation", "webhook_delivery"),
                    kv("transactionId", delivery.getTransactionId()),
                    kv("tenantId", tenant.getTenantRef()),
                    kv("durationMs", System.currentTimeMillis() - deliveryStart),
                    kv("httpStatus", httpStatus),
                    kv("status", "FAILED"),
                    kv("retryCount", delivery.getAttemptCount()));
                scheduleRetry(delivery);
            }
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // HTTP error response (4xx/5xx) — extract status for logging/querying
            delivery.setHttpStatus(e.getStatusCode().value());
            log.warn("Webhook delivery",
                kv("operation", "webhook_delivery"),
                kv("transactionId", delivery.getTransactionId()),
                kv("tenantId", tenant.getTenantRef()),
                kv("durationMs", System.currentTimeMillis() - deliveryStart),
                kv("httpStatus", e.getStatusCode().value()),
                kv("status", "FAILED"),
                kv("retryCount", delivery.getAttemptCount()));
            scheduleRetry(delivery);
        } catch (Exception e) {
            log.warn("Webhook delivery",
                kv("operation", "webhook_delivery"),
                kv("transactionId", delivery.getTransactionId()),
                kv("tenantId", tenant.getTenantRef()),
                kv("durationMs", System.currentTimeMillis() - deliveryStart),
                kv("httpStatus", -1),
                kv("status", "FAILED"),
                kv("retryCount", delivery.getAttemptCount()));
            scheduleRetry(delivery);
        }
    }

    /**
     * Schedule the next retry with exponential backoff: 2^attemptCount minutes, capped at 60.
     * If MAX_ATTEMPTS is reached, sets nextRetryAt=null (no more retries scheduled).
     */
    private void scheduleRetry(WebhookDeliveryLog delivery) {
        if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {
            log.warn("Max delivery attempts reached",
                kv("operation", "webhook_delivery"),
                kv("transactionId", delivery.getTransactionId()),
                kv("maxAttempts", MAX_ATTEMPTS),
                kv("status", "MAX_RETRIES_EXCEEDED"));
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
