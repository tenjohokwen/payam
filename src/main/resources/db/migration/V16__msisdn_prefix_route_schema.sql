CREATE TABLE IF NOT EXISTS main.msisdn_prefix_route (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    prefix              VARCHAR(10)  UNIQUE NOT NULL,
    provider            VARCHAR(20)  NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Seed Cameroon MSISDN prefix routing rules (ids 10–19)
-- Orange: 65, 69
-- MTN: 60, 61, 62, 63, 64, 66, 67, 68
INSERT INTO main.msisdn_prefix_route (id, prefix, provider, enabled, status, version)
VALUES
    (10, '65', 'ORANGE', true, 'ACTIVE', 0),
    (11, '69', 'ORANGE', true, 'ACTIVE', 0),
    (12, '60', 'MTN',    true, 'ACTIVE', 0),
    (13, '61', 'MTN',    true, 'ACTIVE', 0),
    (14, '62', 'MTN',    true, 'ACTIVE', 0),
    (15, '63', 'MTN',    true, 'ACTIVE', 0),
    (16, '64', 'MTN',    true, 'ACTIVE', 0),
    (17, '66', 'MTN',    true, 'ACTIVE', 0),
    (18, '67', 'MTN',    true, 'ACTIVE', 0),
    (19, '68', 'MTN',    true, 'ACTIVE', 0)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE main.msisdn_prefix_route IS 'DB-backed MSISDN prefix-to-provider routing table; replaces hardcoded MsisdnRouter logic (Phase 10 hardening)';
COMMENT ON COLUMN main.msisdn_prefix_route.prefix IS 'Two-character national MSISDN prefix (e.g. 65, 69 for ORANGE; 60-64,66-68 for MTN)';
