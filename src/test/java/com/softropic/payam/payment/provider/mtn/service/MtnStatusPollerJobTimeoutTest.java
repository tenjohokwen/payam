package com.softropic.payam.payment.provider.mtn.service;

import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-01: Pins the @Transactional(timeout = 300) annotation on
 * {@link MtnStatusPollerJob#executeInternal(JobExecutionContext)}.
 *
 * This reflection-based test fails loudly if:
 *  - the @Transactional annotation is removed or replaced,
 *  - the timeout attribute is dropped (default is -1),
 *  - the value is accidentally changed from seconds to milliseconds (e.g. 300000).
 *
 * The 300-second value matches the Quartz re-fire interval so a hung tick
 * cannot hold a DB connection across successive ticks.
 */
class MtnStatusPollerJobTimeoutTest {

    @Test
    void executeInternal_hasTransactionalTimeoutOf300Seconds() throws NoSuchMethodException {
        Method executeInternal = MtnStatusPollerJob.class
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
