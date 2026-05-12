package com.softropic.payam.platform.security.contract.exception;


import com.softropic.payam.infrastructure.exception.ApplicationException;
import com.softropic.payam.infrastructure.exception.ErrorCode;

public class EncryptionException extends ApplicationException {
    public EncryptionException(String msg,
                               ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public EncryptionException(String msg, Throwable cause, ErrorCode errorCode) {
        super(msg, cause, errorCode);
    }
}
