package com.softropic.payam.payment.ledger.contract;

/**
 * Ledger flow classification used by LedgerService.postEntry to route to
 * flow-specific entry builders. COLLECTION = customer -> provider (2 entries).
 * DISBURSEMENT = merchant -> customer + fee (3 entries).
 */
public enum LedgerFlow {
    COLLECTION,
    DISBURSEMENT
}
