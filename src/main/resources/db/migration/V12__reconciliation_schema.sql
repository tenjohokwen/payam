-- V12: Reconciliation schema
-- Stores daily reconciliation run results and discrepancy records.

CREATE TABLE main.reconciliation_report (
    id                  BIGINT PRIMARY KEY,
    report_date         DATE NOT NULL,
    provider            VARCHAR(20) NOT NULL,
    run_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    total_checked       INT NOT NULL DEFAULT 0,
    total_matched       INT NOT NULL DEFAULT 0,
    total_discrepancies INT NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'COMPLETE',
    CONSTRAINT uq_report_date_provider UNIQUE (report_date, provider)
);

CREATE TABLE main.reconciliation_discrepancy (
    id                  BIGINT PRIMARY KEY,
    report_id           BIGINT NOT NULL REFERENCES main.reconciliation_report(id),
    report_date         DATE NOT NULL,
    provider            VARCHAR(20) NOT NULL,
    payam_tx_id         VARCHAR(100),
    provider_ref        VARCHAR(100),
    payam_status        VARCHAR(30),
    provider_status     VARCHAR(30),
    payam_amount        NUMERIC(20,2),
    provider_amount     NUMERIC(20,2),
    discrepancy_type    VARCHAR(30) NOT NULL,
    severity            VARCHAR(10) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
