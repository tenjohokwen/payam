package com.softropic.payam.payment.provider.mtn.config;

import com.softropic.payam.payment.provider.mtn.service.MtnStatusPollerJob;

import org.quartz.DateBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MtnSchedulerConfig {

    @Bean
    public JobDetail mtnPollerJobDetail() {
        return JobBuilder.newJob(MtnStatusPollerJob.class)
            .withIdentity("mtn-status-poller")
            .storeDurably()
            .build();
    }

    @Bean
    public Trigger mtnPollerTrigger(JobDetail mtnPollerJobDetail, MtnMoMoConfig config) {
        return TriggerBuilder.newTrigger()
            .forJob(mtnPollerJobDetail)
            .withIdentity("mtn-status-poller-trigger")
            .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInSeconds(config.getPoller().getIntervalSeconds())
                .repeatForever()
                .withMisfireHandlingInstructionNextWithRemainingCount())
            .startAt(DateBuilder.futureDate(config.getPoller().getInitialDelaySeconds(), DateBuilder.IntervalUnit.SECOND))
            .build();
    }
}
