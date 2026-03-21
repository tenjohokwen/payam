package com.softropic.payam.security.repo;





import com.softropic.payam.security.contract.PermutedSecretKey;

import jakarta.persistence.PostLoad;

/**
 *
 */
public class SecKeyEntityListener {

    @PostLoad
    public void derivePermKey(final SecKey secKey) {
        final String encrPermKey = secKey.getEncrPermKey();

        final String sequence = secKey.getSeq();

        String permKey = PermutedSecretKey.instanceFromEncryptedKey(encrPermKey, sequence).getPermKey();

        secKey.setPermKey(permKey);
    }
}
