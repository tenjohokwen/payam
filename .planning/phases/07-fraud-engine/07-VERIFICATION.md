---
phase: 07-fraud-engine
verified: 2026-03-24T11:43:52Z
status: passed
score: 5/5 must-haves verified
---

# Phase 7: Fraud Engine Verification Report

**Phase Goal:** Velocity checks, risk scoring pipeline, device fingerprinting — all signal weights DB-configurable
**Verified:** 2026-03-24T11:43:52Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A transaction exceeding velocity thresholds is blocked before reaching the provider | VERIFIED | `FraudScoringService.evaluate()` fires at PaymentOrchestrator Step 4.5 — before `port.initiateMerchantPayment()` at Step 5; `FraudEngineIT.velocityBlockReturns422` asserts `mtnServer.verify(0, postRequestedFor(...))` |
| 2 | Every transaction receives a risk score 0–100 computed from configured signals before provider dispatch | VERIFIED | `FraudDecision.allow(riskScore)` returned on all non-blocked paths; `PaymentOrchestrator` persists `fraud.riskScore()` via `TransactionTemplate`; `FraudEngineIT.normalPaymentHasRiskScoreInDb` queries `risk_score` from DB and asserts non-null and 0–100 |
| 3 | Device fingerprint data from the client request is stored with the transaction | VERIFIED | `PaymentRequest.deviceFingerprint()` field exists; `PaymentCommand.deviceFingerprint` carries it; `PaymentOrchestrator` calls `locked.setDeviceFingerprint(cmd.deviceFingerprint())`; `Transaction.deviceFingerprint` column mapped with `@NotAudited` and `columnDefinition = "TEXT"`; V10 migration adds `device_fingerprint TEXT` to `transaction` |
| 4 | All fraud signal weights are stored in DB and hot-reloadable — no restart needed | VERIFIED | `FraudRuleCache` holds `AtomicReference<List<FraudRule>>`; `@Scheduled(fixedDelayString = "${fraud.rule-cache.refresh-interval-ms:60000}")` refreshes from DB; `@EnableScheduling` present in `AsyncConfig`; `application.yaml` exposes `fraud.rule-cache.refresh-interval-ms: 60000`; `FraudEngineIT` exercises live cache refresh via `fraudRuleCache.refreshRules()` after JDBC threshold update |
| 5 | SIM-sharing household patterns can be down-weighted via config, reducing false positives | VERIFIED | `MSISDN_HOUSEHOLD` seeded with weight=15 — lowest of all four signals (IP_VELOCITY=40, MSISDN_VELOCITY=35, APP_VELOCITY=25); weight stored in `fraud_rule` DB row, editable without restart; `FraudSignal.MSISDN_HOUSEHOLD` uses 9-digit prefix key; `FraudScoringService` Javadoc explicitly notes "lower weight to reduce false positives" |

**Score:** 5/5 truths verified

---

### Required Artifacts

| Artifact | Lines | Substantive | Wired | Status |
|----------|-------|-------------|-------|--------|
| `src/main/resources/db/migration/V10__fraud_schema.sql` | 37 | Yes — creates `fraud_rule` table with 5 seed rows; alters `transaction` for `risk_score`/`device_fingerprint` | Flyway auto-applies | VERIFIED |
| `src/main/java/.../fraud/repo/FraudRule.java` | 52 | Yes — `@Entity`, 6 mapped columns, extends `AbstractAuditingEntity` | Used by `FraudRuleRepository`, cached in `FraudRuleCache` | VERIFIED |
| `src/main/java/.../fraud/repo/FraudRuleRepository.java` | 18 | Yes — `JpaRepository<FraudRule, Long>` with `findByEnabledTrue()` | Injected into `FraudRuleCache` | VERIFIED |
| `src/main/java/.../fraud/service/FraudRuleCache.java` | 74 | Yes — `AtomicReference<List<FraudRule>>`, `@PostConstruct` init, `@Scheduled` refresh | Injected into `FraudScoringService` and `VelocityCheckService` | VERIFIED |
| `src/main/java/.../fraud/contract/FraudDecision.java` | 23 | Yes — record with `blocked/riskScore/reason`, static `allow()`/`block()` factories | Returned by `FraudScoringService.evaluate()`, checked in `PaymentOrchestrator` | VERIFIED |
| `src/main/java/.../fraud/contract/FraudSignal.java` | 38 | Yes — 4 enum values with `getSignalName()` matching DB `signal_name` strings exactly | Used by `VelocityCheckService.checkVelocity()` and `FraudScoringService` | VERIFIED |
| `src/main/java/.../fraud/service/VelocityCheckService.java` | 89 | Yes — Bucket4j `LettuceBasedProxyManager`, `@PostConstruct` init, `checkVelocity()` returns true/false | Injected into `FraudScoringService`, called for 4 signals per evaluation | VERIFIED |
| `src/main/java/.../fraud/service/FraudScoringService.java` | 136 | Yes — evaluates 4 signals, computes weighted score, dual-block logic (direct velocity + score threshold) | Injected into `PaymentOrchestrator`, called at Step 4.5 | VERIFIED |
| `src/main/java/.../payment/contract/OrchestratorError.java` | 42 | Yes — `FRAUD_BLOCKED` enum entry present with Javadoc "Maps to HTTP 422" | Returned in `PaymentOrchestrator` fraud-blocked path | VERIFIED |
| `src/main/java/.../transaction/repo/Transaction.java` | 138 | Yes — `riskScore` (Integer, `@NotAudited`) and `deviceFingerprint` (TEXT, `@NotAudited`) fields with public setters | Set by `PaymentOrchestrator` via `TransactionTemplate` | VERIFIED |
| `src/main/java/.../payment/service/PaymentOrchestrator.java` | 270 | Yes — Step 4.5 fraud hook wired; `FraudScoringService` injected; real IP/UA from `RequestMetadataProvider`; fingerprint from `PaymentRequest` | Full payment flow entry point | VERIFIED |
| `src/test/java/.../fraud/FraudScoringServiceIT.java` | 243 | Yes — 3 integration tests against real Redis Testcontainer: `ipVelocityBlock`, `msisdnVelocityBlock`, `scoreComputedWithinRange` | Standalone IT for fraud service layer | VERIFIED |
| `src/test/java/.../fraud/FraudEngineIT.java` | 291 | Yes — 2 end-to-end tests via `POST /v1/payments`: `velocityBlockReturns422` (asserts 422 + zero WireMock calls), `normalPaymentHasRiskScoreInDb` (asserts non-null `risk_score` in DB) | Full E2E coverage of FRAUD-01 | VERIFIED |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `PaymentOrchestrator.initiate()` | `FraudScoringService.evaluate()` | Direct call at Step 4.5 | WIRED | Fires between `PaymentCommand` construction and `port.initiateMerchantPayment()` — provider never reached on block |
| `FraudScoringService` | `VelocityCheckService.checkVelocity()` | 4 explicit calls (IP, MSISDN, APP, MSISDN_HOUSEHOLD) | WIRED | Results used in `rawScore` computation and `anyVelocityViolated` flag |
| `FraudScoringService` | `FraudRuleCache.getRules()` / `findBySignalName()` | Direct method calls | WIRED | Rules used for weight lookup and `BLOCK_THRESHOLD` retrieval |
| `VelocityCheckService` | Redis (Bucket4j) | `LettuceBasedProxyManager.builderFor(RedisClient).build()` | WIRED | `@PostConstruct` init builds `ProxyManager<byte[]>`; `checkVelocity()` calls `proxyManager.builder().build(key, ...).tryConsume(1)` |
| `FraudRuleCache` | `FraudRuleRepository.findByEnabledTrue()` | `@PostConstruct` + `@Scheduled` | WIRED | `@EnableScheduling` in `AsyncConfig`; `refresh-interval-ms` configurable from `application.yaml` |
| `PaymentOrchestrator` | `Transaction.setRiskScore()` + `setDeviceFingerprint()` | `TransactionTemplate.execute()` | WIRED | Executed before provider dispatch on allowed payments; both setters are public |
| `PaymentRequest.deviceFingerprint` | `PaymentCommand.deviceFingerprint` | Passed in `PaymentOrchestrator` Step 4 | WIRED | `request.deviceFingerprint()` → `PaymentCommand` 12th field |

---

### Requirements Coverage

| Requirement | Status | Notes |
|-------------|--------|-------|
| FRAUD-01: Velocity checks block before provider dispatch | SATISFIED | E2E test `velocityBlockReturns422` verifies WireMock receives 0 provider calls |
| FRAUD-01: Risk score 0–100 on every transaction | SATISFIED | `normalPaymentHasRiskScoreInDb` queries DB and asserts `isBetween(0, 100)` |
| FRAUD-01: Device fingerprint stored | SATISFIED | V10 migration column + `Transaction.deviceFingerprint` field + orchestrator persistence |
| FRAUD-01: DB-configurable signal weights, hot-reload | SATISFIED | `FraudRuleCache` `@Scheduled` refresh + `AtomicReference` swap; no restart needed |
| P7.1 fix: SIM-sharing down-weighted via config | SATISFIED | `MSISDN_HOUSEHOLD` weight=15 in DB seed; editable without code change or restart |

---

### Anti-Patterns Found

None. All `return null` occurrences in test files are idiomatic `TransactionTemplate.execute()` lambda returns — required by the `TransactionCallback<Void>` API, not stubs.

---

### Human Verification Required

#### 1. Hot-reload end-to-end (production scenario)

**Test:** With the application running against a real PostgreSQL + Redis, update a `fraud_rule` row weight in the DB (e.g., `UPDATE main.fraud_rule SET weight = 50 WHERE signal_name = 'IP_VELOCITY'`), wait 60 seconds for the scheduled refresh, then submit a payment that would previously score below the block threshold but now scores above it.
**Expected:** Payment is blocked after the cache refresh with no application restart.
**Why human:** Cannot verify scheduled timer behaviour or live DB mutation without a running instance.

#### 2. Device fingerprint round-trip (client-to-DB)

**Test:** Submit `POST /v1/payments` with `{"deviceFingerprint": "fp-abc123", ...}` in the request body, then query `SELECT device_fingerprint FROM main.transaction WHERE ...`.
**Expected:** `fp-abc123` is stored in the row.
**Why human:** FraudEngineIT does not send a `deviceFingerprint` in the request body — it omits the field — so the DB persistence path for a real fingerprint value is not covered by automated tests.

---

## Summary

All 5 must-have truths are verified against the actual codebase:

1. **Velocity block before provider dispatch** — `FraudScoringService.evaluate()` wired at PaymentOrchestrator Step 4.5; `FraudEngineIT` proves zero WireMock calls on blocked payment.

2. **Risk score 0–100 on every transaction** — Weighted sum of violated signals, clamped to 100, persisted via `TransactionTemplate` on every allowed payment; E2E test queries and validates range.

3. **Device fingerprint stored with transaction** — `PaymentRequest.deviceFingerprint` → `PaymentCommand` → `Transaction.deviceFingerprint` column; V10 migration column in place. Note: automated tests do not exercise a non-null fingerprint value end-to-end (flagged for human verification above).

4. **All signal weights DB-configurable, hot-reloadable** — `FraudRuleCache` uses `AtomicReference` + `@Scheduled` refresh from `FraudRuleRepository`; `application.yaml` exposes `fraud.rule-cache.refresh-interval-ms`; `@EnableScheduling` active.

5. **SIM-sharing household down-weighted via config** — `MSISDN_HOUSEHOLD` row weight=15 (lowest of 4 signals); editable in `fraud_rule` table without code change or restart.

No stubs, no orphaned artifacts, no blocker anti-patterns. Phase goal is achieved.

---

*Verified: 2026-03-24T11:43:52Z*
*Verifier: Claude (gsd-verifier)*
