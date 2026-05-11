package com.softropic.payam.disbursement.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for POST /v1/disbursements.
 *
 * <p>Validated by {@code @Valid} in {@code DisbursementResource}. All JSON body fields are
 * bound directly. The {@code idempotencyKey} field is populated from the
 * {@code Idempotency-Key} HTTP header by the controller; it is not part of the JSON body.
 *
 * <p>Currency validation that the value is literally "XAF" is enforced in the orchestrator
 * layer, not at the annotation level — the annotation only enforces the 3-character length.
 */
public record DisbursementRequest(

        /**
         * Recipient MSISDN in E.164 format (e.g. +237692954629 or 237692954629).
         * Determines operator routing (MTN vs Orange). Max 20 characters.
         */
        @NotBlank
        @Size(max = 20)
        String recipientMsisdn,

        /**
         * XAF amount to disburse. Must be positive (> 0).
         */
        @NotNull
        @Positive
        BigDecimal amount,

        /**
         * ISO-4217 currency code. Must be exactly 3 characters (expected to be "XAF").
         */
        @NotBlank
        @Size(min = 3, max = 3)
        String currency,

        /**
         * Tenant-side external reference for reconciliation. Required. Max 50 characters.
         */
        @NotBlank
        @Size(max = 50)
        String reference,

        /**
         * Optional human-readable payee note (e.g. "Salary payment March 2026").
         * Passed through to the provider where supported. Max 140 characters.
         */
        @Size(max = 140)
        String description,

        /**
         * Optional JSON string carrying tenant-side metadata (stored as TEXT on the
         * disbursement record). Not forwarded to the provider. Max 2048 characters.
         */
        @Size(max = 2048)
        String metadata,

        /**
         * v11 TXN-01: non-empty list (max 500) of collection-transaction IDs that back
         * this disbursement. Each element is the logical transactionId (UUID string,
         * matches Transaction.transactionId / VARCHAR(36)). The orchestrator validates
         * tenant ownership, status (SUCCESS), flow (COLLECTION), no active claim
         * (TXN-03), and amount equality (TXN-04 — sum of transaction.amount - feeAmount
         * must equal request.amount).
         */
        @NotEmpty
        @Size(max = 500)
        List<@NotBlank String> transactionIds,

        /**
         * Required idempotency key sourced from the {@code Idempotency-Key} HTTP header.
         * Used to deduplicate concurrent or retried disbursement requests within 24 hours.
         * Not part of the JSON request body — the controller binds it from the header and
         * sets this field before passing the record to the orchestrator.
         *
         * <p>No {@code @NotBlank} here: the HTTP layer enforces header presence via
         * {@code @RequestHeader("Idempotency-Key")} (returns 400 if missing). Adding
         * {@code @NotBlank} would cause {@code @Valid} on the raw JSON body to fail before
         * the controller can inject the header value into the reconstructed record.
         */
        String idempotencyKey

) {}
