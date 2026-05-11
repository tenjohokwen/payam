package com.softropic.payam.payment.provider.orange.service;

import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-01: Pins the @Transactional(timeout = 300) annotation on
 * {@link OrangeStatusPollerJob#executeInternal(JobExecutionContext)}.
 *
 * See {@link com.softropic.payam.payment.provider.mtn.service.MtnStatusPollerJobTimeoutTest} for rationale.
 */
class OrangeStatusPollerJobTimeoutTest {

    @Test
    void executeInternal_hasTransactionalTimeoutOf300Seconds() throws NoSuchMethodException {
        Method executeInternal = OrangeStatusPollerJob.class
                .getDeclaredMethod("executeInternal", JobExecutionContext.class);

        Transactional txAnnotation = executeInternal.getAnnotation(Transactional.class);

        assertThat(txAnnotation)
                .as("executeInternal must carry @Transactional")
                .isNotNull();
        assertThat(txAnnotation.timeout())
                .as("OPS-01: poller transaction timeout must be exactly 300 seconds")
                .isEqualTo(300);
    }
}
