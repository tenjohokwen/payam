package com.softropic.payam.webhook.contract;

import com.softropic.payam.tenant.repo.Tenant;
import com.softropic.payam.webhook.repo.WebhookDeliveryLog;

/**
 * Internal Spring application event published by WebhookDeliveryService.enqueue() after the
 * delivery log row has been inserted and the enclosing transaction has committed.
 *
 * Consumed by WebhookDeliveryService.onFirstDeliveryRequested() via
 * @TransactionalEventListener(phase = AFTER_COMMIT) to fire the first HTTP delivery attempt
 * outside any transaction — so the DB connection is not held open during the HTTP call (H1).
 *
 * Carries the already-saved (detached after commit) delivery log and the pre-loaded tenant so
 * the listener does not need to issue any SELECT before the HTTP POST.
 */
public record WebhookFirstDeliveryEvent(WebhookDeliveryLog delivery, Tenant tenant) {}
