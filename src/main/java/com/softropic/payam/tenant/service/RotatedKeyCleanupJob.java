package com.softropic.payam.tenant.service;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RotatedKeyCleanupJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(RotatedKeyCleanupJob.class);

    @Autowired
    private ApiKeyService apiKeyService;

    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        int revokedCount = apiKeyService.revokeExpiredRotatedKeys();
        if (revokedCount > 0) {
            log.info("Rotated key cleanup complete",
                kv("operation", "rotated_key_cleanup"),
                kv("revokedCount", revokedCount));
        }
    }
}
