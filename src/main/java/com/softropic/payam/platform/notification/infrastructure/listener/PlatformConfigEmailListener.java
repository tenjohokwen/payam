package com.softropic.payam.platform.notification.infrastructure.listener;

import com.softropic.payam.common.ClockProvider;
import com.softropic.payam.platform.notification.contract.EmailTemplate;
import com.softropic.payam.platform.notification.contract.Envelope;
import com.softropic.payam.platform.notification.contract.Recipient;
import com.softropic.payam.platform.admin.contract.event.PlatformConfigChangedEvent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Slf4j
@Component
public class PlatformConfigEmailListener {

    private final ApplicationEventPublisher publisher;
    private final String notificationEmail;

    public PlatformConfigEmailListener(ApplicationEventPublisher publisher,
                                       @Value("${payam.platform.notification-email}") String notificationEmail) {
        this.publisher = publisher;
        this.notificationEmail = notificationEmail;
    }

    @Transactional
    @EventListener
    public void onConfigChanged(PlatformConfigChangedEvent event) {
        log.info("Sending platform config change notification email",
            kv("provider", event.provider()),
            kv("event", "platform_config_email_dispatched"));

        final Recipient recipient = new Recipient();
        recipient.setEmail(notificationEmail);
        recipient.setLangKey("en");

        final Map<String, Object> data = new HashMap<>();
        data.put("provider", event.provider());
        data.put("oldMsisdn", event.oldMsisdn() != null ? event.oldMsisdn() : "");
        data.put("newMsisdn", event.newMsisdn());
        data.put("msisdnChanged", event.msisdnChanged());
        data.put("pinChanged", event.pinChanged());
        data.put("changedBy", event.changedBy() != null ? event.changedBy() : "unknown");
        data.put("changedAt", Instant.now(ClockProvider.getClock()).toString());

        final Envelope envelope = new Envelope(
            List.of(recipient),
            EmailTemplate.PLATFORM_CONFIG_CHANGED,
            Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
            data,
            UUID.randomUUID().toString()
        );

        publisher.publishEvent(envelope);
    }
}
