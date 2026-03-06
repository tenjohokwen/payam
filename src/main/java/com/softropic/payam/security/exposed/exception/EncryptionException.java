package com.softropic.payam.security.exposed.exception;


import com.softropic.payam.common.exception.ApplicationException;
import com.softropic.payam.common.exception.ErrorCode;

public class EncryptionException extends ApplicationException {
    public EncryptionException(String msg,
                               ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public EncryptionException(String msg, Throwable cause, ErrorCode errorCode) {
        super(msg, cause, errorCode);
    }
}
