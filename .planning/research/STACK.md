# Technology Stack — v10 Client Disbursement API

**Project:** Payam — unified multi-tenant mobile money API for Cameroon
**Researched:** 2026-04-24
**Scope:** Stack additions and changes needed for the NEW `POST /v1/disbursements` endpoint. Existing stack capabilities are not re-evaluated.
**Overall confidence:** HIGH — all findings verified directly against codebase (pom.xml, migration files, service classes, provider ports).

---

## Summary Verdict

**No new library dependencies are required.** Every capability needed for v10 — pessimistic locking, BigDecimal arithmetic, Redis atomic operations, Bucket4j velocity checks, Spring Data JPA, Flyway migrations, Testcontainers, WireMock — is already in `pom.xml`.

The deliverables are entirely new Java classes and one Flyway migration. The critical architectural decision is how to implement concurrency-safe balance reservation; this is solved by the existing `SELECT ... FOR UPDATE` + `TransactionTemplate` pattern already proven in the codebase, applied to a new `merchant_wallet_balance` table.

---

## Recommended Stack

### Core Framework — No Changes

| Technology | Version in pom.xml | Purpose | Status |
|------------|--------------------|---------|--------|
| Spring Boot | 3.5.11 | Web, Data JPA, Security, Actuator | No change |
| Spring Data JPA | managed by Boot 3.5 | `DisbursementRepository`, `WalletBalanceRepository` | No change |
| Spring Security | managed by Boot 3.5 | `ApiKeyAuthenticationFilter`, tenant isolation | No change |
| Resilience4j | managed by `spring-cloud-starter-circuitbreaker-resilience4j` | `@CircuitBreaker` + `@Retry` on provider ports | No change |
| Spring Modulith (via `@TransactionalEventListener`) | managed by Boot 3.5 | Webhook delivery decoupling | No change |
| Flyway | managed by Boot 3.5 | V26 migration for new tables | Next migration is V26 (V25 already shipped) |

### Database — No Version Changes

| Technology | Version | Purpose | v10 change |
|------------|---------|---------|------------|
| PostgreSQL | 14+ (via Testcontainers) | Primary store | One new table: `merchant_wallet_balance` |
| Flyway migration | V26 | `disbursement` table + `merchant_wallet_balance` table | New |
| Hibernate Envers | 6.6.14.Final | Audit trail | `Disbursement` entity needs `@Audited`; new `disbursement_aud` table in V26 |

### Cache / Atomic Operations — No Version Changes

| Technology | Version | Purpose | v10 change |
|------------|---------|---------|------------|
| Redis (via `spring-boot-starter-data-redis`) | managed by Boot 3.5 | Idempotency NX, velocity buckets | Reuse `IdempotencyService.checkAndReserve()` and `store()` as-is |
| Bucket4j | 8.10.1 | Velocity windows | Add new disbursement-specific rules to `fraud_rule` table; `VelocityCheckService` is unchanged |

### New Libraries — Zero

No library needs to be added. The table below documents what was evaluated and rejected.

---

## New Java Types Required (No New Dependencies)

These are code deliverables, not stack additions. Listed here because they affect module structure.

### New module: `disbursement/`

Following the established `contract → repo → service → api → config` layering (same as `payment/`):

```
disbursement/
  contract/
    DisbursementRequest.java          (Jakarta validation: @NotBlank, @Pattern, @DecimalMin)
    DisbursementResponse.java         (record)
    DisbursementStatus.java           (enum: PROCESSING, SUCCESS, FAILED, EXPIRED)
    DisbursementError.java            (enum parallel to OrchestratorError)
  repo/
    Disbursement.java                 (@Entity, @Audited, extends AbstractAuditingEntity)
    DisbursementRepository.java       (JpaRepository + findByTransactionIdForUpdate)
    WalletBalance.java                (@Entity — NOT @Audited — balance row with @Version)
    WalletBalanceRepository.java      (findByTenantIdForUpdate)
  service/
    DisbursementOrchestrator.java     (mirrors PaymentOrchestrator; no @Transactional on method)
    WalletBalanceService.java         (checkAndReserve, release, finalise)
  api/
    DisbursementResource.java         (POST /v1/disbursements, GET /v1/disbursements/{id})
    MtnDisbursementCallbackController.java
    OrangeDisbursementCallbackController.java
  config/
    DisbursementConfig.java           (if any config binding is needed)
```

### Changes to existing types

| Type | Change | Why |
|------|--------|-----|
| `TransactionEventType` | Add `DISBURSEMENT_INITIATED`, `BALANCE_RESERVED`, `BALANCE_RESERVATION_FAILED`, `BALANCE_RELEASED` | New event types for event log |
| `TransactionStatus` | Add `EXPIRED` terminal state | Disbursement polling timeout path |
| `AbstractPayamE2ETest` | Add `@ConfigureWireMock` binding for MTN disbursement base URL | Separate WireMock instance needed for `/disbursement/v1_0/transfer` stubs |

---

## Critical Pattern: Concurrency-Safe Balance Reservation

This is the most important architectural decision for v10. No new library is needed because the existing codebase already has all the primitives.

### Recommended pattern: `SELECT ... FOR UPDATE` via `TransactionTemplate`

The `merchant_wallet_balance` table holds one row per tenant. The balance gate works as follows:

```
WalletBalanceService.checkAndReserve(tenantId, grossAmount):
  transactionTemplate.execute(status -> {
      WalletBalance balance = walletBalanceRepository.findByTenantIdForUpdate(tenantId)
                                                      .orElseThrow(InsufficientBalanceException::new);
      if (balance.getAvailable().compareTo(grossAmount) < 0) {
          throw new InsufficientBalanceException(balance.getAvailable(), grossAmount);
      }
      balance.reserve(grossAmount);          // deducts from available, increments reserved
      walletBalanceRepository.save(balance);
      return null;
  });
```

`findByTenantIdForUpdate` uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` — the same pattern as `TransactionRepository.findByTransactionIdForUpdate()` used throughout the existing codebase. The pessimistic write lock serialises concurrent disbursements against the same `tenantId` row. The `TransactionTemplate` ensures the lock is held only for the duration of the check-and-reserve, not across provider I/O.

On disbursement FAILED or EXPIRED: `WalletBalanceService.release(tenantId, grossAmount)` reverses the reservation in a separate `TransactionTemplate.execute` block (same pattern as `applyFailed()` in `PaymentOrchestrator`).

On disbursement SUCCESS: `WalletBalanceService.finalise(tenantId, grossAmount)` moves the reserved amount to the committed debit column (or simply clears it, depending on the balance model chosen).

**Why not optimistic locking (`@Version`)?** Optimistic locking is correct for low-contention writes (API key rotation) but wrong for a financial balance gate. A disbursement burst from a tenant with a low balance will produce `ObjectOptimisticLockingFailureException` on most threads, requiring the caller to retry — adding complexity and latency. Pessimistic `SELECT FOR UPDATE` is the correct primitive for a serialised balance check in PostgreSQL and is already the project's established pattern for contested rows.

**Why not a database sequence / counter?** A counter cannot represent a fractional balance with a fee calculation in one atomic step. `SELECT FOR UPDATE` on a `NUMERIC(20,2)` available column is the correct model.

**Confidence:** HIGH — pattern read directly from `TransactionRepository.findByTransactionIdForUpdate` + `TransactionTemplate` usage in `MtnMoMoPort`, `OrangeMoneyPort`, and `PaymentOrchestrator`.

---

## V26 Migration: New Tables

Next migration number is V26 (V25 shipped in v9 as of 2026-04-23).

```sql
-- V26: Disbursement API schema

-- 1. Disbursement entity table
CREATE TABLE main.disbursement (
    id                   BIGINT       PRIMARY KEY,
    disbursement_id      VARCHAR(36)  NOT NULL UNIQUE,
    trace_id             VARCHAR(255) NOT NULL,
    tenant_id            BIGINT       NOT NULL REFERENCES main.tenant(id),
    status               VARCHAR(20)  NOT NULL DEFAULT 'INITIATED',
    provider             VARCHAR(20)  NOT NULL,
    recipient_msisdn     VARCHAR(20)  NOT NULL,
    amount               NUMERIC(20,2) NOT NULL,
    fee_amount           NUMERIC(20,2),
    fee_rule_id          BIGINT,
    currency             CHAR(3)      NOT NULL,
    reference            VARCHAR(50)  NOT NULL,
    description          VARCHAR(140),
    metadata             JSONB,
    provider_ref         VARCHAR(255),
    provider_tx_id       VARCHAR(255),
    risk_score           INTEGER,
    created_by           VARCHAR(50),
    created_date         TIMESTAMP,
    last_modified_by     VARCHAR(50),
    last_modified_date   TIMESTAMP,
    CONSTRAINT chk_disbursement_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_disbursement_tenant_id       ON main.disbursement(tenant_id);
CREATE INDEX idx_disbursement_disbursement_id ON main.disbursement(disbursement_id);
CREATE INDEX idx_disbursement_reference       ON main.disbursement(tenant_id, reference);
CREATE INDEX idx_disbursement_status          ON main.disbursement(status) WHERE status NOT IN ('SUCCESS','FAILED','EXPIRED');

-- Envers audit table for disbursement
CREATE TABLE main.disbursement_aud (
    id                   BIGINT      NOT NULL,
    rev                  INTEGER     NOT NULL REFERENCES main.revinfo(rev),
    revtype              SMALLINT,
    disbursement_id      VARCHAR(36),
    trace_id             VARCHAR(255),
    tenant_id            BIGINT,
    status               VARCHAR(20),
    provider             VARCHAR(20),
    recipient_msisdn     VARCHAR(20),
    amount               NUMERIC(20,2),
    fee_amount           NUMERIC(20,2),
    fee_rule_id          BIGINT,
    currency             CHAR(3),
    reference            VARCHAR(50),
    description          VARCHAR(140),
    provider_ref         VARCHAR(255),
    provider_tx_id       VARCHAR(255),
    created_by           VARCHAR(50),
    created_date         TIMESTAMP,
    last_modified_by     VARCHAR(50),
    last_modified_date   TIMESTAMP,
    PRIMARY KEY (id, rev)
);

-- 2. Merchant wallet balance table (one row per tenant)
CREATE TABLE main.merchant_wallet_balance (
    id                   BIGINT       PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL UNIQUE REFERENCES main.tenant(id),
    available            NUMERIC(20,2) NOT NULL DEFAULT 0 CHECK (available >= 0),
    reserved             NUMERIC(20,2) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    currency             CHAR(3)      NOT NULL DEFAULT 'XAF',
    version              BIGINT       NOT NULL DEFAULT 0,
    last_modified_date   TIMESTAMP
);
```

### Key schema decisions

**Separate `Disbursement` entity (not reusing `Transaction`):** The requirements doc (section 14, question 2) explicitly recommends a separate entity. Querying disbursements does not require filtering `flow = 'DISBURSEMENT'` on a shared table, callback controllers look up records by `disbursementId` directly, and the `Disbursement.reference` uniqueness constraint is simpler on a dedicated table.

**`merchant_wallet_balance.version` for optimistic locking fallback:** The `available` balance is read and mutated under `SELECT FOR UPDATE` (pessimistic), but adding `@Version` provides an extra safety net at the JPA layer if code paths ever bypass the for-update lock. This mirrors the `TenantApiKey.@Version` pattern added in v7.

**`metadata JSONB`:** Matches the project's existing JSONB usage (see `PaymentEventLog.metadata` in V3). Stored as-is; max 2 KB enforced at the validation layer.

**`provider_tx_id`:** Holds the MTN `financialTransactionId` or Orange `txnid` — equivalent to `mtn_financial_tx_id` on `Transaction`. Not `@NotAudited` because the disbursement Envers table is created in V26 with the column present from the start.

**Partial index on `status`:** Filters out terminal rows. The poller job and in-flight dashboard queries only care about non-terminal disbursements. Confidence: HIGH (same rationale as existing `idx_transaction_status` usage in reconciliation queries).

---

## Testing — No New Libraries

All test libraries required for v10 are already present in `pom.xml`.

| Library | Version | Use in v10 |
|---------|---------|------------|
| Testcontainers (`postgresql`, `junit-jupiter`) | managed by Boot 3.5 | `WalletBalanceIT`, `DisbursementOrchestratorIT` |
| WireMock Spring Boot | 4.0.9 | MTN disbursement endpoint stubs (`/disbursement/v1_0/transfer`) |
| Awaitility | 4.2.0 | Async callback timing in E2E tests |
| AssertJ | 3.24.2 | Assertion style already in use |
| Mockito | managed by Boot 3.5 | `DisbursementOrchestratorTest` (unit, no Spring context) |
| PITest | 1.15.3 (mutation profile) | Extend `targetClasses` to include `DisbursementOrchestrator` or `WalletBalanceService` |

### New WireMock server requirement

`AbstractPayamE2ETest` currently binds a single `mtn` WireMock server to `mtn.collection-base-url`. The disbursement E2E tests need `/disbursement/v1_0/transfer` stubs. MTN uses a different base URL for the Disbursement product (`mtn.disbursement-base-url`). Add a second `@ConfigureWireMock(name = "mtn-disbursement", baseUrlProperties = {"mtn.disbursement-base-url"})` to the E2E base class (or to a new `AbstractDisbursementE2ETest` that extends it).

**Confidence:** HIGH — read from `AbstractPayamE2ETest.java` directly; current `@ConfigureWireMock` only covers `mtn.collection-base-url`.

---

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Balance reservation | `SELECT FOR UPDATE` via `TransactionTemplate` | Optimistic `@Version` retry loop | Optimistic locking on a hot financial balance causes retry cascades under load; pessimistic lock is the correct primitive for serialised balance gates |
| Separate `Disbursement` entity | Separate `main.disbursement` table | Add `flow` filter to existing `main.transaction` | Requirements doc explicitly recommends separate entity; callback controllers require clean lookup by disbursementId without cross-table joins |
| Disbursement velocity rules | New rows in `main.fraud_rule` DB table | New config properties | `FraudRuleCache` already loads rules from DB; adding rows is zero-code, admin-configurable without redeploy |
| Orange IC2C endpoint | `OrangeMoneyPort.ic2cDisbursement()` | Reuse existing `initiateDisbursement` (cashout path) | IC2C (`/ic2c/pay`) is a different endpoint from cashout (`/cashout`); they have different request shapes and auth headers (`X-AUTH-TOKEN`); separate method is correct |
| Balance floor check | 10,000 XAF platform-wide minimum config | Hard-coded minimum | Requirements doc question 4 recommends configurable floor; store in platform config or a new tenant config row |
| `EXPIRED` status | Add to `TransactionStatus` enum | Reuse `FAILED` for timed-out disbursements | The state machine doc distinguishes EXPIRED (ops investigation required) from FAILED (deterministic failure); the distinction matters for reconciliation and alerting |

---

## What Does Not Change

- `pom.xml` — zero new dependencies
- `LedgerService` — already handles DISBURSEMENT flow via `LedgerPosting.disbursement()`; no changes needed
- `IdempotencyService` — reuse as-is; disbursements use the same Redis NX + PostgreSQL upsert pattern
- `FeeEvaluationService` — fee computation unchanged; disbursements consume the same fee rules as collections
- `FraudScoringService` — reuse `evaluate(PaymentCommand)` as-is; new disbursement-specific signals are added as rows in `fraud_rule`, not code
- `VelocityCheckService` — reuse as-is; new velocity buckets for disbursements are loaded from the same DB rules table
- `WebhookDeliveryService` — reuse as-is; `disbursement.completed` and `disbursement.failed` are new event type strings, not new code paths
- `MtnMoMoPort.initiateDisbursement()` — already implemented; wired into the new `DisbursementOrchestrator`
- `OrangeMoneyPort.initiateDisbursement()` — reuses the cashout path; the IC2C endpoint differs only in URL and auth; a new `ic2cDisbursement()` method is added to `OrangeMoneyPort`
- Flyway migration numbering — V26 is next (V25 shipped 2026-04-23)
- Hibernate Envers configuration — unchanged; V26 creates `disbursement_aud` directly, same pattern as `V24__platform_config_pin.sql` created `platform_config_aud`

---

## Sources

- `pom.xml` — read directly; all library versions confirmed
- `src/main/resources/db/migration/` — all 27 migrations listed; last is V25 (2026-04-23)
- `PaymentOrchestrator.java` — pattern for no-`@Transactional` orchestrator, `TransactionTemplate`, fraud + fee ordering
- `MtnMoMoPort.java` — `initiateDisbursement()` exists, uses `fetchDisbursementToken()`
- `OrangeMoneyPort.java` — `initiateDisbursement()` exists (cashout path); IC2C differs
- `TransactionRepository.findByTransactionIdForUpdate` — confirmed pessimistic lock pattern
- `VelocityCheckService.java` — Bucket4j `LettuceBasedProxyManager` pattern confirmed
- `IdempotencyService.java` — Redis NX + PostgreSQL upsert pattern confirmed
- `AbstractPayamE2ETest.java` — WireMock server binding read directly; single MTN server confirmed
- `transaction/contract/TransactionEventType.java` — existing event types enumerated
- `transaction/contract/TransactionStatus.java` — existing states; EXPIRED not present
- `requirements/disbursement-request.md` — read directly for API contract, state machine, entity/field requirements
