CREATE TABLE main.transaction (
    id                  BIGINT PRIMARY KEY,
    transaction_id      VARCHAR(36) UNIQUE NOT NULL,
    trace_id            VARCHAR(255) NOT NULL,
    external_reference  VARCHAR(255),
    tenant_id           BIGINT NOT NULL REFERENCES main.tenant(id),
    tx_status           VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    provider            VARCHAR(20) NOT NULL,
    amount              NUMERIC(20, 2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    provider_ref        VARCHAR(255),
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT
);

CREATE INDEX idx_transaction_tenant_id    ON main.transaction(tenant_id);
CREATE INDEX idx_transaction_external_ref ON main.transaction(external_reference);
CREATE INDEX idx_transaction_provider_ref ON main.transaction(provider_ref);

CREATE TABLE main.payment_event_log (
    id                  BIGINT PRIMARY KEY,
    transaction_id      VARCHAR(36) NOT NULL,
    trace_id            VARCHAR(255) NOT NULL,
    external_reference  VARCHAR(255),
    event_type          VARCHAR(50) NOT NULL,
    status_from         VARCHAR(20),
    status_to           VARCHAR(20) NOT NULL,
    actor               VARCHAR(100) NOT NULL,
    metadata            JSONB,
    previous_hash       VARCHAR(64) NOT NULL,
    event_hash          VARCHAR(64) NOT NULL,
    created_date        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_event_log_transaction_id ON main.payment_event_log(transaction_id);
CREATE INDEX idx_event_log_created_date   ON main.payment_event_log(created_date);
