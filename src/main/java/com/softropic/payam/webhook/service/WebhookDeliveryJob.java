package com.softropic.payam.webhook.service;

import com.softropic.payam.webhook.repo.WebhookDeliveryLog;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        List<WebhookDeliveryLog> pending = deliveryService.findPendingDeliveries();
        log.info("Webhook delivery job scan",
            kv("operation", "webhook_delivery_scan"),
            kv("pendingCount", pending.size()));
        for (WebhookDeliveryLog delivery : pending) {
            try {
                deliveryService.attemptDelivery(delivery);
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
