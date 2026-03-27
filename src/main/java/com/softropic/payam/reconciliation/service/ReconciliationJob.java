package com.softropic.payam.reconciliation.service;

import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Quartz job that fires daily at 02:00 UTC to run the reconciliation pipeline.
 *
 * Extends QuartzJobBean — executeInternal is called by QuartzJobBean.execute() which is final.
 * @Transactional on executeInternal is effective because Spring AOP proxies the non-final
 * executeInternal method (QuartzJobBean.execute() -> proxy.executeInternal() -> this).
 *
 * Reconciles transactions from the previous UTC calendar day against provider records.
 * Top-level exceptions are caught and logged to prevent Quartz from unscheduling the trigger.
 */
@Component
public class ReconciliationJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    @Autowired
    private ReconciliationService reconciliationService;

    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        try {
            reconciliationService.runForDate(yesterday);
        } catch (Exception e) {
            // Catch top-level exceptions to prevent Quartz from unscheduling the trigger
            log.error("Reconciliation job fatal error",
                kv("operation", "reconciliation_run"),
                kv("date", yesterday),
                kv("status", "FATAL_ERROR"),
                e);
        }
    }
}
