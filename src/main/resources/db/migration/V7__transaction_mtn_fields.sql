SET search_path = main;

ALTER TABLE transaction
    ADD COLUMN IF NOT EXISTS mtn_financial_tx_id VARCHAR(255);
-- MTN financialTransactionId from callback/status response — null on FAILED, absent for non-MTN transactions.
