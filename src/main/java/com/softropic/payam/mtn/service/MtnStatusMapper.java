package com.softropic.payam.mtn.service;

import com.softropic.payam.transaction.contract.TransactionStatus;

public final class MtnStatusMapper {

    private MtnStatusMapper() {}

    /**
     * Map MTN provider status to internal TransactionStatus.
     * // MTN status strings: SUCCESSFUL (single-L), FAILED, PENDING
     * Note: unlike Orange which uses "SUCCESSFULL" (double-L), MTN uses single-L "SUCCESSFUL".
     */
    public static TransactionStatus toInternal(String mtnStatus) {
        if (mtnStatus == null) return TransactionStatus.PROCESSING;
        return switch (mtnStatus.toUpperCase()) {
            case "SUCCESSFUL" -> TransactionStatus.SUCCESS;
            case "FAILED"     -> TransactionStatus.FAILED;
            case "PENDING"    -> TransactionStatus.PROCESSING;
            default           -> TransactionStatus.PROCESSING;
        };
    }
}
