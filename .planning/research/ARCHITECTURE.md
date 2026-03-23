# Architecture Patterns: Payment Module

**Domain:** Multi-tenant mobile money payment gateway (MTN MoMo + Orange Money, Cameroon)
**Researched:** 2026-03-23
**Confidence:** HIGH (based on existing codebase analysis, provider API specs, and established patterns)

---

## 1. Module Placement Within the Existing Convention

The payment module lives at `com.softropic.payam.payment` and mirrors the exact vertical slice structure of `security` and `email`. Every architectural decision below maps to a specific layer.

```
payment/
├── contract/         DTOs, enums, events, exceptions — zero dependencies
├── repo/             JPA entities + Spring Data repositories
├── service/          Business logic, state machine, orchestration
├── infrastructure/   Provider adapters, webhook handlers, schedulers, listeners
├── api/              REST controllers, webhook endpoints, API advice
└── config/           Spring wiring — sole cross-layer importer
```

Dependency flow is identical to the existing modules:

```
api → service → repo
 ↓        ↓       ↓
       contract ←──

infrastructure → service
             └→ repo
             └→ contract

config → all layers (composition root only)
```

The payment module does NOT import from `security` service or infrastructure layers directly. It receives the authenticated `Principal` (already in `SecurityContextHolder`) and looks up the tenant's API key credentials through its own `repo` layer. Cross-module communication happens only through:
- Spring Security `Principal` (already resolved upstream by `JWTAuthorizationFilter`)
- Spring application events for loose coupling (e.g., payment completion notifying email module)

---

## 2. Provider Adapter Pattern

### The Problem

Orange Money and MTN MoMo have structurally different flows:

| Concern | Orange Money | MTN MoMo |
|---------|-------------|----------|
| Init | POST /mp/init → payToken | Single POST /requesttopay → 202 |
| Submit | POST /mp/pay (payToken + details) | Included in requesttopay body |
| Trigger | GET /mp/push/{token} (optional) | None (USSD auto-triggered) |
| Completion | POST webhook to notifUrl | PUT callback to callbackUrl OR poll |
| Status poll | GET /{type}/paymentstatus/{token} | GET /requesttopay/{referenceId} |
| Auth | X-AUTH-TOKEN + Bearer (static, long-lived) | OAuth2 Bearer (expires, must refresh) |
| Payout (disburse) | POST /ic2c or /cashout flow | POST /disbursement/v1_0/transfer |
| Balance | No endpoint | GET /account/balance |
| Refund | No endpoint | Via disbursement transfer |

### Recommended Abstraction

Define a `ProviderGateway` interface in `payment/contract/`. Implementations live in `payment/infrastructure/`.

**`payment/contract/ProviderGateway.java`** (interface):

```java
public interface ProviderGateway {

    MobilePaymentProvider provider();

    /**
     * Initiate a collection request. Returns a ProviderRequestResult containing
     * the provider reference (payToken for Orange, X-Reference-Id for MTN)
     * and the initial status.
     */
    ProviderRequestResult initiateCollection(CollectionRequest request);

    /**
     * Initiate a disbursement (payout to customer).
     */
    ProviderRequestResult initiateDisbursement(DisbursementRequest request);

    /**
     * Poll the provider for current transaction status.
     * Used as fallback when webhook is not received.
     */
    ProviderStatusResult queryStatus(String providerReference, TransactionType type);

    /**
     * Validate that a subscriber account is active.
     */
    boolean validateSubscriber(String msisdn);
}
```

**`payment/contract/` DTOs:**

```
CollectionRequest       — amount, msisdn, tenantRef, callbackUrl, idempotencyKey
DisbursementRequest     — amount, msisdn, tenantRef, idempotencyKey
ProviderRequestResult   — providerReference, initialStatus, rawResponse (JSONB)
ProviderStatusResult    — providerReference, status (mapped to PaymentStatus enum), providerTxId
```

**`payment/infrastructure/mtn/MtnMoMoGateway.java`** handles:
1. OAuth2 token lifecycle (refresh when approaching expiry, cached in Redis)
2. POST /requesttopay with X-Reference-Id UUID (= our `providerReference`)
3. Handles 202 Accepted → maps to `PROCESSING`
4. GET /requesttopay/{referenceId} for polling
5. POST /disbursement/v1_0/transfer for payouts

**`payment/infrastructure/orange/OrangeMoMoGateway.java`** handles:
1. POST /mp/init → extract payToken
2. POST /mp/pay with payToken + details (= our `providerReference` is the payToken)
3. Optional GET /mp/push/{payToken}
4. GET /mp/paymentstatus/{payToken} for polling

**`payment/config/PaymentConfig.java`** registers a `Map<MobilePaymentProvider, ProviderGateway>` bean, resolving the correct implementation at runtime by provider key.

### Status Normalization

Each gateway normalizes provider-specific status strings to a single internal `PaymentStatus` enum (defined in `payment/contract/`):

```
INITIATED       — record created, before calling provider
PROCESSING      — provider accepted request (202 or after /pay)
SUCCESS         — provider confirmed completion
FAILED          — provider confirmed failure or timeout
REVERSED        — disbursement completed as reversal
```

The state machine in `payment/service/PaymentStateMachine.java` enforces valid transitions and rejects illegal jumps.

---

## 3. Event Sourcing Within the Monolith (No Framework)

### Design

Append-only event log implemented as a plain PostgreSQL table. No Axon, no Kafka, no event store server. The approach matches the existing `EnvelopeEntity` pattern (append + status, never delete) but adds hash chain integrity.

### Table: `payment_events`

```sql
CREATE TABLE payment.payment_events (
    id               BIGINT        NOT NULL,          -- TSID, from BaseEntity
    transaction_id   BIGINT        NOT NULL,          -- FK to payment_transactions
    sequence_no      INT           NOT NULL,          -- per-transaction monotonic counter
    event_type       VARCHAR(64)   NOT NULL,          -- PaymentInitiated, WebhookReceived, etc.
    status_from      VARCHAR(32),                     -- nullable for first event
    status_to        VARCHAR(32)   NOT NULL,
    actor            VARCHAR(64)   NOT NULL,          -- SYSTEM, API_CLIENT, PROVIDER_WEBHOOK
    actor_id         VARCHAR(128),                    -- tenant API key ID or provider name
    metadata         JSONB,                           -- IP, user-agent, provider raw payload, etc.
    previous_hash    CHAR(64),                        -- SHA-256 of previous event, NULL for first
    event_hash       CHAR(64)      NOT NULL,          -- SHA-256(event fields + previous_hash)
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    PRIMARY KEY (id),
    CONSTRAINT fk_pe_transaction FOREIGN KEY (transaction_id)
        REFERENCES payment.payment_transactions(id),
    CONSTRAINT uq_pe_sequence UNIQUE (transaction_id, sequence_no)
);

CREATE INDEX idx_pe_transaction_id ON payment.payment_events(transaction_id);
```

### Hash Chain

Computed in `payment/service/PaymentEventService.java`:

```
event_hash = SHA-256(
    transaction_id
    || sequence_no
    || event_type
    || status_from
    || status_to
    || actor
    || metadata_canonical_json
    || previous_hash (or "GENESIS" for first)
    || created_at_iso
)
```

`commons-codec` (already on classpath) provides `DigestUtils.sha256Hex()`. The canonical JSON for `metadata` must be deterministic — serialize with sorted keys.

### Why No Framework

Axon Framework and EventStore server add operational weight (separate process, schema, deployment) not justified for a monolith at this scale. The hash chain gives tamper evidence. Append-only with a sequence number per transaction gives replay capability. This is the minimum viable immutable audit log that satisfies the spec without architectural overhead.

### Event Types (defined as enum in `payment/contract/event/`)

```
PaymentInitiated
FraudCheckPassed
FraudCheckFailed
ProviderRequestSent
ProviderRequestAcknowledged
WebhookReceived
WebhookValidated
WebhookRejected
ProviderStatusVerified
PaymentCompleted
PaymentFailed
PaymentTimedOut
DisbursementInitiated
DisbursementCompleted
DisbursementFailed
ReconciliationMismatch
```

---

## 4. Webhook Processing Architecture

### Threat Model

Webhooks are the highest-risk surface. Threats specific to this environment:
- Fake webhook from unknown IP claiming SUCCESS on a PENDING transaction
- Replay attack (same webhook delivered twice)
- Race condition (webhook processed before `payment_transactions` row is committed)

### Recommended Architecture: Synchronous Validation, Async Double-Check

```
HTTP POST /v1/webhooks/mtn
HTTP POST /v1/webhooks/orange
         ↓
WebhookController (api layer)
  1. IP whitelist check (fail fast, return 403)
  2. HMAC signature verification (fail fast, return 401)
  3. Idempotency check via Redis SET NX (return 200 if duplicate)
  4. Persist raw webhook payload to payment_webhook_inbox (PENDING)
  5. Return HTTP 200 immediately

         ↓ (Spring @TransactionalEventListener or @Async)

WebhookProcessor (infrastructure layer)
  6. Load transaction by provider reference
  7. Call provider status API (double-check pattern)
  8. Compare: amount + msisdn + status must match
  9. If match: advance state machine, write event, update transaction
 10. If mismatch: write FRAUD_SUSPECTED event, alert
 11. Mark inbox row PROCESSED or FAILED
```

**Why return 200 before processing:** Both MTN and Orange retry on non-200. Orange explicitly documents this. Returning 200 immediately and processing asynchronously prevents provider retry storms and keeps the webhook handler non-blocking.

**Why double-check:** The spec mandates this. Fake webhooks claiming SUCCESS are a known attack vector in African mobile money systems.

### Table: `payment_webhook_inbox`

```sql
CREATE TABLE payment.payment_webhook_inbox (
    id               BIGINT        NOT NULL,
    provider         VARCHAR(16)   NOT NULL,          -- MTN, ORANGE
    provider_ref     VARCHAR(128)  NOT NULL,          -- payToken or X-Reference-Id
    raw_payload      JSONB         NOT NULL,
    received_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',  -- PENDING, PROCESSED, FAILED
    processed_at     TIMESTAMPTZ,
    failure_reason   TEXT,
    idempotency_key  VARCHAR(256)  UNIQUE NOT NULL,   -- hash(provider + provider_ref + payload)

    PRIMARY KEY (id)
);

CREATE INDEX idx_pwi_status ON payment.payment_webhook_inbox(status)
    WHERE status = 'PENDING';
```

### Idempotency Key Construction

`idempotency_key = SHA-256(provider + ":" + providerRef + ":" + canonicalPayload)`

Stored in Redis with TTL 48 hours (`SET NX EX 172800`) for fast duplicate rejection before touching the DB. Also enforced by `UNIQUE` constraint on `idempotency_key` column as a database-level backstop.

### Fallback: Scheduled Polling

`PaymentTimeoutScheduler` (in `infrastructure/`) runs every 5 minutes via `@Scheduled`. It queries for transactions in `PROCESSING` state older than the configured timeout (default 10 minutes). For each:
1. Calls `providerGateway.queryStatus(providerReference, type)`
2. If resolved: advances state machine
3. If still PENDING after configured max age (default 30 minutes): marks `TIMED_OUT` → `FAILED`

Uses `SELECT FOR UPDATE SKIP LOCKED` (same pattern as `EmailRetryScheduler`) for safe multi-node execution.

---

## 5. Async Payment Flow State Machine

### States and Transitions

```
INITIATED
    → AUTH_PENDING      (fraud check requires step-up, waiting for OTP)
    → PROCESSING        (provider accepted, waiting for resolution)

AUTH_PENDING
    → PROCESSING        (step-up completed)
    → FAILED            (step-up timed out or rejected)

PROCESSING
    → SUCCESS           (webhook validated + double-check passed)
    → FAILED            (webhook FAILED, or poll confirmed failure, or timeout)

SUCCESS
    → REVERSED          (disbursement reversal completed)

FAILED
    → (terminal)

REVERSED
    → (terminal)
```

### Implementation

`PaymentStateMachine` in `payment/service/` is a plain Java class — not a Spring State Machine dependency. It holds the valid transition table as an `EnumMap<PaymentStatus, Set<PaymentStatus>>` and throws `InvalidStateTransitionException` (in `payment/contract/exception/`) for illegal jumps.

The service layer calls `stateMachine.validateTransition(from, to)` before persisting any state change. State is only persisted to `payment_transactions.status` after the corresponding event is written to `payment_events`.

### Timeout Coordination

On transaction creation, store:
- `provider_deadline_at` = `now() + provider_timeout` (Orange: ~5 min; MTN: ~10 min)
- `system_deadline_at` = `now() + system_max_wait` (30 min)

`PaymentTimeoutScheduler` queries: `WHERE status = 'PROCESSING' AND system_deadline_at < now()`.

---

## 6. Double-Entry Ledger Design

### Principle

Never read the balance from a SUM scan. Maintain a running balance per account. Use double-entry so every amount appears as both a debit and a credit — the ledger must always sum to zero.

### Accounts (Chart of Accounts)

```sql
CREATE TABLE payment.ledger_accounts (
    id               BIGINT        NOT NULL,
    tenant_id        BIGINT        NOT NULL,
    account_type     VARCHAR(32)   NOT NULL,  -- CUSTOMER_WALLET, MTN_CLEARING, ORANGE_CLEARING,
                                              -- PAYAM_FEE_INCOME, PAYAM_FLOAT
    account_ref      VARCHAR(128)  NOT NULL,  -- e.g. msisdn, or 'MTN_CAMEROON'
    currency         CHAR(3)       NOT NULL DEFAULT 'XAF',
    balance          NUMERIC(18,0) NOT NULL DEFAULT 0,  -- running balance in smallest unit
    version          BIGINT        NOT NULL DEFAULT 0,  -- optimistic lock
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    PRIMARY KEY (id),
    CONSTRAINT uq_la_tenant_type_ref UNIQUE (tenant_id, account_type, account_ref)
);
```

### Journal Entries

```sql
CREATE TABLE payment.ledger_entries (
    id               BIGINT        NOT NULL,
    transaction_id   BIGINT        NOT NULL,  -- FK to payment_transactions
    entry_type       VARCHAR(16)   NOT NULL,  -- DEBIT or CREDIT
    account_id       BIGINT        NOT NULL,  -- FK to ledger_accounts
    amount           NUMERIC(18,0) NOT NULL,  -- always positive
    description      VARCHAR(255),
    posted_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    PRIMARY KEY (id),
    CONSTRAINT fk_le_transaction FOREIGN KEY (transaction_id)
        REFERENCES payment.payment_transactions(id),
    CONSTRAINT fk_le_account FOREIGN KEY (account_id)
        REFERENCES payment.ledger_accounts(id),
    CONSTRAINT chk_le_positive_amount CHECK (amount > 0)
);

CREATE INDEX idx_le_transaction_id ON payment.ledger_entries(transaction_id);
CREATE INDEX idx_le_account_id ON payment.ledger_entries(account_id);
```

### Example: Customer pays 5,000 XAF via MTN, Payam charges 50 XAF fee

```
DR  CUSTOMER_WALLET  (msisdn)         5,000  — customer's money leaves
CR  MTN_CLEARING     (MTN_CAMEROON)   4,950  — net amount at MTN
CR  PAYAM_FEE_INCOME (tenant_id)         50  — Payam's cut
```

The sum of all credits (4,950 + 50 = 5,000) equals the sum of all debits (5,000). Invariant holds.

### Balance Update Strategy

On each `LedgerEntry` insert, update the running balance on `ledger_accounts` using optimistic locking:

```sql
UPDATE payment.ledger_accounts
SET balance = balance + :delta,
    version = version + 1
WHERE id = :accountId
  AND version = :expectedVersion;
```

`delta` is positive for CREDIT, negative for DEBIT. If zero rows updated (version conflict), retry. This avoids SELECT FOR UPDATE contention on busy accounts while maintaining consistency.

### Service Location

`LedgerService` lives in `payment/service/`. It is called by `PaymentOrchestrationService` after a successful state transition to SUCCESS. Ledger entries are written in the same database transaction as the state update.

---

## 7. Reconciliation System Design

### Overview

Daily job: compare internal `payment_transactions` records against provider-supplied transaction reports. Detect gaps, mismatches, and ghost transactions.

### Reconciliation Flow

```
ReconciliationScheduler (infrastructure, @Scheduled daily at 02:00)
    ↓
1. Fetch provider report
   - MTN: GET /collection/v1_0/account/balance + poll individual transactions
          (MTN has no CSV export API; reconstruct from stored events)
   - Orange: POST /transactions/paymentstatus with comma-separated payTokens
             (bulk status check, no auth required per spec)
2. Load internal records for date window from payment_transactions
3. Compare: for each internal record, find provider counterpart
4. Classify discrepancies:
   a. MATCH            — amounts, status, MSISDNs agree
   b. AMOUNT_MISMATCH  — status matches but amounts differ
   c. STATUS_MISMATCH  — internal SUCCESS but provider FAILED (or vice versa)
   d. MISSING_LOCAL    — provider has record, we do not
   e. MISSING_PROVIDER — we have SUCCESS record, provider has no record
5. Write results to payment_reconciliation_runs and payment_reconciliation_items
6. Alert on any non-MATCH item
```

### Tables

```sql
CREATE TABLE payment.reconciliation_runs (
    id               BIGINT        NOT NULL,
    tenant_id        BIGINT        NOT NULL,
    provider         VARCHAR(16)   NOT NULL,
    window_start     DATE          NOT NULL,
    window_end       DATE          NOT NULL,
    run_status       VARCHAR(16)   NOT NULL,  -- RUNNING, COMPLETE, FAILED
    total_internal   INT           NOT NULL DEFAULT 0,
    total_provider   INT           NOT NULL DEFAULT 0,
    total_matched    INT           NOT NULL DEFAULT 0,
    total_mismatched INT           NOT NULL DEFAULT 0,
    total_missing    INT           NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,

    PRIMARY KEY (id)
);

CREATE TABLE payment.reconciliation_items (
    id                   BIGINT        NOT NULL,
    run_id               BIGINT        NOT NULL,
    transaction_id       BIGINT,               -- NULL if MISSING_LOCAL
    provider_ref         VARCHAR(128)  NOT NULL,
    discrepancy_type     VARCHAR(32)   NOT NULL,
    internal_amount      NUMERIC(18,0),
    provider_amount      NUMERIC(18,0),
    internal_status      VARCHAR(32),
    provider_status      VARCHAR(32),
    notes                TEXT,

    PRIMARY KEY (id),
    CONSTRAINT fk_ri_run FOREIGN KEY (run_id)
        REFERENCES payment.reconciliation_runs(id)
);
```

### Orange Constraint

Orange has no balance endpoint. Reconciliation for Orange must work entirely from transaction-level status checks (bulk `/transactions/paymentstatus`). For transactions older than the bulk query window, the system relies on stored webhook payloads and polling history from `payment_events`.

---

## 8. Multi-Tenant Data Isolation

### Recommended Approach: `tenant_id` on Every Table + Row-Level Security

Single schema (`payment`), single database. Every table that holds tenant data carries a `tenant_id BIGINT NOT NULL` column. PostgreSQL Row-Level Security (RLS) is the enforcement backstop; the application layer enforces it first.

**Do not use separate schemas per tenant.** Schema-per-tenant breaks Flyway migrations (must apply to each schema), complicates connection pooling, and creates operational overhead disproportionate to the scale of this system.

### Tenant Resolution

The `tenant_id` is resolved at the API boundary in `PaymentApiKeyFilter` (lives in `payment/infrastructure/`). This filter:
1. Extracts `X-API-Key` header from the request
2. Looks up the key in Redis (cache) or `payment_api_keys` table (miss)
3. Populates a `TenantContext` (thread-local, MDC-cleared after request) with `tenantId` and `apiKeyId`
4. Every service method receives `tenantId` as a parameter — no ambient thread-local access inside service layer

**Do not use thread-local tenant propagation in service methods.** Pass `tenantId` explicitly. Thread-locals are invisible in async contexts and create testing pain.

### PostgreSQL RLS (Defense in Depth)

```sql
ALTER TABLE payment.payment_transactions ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON payment.payment_transactions
    USING (tenant_id = current_setting('app.tenant_id')::bigint);
```

The application sets `app.tenant_id` at the start of each connection using:

```sql
SET LOCAL app.tenant_id = :tenantId;
```

This is set in a `TenantRlsInterceptor` (Spring `HandlerInterceptor` or Hibernate `SessionEventListener`) that fires per request. RLS serves as the last-line backstop if application code forgets to filter by `tenant_id`.

### Tables Requiring `tenant_id`

```
payment_transactions
payment_events
payment_api_keys
payment_api_key_webhooks   (outbound webhook destinations)
payment_webhook_inbox
ledger_accounts
ledger_entries
reconciliation_runs
reconciliation_items
```

---

## 9. Redis Usage Patterns

All Redis keys are namespaced by `payam:{purpose}:{identifier}`.

### 9.1 Idempotency Keys (Webhook Deduplication)

```
Key:   payam:wh-idem:{sha256_of_provider_ref_and_payload}
Value: "1"
TTL:   48 hours (172800 seconds)
Op:    SET NX EX 172800
```

If SET NX returns 0 (key already exists), return HTTP 200 immediately without processing.

### 9.2 Idempotency Keys (API Request Deduplication)

API clients send `Idempotency-Key: <uuid>` on payment initiation requests.

```
Key:   payam:api-idem:{tenantId}:{idempotencyKey}
Value: JSON of serialized response (status + transactionId)
TTL:   24 hours
Op:    SETNX + GETSET for atomic first-write-wins
```

### 9.3 MTN OAuth2 Token Cache

MTN access tokens expire in 3600 seconds.

```
Key:   payam:mtn-token:{apiUserId}
Value: {access_token}
TTL:   3000 seconds (refresh 10 minutes before expiry)
Op:    SET EX 3000
```

`MtnTokenManager` (in `payment/infrastructure/mtn/`) checks this key before every API call. On miss: call `/collection/token/`, store, proceed.

### 9.4 API Key Cache

Avoid a database lookup on every inbound API call.

```
Key:   payam:apikey:{sha256_of_raw_key}
Value: JSON of TenantApiKeyDto {tenantId, keyId, status, rateLimit}
TTL:   5 minutes (300 seconds)
Op:    GET on hit, SET EX 300 on miss
```

On key revocation: delete `payam:apikey:{sha256}` immediately (cache invalidation by event).

### 9.5 Velocity Counters (Fraud/Rate Limiting)

```
Key:   payam:velocity:{tenantId}:{window}:{metric}
       e.g. payam:velocity:42:1min:count
            payam:velocity:42:1min:amount
Value: counter (INCR) or sum (INCRBY)
TTL:   window + 10s buffer
```

Per-tenant counters: transaction count per minute, cumulative amount per day. Per-MSISDN counters for cross-tenant fraud signals.

### 9.6 Risk Score Cache

```
Key:   payam:risk:{msisdn}
Value: JSON {score, signals[], computedAt}
TTL:   10 minutes
```

Populated by `RiskScoringService` (in `payment/service/`). Avoids recomputing for rapid successive requests from the same number.

---

## 10. Security Module Integration: API Key vs JWT

### Context

The existing security module authenticates users via JWT cookies (browser-facing). The payment module serves API clients (tenant applications), not browser users. These are two different authentication surfaces that must coexist in the same Spring Security filter chain.

### Resolution: Two Parallel Security Realms

The `SecurityConfiguration` (in `security/config/`) currently defines one filter chain. The payment module requires a second filter chain for API clients that:
1. Matches on `/v1/payment/**` and `/v1/webhooks/**`
2. Is stateless (no session, no cookies)
3. Authenticates via `X-API-Key` header, not JWT

**`payment/config/PaymentSecurityConfig.java`** defines a second `SecurityFilterChain` bean with a higher `@Order` (e.g., `@Order(1)`) so it is evaluated before the existing JWT chain (`@Order(2)`):

```java
@Bean
@Order(1)
public SecurityFilterChain paymentApiFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/v1/payment/**", "/v1/webhooks/**")
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .addFilterBefore(paymentApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/v1/webhooks/**").permitAll()  // IP + HMAC checked in filter
            .requestMatchers("/v1/payment/**").authenticated()
        );
    return http.build();
}
```

The `PaymentApiKeyFilter` (in `payment/infrastructure/`) extracts `X-API-Key`, validates it (Redis cache → DB fallback), and populates `SecurityContextHolder` with a `PaymentApiKeyAuthentication` object that carries `tenantId`, `keyId`, and granted authorities.

Webhook endpoints (`/v1/webhooks/**`) are `permitAll()` at the Spring Security level — IP whitelist and HMAC verification happen inside the filter itself before any business logic touches the request.

### Admin Access to Payment Data

Admin users (authenticated via JWT, `ROLE_ADMIN`) access payment reports and reconciliation data through a separate route prefix (`/v1/admin/payment/**`) protected by the JWT filter chain, not the API key chain. This keeps the two auth surfaces cleanly separated.

---

## 11. Data Model Sketches

### Core Tables (PostgreSQL, schema: `payment`)

#### `payment_tenants`

```sql
CREATE TABLE payment.payment_tenants (
    id               BIGINT        NOT NULL,          -- TSID
    name             VARCHAR(255)  NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    config           JSONB,                            -- fee rules, provider prefs
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    PRIMARY KEY (id)
);
```

#### `payment_api_keys`

```sql
CREATE TABLE payment.payment_api_keys (
    id               BIGINT        NOT NULL,
    tenant_id        BIGINT        NOT NULL,
    key_hash         CHAR(64)      NOT NULL UNIQUE,   -- SHA-256 of raw key; raw key shown once
    key_prefix       VARCHAR(8)    NOT NULL,           -- first 8 chars for display/identification
    secret_hash      CHAR(64)      NOT NULL,           -- for HMAC request signing
    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    rate_limit_rpm   INT           NOT NULL DEFAULT 60,
    ip_whitelist     INET[],                           -- null = allow all
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    last_used_at     TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,

    PRIMARY KEY (id),
    CONSTRAINT fk_pak_tenant FOREIGN KEY (tenant_id)
        REFERENCES payment.payment_tenants(id)
);
```

#### `payment_transactions`

```sql
CREATE TABLE payment.payment_transactions (
    id                   BIGINT        NOT NULL,
    tenant_id            BIGINT        NOT NULL,
    trace_id             VARCHAR(64)   NOT NULL,       -- OTel trace propagation
    external_reference   VARCHAR(128)  NOT NULL,       -- client-supplied reference
    idempotency_key      VARCHAR(256)  NOT NULL,       -- from API request
    provider             VARCHAR(16)   NOT NULL,       -- MTN, ORANGE
    transaction_type     VARCHAR(16)   NOT NULL,       -- COLLECTION, DISBURSEMENT
    status               VARCHAR(32)   NOT NULL,
    msisdn               VARCHAR(20)   NOT NULL,       -- encrypted at rest
    amount               NUMERIC(18,0) NOT NULL,       -- in XAF, smallest unit
    fee_amount           NUMERIC(18,0) NOT NULL DEFAULT 0,
    currency             CHAR(3)       NOT NULL DEFAULT 'XAF',
    provider_reference   VARCHAR(128),                 -- payToken or X-Reference-Id
    provider_tx_id       VARCHAR(128),                 -- financialTransactionId from provider
    callback_url         VARCHAR(512),                 -- tenant's webhook URL
    provider_deadline_at TIMESTAMPTZ,
    system_deadline_at   TIMESTAMPTZ   NOT NULL,
    risk_score           SMALLINT,
    raw_provider_response JSONB,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    CONSTRAINT uq_pt_tenant_idem UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_pt_provider_ref UNIQUE (provider, provider_reference)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_pt_tenant FOREIGN KEY (tenant_id)
        REFERENCES payment.payment_tenants(id)
);

CREATE INDEX idx_pt_tenant_status ON payment.payment_transactions(tenant_id, status);
CREATE INDEX idx_pt_provider_ref ON payment.payment_transactions(provider, provider_reference);
CREATE INDEX idx_pt_external_ref ON payment.payment_transactions(tenant_id, external_reference);
CREATE INDEX idx_pt_system_deadline ON payment.payment_transactions(system_deadline_at)
    WHERE status = 'PROCESSING';
```

---

## 12. Component Boundary Map

```
HTTP request (API client)
    ↓
PaymentApiKeyFilter  [infrastructure/]
    ↓ (populates TenantContext + SecurityContextHolder)
PaymentResource      [api/]
    ↓
PaymentOrchestrationService  [service/]
    ├─→ FraudCheckService     [service/] → Redis velocity counters
    ├─→ PaymentStateMachine   [service/]
    ├─→ ProviderGateway       [contract/ interface]
    │       └─ MtnMoMoGateway       [infrastructure/mtn/]
    │       └─ OrangeMoMoGateway    [infrastructure/orange/]
    ├─→ PaymentEventService   [service/] → SHA-256 hash chain
    ├─→ LedgerService         [service/] → double-entry writes
    └─→ PaymentTransactionRepository [repo/]

HTTP POST (provider webhook)
    ↓
WebhookController    [api/]
    ├─ IP whitelist check
    ├─ HMAC signature check
    ├─ Redis idempotency SET NX
    ├─ Persist to payment_webhook_inbox
    └─ Return 200

    ↓ (async, @TransactionalEventListener)
WebhookProcessingService  [infrastructure/]
    ├─→ ProviderGateway.queryStatus()  (double-check)
    └─→ PaymentOrchestrationService.advanceToFinalState()

Scheduled work
    ↓
PaymentTimeoutScheduler  [infrastructure/] @Scheduled every 5 min
    └─ SELECT FOR UPDATE SKIP LOCKED on PROCESSING transactions past deadline

ReconciliationScheduler  [infrastructure/] @Scheduled daily 02:00
    └─ compare internal vs provider, write reconciliation_runs/items

Admin HTTP (ROLE_ADMIN, JWT)
    ↓
PaymentAdminResource  [api/]
    └─→ ReconciliationService [service/]
    └─→ PaymentReportingService [service/]
```

---

## 13. Suggested Build Order (with Dependency Rationale)

Build phases should follow the dependency graph. Each phase produces working, testable code before the next begins.

### Phase 1 — Foundation: Tenant, API Keys, Data Schema

**What:** Database schema, tenant model, API key management, `PaymentApiKeyFilter`, second security filter chain.

**Why first:** Everything else requires a tenant context and authenticated API key. No payment flow can run without this. The filter chain work touches `SecurityConfiguration` — isolate this risk early.

**Deliverables:**
- Flyway migration: `payment_tenants`, `payment_api_keys`
- `payment/repo/`: `PaymentTenant`, `PaymentApiKey`, repositories
- `payment/service/ApiKeyService`: issue, validate, revoke keys
- `payment/infrastructure/PaymentApiKeyFilter`
- `payment/config/PaymentSecurityConfig`: second filter chain
- `payment/api/TenantAdminResource`: CRUD for admin
- Redis API key cache

**Tests:** Integration tests for filter (valid key → 200, invalid → 401, revoked → 401).

### Phase 2 — Transaction Core: State Machine, Events, Ledger Schema

**What:** `payment_transactions` table, state machine, event log with hash chain, ledger accounts and entries tables.

**Why second:** The state machine and event log are the backbone every subsequent feature writes to. Build and test them in isolation before plugging in provider adapters.

**Deliverables:**
- Flyway migration: `payment_transactions`, `payment_events`, `ledger_accounts`, `ledger_entries`
- `payment/contract/`: `PaymentStatus` enum, `PaymentEventType` enum, DTOs
- `payment/repo/`: all entity classes and repositories
- `payment/service/PaymentStateMachine`
- `payment/service/PaymentEventService` (with hash chain computation)
- `payment/service/LedgerService` (double-entry writes)

**Tests:** Unit tests for state machine (all valid + invalid transitions), hash chain integrity (verify chain breaks on modification), ledger balance invariant.

### Phase 3 — Provider Adapters: MTN and Orange

**What:** `ProviderGateway` implementations for both providers, OAuth2 token management for MTN, Redis token cache.

**Why third:** Adapters depend on `payment/contract/` types established in Phase 2. Can be built in parallel (MTN and Orange) once the interface is stable.

**Deliverables:**
- `payment/infrastructure/mtn/MtnMoMoGateway`
- `payment/infrastructure/mtn/MtnTokenManager` (Redis-cached OAuth2)
- `payment/infrastructure/orange/OrangeMoMoGateway`
- `payment/config/PaymentConfig`: `Map<MobilePaymentProvider, ProviderGateway>` bean
- Status normalization to `PaymentStatus` in each adapter

**Tests:** Integration tests against WireMock stubs of MTN and Orange APIs. Test happy path, network timeout, 500 from provider, status normalization.

### Phase 4 — Payment Orchestration and Webhook Processing

**What:** `PaymentOrchestrationService`, collection and disbursement endpoints, webhook receiver, async processing, Redis idempotency.

**Why fourth:** Requires Phase 2 (state machine, events) and Phase 3 (adapters) to both be complete.

**Deliverables:**
- `payment/service/PaymentOrchestrationService`
- `payment/api/PaymentResource`: POST /v1/payment/collect, POST /v1/payment/disburse, GET /v1/payment/{id}
- `payment_webhook_inbox` table (Flyway migration)
- `payment/api/WebhookController`: POST /v1/webhooks/mtn, POST /v1/webhooks/orange
- `payment/infrastructure/WebhookProcessingService` (async, double-check)
- Redis idempotency for both webhook dedup and API request dedup

**Tests:** End-to-end integration tests: initiate → simulate webhook → verify final state. Test duplicate webhook handling. Test HMAC rejection.

### Phase 5 — Timeout Recovery and Fraud Checks

**What:** `PaymentTimeoutScheduler`, basic fraud scoring (velocity counters, risk score cache).

**Why fifth:** These are operational safety mechanisms. They can be added after the main flow works, but should be present before any production traffic.

**Deliverables:**
- `payment/infrastructure/PaymentTimeoutScheduler` (`SELECT FOR UPDATE SKIP LOCKED`)
- `payment/service/FraudCheckService` (velocity counters in Redis)
- `payment/service/RiskScoringService` (score computation + Redis cache)
- Integration into `PaymentOrchestrationService`

**Tests:** Scheduler integration test with clock-controlled `ClockProvider`. Fraud check unit tests against Redis mock.

### Phase 6 — Reconciliation

**What:** `ReconciliationScheduler`, reconciliation tables, admin API.

**Why last:** Reconciliation reads from completed transaction data. It adds no risk to the payment flow itself. It is operationally important but not blocking for initial go-live.

**Deliverables:**
- `reconciliation_runs`, `reconciliation_items` tables (Flyway migration)
- `payment/infrastructure/ReconciliationScheduler`
- `payment/service/ReconciliationService`
- `payment/api/PaymentAdminResource`: GET /v1/admin/payment/reconciliation/{runId}

**Tests:** Reconciliation logic unit tests with known fixtures. Test all discrepancy types.

---

## 14. Key Architecture Decisions and Rationale

| Decision | Chosen Approach | Why |
|----------|----------------|-----|
| Provider abstraction | `ProviderGateway` interface in `contract/`, implementations in `infrastructure/` | Fits existing pattern (interface-in-service/contract, impl-in-infrastructure); decouples orchestration from provider details |
| Event sourcing | Plain append-only PostgreSQL table with SHA-256 hash chain | No framework overhead; `commons-codec` already on classpath; satisfies audit spec; reversible if future migration to dedicated event store needed |
| Webhook processing | Sync 200 + async double-check | Prevents provider retry storms; double-check is mandatory per spec; matches email module's decouple-then-process pattern |
| Multi-tenant isolation | `tenant_id` everywhere + PostgreSQL RLS | Single schema is operationally simple; RLS is defense in depth; column-level isolation is tested and understood |
| API key auth | Second `SecurityFilterChain` at `@Order(1)` | Does not touch existing JWT chain; Spring Security natively supports multiple chains with matchers |
| Idempotency | Redis SET NX (fast path) + DB unique constraint (backstop) | Handles network instability in Cameroon; both layers needed because Redis is not durable |
| Async coordination | Scheduled polling fallback (`SELECT FOR UPDATE SKIP LOCKED`) | Same pattern as `EmailRetryScheduler`; proven in codebase; handles unreliable webhooks |
| Ledger balance | Running balance column + optimistic lock | Avoids expensive SUM queries; optimistic lock handles low-contention concurrent updates |
| Orange reconciliation | Transaction-level status polling (no balance API) | Orange has no balance endpoint per spec; reconciliation must be transaction-by-transaction |
| State machine | Plain Java `EnumMap` of valid transitions | No Spring State Machine dependency; simpler to test; state machine spec is small enough to not need a framework |

---

## 15. Cross-Cutting Concerns Within the Payment Module

### Observability

- All `PaymentOrchestrationService` methods emit Micrometer timer metrics: `payment.collection.duration`, `payment.disbursement.duration`
- `trace_id` stored on `payment_transactions` row for correlation with OTel distributed traces
- MDC keys: `tenantId`, `transactionId`, `providerRef` — set at filter entry, cleared at filter exit
- Structured log keys follow existing `LogKeys` pattern

### Error Handling

- `PaymentApiAdvice` (`@RestControllerAdvice` scoped to `payment/api/`) handles payment-specific exceptions
- Exception hierarchy: `PaymentException` (base) → `InvalidStateTransitionException`, `ProviderException`, `DuplicateTransactionException`, `WebhookSignatureException`
- All exceptions carry an `ErrorCode` and are logged with `transactionId` in context
- `ProviderException` distinguishes retryable (network timeout, 5xx) from non-retryable (4xx) via a boolean flag — used by Resilience4j retry configuration in `PaymentConfig`

### Resilience

- Each `ProviderGateway` implementation wraps outbound calls with Resilience4j `CircuitBreaker` (separate instance per provider) and `Retry` (3 attempts, exponential backoff)
- Circuit breakers are registered in `payment/config/PaymentConfig` using the existing Resilience4j dependency
- Webhook processing uses `@Retryable` (Spring Retry, already on classpath) with 3 retries for transient DB errors

---

*Architecture research: 2026-03-23*
*Sources: existing codebase analysis (`/payam/.planning/codebase/`), Orange Money integration spec (`requirements/orange-money-integration-guide.md`), MTN MoMo API spec (`requirements/mtn-api.md`, `requirements/mtn-use-cases.md`), payment security architecture spec (`requirements/paymentApi_security_architecture.md`), Payam product spec (`requirements/sendam-wrapper-requirements.md`)*
