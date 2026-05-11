package com.softropic.payam.payment.reconciliation.config;

import com.softropic.payam.payment.reconciliation.service.ReconciliationJob;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Quartz scheduler configuration for the daily reconciliation job.
 *
 * Fires every day at 02:00 UTC (cron: "0 0 2 * * ?") to reconcile transactions
 * from the previous UTC calendar day against provider records.
 *
 * Uses Quartz JDBC store (configured in application.yaml) for durable scheduling that
 * survives JVM restart — job state is persisted in QRTZ_* tables in the database.
 */
@Configuration
public class ReconciliationSchedulerConfig {

    @Bean
    public JobDetail reconciliationJobDetail() {
        return JobBuilder.newJob(ReconciliationJob.class)
            .withIdentity("reconciliationJob")
            .storeDurably()
            .build();
    }

    @Bean
    public Trigger reconciliationTrigger(JobDetail reconciliationJobDetail) {
        return TriggerBuilder.newTrigger()
            .forJob(reconciliationJobDetail)
            .withIdentity("reconciliationTrigger")
            .withSchedule(CronScheduleBuilder.cronSchedule("0 0 2 * * ?"))
            .build();
    }
}
