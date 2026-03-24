package com.softropic.payam.payment.contract.exception;

/**
 * Thrown by {@link com.softropic.payam.payment.service.MsisdnRouter} when the MSISDN prefix
 * cannot be matched to any known Cameroonian mobile money operator (Orange, MTN).
 */
public class UnknownMsisdnPrefixException extends RuntimeException {

    private final String msisdn;

    public UnknownMsisdnPrefixException(String msisdn) {
        super("Unknown MSISDN prefix: " + msisdn);
        this.msisdn = msisdn;
    }

    public String getMsisdn() {
        return msisdn;
    }
}
