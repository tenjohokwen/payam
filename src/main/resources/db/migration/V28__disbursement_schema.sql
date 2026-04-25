-- V28: Disbursement Schema + Merchant Wallet Balance
-- Creates: main.disbursement, main.disbursement_aud,
--          main.merchant_wallet_balance, main.merchant_wallet_balance_aud
-- NOTE: disbursement lifecycle column is named disbursement_status (not status)
--       to avoid collision with AbstractAuditingEntity.status (EntityStatus).

-- ============================================================
-- Table: main.merchant_wallet_balance_aud (Envers)
-- Created before the base table so the _aud table exists whether
-- or not the base table was applied in a prior run.
-- ============================================================
CREATE TABLE IF NOT EXISTS main.merchant_wallet_balance_aud (
    id BIGINT NOT NULL,
    rev INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype SMALLINT,
    tenant_id BIGINT,
    balance NUMERIC(20, 2),
    reserved_amount NUMERIC(20, 2),
    currency CHAR(3),
    version BIGINT,
    status VARCHAR(20),
    created_by VARCHAR(50),
    created_date TIMESTAMP,
    last_modified_by VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id VARCHAR(255),
    session_id TEXT,
    PRIMARY KEY (id, rev)
);

-- ============================================================
-- Table: main.merchant_wallet_balance
-- One row per tenant. balance is the AVAILABLE (spendable) amount.
-- reserved_amount tracks total earmarked across open disbursements.
-- version column reserved for future optimistic-lock uses;
-- pessimistic write lock (@Lock PESSIMISTIC_WRITE) is the BAL-01 strategy.
-- ============================================================
CREATE TABLE IF NOT EXISTS main.merchant_wallet_balance (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    balance NUMERIC(20, 2) NOT NULL DEFAULT 0,
    reserved_amount NUMERIC(20, 2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'XAF',
    version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR(50),
    created_date TIMESTAMP,
    last_modified_by VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id VARCHAR(255),
    session_id TEXT,
    CONSTRAINT pk_merchant_wallet_balance PRIMARY KEY (id),
    CONSTRAINT uq_wallet_tenant_id UNIQUE (tenant_id),
    CONSTRAINT chk_wallet_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_wallet_reserved_non_negative CHECK (reserved_amount >= 0)
);

-- ============================================================
-- Table: main.disbursement
-- Tracks individual payout requests.
-- disbursement_status is the lifecycle column (NOT status — that is owned
-- by AbstractAuditingEntity for EntityStatus).
-- reserved_amount stores the exact amount deducted from the wallet on
-- reservation; used for precise release on FAILED terminal state.
-- ============================================================
CREATE TABLE IF NOT EXISTS main.disbursement (
    id BIGINT NOT NULL,
    disbursement_id VARCHAR(36) NOT NULL,
    tenant_id BIGINT NOT NULL,
    recipient_msisdn VARCHAR(50) NOT NULL,
    amount NUMERIC(20, 2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'XAF',
    reference VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    disbursement_status VARCHAR(30) NOT NULL,
    provider VARCHAR(20),
    provider_ref VARCHAR(255),
    idempotency_key VARCHAR(255),
    reserved_amount NUMERIC(20, 2),
    metadata TEXT,
    created_by VARCHAR(50),
    created_date TIMESTAMP,
    last_modified_by VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id VARCHAR(255),
    session_id TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT pk_disbursement PRIMARY KEY (id),
    CONSTRAINT uq_disbursement_id UNIQUE (disbursement_id),
    CONSTRAINT chk_disbursement_amount_positive CHECK (amount > 0)
);

-- Index for tenant-scoped queries (GET /v1/disbursements)
CREATE INDEX IF NOT EXISTS idx_disbursement_tenant_id ON main.disbursement (tenant_id);

-- ============================================================
-- Table: main.disbursement_aud (Envers)
-- Mirrors @Audited fields on the Disbursement entity.
-- All audited fields are nullable in _aud tables (Envers pattern).
-- ============================================================
CREATE TABLE IF NOT EXISTS main.disbursement_aud (
    id BIGINT NOT NULL,
    rev INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype SMALLINT,
    disbursement_id VARCHAR(36),
    tenant_id BIGINT,
    recipient_msisdn VARCHAR(50),
    amount NUMERIC(20, 2),
    currency CHAR(3),
    reference VARCHAR(255),
    description VARCHAR(500),
    disbursement_status VARCHAR(30),
    provider VARCHAR(20),
    provider_ref VARCHAR(255),
    idempotency_key VARCHAR(255),
    reserved_amount NUMERIC(20, 2),
    metadata TEXT,
    status VARCHAR(20),
    created_by VARCHAR(50),
    created_date TIMESTAMP,
    last_modified_by VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id VARCHAR(255),
    session_id TEXT,
    PRIMARY KEY (id, rev)
);
