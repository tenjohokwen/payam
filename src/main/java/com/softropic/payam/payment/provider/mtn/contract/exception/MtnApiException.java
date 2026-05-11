package com.softropic.payam.payment.provider.mtn.contract.exception;

import com.softropic.payam.common.exception.ApplicationException;

public class MtnApiException extends ApplicationException {
    public MtnApiException(String message) { super(message); }
    public MtnApiException(String message, Throwable cause) {
        super(message);
        this.initCause(cause);
    }
}
