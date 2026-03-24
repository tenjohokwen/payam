CREATE TABLE IF NOT EXISTS main.fraud_rule (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    signal_name         VARCHAR(100) UNIQUE NOT NULL,
    weight              INTEGER      NOT NULL DEFAULT 0,
    threshold           INTEGER      NOT NULL DEFAULT 10,
    window_seconds      INTEGER      NOT NULL DEFAULT 60,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    description         VARCHAR(500)
);

INSERT INTO main.fraud_rule (id, signal_name, weight, threshold, window_seconds, enabled, description)
VALUES
    (1, 'IP_VELOCITY',       40, 10, 60,   true, 'Too many payments from same IP in 60s'),
    (2, 'MSISDN_VELOCITY',   35, 5,  60,   true, 'Too many payments to same MSISDN in 60s'),
    (3, 'APP_VELOCITY',      25, 20, 60,   true, 'Too many payments from same tenant+app in 60s'),
    (4, 'MSISDN_HOUSEHOLD',  15, 8,  3600, true, 'SIM-sharing household pattern — down-weighted'),
    (5, 'BLOCK_THRESHOLD',   0,  70, 0,    true, 'Risk score >= threshold blocks payment');

ALTER TABLE main.transaction
    ADD COLUMN IF NOT EXISTS risk_score         INTEGER,
    ADD COLUMN IF NOT EXISTS device_fingerprint TEXT;

COMMENT ON TABLE main.fraud_rule IS 'Configurable fraud signal rules with weights and velocity thresholds';
COMMENT ON COLUMN main.fraud_rule.weight IS 'Contribution to 0-100 risk score when signal fires';
COMMENT ON COLUMN main.fraud_rule.threshold IS 'Velocity window capacity (max requests allowed)';
COMMENT ON COLUMN main.fraud_rule.window_seconds IS 'Velocity window duration in seconds';
COMMENT ON COLUMN main.transaction.risk_score IS 'Computed fraud risk score 0-100 at payment initiation';
COMMENT ON COLUMN main.transaction.device_fingerprint IS 'Client device fingerprint token (FingerprintJS or similar)';
