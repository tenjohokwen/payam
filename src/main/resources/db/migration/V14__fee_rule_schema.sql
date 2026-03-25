CREATE TABLE IF NOT EXISTS main.fee_rule (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    tenant_id           BIGINT       REFERENCES main.tenant(id),
    fee_type            VARCHAR(20)  NOT NULL,
    fixed_amount        NUMERIC(20,2),
    percentage_rate     NUMERIC(5,4),
    currency            CHAR(3),
    rule_name           VARCHAR(200) NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    description         VARCHAR(500)
);

-- Seed default global rule (id=1): 0.00 XAF fixed — effectively no fee until overridden via admin API
INSERT INTO main.fee_rule (id, fee_type, fixed_amount, currency, rule_name, enabled, status, version)
VALUES (1, 'FEE_FIXED', 0.00, 'XAF', 'Default (no fee)', true, 'ACTIVE', 0)
ON CONFLICT DO NOTHING;

ALTER TABLE main.transaction
    ADD COLUMN IF NOT EXISTS fee_amount  NUMERIC(20,2),
    ADD COLUMN IF NOT EXISTS fee_rule_id BIGINT REFERENCES main.fee_rule(id);

COMMENT ON TABLE main.fee_rule IS 'Configurable fee rules — global (tenant_id IS NULL) or per-tenant; hot-reloaded without restart';
COMMENT ON COLUMN main.fee_rule.tenant_id IS 'NULL = global rule applied to all tenants; non-NULL = overrides global for that tenant';
COMMENT ON COLUMN main.fee_rule.fee_type IS 'FEE_FIXED (fixed_amount) or FEE_PERCENTAGE (percentage_rate)';
COMMENT ON COLUMN main.transaction.fee_amount IS 'Computed fee amount at payment initiation; NULL for pre-Phase-10 transactions';
COMMENT ON COLUMN main.transaction.fee_rule_id IS 'Fee rule applied at initiation time; NULL for pre-Phase-10 transactions';
