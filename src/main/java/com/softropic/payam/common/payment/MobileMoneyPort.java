package com.softropic.payam.common.payment;

public interface MobileMoneyPort {
    /**
     * Initiate a merchant payment. Returns a ProviderResult containing
     * the provider reference and initial status. Called AFTER the Transaction row
     * has already been committed (separate @Transactional boundary — P1.1).
     */
    ProviderResult initiateMerchantPayment(PaymentCommand cmd);

    /**
     * Poll the provider for current transaction status.
     * @param providerRef the payToken (Orange) or financialTransactionId (MTN)
     */
    ProviderResult getTransactionStatus(String providerRef);

    /**
     * Validate that an MSISDN belongs to an active subscriber.
     */
    SubscriberStatus validateSubscriber(String msisdn);
}
