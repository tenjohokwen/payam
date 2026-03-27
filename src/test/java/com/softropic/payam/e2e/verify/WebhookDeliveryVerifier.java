package com.softropic.payam.e2e.verify;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.commons.codec.binary.Hex;
import org.awaitility.Awaitility;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIF-07: Asserts outbound webhook delivery, HMAC signature correctness, and retry scheduling.
 *
 * Uses Awaitility for async delivery assertions — webhook delivery is triggered via
 * TransactionalEventListener(AFTER_COMMIT) and may not be immediately visible after HTTP response.
 *
 * HMAC computation uses the identical algorithm as production WebhookDeliveryService:
 *   Mac.getInstance("HmacSHA256") + org.apache.commons.codec.binary.Hex.encodeHexString
 * Signature format: "sha256=<hex>"
 * Header name: "X-Payam-Signature"
 */
public class WebhookDeliveryVerifier {

    private final JdbcTemplate jdbc;
    private final WireMockServer tenantCallbackServer;

    public WebhookDeliveryVerifier(JdbcTemplate jdbc, WireMockServer tenantCallbackServer) {
        this.jdbc = jdbc;
        this.tenantCallbackServer = tenantCallbackServer;
    }

    /**
     * Waits up to 5 seconds for at least one delivered=true row in webhook_delivery_log
     * for the given transactionId.
     */
    public void assertDelivered(String transactionId) {
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                Integer delivered = jdbc.queryForObject(
                    "SELECT count(*) FROM main.webhook_delivery_log " +
                    "WHERE transaction_id = ? AND delivered = true",
                    Integer.class, transactionId);
                assertThat(delivered)
                    .as("delivered webhook count for transactionId=%s", transactionId)
                    .isGreaterThanOrEqualTo(1);
            });
    }

    /**
     * Asserts that the callback server received a POST request with an X-Payam-Signature header
     * matching the format "sha256=.*".
     */
    public void assertHmacHeaderPresent(WireMockServer callbackServer, String transactionId) {
        callbackServer.verify(postRequestedFor(anyUrl())
            .withHeader("X-Payam-Signature", matching("sha256=.*")));
    }

    /**
     * Recomputes HMAC-SHA256 over the payload and asserts it matches the received signature.
     * Uses the identical algorithm and encoding as production WebhookDeliveryService:
     *   Mac.getInstance("HmacSHA256") + Hex.encodeHexString (Apache commons-codec)
     * Expected format: "sha256=<hex>"
     */
    public void assertHmacSignatureCorrect(String payloadJson, String webhookSecret,
                                           String receivedSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" +
                Hex.encodeHexString(mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8)));
            assertThat(receivedSignature)
                .as("HMAC-SHA256 signature")
                .isEqualTo(expected);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new AssertionError("Failed to compute HMAC-SHA256 for signature verification", e);
        }
    }

    /**
     * Asserts that at least one webhook_delivery_log row exists with next_retry_at IS NOT NULL
     * and delivered=false, indicating a retry has been scheduled.
     */
    public void assertRetryScheduled(String transactionId) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM main.webhook_delivery_log " +
            "WHERE transaction_id = ? AND next_retry_at IS NOT NULL AND delivered = false",
            Integer.class, transactionId);
        assertThat(count)
            .as("retry-scheduled webhook count for transactionId=%s", transactionId)
            .isGreaterThanOrEqualTo(1);
    }
}
