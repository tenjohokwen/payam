package com.softropic.payam.webhook.service;

import com.softropic.payam.tenant.repo.Tenant;
import com.softropic.payam.webhook.repo.WebhookDeliveryLog;

import static net.logstash.logback.argument.StructuredArguments.kv;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quartz job that polls for pending outbound webhook deliveries and retries them.
 *
 * Extends QuartzJobBean — executeInternal is called by QuartzJobBean.execute() which is final.
 * @Transactional on executeInternal is effective because Spring AOP proxies the non-final
 * executeInternal method (QuartzJobBean.execute() -> proxy.executeInternal() -> this).
 *
 * Uses Quartz JDBC store — delivery state survives JVM restart.
 * NOT @Retryable — in-memory retry state is lost on restart; Quartz JDBC is the durability mechanism.
 */
@Component
public class WebhookDeliveryJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryJob.class);

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private ObservationRegistry observationRegistry;

    /**
     * Quartz entry point. @Observed cannot advise this protected method via Spring AOP
     * (the parent class calls it via self-invocation, bypassing the proxy), so we use
     * a programmatic Observation that produces the same span + timer as @Observed would.
     */
    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        Observation.createNotStarted("quartz.webhook-delivery", observationRegistry)
                .lowCardinalityKeyValue("job", "WebhookDeliveryJob")
                .observe(this::runDelivery);
    }

    private void runDelivery() {
        List<WebhookDeliveryLog> pending = deliveryService.findPendingDeliveries();
        log.info("Webhook delivery job scan",
            kv("operation", "webhook_delivery_scan"),
            kv("pendingCount", pending.size()));
        if (pending.isEmpty()) {
            return;
        }
        Set<Long> tenantIds = pending.stream()
            .map(WebhookDeliveryLog::getTenantId)
            .collect(Collectors.toSet());
        // WEBHOOK-01: one tenant SELECT per tick regardless of pending-delivery count.
        Map<Long, Tenant> tenantMap = deliveryService.loadTenants(tenantIds);
        for (WebhookDeliveryLog delivery : pending) {
            try {
                deliveryService.attemptDelivery(delivery, tenantMap.get(delivery.getTenantId()));
            } catch (Exception e) {
                log.warn("Webhook delivery attempt failed",
                    kv("operation", "webhook_delivery"),
                    kv("transactionId", delivery.getTransactionId()),
                    kv("status", "ERROR"),
                    e);
            }
        }
    }
}
