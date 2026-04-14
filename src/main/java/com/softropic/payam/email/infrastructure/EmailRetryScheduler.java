package com.softropic.payam.email.infrastructure;

import com.softropic.payam.email.contract.EmailDeliveryStatus;
import com.softropic.payam.email.contract.Envelope;
import com.softropic.payam.email.repo.EnvelopeEntity;
import com.softropic.payam.email.repo.EnvelopeEntityRepository;
import com.softropic.payam.email.service.EnvelopeMapper;
import com.softropic.payam.email.service.MailManager;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled job that retries email deliveries that previously failed with a retryable error.
 *
 * <p>Each invocation issues a {@code SELECT FOR UPDATE SKIP LOCKED} query so that concurrent
 * application instances each claim a disjoint batch of rows and never race over the same
 * envelope. The transaction started by {@link Transactional} keeps those row-locks alive
 * until all retries in the batch are finished, at which point the updated statuses are
 * committed atomically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailRetryScheduler {

    private final EnvelopeEntityRepository envelopeEntityRepository;
    private final MailManager mailManager;

    /**
     * Maximum number of scheduler-level retries permitted per envelope.
     * When {@code attempts} reaches this value the envelope is marked
     * {@link EmailDeliveryStatus#ATTEMPTS_EXHAUSTED} and no further send is attempted.
     *
     * <p>Interpretation A: the initial send (via the event listener) is not a retry.
     * {@code attempts} is incremented once per {@code sendEmailSync} call, so a value of 6
     * means 1 initial + 5 scheduler retries have already occurred.
     */
    static final long MAX_RETRY_ATTEMPTS = 6;

    /**
     * Polls for failed, retryable emails and re-attempts delivery.
     *
     * <p>Runs with a fixed delay (default 60 s, overridable via {@code email.retry.interval-ms})
     * so a slow batch always completes before the next one starts.
     *
     * <p>The {@code SELECT FOR UPDATE SKIP LOCKED} in {@link EnvelopeEntityRepository#fetchFailedEmails()}
     * ensures that in a multi-instance deployment each pod works on a distinct set of rows.
     *
     * <p>Before each send attempt the following pre-checks are applied in order:
     * <ol>
     *   <li>If the envelope's deadline has passed it is marked
     *       {@link EmailDeliveryStatus#DEADLINE_EXPIRED} and skipped.</li>
     *   <li>If {@code attempts >= MAX_RETRY_ATTEMPTS} it is marked
     *       {@link EmailDeliveryStatus#ATTEMPTS_EXHAUSTED} and skipped.</li>
     * </ol>
     * In both cases {@code retry} is set to {@code false} so the row is never fetched again.
     */
    @Observed(name = "scheduler.email-retry")
    @Scheduled(fixedDelayString = "${email.retry.interval-ms:60000}")
    @Transactional
    public void retryFailedEmails() {
        List<EnvelopeEntity> candidates = envelopeEntityRepository.fetchFailedEmails();
        if (candidates.isEmpty()) {
            return;
        }
        log.info("Retrying failed emails", kv("count", candidates.size()));
        for (EnvelopeEntity entity : candidates) {
            if (Instant.now().isAfter(entity.getDeadline())) {
                log.warn("Email deadline expired", kv("sendId", entity.getSendId()), kv("newStatus", "DEADLINE_EXPIRED"));
                entity.setStatus(EmailDeliveryStatus.DEADLINE_EXPIRED);
                entity.setRetry(false);
                continue;
            }
            if (entity.getAttempts() >= MAX_RETRY_ATTEMPTS) {
                log.warn("Email attempts exhausted", kv("sendId", entity.getSendId()), kv("attempts", entity.getAttempts()), kv("newStatus", "ATTEMPTS_EXHAUSTED"));
                entity.setStatus(EmailDeliveryStatus.ATTEMPTS_EXHAUSTED);
                entity.setRetry(false);
                continue;
            }
            try {
                Envelope envelope = EnvelopeMapper.toEnvelope(entity);
                mailManager.sendEmailSync(envelope);
            } catch (Exception e) {
                log.error("Email retry attempt failed", kv("sendId", entity.getSendId()), e);
            }
        }
    }
}
