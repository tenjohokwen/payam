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
    MobilePaymentProvider provider,
    String clientIp,          // client IP from RequestMetadata (nullable — null if unavailable)
    String userAgent,         // User-Agent header (nullable)
    String deviceFingerprint  // X-Device-Fingerprint header value (nullable)
) {}
