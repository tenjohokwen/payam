---
phase: 07-fraud-engine
plan: 01
subsystem: payments
tags: [fraud, bucket4j-redis, velocity-checks, risk-scoring, flyway, lettuce, java-records]

# Dependency graph
requires:
  - phase: 06-webhook-processing
    provides: Redis Testcontainer in TestConfig, established IT test pattern, TransactionTemplate pattern
  - phase: 05-payment-orchestration
    provides: PaymentCommand record, PaymentOrchestrator non-transactional pattern, TransactionTemplate usage
  - phase: 02-transaction-core
    provides: Transaction entity, AbstractAuditingEntity pattern, BaseEntity with @Tsid
provides:
  - FraudScoringService.evaluate(PaymentCommand) returning FraudDecision with blocked/riskScore/reason
  - VelocityCheckService with Redis-backed Bucket4j token buckets via LettuceBasedProxyManager
  - FraudRuleCache with AtomicReference hot-reload via @Scheduled
  - FraudRule entity + FraudRuleRepository in fraud.repo package
  - FraudDecision record and FraudSignal enum in fraud.contract package
  - V10 Flyway migration: fraud_rule table + risk_score/device_fingerprint columns on transaction
  - PaymentCommand extended with clientIp, userAgent, deviceFingerprint (nullable)
  - PaymentRequest extended with optional deviceFingerprint field
  - FraudScoringServiceIT: 3 passing integration tests against real Redis Testcontainer
affects:
  - 07-02 (PaymentOrchestrator integration — will inject FraudScoringService and wire fraud hook)

# Tech tracking
tech-stack:
  added:
    - bucket4j-redis 8.10.1 (Redis-backed token bucket proxy manager via LettuceBasedProxyManager)
  patterns:
    - FraudRuleCache uses AtomicReference<List<FraudRule>> for thread-safe hot-reload without locking
    - VelocityCheckService extracts host/port from LettuceConnectionFactory for Testcontainer compatibility
    - FraudScoringService direct velocity block: any exceeded velocity limit is immediate block (separate from score-threshold block)
    - Fraud rules seeded via JDBC in IT @BeforeEach — dev profile create-drop wipes Flyway seed data
    - LettuceBasedProxyManager.builderFor(RedisClient) created from host/port (not getNativeClient() cast)

key-files:
  created:
    - src/main/resources/db/migration/V10__fraud_schema.sql
    - src/main/java/com/softropic/payam/fraud/repo/FraudRule.java
    - src/main/java/com/softropic/payam/fraud/repo/FraudRuleRepository.java
    - src/main/java/com/softropic/payam/fraud/service/FraudRuleCache.java
    - src/main/java/com/softropic/payam/fraud/contract/FraudDecision.java
    - src/main/java/com/softropic/payam/fraud/contract/FraudSignal.java
    - src/main/java/com/softropic/payam/fraud/service/VelocityCheckService.java
    - src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java
    - src/test/java/com/softropic/payam/fraud/FraudScoringServiceIT.java
  modified:
    - pom.xml (bucket4j-redis dependency)
    - src/main/java/com/softropic/payam/common/payment/PaymentCommand.java (3 new nullable fields)
    - src/main/java/com/softropic/payam/payment/contract/PaymentRequest.java (deviceFingerprint field)
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java (null for 3 new fields)
    - src/test/java/com/softropic/payam/mtn/MtnMoMoPortIT.java (PaymentCommand constructor update)
    - src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java (PaymentCommand constructor update)

key-decisions:
  - "VelocityCheckService uses LettuceConnectionFactory.getHostName()/getPort() to create dedicated RedisClient — getNativeClient() cast unreliable in test context where @ServiceConnection reconfigures the factory"
  - "FraudScoringService.evaluate() has dual block logic: direct velocity block (any exceeded signal = immediate block) + score-based block (weighted sum >= BLOCK_THRESHOLD); direct block satisfies the must-have truths"
  - "Fraud rules seeded via JDBC in IT @BeforeEach — dev profile uses create-drop so Flyway seed data is wiped by Hibernate schema generation; JDBC with ON CONFLICT DO UPDATE avoids TSID auto-generation on BaseEntity"
  - "FraudSignal enum values match signal_name DB strings exactly — getSignalName() returns the enum name, enabling zero-mapping between Java and DB"
  - "BLOCK_THRESHOLD stored as fraud_rule row (threshold=70) — DB-configurable without restart; default=70 if rule not found (fail-safe)"
  - "bucket4j-redis 8.10.1 uses Bandwidth.builder().capacity().refillIntervally() API (not the deprecated Bandwidth.classic()) — verified by compiling against 8.10.1 jar"

patterns-established:
  - "Pattern: FraudRuleCache — AtomicReference<List<T>> for thread-safe hot-reload from DB without synchronization"
  - "Pattern: IT @BeforeEach JDBC seeding for data that Flyway inserts but Hibernate create-drop wipes"
  - "Pattern: VelocityCheckService host/port extraction from LettuceConnectionFactory for Testcontainer-safe Redis client creation"

# Metrics
duration: 23min
completed: 2026-03-24
---

# Phase 7 Plan 01: Fraud Engine Foundation Summary

**Redis-backed Bucket4j velocity checks, weighted risk scoring, DB-configurable fraud rules with hot-reload, and 3 IT tests against real Redis Testcontainer**

## Performance

- **Duration:** 23 minutes
- **Started:** 2026-03-24T10:59:56Z
- **Completed:** 2026-03-24T11:22:00Z
- **Tasks:** 3/3
- **Files modified:** 15

## Accomplishments

- Complete `fraud` package: `contract/`, `repo/`, `service/` subpackages with all scoring logic self-contained
- VelocityCheckService uses `LettuceBasedProxyManager` with Redis-backed token buckets — state survives JVM restart and works across nodes
- FraudRuleCache hot-reloads DB rules into AtomicReference — no restart needed when rule weights/thresholds change
- FraudScoringService.evaluate() checks 4 velocity signals (IP, MSISDN, APP, MSISDN_HOUSEHOLD), computes weighted risk score, and blocks on direct velocity violation or score threshold
- V10 migration adds `fraud_rule` table + `risk_score`/`device_fingerprint` columns on `transaction`
- All 3 FraudScoringServiceIT tests pass: ipVelocityBlock, msisdnVelocityBlock, scoreComputedWithinRange

## Task Commits

Each task was committed atomically:

1. **Task 1: Flyway V10 + FraudRule + FraudRuleRepository + FraudRuleCache + pom.xml** - `64cd7a7` (feat)
2. **Task 2: FraudDecision + FraudSignal + VelocityCheckService + FraudScoringService + PaymentCommand/Request extensions** - `6f04523` (feat)
3. **Task 3: FraudScoringServiceIT** - `57fbc53` (feat)

## Files Created/Modified

- `src/main/resources/db/migration/V10__fraud_schema.sql` — fraud_rule table DDL with 5 seed rows, risk_score/device_fingerprint ALTER on transaction
- `src/main/java/com/softropic/payam/fraud/repo/FraudRule.java` — @Entity extending AbstractAuditingEntity
- `src/main/java/com/softropic/payam/fraud/repo/FraudRuleRepository.java` — JpaRepository with findByEnabledTrue()
- `src/main/java/com/softropic/payam/fraud/service/FraudRuleCache.java` — @Component with AtomicReference hot-reload
- `src/main/java/com/softropic/payam/fraud/contract/FraudDecision.java` — record with allow/block static factories
- `src/main/java/com/softropic/payam/fraud/contract/FraudSignal.java` — enum mapping to DB signal_name strings
- `src/main/java/com/softropic/payam/fraud/service/VelocityCheckService.java` — Bucket4j-Redis token buckets
- `src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java` — orchestrates 4 signals + scoring
- `src/test/java/com/softropic/payam/fraud/FraudScoringServiceIT.java` — 3 IT tests against real Redis
- `pom.xml` — bucket4j-redis 8.10.1 added
- `PaymentCommand.java` — 3 new nullable fields: clientIp, userAgent, deviceFingerprint
- `PaymentRequest.java` — optional deviceFingerprint field added
- `PaymentOrchestrator.java` — null passed for 3 new PaymentCommand fields (Plan 07-02 wires real values)
- `MtnMoMoPortIT.java` / `OrangeMoneyPortIT.java` — PaymentCommand constructor updated for new fields

## Decisions Made

- **VelocityCheckService Redis client construction:** Uses `LettuceConnectionFactory.getHostName()/getPort()` to create a dedicated `RedisClient` — `getNativeClient()` cast is unreliable in test contexts where `@ServiceConnection` reconfigures the factory. Direct host/port extraction is Testcontainer-safe.
- **Dual block logic in FraudScoringService:** Direct velocity block (any exceeded signal = immediate block regardless of score) + score-based block (weighted sum ≥ BLOCK_THRESHOLD). The direct block satisfies the must-have truths: "A payment from an IP exceeding 10 requests/60s triggers a velocity block."
- **Fraud rule test seeding via JDBC:** Dev profile uses `create-drop` so Flyway seed data is wiped by Hibernate schema generation. `@BeforeEach` seeds rules via `jdbcTemplate.update()` with `ON CONFLICT DO UPDATE` — avoids @Tsid auto-generation overwriting explicit IDs.
- **FraudSignal enum matches DB signal_name exactly:** `IP_VELOCITY.getSignalName()` returns `"IP_VELOCITY"` — zero translation layer between Java and DB.
- **BLOCK_THRESHOLD as fraud_rule row:** DB-driven and hot-reloadable; default=70 if row not found (fail-safe).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] VelocityCheckService: getNativeClient() cast unreliable for Testcontainer**
- **Found during:** Task 3 (FraudScoringServiceIT — velocity block tests failed)
- **Issue:** `LettuceConnectionFactory.getNativeClient()` returns `AbstractRedisClient` cast to `RedisClient`, but the client configuration may predate `@ServiceConnection` reconfiguring the factory to use Testcontainer's dynamic port
- **Fix:** Use `lettuceConnectionFactory.getHostName() + getPort()` to create a fresh `RedisClient` — these values are updated by `@ServiceConnection` at context startup
- **Files modified:** `VelocityCheckService.java`
- **Verification:** `ipVelocityBlock` and `msisdnVelocityBlock` tests pass with Redis at dynamic Testcontainer port
- **Committed in:** `57fbc53`

**2. [Rule 1 - Bug] FraudScoringService: single-signal velocity block not firing with BLOCK_THRESHOLD=70**
- **Found during:** Task 3 (msisdnVelocityBlock test — MSISDN weight=35 < BLOCK_THRESHOLD=70)
- **Issue:** Must-have truths require "A payment to an MSISDN exceeding 5 requests/60s triggers a velocity block" — but with MSISDN_VELOCITY weight=35 and BLOCK_THRESHOLD=70, a single exceeded signal didn't reach the score threshold
- **Fix:** Added direct velocity block step before score-based block — any exceeded velocity signal triggers immediate block, score-based block is secondary for composite signal patterns
- **Files modified:** `FraudScoringService.java`
- **Verification:** `msisdnVelocityBlock` passes; `ipVelocityBlock` passes; `scoreComputedWithinRange` passes
- **Committed in:** `57fbc53`

**3. [Rule 3 - Blocking] MtnMoMoPortIT + OrangeMoneyPortIT: PaymentCommand constructor arity mismatch**
- **Found during:** Task 3 (testCompile failed with 5 constructor errors)
- **Issue:** Adding 3 fields to `PaymentCommand` record broke all existing test construction sites
- **Fix:** Updated all 5 `new PaymentCommand(...)` calls in MtnMoMoPortIT and OrangeMoneyPortIT to pass `null, null, null` for the new fields
- **Files modified:** `MtnMoMoPortIT.java`, `OrangeMoneyPortIT.java`
- **Verification:** `mvn compiler:testCompile` exits 0
- **Committed in:** `57fbc53`

**4. [Rule 1 - Bug] ipVelocityBlock test: unique MSISDN + household prefix required per call**
- **Found during:** Task 3 (ipVelocityBlock failed on request 6 because MSISDN_VELOCITY exhausted, then on request 9 because MSISDN_HOUSEHOLD exhausted)
- **Issue:** Plan spec said "call 10 times, all should be allowed" but used a fixed MSISDN — MSISDN_VELOCITY threshold=5 and MSISDN_HOUSEHOLD threshold=8 triggered before IP threshold=10
- **Fix:** Use unique MSISDN AND unique 9-digit prefix per call ("+237691{2-digit-counter}00000") so IP bucket is the only one progressively consumed
- **Files modified:** `FraudScoringServiceIT.java`
- **Verification:** All 10 preparatory calls are `blocked=false`, 11th is `blocked=true` with IP_VELOCITY reason
- **Committed in:** `57fbc53`

---

**Total deviations:** 4 auto-fixed (2 Rule 1 bugs, 1 Rule 3 blocking, 1 Rule 1 test bug)
**Impact on plan:** All fixes necessary for correctness and test validity. No scope creep. The direct velocity block is aligned with the must-have truths and success criteria.

## Issues Encountered

- bucket4j-redis `LettuceBasedProxyManager.builderFor(RedisClient)` API verified by decompiling the 8.10.1 jar — `Bandwidth.builder().capacity().refillIntervally()` confirmed as the correct 8.x API (not deprecated `Bandwidth.classic()`)
- Hibernate `create-drop` in dev profile wipes Flyway seed data — established pattern of JDBC seeding in IT `@BeforeEach` for all future fraud IT tests

## User Setup Required

None — no external service configuration required. Redis Testcontainer handles all test infrastructure.

## Next Phase Readiness

- Plan 07-02 can inject `FraudScoringService` into `PaymentOrchestrator` — the service is self-contained and ready
- Plan 07-02 needs to: add `OrchestratorError.FRAUD_BLOCKED`, wire `fraudScoringService.evaluate(cmd)` between Step 4 and Step 5, populate `clientIp`/`userAgent` from `RequestMetadataProvider`, and populate `deviceFingerprint` from `PaymentRequest.deviceFingerprint()`
- `Transaction.setRiskScore()` and `Transaction.setDeviceFingerprint()` setters are NOT yet added — Plan 07-02 needs to add them for score/fingerprint persistence
- V10 migration columns (`risk_score`, `device_fingerprint`) are in place on `transaction` table, ready for Plan 07-02 to use

---
*Phase: 07-fraud-engine*
*Completed: 2026-03-24*
