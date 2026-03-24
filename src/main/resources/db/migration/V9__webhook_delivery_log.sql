CREATE TABLE IF NOT EXISTS main.webhook_delivery_log (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    status             VARCHAR(20)  NOT NULL DEFAULT 'INACTIVE',

    transaction_id     VARCHAR(36)  NOT NULL,
    tenant_id          BIGINT       NOT NULL,
    webhook_url        VARCHAR(2048) NOT NULL,
    event_type         VARCHAR(50)  NOT NULL,
    external_reference VARCHAR(255),
    http_status        INTEGER,
    attempt_count      INTEGER      NOT NULL DEFAULT 0,
    next_retry_at      TIMESTAMP WITH TIME ZONE,
    delivered          BOOLEAN      NOT NULL DEFAULT FALSE,
    last_attempt_at    TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_wdl_tenant FOREIGN KEY (tenant_id) REFERENCES main.tenant(id)
);
CREATE INDEX IF NOT EXISTS idx_wdl_transaction_id ON main.webhook_delivery_log (transaction_id);
CREATE INDEX IF NOT EXISTS idx_wdl_pending ON main.webhook_delivery_log (delivered, next_retry_at)
    WHERE delivered = FALSE;
COMMENT ON TABLE main.webhook_delivery_log IS 'Outbound tenant webhook delivery attempts with retry state';
