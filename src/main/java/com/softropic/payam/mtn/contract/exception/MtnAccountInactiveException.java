package com.softropic.payam.mtn.contract.exception;

public class MtnAccountInactiveException extends RuntimeException {
    public MtnAccountInactiveException(String msisdn) {
        super("MTN account inactive: " + msisdn);
    }
}
