-- V24: Create platform_config_aud (missing from V20) + add nullable pin column (PIN-01)
--
-- platform_config_aud was never created in V20 (only tenant_aud and tenant_api_key_aud).
-- PlatformConfig inherits @Audited from AbstractAuditingEntity, and hibernate.ddl-auto=none
-- prevents Envers from auto-creating the table. This migration creates it now and adds
-- the pin column to both tables.

-- Step 1: Create the missing Envers AUD table for platform_config (V20 pattern)
CREATE TABLE IF NOT EXISTS main.platform_config_aud (
    id                 BIGINT    NOT NULL,
    rev                INTEGER   NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    provider           VARCHAR(20),
    platform_msisdn    VARCHAR(20),
    pin                VARCHAR(500),
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);

-- Step 2: Add nullable pin column to the base table
-- Safe on existing rows: nullable column, no default, instant DDL in PostgreSQL.
ALTER TABLE main.platform_config
    ADD COLUMN IF NOT EXISTS pin VARCHAR(500);

COMMENT ON COLUMN main.platform_config.pin IS
    'AES256-encrypted PIN for this provider; NULL when no PIN has been set; ciphertext only — never plaintext';
