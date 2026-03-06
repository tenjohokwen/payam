package com.softropic.payam.security.exposed.util;



import com.softropic.payam.security.exposed.exception.EncryptionError;
import com.softropic.payam.security.exposed.exception.EncryptionException;

import org.apache.commons.lang3.StringUtils;
import org.jasypt.util.text.AES256TextEncryptor;

import static com.softropic.payam.security.exposed.exception.EncryptionError.DECRYPTION_ERROR;
import static com.softropic.payam.security.exposed.exception.EncryptionError.ENCRYPTION_ERROR;


public class Cryptopher {
    private final AES256TextEncryptor textEncryptor = new AES256TextEncryptor();

    public Cryptopher(String secret) {
        if(StringUtils.isBlank(secret)) {
            throw new EncryptionException("Cryptopher cannot be created without a PermutedSecretKey object",
                                          EncryptionError.MISSING_SECRET);
        }
        textEncryptor.setPassword(secret);
    }


    public  String encrypt(String message) {
        if(StringUtils.isBlank(message)) {
            throw new EncryptionException("Cryptopher encrypt a blank string",
                                          EncryptionError.MISSING_TEXT);
        }
        try {
            return textEncryptor.encrypt(message);
        }
        catch (Exception e) {
            throw new EncryptionException("Error while attempting to encrypt. Ensure Password has been set ", e, ENCRYPTION_ERROR);
        }
    }

    public String decrypt(String message) {
        try {
            return textEncryptor.decrypt(message);
        }
        catch (Exception e) {
            final String msg = "Error while attempting to decrypt. Ensure: 1. message was already encrypted 2. Password set 3. The set password was exactly the one used for encryption ";
            throw new EncryptionException(msg, e, DECRYPTION_ERROR);
        }
    }

}
