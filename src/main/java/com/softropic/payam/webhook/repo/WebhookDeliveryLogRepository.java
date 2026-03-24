package com.softropic.payam.webhook.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLog, Long> {

    List<WebhookDeliveryLog> findByTransactionIdOrderByCreatedDateAsc(String transactionId);

    /**
     * For Quartz retry job: find pending deliveries whose retry time has passed.
     * Returns rows where delivered=false, nextRetryAt is due, and attemptCount is below max.
     */
    @Query("SELECT w FROM WebhookDeliveryLog w WHERE w.delivered = false AND w.nextRetryAt <= :now AND w.attemptCount < :maxAttempts")
    List<WebhookDeliveryLog> findPendingForRetry(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);
}
