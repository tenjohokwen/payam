package com.softropic.payam.email.api;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.utils.TestMailManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                        "ledger.database.spy=true",
                        "enable.test.mail=true",
                        "spring.cloud.compatibility-verifier.enabled=false"
                })
@Import(TestConfig.class)
public class MailManagerIT {

    @Autowired
    private MailManager mailManager;

    @Test
    void contextLoads() {
    }

    @Test
    void testTestMailManagerDispatchesEmails() {
        assertThat(mailManager).isInstanceOf(TestMailManager.class);
        TestMailManager testMailManager = (TestMailManager) mailManager;

        Recipient recipient = new Recipient();
        recipient.setEmail("test@example.com");

        String sendId = UUID.randomUUID().toString();
        Envelope envelope = new Envelope(
                List.of(recipient),
                EmailTemplate.ACTIVATION,
                Instant.now(),
                Map.of("activationKey", "12345"),
                sendId
        );

        mailManager.sendEmailFromTemplate(envelope);

        // Verify dispatch (TestMailManager stores it in memory)
        await().until(() -> testMailManager.getEnvelope(sendId) != null);
        Envelope received = testMailManager.getEnvelope(sendId);
        assertThat(received).isNotNull();
        assertThat(received.sendId()).isEqualTo(sendId);
        assertThat(received.data().get("activationKey")).isEqualTo("12345");
    }

    @Test
    void testTestMailManagerDispatchesEmailsSynchronously() {
        assertThat(mailManager).isInstanceOf(TestMailManager.class);
        TestMailManager testMailManager = (TestMailManager) mailManager;

        Recipient recipient = new Recipient();
        recipient.setEmail("test@example.com");

        String sendId = UUID.randomUUID().toString();
        Envelope envelope = new Envelope(
                List.of(recipient),
                EmailTemplate.ACTIVATION,
                Instant.now(),
                Map.of("activationKey", "12345"),
                sendId
        );

        mailManager.sendEmailSync(envelope);

        // Verify dispatch (TestMailManager stores it in memory)
        Envelope received = testMailManager.getEnvelope(sendId);
        assertThat(received).isNotNull();
        assertThat(received.sendId()).isEqualTo(sendId);
        assertThat(received.data().get("activationKey")).isEqualTo("12345");
    }

}
