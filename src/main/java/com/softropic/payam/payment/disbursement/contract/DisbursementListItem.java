package com.softropic.payam.payment.disbursement.contract;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Projection DTO returned in the paginated response body of GET /v1/disbursements.
 *
 * <p>Each item summarises one disbursement record without exposing internal fields
 * (idempotency key, metadata, tenant ID). The caller can use {@code disbursementId}
 * to fetch the full detail via GET /v1/disbursements/{disbursementId}.
 */
public record DisbursementListItem(

        /** Stable, externally visible disbursement identifier. */
        String disbursementId,

        /**
         * Current lifecycle status. One of: INITIATED, PENDING_CONFIRMATION,
         * PROCESSING, SUCCESS, FAILED, EXPIRED.
         */
        String status,

        /** Recipient MSISDN in E.164 or local format as stored. */
        String recipientMsisdn,

        /** Principal amount disbursed (or attempted). */
        BigDecimal amount,

        /** Fee charged for this disbursement. Zero when no fee rule matched. */
        BigDecimal fee,

        /** ISO-4217 currency code (e.g. "XAF"). */
        String currency,

        /** Tenant-side external reference supplied at creation time. */
        String reference,

        /**
         * Provider that processed (or is processing) this disbursement
         * (e.g. "MTN", "ORANGE"). Null if routing failed before provider selection.
         */
        String provider,

        /**
         * Provider's own reference token. Null until the provider call returns
         * a reference, or on pre-dispatch failures.
         */
        String providerRef,

        /** Timestamp when the disbursement row was created. */
        Instant createdAt,

        /**
         * Timestamp when the disbursement reached a terminal state
         * (SUCCESS, FAILED, or EXPIRED). Null while still in progress.
         */
        Instant completedAt

) {}
