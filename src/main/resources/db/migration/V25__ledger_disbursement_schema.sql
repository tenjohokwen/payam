-- V25: Ledger disbursement schema support
-- (1) Preflight: assert no unbalanced entry groups exist
-- (2) Drop uq_ledger_entry_group_direction (V23) — incompatible with 3-entry disbursement groups
-- (3) Replace with deferrable balance-check trigger (SUM(DEBIT) == SUM(CREDIT) per group at commit)
-- (4) Relax ledger_entry.amount CHECK from (amount > 0) to (amount >= 0)
-- (5) Add nullable flow VARCHAR(20) to main.transaction and main.transaction_aud (Envers parity)

-- ============================================================
-- Step 1: Pre-flight — verify no unbalanced entry groups exist
-- ============================================================
DO $$
DECLARE
    bad_count INT;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM (
        SELECT entry_group_id
        FROM main.ledger_entry
        GROUP BY entry_group_id
        HAVING COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'),  0) <>
               COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
    ) unbalanced;
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'V25 pre-flight: % unbalanced entry_group_id(s) in main.ledger_entry — fix before migration', bad_count;
    END IF;
END $$;

-- ============================================================
-- Step 2: Drop V23 unique constraint
-- ============================================================
ALTER TABLE main.ledger_entry
    DROP CONSTRAINT IF EXISTS uq_ledger_entry_group_direction;

-- ============================================================
-- Step 3: Replace with deferrable balance-check trigger
-- AFTER INSERT only (ledger_entry is @Immutable — no UPDATE/DELETE paths)
-- DEFERRABLE INITIALLY DEFERRED so that multi-row groups (LedgerService.saveAll)
-- are checked at commit time, not per-row insert.
-- COALESCE to 0 is critical — without it, NULL != number silently passes.
-- ============================================================
CREATE OR REPLACE FUNCTION main.check_ledger_balance()
    RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    debit_sum  NUMERIC;
    credit_sum NUMERIC;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'),  0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO debit_sum, credit_sum
      FROM main.ledger_entry
     WHERE entry_group_id = NEW.entry_group_id;

    IF debit_sum <> credit_sum THEN
        RAISE EXCEPTION
            'Ledger balance violation: entry_group_id=% has DEBIT sum=% != CREDIT sum=%',
            NEW.entry_group_id, debit_sum, credit_sum;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_balance_check
    AFTER INSERT ON main.ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION main.check_ledger_balance();

-- ============================================================
-- Step 4: Relax amount CHECK (amount > 0) → (amount >= 0)
-- V4 created the CHECK inline without a name — discover auto-generated
-- constraint name via pg_constraint and drop it dynamically.
-- ============================================================
DO $$
DECLARE
    v_conname TEXT;
BEGIN
    SELECT conname INTO v_conname
    FROM pg_constraint
    WHERE conrelid = 'main.ledger_entry'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%amount%>%0%'
      AND pg_get_constraintdef(oid) NOT LIKE '%>=%';
    IF v_conname IS NOT NULL THEN
        EXECUTE 'ALTER TABLE main.ledger_entry DROP CONSTRAINT ' || quote_ident(v_conname);
    END IF;
END $$;

ALTER TABLE main.ledger_entry
    ADD CONSTRAINT chk_ledger_amount_non_negative CHECK (amount >= 0);

-- ============================================================
-- Step 5: Add flow column to transaction and transaction_aud
-- ============================================================
ALTER TABLE main.transaction
    ADD COLUMN IF NOT EXISTS flow VARCHAR(20);

-- Envers AUD parity: create transaction_aud if absent (V20 never created it).
-- Column list mirrors @Audited fields on Transaction.java (excludes @NotAudited:
-- risk_score, device_fingerprint, fee_amount, fee_rule_id).
-- CREATE TABLE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS is idempotent — safe
-- regardless of whether Envers auto-created the table in a prior dev run.
CREATE TABLE IF NOT EXISTS main.transaction_aud (
    id                  BIGINT      NOT NULL,
    rev                 INTEGER     NOT NULL REFERENCES main.revinfo(rev),
    revtype             SMALLINT,
    transaction_id      VARCHAR(36),
    trace_id            VARCHAR(255),
    external_reference  VARCHAR(255),
    tenant_id           BIGINT,
    tx_status           VARCHAR(20),
    status              VARCHAR(20),
    provider            VARCHAR(20),
    amount              NUMERIC(20, 2),
    currency            CHAR(3),
    provider_ref        VARCHAR(255),
    mtn_financial_tx_id VARCHAR(255),
    pay_token           VARCHAR(255),
    pay_token_issued_at TIMESTAMP,
    poll_attempts       INTEGER,
    flow                VARCHAR(20),
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    PRIMARY KEY (id, rev)
);

ALTER TABLE main.transaction_aud
    ADD COLUMN IF NOT EXISTS flow VARCHAR(20);

COMMENT ON COLUMN main.transaction.flow IS
    'Ledger flow direction for this transaction — NULL for pre-v9 rows (interpreted as COLLECTION); COLLECTION or DISBURSEMENT for v9+ rows. Mapped by Transaction.flow via @Enumerated(STRING) in Phase 47.';
