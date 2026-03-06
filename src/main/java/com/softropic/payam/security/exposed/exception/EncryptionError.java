package com.softropic.payam.security.exposed.exception;


import com.softropic.payam.common.exception.ErrorCode;

public enum EncryptionError implements ErrorCode {
    MISSING_SECRET,
    MISSING_TEXT,
    ENCRYPTION_ERROR,
    DECRYPTION_ERROR;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
