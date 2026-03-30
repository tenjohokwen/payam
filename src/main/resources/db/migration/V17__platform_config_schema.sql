CREATE TABLE IF NOT EXISTS main.platform_config (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    provider            VARCHAR(20)  NOT NULL UNIQUE,   -- 'ORANGE' | 'MTN'
    platform_msisdn     VARCHAR(20)  NOT NULL DEFAULT ''
);

INSERT INTO main.platform_config (id, version, provider, platform_msisdn, status)
VALUES
    (1, 0, 'ORANGE', '', 'ACTIVE'),
    (2, 0, 'MTN',    '', 'ACTIVE')
ON CONFLICT DO NOTHING;

COMMENT ON TABLE main.platform_config IS 'Platform-owned MSISDNs per provider; editable by admin without app restart';
COMMENT ON COLUMN main.platform_config.provider IS 'Provider key: ORANGE or MTN';
COMMENT ON COLUMN main.platform_config.platform_msisdn IS 'Platform-owned MSISDN for this provider; empty string until configured by admin';
