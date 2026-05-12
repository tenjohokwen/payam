package com.softropic.payam.payment.core.contract;

public interface MobileMoneyPort {
    /**
     * Initiate a merchant payment (collection). Returns a ProviderResult containing
     * the provider reference and initial status. Called AFTER the Transaction row
     * has already been committed (separate @Transactional boundary — P1.1).
     */
    ProviderResult initiateMerchantPayment(PaymentCommand cmd);

    /**
     * Initiate a disbursement (payout/transfer). Returns a ProviderResult.
     * Implementation should post a ledger entry to record the money-moving event.
     */
    ProviderResult initiateDisbursement(PaymentCommand cmd);

    /**
     * Poll the provider for current COLLECTION transaction status.
     * @param providerRef the payToken (Orange) or referenceId (MTN)
     */
    ProviderResult getCollectionTransactionStatus(String providerRef);

    /**
     * Poll the provider for current DISBURSEMENT transaction status.
     * @param providerRef the payToken (Orange) or referenceId (MTN)
     */
    ProviderResult getDisbursementTransactionStatus(String providerRef);

    /**
     * Validate that an MSISDN belongs to an active subscriber.
     */
    SubscriberStatus validateSubscriber(String msisdn);
}
