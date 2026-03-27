package com.softropic.payam.e2e.builder;

import com.softropic.payam.mtn.contract.MtnCallbackPayload;

import java.util.UUID;

/**
 * Fluent builder for {@link MtnCallbackPayload} test data (BUILD-04).
 *
 * <p>Defaults to a SUCCESSFUL callback. Use {@link #asFailed(String)} to produce a
 * failed callback with status=FAILED, financialTransactionId=null.
 *
 * <p>Usage:
 * <pre>
 *     MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
 *         .forTransaction(transactionId)
 *         .asSuccessful()
 *         .build();
 * </pre>
 */
public class MtnWebhookPayloadBuilder {

    private String externalId;
    private String status = "SUCCESSFUL";
    private String financialTransactionId = UUID.randomUUID().toString();
    private String reason = null;

    /**
     * Sets the externalId (the Payam transactionId used as the correlation key).
     */
    public MtnWebhookPayloadBuilder forTransaction(String transactionId) {
        this.externalId = transactionId;
        return this;
    }

    public MtnWebhookPayloadBuilder withStatus(String status) {
        this.status = status;
        return this;
    }

    public MtnWebhookPayloadBuilder withFinancialTransactionId(String financialTransactionId) {
        this.financialTransactionId = financialTransactionId;
        return this;
    }

    public MtnWebhookPayloadBuilder withReason(String reason) {
        this.reason = reason;
        return this;
    }

    /**
     * Configures the payload as a successful callback with a random financialTransactionId.
     */
    public MtnWebhookPayloadBuilder asSuccessful() {
        this.status = "SUCCESSFUL";
        this.reason = null;
        return this;
    }

    /**
     * Configures the payload as a failed callback.
     * Sets status=FAILED, clears financialTransactionId (absent on failure per MTN spec).
     */
    public MtnWebhookPayloadBuilder asFailed(String reason) {
        this.status = "FAILED";
        this.financialTransactionId = null;
        this.reason = reason;
        return this;
    }

    /**
     * Builds and returns an {@link MtnCallbackPayload} with all configured fields.
     */
    public MtnCallbackPayload build() {
        MtnCallbackPayload payload = new MtnCallbackPayload();
        payload.setExternalId(externalId);
        payload.setStatus(status);
        payload.setFinancialTransactionId(financialTransactionId);
        payload.setReason(reason);
        return payload;
    }
}
