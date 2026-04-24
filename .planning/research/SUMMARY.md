# Research Summary — v10 Client Disbursement API

**Project:** Payam — unified multi-tenant mobile money API for Cameroon
**Domain:** B2B disbursement/payout API (MTN MoMo + Orange Money)
**Researched:** 2026-04-24
**Confidence:** HIGH

---

## Executive Summary

v10 exposes `POST /v1/disbursements` — a production-ready payout endpoint that lets tenants send money to MTN MoMo and Orange Money subscribers with full idempotency, pre-funded balance gating, fraud scoring, and async webhook delivery. The foundation is already built: v9 shipped the 3-entry DISBURSEMENT ledger flow, MTN has a working `MtnMoMoClient.transfer()`, and Orange has a `cashout` port method that needs redirecting to the correct `/ic2c/pay` endpoint. v10 assembles these pieces into a cohesive, tenant-facing API.

The recommended approach is a standalone `disbursement/` module that mirrors the established `payment/` module structure without touching it. Zero new library dependencies are needed — every capability (pessimistic locking, Redis idempotency, Bucket4j velocity, Testcontainers, WireMock) is already in `pom.xml`. The one non-trivial new primitive is the `MERCHANT_WALLET` balance gate, which must use `SELECT FOR UPDATE` via `TransactionTemplate` — the same pattern already used throughout the codebase — to prevent double-spend under concurrent disbursement bursts.

The primary risks are financial, not technical: double-spend from concurrent balance reads (mitigated by pessimistic locking), partial failure after a provider accepts but before the ledger write commits (mitigated by setting `EXPIRED` status and triggering an ops alert), and Orange endpoint confusion where the v9 cashout port may call `/cashout` (customer self-cashout) instead of `/ic2c/pay` (merchant-to-customer). All three risks have clear prevention paths. Build in 4 dependency-respecting phases: schema + entities (Phase 50), orchestrator + API (Phase 51), callback controllers + webhooks (Phase 52), E2E tests (Phase 53).

---

## Key Findings

### Stack Additions

**Zero new library dependencies.** Every capability needed for v10 is already in `pom.xml`. The deliverables are entirely new Java classes and one Flyway migration (V26 — next in sequence after V25 shipped 2026-04-23).

**New patterns introduced (no new libs):**

- `WalletBalanceService` with `@Lock(PESSIMISTIC_WRITE)` — same JPA lock mode already used in `TransactionRepository.findByTransactionIdForUpdate()`; applied to a new `merchant_wallet_balance` row
- Second WireMock server bound to `mtn.disbursement-base-url` — MTN uses a distinct base URL for the Disbursement product; the existing E2E base class only covers `mtn.collection-base-url`
- Distinct Redis idempotency namespace `idempotency:dsb:<tenantId>:<key>` — prevents cross-flow key collisions with collections
- `EXPIRED` terminal state added to the status enum — distinguishes polling-timeout (ops investigation required) from deterministic `FAILED`

**Flyway V26 creates:** `main.disbursement`, `main.disbursement_aud` (Envers), `main.merchant_wallet_balance`

### Features: Table Stakes vs Differentiators

**Must-have (table stakes — none can be omitted without financial risk):**

- `POST /v1/disbursements` + `GET /v1/disbursements/{id}` — the core contract
- 202 Accepted + async provider path — both MTN and Orange are async-first
- Idempotency-Key header enforcement — Cameroon network retries make double-sends a production certainty without this
- Pre-funded balance gate (`MERCHANT_WALLET`) — outbound money; no reservation = wallet drain race
- MSISDN routing (MTN vs Orange by prefix) — existing `MsisdnPrefixRoute`, zero changes
- Tenant-scoped isolation — existing `TenantContext`, zero changes
- Disbursement-specific fraud velocity rules — stricter thresholds than collections (outbound money is higher risk)
- `disbursement.completed` / `disbursement.failed` outbound webhooks — tenants build async workflows on these
- FAILED state balance reversal — reservation must release atomically on failure or wallet locks
- MTN disbursement callback controller — async result delivery; polling alone is insufficient
- Orange disbursement callback controller — same requirement
- Polling fallback Quartz job — callbacks not guaranteed on Cameroon networks
- E2E test suite (both providers) — `mvn verify` is a non-negotiable platform invariant

**Include in same milestone (low incremental cost, high operational value):**

- SHA-256 MSISDN hashing in logs — free at write time; expensive to retrofit after logs are in Loki
- `failureReason` on terminal webhook — same payload construction effort as omitting it
- `EXPIRED` terminal state + ops alert — one enum value; meaningful for reconciliation and alerting
- Per-tenant configurable daily disbursement cap — prevents deployments to change limits
- Disbursement Micrometer metrics — same effort as not adding them

**Defer to v11+ (anti-features at this stage):**

- Batch disbursement endpoint — partial-success semantics multiply error surface; defer pending confirmed tenant demand
- Two-step approval workflow — explicitly deferred in disbursement-request.md; high complexity, unconfirmed demand
- Manual reversal endpoint — out of scope in PROJECT.md; neither MTN nor Orange supports native reversal
- Recipient KYC beyond active-subscriber check — non-uniform provider API surface; asymmetric behavior risk
- Scheduled/recurring payouts — scheduling engine is a product feature, not an API primitive
- Multi-currency — XAF only; hard-validate at input

### Architecture

v10 adds a standalone `disbursement/` module with the same `contract → repo → service → api → config` layering as `payment/`. It is additive-only — no existing modules are modified, except minimal wiring into provider ports and the webhook pipeline.

**Major components:**

1. **`DisbursementOrchestrator`** — idempotency check → fraud scoring → MSISDN routing → recipient validation → fee computation → balance check-and-reserve → provider call → ledger posting → 202 response. No `@Transactional` on the method (same rule as `PaymentOrchestrator`).
2. **`WalletBalanceService`** — `checkAndReserve()` (pessimistic `SELECT FOR UPDATE`), `release()` (on FAILED), `finalise()` (on SUCCESS). Wraps a single `merchant_wallet_balance` row per tenant.
3. **`DisbursementResource`** — `POST /v1/disbursements` + `GET /v1/disbursements/{id}` with tenant-scoped isolation.
4. **`MtnDisbursementCallbackController`** + **`OrangeDisbursementCallbackController`** — distinct paths (`/v1/callbacks/mtn/disbursement/{ref}` and `/v1/callbacks/orange/disbursement`); IP whitelist, signature verify, Redis replay guard, double-check via provider status API, then state transition + balance action + webhook enqueue.
5. **`OrangeMoneyPort.ic2cDisbursement()`** — new method targeting `/ic2c/pay`; must not reuse the `/cashout` path from v9.
6. **Disbursement state machine:** `INITIATED` → (balance/fraud/recipient check) → `PROCESSING` → (callback or poll) → `SUCCESS` | `FAILED` | `EXPIRED`. `EXPIRED` is a new terminal state (polling timeout ~10 min).

**Build order (dependency-respecting):**

| Phase | Contents |
|-------|----------|
| 50 | Flyway V26, `Disbursement` entity, `WalletBalance` entity, repositories, `WalletBalanceService`, `DisbursementStatus` enum, wallet concurrency tests |
| 51 | `DisbursementOrchestrator`, `DisbursementResource` (POST + GET), MTN and Orange port wiring |
| 52 | `MtnDisbursementCallbackController`, `OrangeDisbursementCallbackController`, outbound webhook extension |
| 53 | E2E tests: both provider happy paths, insufficient balance, fraud block, idempotency race, callback replay |

**Existing files modified (minimal surface):**

- `OrangeMoneyPort.java` — add `ic2cDisbursement()` method
- `MtnMoMoPort.java` — add `disbursementTransfer()` method wrapping existing client
- `FraudScoringService.java` — add disbursement-specific signal weights
- `WebhookDeliveryService.java` — handle `DisbursementCompletedEvent` alongside `PaymentCompletedEvent`

### Critical Pitfalls

Ranked by financial severity:

1. **Double-spend race on MERCHANT_WALLET balance (P-01, CRITICAL — Phase 50)** — Two concurrent requests read the same balance, both pass the sufficiency check, both proceed, wallet goes negative. Prevention: `@Lock(PESSIMISTIC_WRITE)` on `WalletBalanceRepository.findByTenantIdForUpdate()` inside `TransactionTemplate`. Verify with a 20-thread `WalletBalanceConcurrencyIT` before Phase 51 starts.

2. **Partial failure: provider accepts, ledger write fails (P-02, CRITICAL — Phase 51)** — Provider has money in flight but internal state is inconsistent. Prevention: set `EXPIRED` (not `FAILED`) on ledger failure after a successful provider call; trigger ops alert. Never hold a DB transaction open during provider HTTP calls.

3. **Orange IC2C vs cashout endpoint confusion (P-03, CRITICAL — Phase 51)** — `OrangeMoneyPort.initiateCashout()` from v9 calls `orangeMoneyClient.cashout()`. The disbursement spec requires `/ic2c/pay`. These are different endpoints with different request shapes; the wrong one works in sandbox but fails silently in production. Prevention: read `OrangeMoneyClient.cashout()` HTTP path before writing any disbursement code; if it calls `/cashout`, add `ic2cTransfer()` targeting `/ic2c/pay`; WireMock stubs must match exact URL.

4. **Missing WireMock server for MTN disbursement base URL (P-07, HIGH — Phase 53)** — `AbstractPayamE2ETest` binds one WireMock server to `mtn.collection-base-url`. MTN disbursements use `mtn.disbursement-base-url`. E2E tests will fail with connection refused or unexpected 404. Prevention: add a second `@ConfigureWireMock(name = "mtn-disbursement", baseUrlProperties = {"mtn.disbursement-base-url"})` to the E2E base class before writing any disbursement E2E tests.

5. **Missing wallet balance seed causes all tests to fail at balance gate (P-08, HIGH — Phase 50/53)** — `MERCHANT_WALLET` starts at zero; every disbursement attempt returns `INSUFFICIENT_BALANCE`. Prevention: add a `WalletTestSeeder` helper to `TestDataCleaner.setUp()` alongside existing tenant/transaction setup.

Additional pitfalls to address per phase:

- Phase 51: MTN disbursement OAuth2 token is short-lived — fetch fresh token per request and per polling-job invocation; do not cache across disbursements (P-04)
- Phase 52: Callback path collision — collection path `/v1/callbacks/mtn/{ref}` must not overlap with disbursement path `/v1/callbacks/mtn/disbursement/{ref}`; register disbursement callback URL with providers using the `/disbursement/` variant from initial configuration (P-05)
- Phase 52: Outbound webhook payload divergence — define `DisbursementWebhookPayload` as a separate record; event strings `"disbursement.completed"` / `"disbursement.failed"` distinct from payment namespace (P-06)
- Phase 50: `EXPIRED` state missing from illegal-transition matrix — add EXPIRED rows to the SM path matrix test in the same phase the enum value is added (P-10)
- Phase 51: Idempotency key namespace collision — use `idempotency:dsb:<tenantId>:<key>` prefix throughout (P-09)

---

## Implications for Roadmap

### Phase 50: Schema, Entities, and Balance Infrastructure

**Rationale:** All downstream phases depend on the DB schema and the balance gate. Getting the concurrency model right first means Phase 51 can focus on orchestrator logic without revisiting data access.

**Delivers:** Flyway V26 (`main.disbursement`, `main.disbursement_aud`, `main.merchant_wallet_balance`); `Disbursement` entity + repository; `WalletBalance` entity + repository; `WalletBalanceService` (`checkAndReserve`, `release`, `finalise`); `DisbursementStatus` enum including `EXPIRED`; `WalletBalanceConcurrencyIT` (20-thread race test).

**Addresses:** DISB-04 (balance gate infrastructure)

**Avoids:** P-01 (double-spend — pessimistic lock proven before any orchestrator code); P-08 (wallet seed in `TestDataCleaner`); P-10 (`EXPIRED` added to SM matrix in same phase)

**Research flag:** No deeper research needed — all patterns are established in the codebase.

---

### Phase 51: DisbursementOrchestrator and Public API

**Rationale:** The orchestrator is the core business logic. It depends on the entities, balance service, and provider ports from Phase 50 + existing infrastructure. Both provider paths must be wired together because the routing decision (MTN vs Orange) happens inside the orchestrator.

**Delivers:** `DisbursementOrchestrator` (full happy path: idempotency → fraud → balance gate → routing → recipient validation → fee → provider call → ledger → 202); `DisbursementResource` (`POST /v1/disbursements`, `GET /v1/disbursements/{id}`); `OrangeMoneyPort.ic2cDisbursement()` (after verifying `/ic2c/pay` endpoint); `MtnMoMoPort.disbursementTransfer()`.

**Addresses:** DISB-01 (payout initiation), DISB-02 (status query), DISB-03 (idempotency), DISB-05 (fraud scoring)

**Avoids:** P-02 (EXPIRED on ledger failure after provider success); P-03 (Orange endpoint verified before coding); P-04 (fresh MTN token per request); P-09 (distinct idempotency namespace)

**Research flag:** Read `OrangeMoneyClient.cashout()` HTTP path at the very start of Phase 51. This is the one concrete unknown that could derail the phase.

---

### Phase 52: Callback Controllers and Outbound Webhooks

**Rationale:** Provider callbacks are the mechanism by which PROCESSING disbursements reach terminal states. They depend on the orchestrator's state machine being defined (Phase 51) and must be built before E2E tests can exercise full flows.

**Delivers:** `MtnDisbursementCallbackController` (`/v1/callbacks/mtn/disbursement/{ref}`); `OrangeDisbursementCallbackController` (`/v1/callbacks/orange/disbursement`); `DisbursementCompletedEvent` + `WebhookDeliveryService` extension for `disbursement.completed` / `disbursement.failed`; `DisbursementWebhookPayload` (separate record from `PaymentWebhookPayload`).

**Addresses:** DISB-06 (outbound webhooks)

**Avoids:** P-05 (distinct callback paths registered with providers from the start); P-06 (separate webhook payload record, correct event strings)

**Research flag:** No deeper research needed — double-check pattern, IP whitelist, HMAC verification, Redis replay guard all have established implementations to clone.

---

### Phase 53: E2E Test Suite

**Rationale:** E2E tests are the platform-wide correctness gate (`mvn verify` must pass before every commit). They depend on all prior phases being complete. The WireMock gap for MTN disbursements must be closed before any E2E test stub is written.

**Delivers:** E2E coverage for both providers: happy path (MTN + Orange), insufficient balance, fraud block, idempotency race (concurrent requests), callback replay protection, polling fallback, `EXPIRED` terminal path. `WalletTestSeeder` in `TestDataCleaner`. Second WireMock server for `mtn.disbursement-base-url`. `LedgerVerifier.assertDisbursementLedgerBalanced()` assertions in all terminal-state flows.

**Addresses:** DISB-07 (E2E verification)

**Avoids:** P-07 (second WireMock server added first, before any test stub is written); P-08 (wallet seed integrated into `TestDataCleaner` setup)

**Research flag:** No deeper research needed — E2E infrastructure (Testcontainers, WireMock, `AbstractPayamE2ETest`, test data builders) is production-grade and fully documented.

---

### Phase Ordering Rationale

- Phase 50 before 51: orchestrator cannot compile without entities and `WalletBalanceService`
- Phase 51 before 52: callback controllers call `DisbursementRepository.updateStatus()` and `WalletBalanceService.release()` — both defined in 50/51
- Phase 52 before 53: E2E happy-path tests require a callback to arrive and be processed to assert `SUCCESS` terminal state
- Phases 50–53 are a single cohesive block: the balance gate + reversal + idempotency form an atomic financial safety guarantee — shipping any subset creates irreversible financial risk

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All library versions confirmed directly from `pom.xml`; migration numbering confirmed from migration directory listing |
| Features | HIGH | Cross-verified against MTN MoMo API docs, PawaPay implementation guide, existing requirements doc, and codebase capabilities |
| Architecture | HIGH | Based on direct codebase inspection of `PaymentOrchestrator`, `TransactionRepository`, `OrangeMoneyPort`, `MtnMoMoPort`, `AbstractPayamE2ETest`, and all callback controllers |
| Pitfalls | HIGH | Derived from direct codebase inspection; P-03 is the one area where a concrete value (the actual HTTP path in `OrangeMoneyClient.cashout()`) must be confirmed at Phase 51 start |

**Overall confidence:** HIGH

### Gaps to Address

- **Orange `/ic2c/pay` vs `/cashout` endpoint (P-03):** The exact HTTP path called by `OrangeMoneyClient.cashout()` was not confirmed in this research pass. Read `OrangeMoneyClient.java` at the start of Phase 51 before writing any port code.

- **Balance model — available/reserved vs single column:** STACK.md recommends a two-column model (`available` + `reserved`). ARCHITECTURE.md uses a single `balance` column. Decide before Phase 50 migration is written. Two-column gives cleaner observability; single-column is simpler. Either is correct with pessimistic locking.

- **`EXPIRED` state machine matrix row count:** The existing SM path matrix test covers 32 illegal transitions. Confirm the exact count of new rows needed when reading the matrix test at Phase 50 start.

- **Per-tenant daily cap storage:** Decide whether to add a column on `tenant` or a new `tenant_config` row. A column on `tenant` is the lowest-friction path given existing `TenantService` patterns.

---

## Sources

### Primary (HIGH confidence — direct codebase reads)

- `pom.xml` — all library versions confirmed; zero new dependencies needed
- `src/main/resources/db/migration/` — all migration files listed; V26 is next
- `PaymentOrchestrator.java` — orchestrator pattern, `TransactionTemplate` usage, no-`@Transactional` rule
- `MtnMoMoPort.java` — `initiateDisbursement()` exists; `fetchDisbursementToken()` exists
- `OrangeMoneyPort.java` — `initiateCashout()` exists; IC2C endpoint needs verification
- `TransactionRepository.java` — `findByTransactionIdForUpdate` pessimistic lock pattern confirmed
- `AbstractPayamE2ETest.java` — single WireMock server binding confirmed; disbursement server gap confirmed
- `requirements/disbursement-request.md` — API contract, state machine, entity field requirements

### Secondary (HIGH confidence — external docs cross-verified against codebase)

- MTN MoMo API: https://momo.mtn.com/api/
- PawaPay Implementation Considerations: https://docs.pawapay.io/implementation
- Stripe Payout Reconciliation: https://docs.stripe.com/payouts/reconciliation
- Modern Treasury — Ledger API: https://www.moderntreasury.com/journal/designing-ledgers-with-optimistic-locking
- Idempotency in Payment APIs (Brandur): https://brandur.org/http-transactions
- AfricaNenda — Network connectivity barriers: https://www.africanenda.org/en/blog/2025/the-biggest-barrier-to-digital-payment-adoption-may-be-dropped-network-connections
- Sourcery — Race Conditions in Financial Transactions: https://www.sourcery.ai/vulnerabilities/race-condition-financial-transactions

---

*Research completed: 2026-04-24*
*Ready for roadmap: yes*
