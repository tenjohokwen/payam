package com.softropic.payam.webhook.config;

import com.softropic.payam.webhook.service.WebhookDeliveryJob;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Quartz scheduler configuration for outbound webhook delivery retry job.
 *
 * Fires every 1 minute to pick up pending delivery log rows whose nextRetryAt has passed.
 * Uses Quartz JDBC store (configured in application.yaml) for durable scheduling that
 * survives JVM restart.
 */
@Configuration
public class WebhookSchedulerConfig {

    @Bean
    public JobDetail webhookDeliveryJobDetail() {
        return JobBuilder.newJob(WebhookDeliveryJob.class)
            .withIdentity("webhookDeliveryJob")
            .storeDurably()
            .build();
    }

    @Bean
    public Trigger webhookDeliveryTrigger(JobDetail webhookDeliveryJobDetail) {
        return TriggerBuilder.newTrigger()
            .forJob(webhookDeliveryJobDetail)
            .withIdentity("webhookDeliveryTrigger")
            .withSchedule(SimpleScheduleBuilder.repeatMinutelyForever(1))
            .build();
    }
}
