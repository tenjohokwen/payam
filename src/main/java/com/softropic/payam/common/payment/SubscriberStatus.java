package com.softropic.payam.common.payment;

public record SubscriberStatus(
    boolean active,
    String msisdn,
    String rawStatus   // "ACTIF", "INACTIF", or provider-specific value
) {}
