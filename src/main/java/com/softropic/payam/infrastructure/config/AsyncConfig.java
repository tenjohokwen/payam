package com.softropic.payam.infrastructure.config;

import com.softropic.payam.common.threadpool.MdcDecorator;
import com.softropic.payam.common.threadpool.TenantContextTaskDecorator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * General-purpose async executor configuration for multi-tenant payment processing.
 *
 * <p>This class is distinct from {@code com.softropic.payam.platform.notification.config.AsyncConfig},
 * which owns the email send pool ({@code "sendMailPool"} bean). Both classes co-exist
 * in different packages and declare different bean names — no conflict.
 *
 * <p>{@code @EnableAsync} is intentionally omitted: the email {@code AsyncConfig}
 * already activates it project-wide. Adding it again here would be harmless but redundant.
 *
 * <p>The task decorator chain composes {@link MdcDecorator} (MDC propagation) with
 * {@link TenantContextTaskDecorator} (tenant identity propagation) so that every
 * {@code @Async} task carries both logging context and the tenant identifier from
 * the originating request.
 */
@Configuration("tenantAsyncConfig")
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("payam-async-");

        // Compose MdcDecorator (existing) + TenantContextTaskDecorator (new)
        executor.setTaskDecorator(task -> {
            Runnable withMdc    = new MdcDecorator().decorate(task);
            Runnable withTenant = new TenantContextTaskDecorator().decorate(withMdc);
            return withTenant;
        });

        executor.initialize();
        return executor;
    }
}
