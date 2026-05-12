package com.softropic.payam.payment.core.contract;

public record SubscriberStatus(
    boolean active,
    String msisdn,
    String rawStatus   // "ACTIF", "INACTIF", or provider-specific value
) {}
