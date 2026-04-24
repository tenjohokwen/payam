# Pitfalls — v10 Client Disbursement API

**Domain:** B2B disbursement/payout API — mobile money (MTN MoMo + Orange Money), Cameroon
**Project:** Payam — Spring Boot 3.5, PostgreSQL, Redis, Vue 3 + Quasar
**Researched:** 2026-04-24
**Overall confidence:** HIGH — derived from direct codebase inspection and cross-validated against disbursement-request.md spec and existing payment patterns.

---

## P-01 — Double-spend race on MERCHANT_WALLET balance (CRITICAL)

**Phase:** Phase 50 (wallet balance infrastructure)

**What goes wrong:** Two concurrent disbursement requests read the same wallet balance, both pass the sufficiency check, both proceed — wallet goes negative. The existing `@Version` optimistic locking pattern (used on `TenantApiKey`) retries on conflict, which for a balance gate allows a second depletion after the first completes.

**Prevention:**
- Use `SELECT FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) on the wallet balance row inside a `TransactionTemplate` block.
- Never use optimistic `@Version` retry loops for balance gates.
- Test with a 20-thread concurrency race in `WalletBalanceConcurrencyIT` (mirrors `IdempotencyRaceIT` pattern in the codebase).

---

## P-02 — Partial failure: provider accepts, ledger write fails (CRITICAL)

**Phase:** Phase 51 (DisbursementOrchestrator)

**What goes wrong:** The orchestrator calls the provider (202 Accepted), then the `TransactionTemplate` for ledger posting throws — the disbursement is PROCESSING at the provider but the internal state is inconsistent.

**Prevention:**
- Post ledger entries only after the provider call succeeds and the transaction is marked PROCESSING.
- If ledger posting throws, set status to EXPIRED (not FAILED) — the provider may have already sent money. Trigger an ops alert.
- Never hold a DB transaction open during provider HTTP calls (established project rule).

---

## P-03 — Orange IC2C vs cashout endpoint confusion (CRITICAL)

**Phase:** Phase 51 (Orange disbursement)

**What goes wrong:** `OrangeMoneyPort.initiateCashout()` from v9 calls `orangeMoneyClient.cashout()`. The disbursement spec uses `/ic2c/pay` (IC2C endpoint for merchant-to-customer payouts). These are different endpoints with different request shapes. Using the wrong one works in sandbox but fails in production.

**Prevention:**
- Verify whether `orangeMoneyClient.cashout()` calls `/cashout` (customer self-cashout) or `/ic2c/pay` (merchant-to-customer).
- If it calls `/cashout`, create a new `OrangeMoneyClient.ic2cTransfer()` method targeting `/ic2c/pay`.
- WireMock stub must match on the exact URL path — `/ic2c/pay` vs `/cashout` are not interchangeable.

---

## P-04 — MTN disbursement token expiry during async processing

**Phase:** Phase 51 (MTN disbursement)

**What goes wrong:** MTN's disbursement OAuth2 token is short-lived. Reusing a cached token across requests or in the Quartz polling job causes silent auth failures (`401 Unauthorized` from MTN) after the token expires.

**Prevention:**
- Fetch a fresh disbursement token per request (same pattern as `fetchCollectionToken()` on the MTN collections path).
- The polling job must fetch a fresh token before each `getTransferStatus()` call.
- Token fetch failures should surface via circuit breaker, not as a silent EXPIRED outcome.

---

## P-05 — Callback routing collision with collection controllers

**Phase:** Phase 52 (callback controllers)

**What goes wrong:** The existing `MtnCallbackController` handles `/v1/callbacks/mtn/{referenceId}`. If the disbursement callback shares the same path or the discriminator logic is missing, a collection callback could be routed to the disbursement handler — wrong state transition, corrupted transaction.

**Prevention:**
- Use distinct paths: `/v1/callbacks/mtn/disbursement/{referenceId}` vs `/v1/callbacks/mtn/{referenceId}`.
- Register the disbursement callback URL with MTN using the `/disbursement/` path variant from the start.
- Add a controller-level type check: resolve `referenceId` → entity flow → assert DISBURSEMENT before any state change.

---

## P-06 — Outbound webhook payload structure diverging from collections

**Phase:** Phase 52 (outbound webhook)

**What goes wrong:** Copying the collection webhook payload for disbursements introduces subtle field differences (`paymentId` vs `disbursementId`, missing `fee` field, wrong `event` string: `"payment.completed"` instead of `"disbursement.completed"`). Tenant integrations break silently.

**Prevention:**
- Define `DisbursementWebhookPayload` as a separate record from `PaymentWebhookPayload`.
- Event strings: `"disbursement.completed"` / `"disbursement.failed"` (distinct namespace).
- E2E assertion: `assertDisbursementWebhookDelivered(disbursementId, expectedStatus)`.

---

## P-07 — WireMock single MTN server — disbursement base URL not stubbed

**Phase:** Phase 53 (E2E tests)

**What goes wrong:** `AbstractPayamE2ETest` binds one WireMock server to `mtn.collection-base-url`. MTN disbursements use `mtn.disbursement-base-url` (different base URL → `/disbursement/v1_0/transfer`). E2E tests route to the wrong server; stubs don't match; tests fail with connection refused or unexpected 404.

**Prevention:**
- Add a second `WireMockServer` bound to `mtn.disbursement-base-url` in `AbstractPayamE2ETest`.
- Register disbursement stubs on the second server, collection stubs on the first.
- Add a startup assertion that both servers are listening before any test runs.

---

## P-08 — Missing initial wallet balance causes all disbursement tests to fail at balance gate

**Phase:** Phase 50 (wallet infrastructure) / Phase 53 (E2E tests)

**What goes wrong:** `MERCHANT_WALLET` starts at zero. Every disbursement attempt returns `INSUFFICIENT_BALANCE` in tests. Tests that expect `PROCESSING` status all fail without a way to seed the wallet.

**Prevention:**
- Add a `WalletTestSeeder` helper that inserts a funded balance row via `JdbcTemplate` before each test that needs it.
- Include wallet seeding in `TestDataCleaner.setUp()` alongside tenant/transaction setup.
- For production, document that the wallet must be funded before disbursements are enabled (separate admin top-up flow or direct DB seeding).

---

## P-09 — Idempotency key namespace collision between collections and disbursements

**Phase:** Phase 51 (DisbursementOrchestrator)

**What goes wrong:** If disbursement idempotency keys share the Redis namespace with collections (`idempotency:<tenantId>:<key>`), a tenant accidentally reusing the same key for a disbursement as a prior collection gets an unexpected cached response (a collection response returned for a disbursement request).

**Prevention:**
- Use a distinct Redis key prefix: `idempotency:dsb:<tenantId>:<key>` for disbursements.
- Allows independent TTL policies and cleaner debugging.

---

## P-10 — `TransactionStatus.EXPIRED` missing from state machine and illegal-transition tests

**Phase:** Phase 50 (schema/entity)

**What goes wrong:** The polling-timeout path needs `EXPIRED` as a distinct terminal state. If `EXPIRED` is added to the enum without updating the state machine illegal-transition test matrix (32 paths today), the `@Test` that asserts all illegal transitions will have an incomplete matrix — EXPIRED → any non-terminal transition may be silently allowed.

**Prevention:**
- Add `EXPIRED` to `TransactionStatus` enum in Phase 50.
- Add EXPIRED rows to the illegal-transition matrix in the existing SM path matrix test.
- Add EXPIRED → SUCCESS (illegal) and EXPIRED → FAILED (illegal) as test cases.

---

## Phase-Specific Warnings Summary

| Phase | Pitfall | Prevention |
|-------|---------|------------|
| 50 (wallet infra) | Double-spend race on balance read-check-decrement | `SELECT FOR UPDATE` + `TransactionTemplate`; 20-thread concurrency test |
| 50 (wallet infra) | No wallet seeding → all balance gate tests fail | `WalletTestSeeder` helper in `TestDataCleaner` |
| 50 (schema) | `EXPIRED` state missing from illegal-transition matrix | Add EXPIRED to SM test matrix in same phase |
| 51 (orchestrator) | Provider accepts, ledger fails → inconsistent state | Set EXPIRED on ledger failure; ops alert; separate `TransactionTemplate` |
| 51 (Orange) | `/cashout` vs `/ic2c/pay` endpoint confusion | Verify endpoint in `OrangeMoneyClient`; stub by exact URL in WireMock |
| 51 (MTN) | Disbursement token expiry in polling job | Fetch fresh token per request and per poll |
| 51 (orchestrator) | Idempotency key namespace collision | Use `idempotency:dsb:` prefix |
| 52 (callbacks) | Collection callback routed to disbursement handler | Distinct paths `/disbursement/{ref}` vs `/{ref}` |
| 52 (webhooks) | Payload field divergence from collections | Separate `DisbursementWebhookPayload` record |
| 53 (E2E) | Second MTN WireMock server missing | Add second WireMockServer for `mtn.disbursement-base-url` |

---

*Pitfalls research for: Payam v10 — Client Disbursement API*
*Researched: 2026-04-24*
