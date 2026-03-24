package com.softropic.payam.common.payment;

import java.math.BigDecimal;

public record PaymentCommand(
    String transactionId,
    String traceId,
    Long tenantId,
    String msisdn,            // E.164 format — adapter must strip country code as needed
    BigDecimal amount,
    String currency,
    String externalReference,
    String idempotencyKey,
    MobilePaymentProvider provider
) {}
