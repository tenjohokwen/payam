package com.softropic.payam.e2e.builder;

import com.softropic.payam.payment.contract.PaymentRequest;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fluent builder for {@link PaymentRequest} test data (BUILD-03).
 *
 * <p>Defaults to an MTN payment (Cameroon MTN prefix 2376 — V16 migration seeds this route).
 * Call {@link #forOrange()} to switch to the Orange MSISDN prefix.
 *
 * <p>Usage:
 * <pre>
 *     PaymentRequest req = new PaymentRequestBuilder()
 *         .withDeterministicIdempotencyKey(uuids)
 *         .build();
 *
 *     PaymentRequest orangeReq = new PaymentRequestBuilder()
 *         .forOrange()
 *         .withAmount(new BigDecimal("500.00"))
 *         .build();
 * </pre>
 */
public class PaymentRequestBuilder {

    /** MTN Cameroon MSISDN prefix — seeded by V16 migration as the MTN route. */
    private static final String MTN_MSISDN = "237690000001";

    /** Orange Cameroon MSISDN prefix — seeded by V16 migration as the Orange route. */
    private static final String ORANGE_MSISDN = "237690000002";

    private String msisdn = MTN_MSISDN;
    private BigDecimal amount = new BigDecimal("1000.00");
    private String currency = "XAF";
    private String idempotencyKey = UUID.randomUUID().toString();
    private String deviceFingerprint = "test-device-fp-001";
    private String externalReference = null;

    public PaymentRequestBuilder withMsisdn(String msisdn) {
        this.msisdn = msisdn;
        return this;
    }

    public PaymentRequestBuilder withAmount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public PaymentRequestBuilder withCurrency(String currency) {
        this.currency = currency;
        return this;
    }

    public PaymentRequestBuilder withIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        return this;
    }

    public PaymentRequestBuilder withDeviceFingerprint(String deviceFingerprint) {
        this.deviceFingerprint = deviceFingerprint;
        return this;
    }

    public PaymentRequestBuilder withExternalReference(String externalReference) {
        this.externalReference = externalReference;
        return this;
    }

    /**
     * Switches the MSISDN to the Orange prefix (237690000002) for Orange payment flows.
     */
    public PaymentRequestBuilder forOrange() {
        this.msisdn = ORANGE_MSISDN;
        return this;
    }

    /**
     * Sets a deterministic idempotency key using the provided factory.
     * Ensures reproducible payment deduplication keys across test runs.
     */
    public PaymentRequestBuilder withDeterministicIdempotencyKey(DeterministicUuidFactory factory) {
        this.idempotencyKey = factory.next().toString();
        return this;
    }

    /**
     * Builds and returns a {@link PaymentRequest} with all configured fields.
     */
    public PaymentRequest build() {
        return new PaymentRequest(msisdn, amount, currency, externalReference, idempotencyKey, deviceFingerprint);
    }
}
