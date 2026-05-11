package com.softropic.payam.payment.provider.orange.contract;

public enum OrangeStatus {
    INITIATED,
    PENDING,
    SUCCESSFULL,  // Orange uses double-L — not a typo
    FAILED,
    EXPIRED
}
