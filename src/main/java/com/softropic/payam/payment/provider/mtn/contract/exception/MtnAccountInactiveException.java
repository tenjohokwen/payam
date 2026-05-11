package com.softropic.payam.payment.provider.mtn.contract.exception;

public class MtnAccountInactiveException extends RuntimeException {
    public MtnAccountInactiveException(String msisdn) {
        super("MTN account inactive: " + msisdn);
    }
}
