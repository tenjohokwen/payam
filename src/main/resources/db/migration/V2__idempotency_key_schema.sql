-- Idempotency key table — Phase 1 schema, Phase 2 adds runtime lookup logic.
--
-- The composite UNIQUE constraint on (tenant_id, idempotency_key) makes cross-tenant
-- key collision structurally impossible at the database level.
-- A tenant cannot accidentally process another tenant's payment by reusing the same
-- idempotency key value — the schema enforces isolation without any application-level check.

CREATE TABLE main.idempotency_key (
    id                  BIGINT PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES main.tenant(id),
    idempotency_key     VARCHAR(255) NOT NULL,
    response_body       TEXT,
    http_status         INT,
    created_date        TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMP NOT NULL,
    CONSTRAINT uq_idempotency_tenant_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_tenant_key ON main.idempotency_key(tenant_id, idempotency_key);
CREATE INDEX idx_idempotency_expires_at ON main.idempotency_key(expires_at);
