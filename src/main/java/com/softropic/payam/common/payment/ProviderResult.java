package com.softropic.payam.common.payment;

public record ProviderResult(
    String providerRef,        // payToken (Orange) or financialTransactionId (MTN)
    String rawStatus,          // Raw status string from provider ("SUCCESSFULL", "PENDING", etc.)
    boolean pending,           // true if requires polling
    String errorCode,          // null on success
    String errorMessage        // null on success
) {
    public static ProviderResult pending(String providerRef, String rawStatus) {
        return new ProviderResult(providerRef, rawStatus, true, null, null);
    }

    public static ProviderResult success(String providerRef, String rawStatus) {
        return new ProviderResult(providerRef, rawStatus, false, null, null);
    }

    public static ProviderResult failure(String errorCode, String errorMessage) {
        return new ProviderResult(null, null, false, errorCode, errorMessage);
    }
}
