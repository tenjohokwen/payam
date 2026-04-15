-- V23: Enforce LEDGER-01 at the DB layer — at most one DEBIT and one CREDIT per entry_group_id.
-- Approach: DEFERRABLE INITIALLY DEFERRED unique constraint on (entry_group_id, direction).
-- Deferral is essential because LedgerService.postEntry() inserts both rows in one saveAll()
-- within a single @Transactional context; the constraint must check at commit, not per-row.

-- Step 1: pre-flight — fail fast with a diagnostic count if existing data violates the invariant.
-- Any violation here indicates prior bug state that must be investigated before the DDL runs,
-- because ADD CONSTRAINT UNIQUE fails instantly (and with a non-diagnostic message) on violations.
DO $$
DECLARE
    bad_count INT;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM (
        SELECT entry_group_id, direction
        FROM main.ledger_entry
        GROUP BY entry_group_id, direction
        HAVING COUNT(*) > 1
    ) violations;
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'LEDGER-01 pre-flight: % duplicate (entry_group_id, direction) pairs found in main.ledger_entry — clean up before migration', bad_count;
    END IF;
END $$;

-- Step 2: add the constraint. DEFERRABLE INITIALLY DEFERRED means PostgreSQL checks it at
-- transaction commit, not row-by-row — required for LedgerService.postEntry()'s saveAll pattern.
ALTER TABLE main.ledger_entry
    ADD CONSTRAINT uq_ledger_entry_group_direction
    UNIQUE (entry_group_id, direction)
    DEFERRABLE INITIALLY DEFERRED;
