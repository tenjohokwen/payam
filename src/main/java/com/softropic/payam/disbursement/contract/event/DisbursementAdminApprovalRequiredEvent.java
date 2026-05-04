package com.softropic.payam.disbursement.contract.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ADMIN-01/ADMIN-02: published when a disbursement transitions to
 * PENDING_ADMIN_APPROVAL. Triggers a best-effort ops notification email
 * (handled by DisbursementOpsEmailListener — created in Plan 03).
 *
 * @param disbursementId UUID of the gated disbursement
 * @param tenantId       tenant that submitted the request
 * @param amount         disbursement amount (above admin-approval threshold)
 * @param currency       ISO-4217 currency code
 * @param recipientMsisdn destination MSISDN
 * @param reference      tenant-supplied external reference
 * @param adminNote      ops-only note recorded on the disbursement row
 * @param submittedAt    when the disbursement was submitted
 */
public record DisbursementAdminApprovalRequiredEvent(
        String disbursementId,
        Long tenantId,
        BigDecimal amount,
        String currency,
        String recipientMsisdn,
        String reference,
        String adminNote,
        Instant submittedAt
) {}
