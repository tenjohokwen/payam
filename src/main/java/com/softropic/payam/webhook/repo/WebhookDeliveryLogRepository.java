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
     * Returns at most {@code limit} rows where delivered=false, nextRetryAt is due, and
     * attemptCount is below max.
     *
     * FOR UPDATE SKIP LOCKED: rows already claimed by another node are silently skipped,
     * so two cluster nodes never process the same delivery row concurrently.
     * ORDER BY next_retry_at ASC: deterministic priority — oldest-due deliveries first.
     */
    @Query(value = "SELECT * FROM main.webhook_delivery_log" +
                   " WHERE delivered = false" +
                   " AND next_retry_at <= :now" +
                   " AND attempt_count < :maxAttempts" +
                   " ORDER BY next_retry_at ASC" +
                   " LIMIT :limit" +
                   " FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<WebhookDeliveryLog> findPendingForRetry(@Param("now") Instant now,
                                                 @Param("maxAttempts") int maxAttempts,
                                                 @Param("limit") int limit);
}
