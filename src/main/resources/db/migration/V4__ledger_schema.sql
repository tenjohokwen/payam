CREATE TABLE main.ledger_entry (
    id              BIGINT PRIMARY KEY,
    transaction_id  VARCHAR(36) NOT NULL,
    entry_group_id  VARCHAR(36) NOT NULL,
    tenant_id       BIGINT NOT NULL REFERENCES main.tenant(id),
    direction       VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    account_code    VARCHAR(50) NOT NULL,
    amount          NUMERIC(20, 2) NOT NULL CHECK (amount > 0),
    currency        CHAR(3) NOT NULL,
    created_date    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_transaction_id  ON main.ledger_entry(transaction_id);
CREATE INDEX idx_ledger_entry_group_id  ON main.ledger_entry(entry_group_id);
CREATE INDEX idx_ledger_tenant_id       ON main.ledger_entry(tenant_id);
