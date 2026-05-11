package com.softropic.payam.payment.webhook.service;

import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.webhook.contract.OutboundWebhookPayload;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliveryServicePayloadTest {

    @Test
    void successPayloadStatusIsSUCCESS_forCollectionEventType() {
        OutboundWebhookPayload p = OutboundWebhookPayload.of(
            "tx-1", TransactionStatus.SUCCESS, "PROVIDER_SUCCESS",
            Instant.now().toString(), "ext-ref", BigDecimal.ZERO);
        assertThat(p.status()).isEqualTo("SUCCESS");
        assertThat(p.eventType()).isEqualTo("PROVIDER_SUCCESS");
    }

    @Test
    void failedPayloadStatusIsFAILED_forCollectionEventType() {
        OutboundWebhookPayload p = OutboundWebhookPayload.of(
            "tx-2", TransactionStatus.FAILED, "PROVIDER_FAILED",
            Instant.now().toString(), "ext-ref", BigDecimal.ZERO);
        assertThat(p.status()).isEqualTo("FAILED");
    }

    @Test
    void successPayloadStatusIsSUCCESS_forDisbursementEventType() {
        OutboundWebhookPayload p = OutboundWebhookPayload.of(
            "dsb-1", TransactionStatus.SUCCESS, "DISBURSEMENT_COMPLETED",
            Instant.now().toString(), "merchant-ref", new BigDecimal("5.00"));
        assertThat(p.status()).isEqualTo("SUCCESS");  // pre-fix BUG returned FAILED
        assertThat(p.eventType()).isEqualTo("DISBURSEMENT_COMPLETED");
        assertThat(p.feeAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    void failedPayloadStatusIsFAILED_forDisbursementEventType() {
        OutboundWebhookPayload p = OutboundWebhookPayload.of(
            "dsb-2", TransactionStatus.FAILED, "DISBURSEMENT_FAILED",
            Instant.now().toString(), "merchant-ref", BigDecimal.ZERO);
        assertThat(p.status()).isEqualTo("FAILED");
    }

    @Test
    void payloadStatusIsAuthoritativeOverEventTypeName() {
        OutboundWebhookPayload p = OutboundWebhookPayload.of(
            "tx-3", TransactionStatus.FAILED, "PROVIDER_SUCCESS",  // mismatched on purpose
            Instant.now().toString(), "ext", BigDecimal.ZERO);
        assertThat(p.status()).isEqualTo("FAILED");  // status follows enum, not name
    }
}
