package com.softropic.payam.payment.provider.orange.config;

import com.softropic.payam.payment.provider.orange.service.OrangeStatusPollerJob;

import org.quartz.DateBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrangeSchedulerConfig {

    @Bean
    public JobDetail orangePollerJobDetail() {
        return JobBuilder.newJob(OrangeStatusPollerJob.class)
            .withIdentity("orange-status-poller")
            .storeDurably()
            .build();
    }

    @Bean
    public Trigger orangePollerTrigger(JobDetail orangePollerJobDetail, OrangeMoneyConfig config) {
        return TriggerBuilder.newTrigger()
            .forJob(orangePollerJobDetail)
            .withIdentity("orange-status-poller-trigger")
            .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInSeconds(config.getPoller().getIntervalSeconds())
                .repeatForever()
                .withMisfireHandlingInstructionNextWithRemainingCount())
            .startAt(DateBuilder.futureDate(config.getPoller().getInitialDelaySeconds(), DateBuilder.IntervalUnit.SECOND))
            .build();
    }
}
