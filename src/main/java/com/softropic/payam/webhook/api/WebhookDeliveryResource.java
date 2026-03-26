package com.softropic.payam.webhook.api;

import com.softropic.payam.security.common.util.SecurityConstants;
import com.softropic.payam.webhook.repo.WebhookDeliveryLog;
import com.softropic.payam.webhook.service.WebhookDeliveryService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST resource for querying outbound webhook delivery status.
 *
 * Requires JWT authentication (standard secured endpoint under /v1/**).
 */
@RestController
@RequestMapping("/v1/admin/webhooks")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public class WebhookDeliveryResource {

    private final WebhookDeliveryService deliveryService;

    public WebhookDeliveryResource(WebhookDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * Returns all delivery log entries for a transaction, ordered by creation date ascending.
     * Each entry shows attempt count, HTTP status, delivered flag, and next retry time.
     */
    @GetMapping("/deliveries/{transactionId}")
    public ResponseEntity<List<WebhookDeliveryLog>> getDeliveries(
            @PathVariable String transactionId) {
        return ResponseEntity.ok(deliveryService.getDeliveries(transactionId));
    }
}
