-- V30: Add transaction_status column to webhook_delivery_log so OutboundWebhookPayload
-- can derive status from an authoritative enum value rather than parsing eventType strings.
-- Required by Phase 52 SEC-06 disbursement event types DISBURSEMENT_COMPLETED / DISBURSEMENT_FAILED.

ALTER TABLE main.webhook_delivery_log
    ADD COLUMN IF NOT EXISTS transaction_status VARCHAR(20);

-- Backfill existing rows: derive from event_type for collection-era data so old rows still
-- serialize correctly if re-attempted by the retry job.
UPDATE main.webhook_delivery_log
   SET transaction_status = CASE
           WHEN event_type LIKE '%SUCCESS%' THEN 'SUCCESS'
           ELSE 'FAILED'
       END
 WHERE transaction_status IS NULL;

-- AUD table mirror (if present)
ALTER TABLE IF EXISTS main.webhook_delivery_log_aud
    ADD COLUMN IF NOT EXISTS transaction_status VARCHAR(20);
