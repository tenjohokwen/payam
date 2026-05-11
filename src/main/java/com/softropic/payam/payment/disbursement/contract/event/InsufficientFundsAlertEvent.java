package com.softropic.payam.disbursement.contract.event;

import com.softropic.payam.common.payment.MobilePaymentProvider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ALERT-01: high-priority event published when a provider returns an
 * Insufficient Funds error during disbursement dispatch or callback.
 * The platform Ops team must top up the provider account.
 *
 * @param disbursementId UUID of the affected disbursement
 * @param tenantId       tenant whose disbursement failed
 * @param provider       MTN or ORANGE — the affected provider account
 * @param amount         requested amount (could not be transferred)
 * @param currency       ISO-4217 currency code
 * @param providerErrorCode raw provider error code (e.g. "NOT_ENOUGH_FUNDS")
 * @param providerMessage   human-readable provider error message
 * @param failedAt       when the failure was detected
 */
public record InsufficientFundsAlertEvent(
        String disbursementId,
        Long tenantId,
        MobilePaymentProvider provider,
        BigDecimal amount,
        String currency,
        String providerErrorCode,
        String providerMessage,
        Instant failedAt
) {}
