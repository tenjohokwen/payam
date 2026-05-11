package com.softropic.payam.payment.disbursement.config;

import com.softropic.payam.payment.disbursement.service.DisbursementAdminApprovalExpiryJob;
import com.softropic.payam.payment.disbursement.service.DisbursementExpiryJob;

import org.quartz.CronScheduleBuilder;
import org.quartz.DateBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the DisbursementExpiryJob with Quartz to run every 60 seconds (SEC-04).
 *
 * <p>Pattern mirrors MtnSchedulerConfig — durable JobDetail + Trigger with a 60s
 * SimpleSchedule and a small future-start to avoid firing during application startup
 * before the JDBC pool and Hibernate are fully initialized.
 *
 * <p>The 60-second cadence is INDEPENDENT of the 15-minute expiry window (EXPIRY_AGE
 * inside the job): the job runs every minute but only transitions rows older than
 * 15 minutes. Choosing a faster cadence than the expiry window simply means rows are
 * expired within ~60 seconds of crossing the 15-minute mark.
 */
@Configuration
public class DisbursementSchedulerConfig {

    @Bean
    public JobDetail disbursementExpiryJobDetail() {
        return JobBuilder.newJob(DisbursementExpiryJob.class)
                .withIdentity("disbursement-expiry-job")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger disbursementExpiryTrigger(JobDetail disbursementExpiryJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(disbursementExpiryJobDetail)
                .withIdentity("disbursement-expiry-trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(60)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .startAt(DateBuilder.futureDate(30, DateBuilder.IntervalUnit.SECOND))
                .build();
    }

    @Bean
    public JobDetail disbursementAdminApprovalExpiryJobDetail() {
        return JobBuilder.newJob(DisbursementAdminApprovalExpiryJob.class)
                .withIdentity("disbursement-admin-approval-expiry-job")
                .storeDurably()
                .build();
    }

    /**
     * Trigger for the admin-approval expiry job. Cron is configurable via
     * {@code payam.disbursement.admin-approval-expiry-cron} (default {@code 0 * * * * ?} —
     * every minute at second 0). Uses cron rather than a SimpleSchedule because the
     * timeout window is configurable in HOURS — administrators may want to tune cadence
     * to match longer/shorter timeouts.
     *
     * <p>Identity {@code disbursement-admin-approval-expiry-trigger} is intentionally distinct
     * from {@code disbursement-expiry-trigger} to prevent Quartz identity collisions (Pitfall 6).
     *
     * <p>45-second future-start staggers initial execution so both jobs do not fire
     * simultaneously during the same context-startup window.
     */
    @Bean
    public Trigger disbursementAdminApprovalExpiryTrigger(
            JobDetail disbursementAdminApprovalExpiryJobDetail,
            @Value("${payam.disbursement.admin-approval-expiry-cron}") String cron) {
        return TriggerBuilder.newTrigger()
                .forJob(disbursementAdminApprovalExpiryJobDetail)
                .withIdentity("disbursement-admin-approval-expiry-trigger")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .withMisfireHandlingInstructionDoNothing())
                .startAt(DateBuilder.futureDate(45, DateBuilder.IntervalUnit.SECOND))
                .build();
    }
}
