---
phase: 19-verifiers-builders
plan: 01
subsystem: testing
tags: [assertj, jdbc, wiremock, redis, awaitility, sha256, hmac]

# Dependency graph
requires:
  - phase: 18-test-infrastructure
    provides: AbstractPayamE2ETest base class, TestConfig, SqlStatementHolder infrastructure
provides:
  - 10 verifier classes in com.softropic.payam.e2e.verify covering all domain invariants
  - Single-line assertion API for Phase 20-23 E2E tests
  - Hash chain re-computation matching production DigestUtils.sha256Hex canonical string
  - Async webhook delivery assertion via Awaitility (atMost 5 seconds)
  - N+1 regression detection wrapping SqlStatementHolder thread-local
affects: [phase-20, phase-21, phase-22, phase-23]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - POJO verifiers — no Spring annotations, constructor-injected infrastructure
    - Delegate pattern in InvariantVerifier — assertAll() chains all verifiers
    - Identical canonical string for SHA-256 re-computation (txId|eventType|statusFrom|statusTo|actor|previousHash)

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/verify/DatabaseVerifier.java
    - src/test/java/com/softropic/payam/e2e/verify/HashChainVerifier.java
    - src/test/java/com/softropic/payam/e2e/verify/InvariantVerifier.java
    - src/test/java/com/softropic/payam/e2e/verify/EventVerifier.java
    - src/test/java/com/softropic/payam/e2e/verify/LedgerVerifier.java
    - src/test/java/com/softropic/payam/e2e/verify/ProviderCallVerifier.java
    - src/test/java/com/softropic/payam/e2e/verify/WebhookDeliveryVerifier.java
    - src/test/java/com/softropic/payam/e2e/verify/TenantIsolationVerifier.java
    - src/test/java/com/softropic/payam/e2e/verify/CacheVerifier.java
    - src/test/java/com/softropic/payam/e2e/verify/QueryCountVerifier.java
  modified: []

key-decisions:
  - "InvariantVerifier constructs all delegates internally from (jdbc, redis, mtnServer, orangeServer) — no separate delegate construction in tests"
  - "EventVerifier queries main.payment_event_log (no Spring Modulith outbox table exists in this project)"
  - "WebhookDeliveryVerifier uses Awaitility atMost(5, SECONDS) for async delivery assertion"
  - "CacheVerifier uses hasKey() for velocity bucket existence — does NOT read opaque Bucket4j byte[] value"
  - "QueryCountVerifier relies on TestConfig.spyDataSource being activated — precondition documented in Javadoc"

patterns-established:
  - "Verifier POJO pattern: plain Java, constructor-injected JdbcTemplate/StringRedisTemplate/WireMockServer, AssertJ assertions, no Spring"
  - "HMAC verification: sha256= prefix + Hex.encodeHexString matching production WebhookDeliveryService"

# Metrics
duration: ~30min
completed: 2026-03-27
---

# Plan 19-01: Verifier Classes Summary

**Ten plain-Java verifier POJOs that let Phase 20-23 E2E tests assert every domain invariant with a single method call.**

## Performance

- **Completed:** 2026-03-27
- **Tasks:** 2
- **Files created:** 10 (QueryCountVerifier was pre-existing; 9 created in this plan)

## Accomplishments

- Built all 10 verifier classes covering VERIF-01 through VERIF-10 requirements
- HashChainVerifier re-computes SHA-256 using the identical 6-field canonical string as production (no traceId/metadata drift)
- InvariantVerifier.assertAll() chains DatabaseVerifier + HashChainVerifier + LedgerVerifier with single-call convenience
- WebhookDeliveryVerifier asserts async delivery via Awaitility and verifies HMAC signature format (`sha256=<hex>`)
- CacheVerifier checks exact Redis key prefixes: `idempotency:{tenantId}:{key}`, `mtn:token:cm`, `orange:token:cm`, `fraud:velocity:{signal}:{id}`
- Zero Spring annotations on any verifier class — injectable as plain POJOs in any test context

## Commits

- `011a1d4` feat(19-01): add verifier classes — DatabaseVerifier, HashChainVerifier, InvariantVerifier, EventVerifier, LedgerVerifier, ProviderCallVerifier, WebhookDeliveryVerifier, TenantIsolationVerifier, CacheVerifier

## Deviations

None.
