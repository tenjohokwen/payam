-- V29: Add poll_attempts column to disbursement and disbursement_aud
-- Required by DisbursementStatusPollerJob (PROV-01, PROV-02 5-minute fallback) — Phase 52.
-- Mirrors transaction.poll_attempts (added in V7); default 0 so existing rows are not NULL.

ALTER TABLE main.disbursement
    ADD COLUMN IF NOT EXISTS poll_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE main.disbursement_aud
    ADD COLUMN IF NOT EXISTS poll_attempts INTEGER;
