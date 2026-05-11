package com.softropic.payam.payment.provider.orange.contract.exception;

public class SubscriberInactiveException extends RuntimeException {
    public SubscriberInactiveException(String msisdn) {
        super("Orange subscriber is inactive: " + msisdn);
    }
}
