package com.softropic.payam.orange.service;

import com.softropic.payam.transaction.contract.TransactionStatus;

public final class OrangeStatusMapper {

    private OrangeStatusMapper() {}

    /**
     * Map Orange provider status to internal TransactionStatus.
     * "SUCCESSFULL" uses double-L — that is the correct Orange spelling.
     */
    public static TransactionStatus toInternal(String orangeStatus) {
        if (orangeStatus == null) return TransactionStatus.PROCESSING;
        return switch (orangeStatus.toUpperCase()) {
            case "SUCCESSFULL" -> TransactionStatus.SUCCESS;
            case "FAILED"      -> TransactionStatus.FAILED;
            case "EXPIRED"     -> TransactionStatus.FAILED;
            case "INITIATED", "PENDING" -> TransactionStatus.PROCESSING;
            default -> TransactionStatus.PROCESSING;
        };
    }
}
