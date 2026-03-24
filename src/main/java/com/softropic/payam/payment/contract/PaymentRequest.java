package com.softropic.payam.payment.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request DTO for payment initiation via POST /v1/payments.
 *
 * <p>All fields are validated by {@code @Valid} in {@link com.softropic.payam.payment.api.PaymentResource}.
 * The {@code idempotencyKey} must be provided by the client and is used to deduplicate
 * concurrent or retried payment requests within a 24-hour window.
 */
public record PaymentRequest(

        /** E.164 format MSISDN (e.g. +237692954629). Determines operator routing. */
        @NotBlank
        String msisdn,

        /** Payment amount. Must be positive. */
        @NotNull
        @Positive
        BigDecimal amount,

        /** ISO-4217 currency code (e.g. XAF). */
        @NotBlank
        @Size(min = 3, max = 3)
        String currency,

        /** Merchant-side reference for reconciliation. Optional but recommended. */
        String externalReference,

        /** Client-generated idempotency key. Required to prevent double charges on retry. */
        @NotBlank
        String idempotencyKey
) {}
