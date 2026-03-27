---
phase: 19-verifiers-builders
plan: 02
subsystem: testing
tags: [test-builders, deterministic-uuid, tsid, jackson, redis, jdbc, orange, mtn]

# Dependency graph
requires:
  - phase: 18-test-infrastructure
    provides: AbstractPayamE2ETest base class, TestDataCleaner
  - plan: 19-01
    provides: Verifier classes (used alongside builders in E2E tests)
provides:
  - 8 builder classes + DeterministicUuidFactory in com.softropic.payam.e2e.builder
  - Deterministic, reproducible test data for any payment scenario
  - Fluent API for tenant/API-key/payment/webhook/fraud/reconciliation seeding
affects: [phase-20, phase-21, phase-22, phase-23]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Explicit-commit builder pattern — fluent accumulate then .create(jdbcTemplate) or .build()
    - DeterministicUuidFactory — per-test-class seed, instance-level counter (no shared state)
    - TSID generation for direct JdbcTemplate inserts (io.hypersistence.tsid.TSID.fast().toLong())
    - Jackson ObjectMapper.convertValue for constructing immutable DTOs with no setters (OrangeWebhookPayload)

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/builder/DeterministicUuidFactory.java
    - src/test/java/com/softropic/payam/e2e/builder/TenantBuilder.java
    - src/test/java/com/softropic/payam/e2e/builder/ApiKeyBuilder.java
    - src/test/java/com/softropic/payam/e2e/builder/PaymentRequestBuilder.java
    - src/test/java/com/softropic/payam/e2e/builder/MtnWebhookPayloadBuilder.java
    - src/test/java/com/softropic/payam/e2e/builder/OrangeWebhookPayloadBuilder.java
    - src/test/java/com/softropic/payam/e2e/builder/FraudSignalBuilder.java
    - src/test/java/com/softropic/payam/e2e/builder/ReconciliationReportBuilder.java
  modified: []

key-decisions:
  - "TenantBuilder delegates to TenantService.createTenant — no hand-rolled SHA-256; production key hashing logic is reused exactly"
  - "ApiKeyBuilder uses DigestUtils.sha256Hex matching ApiKeyAuthenticationFilter's key lookup"
  - "OrangeWebhookPayloadBuilder uses Jackson ObjectMapper.convertValue to construct immutable OrangeWebhookPayload DTO (no setters on that class)"
  - "OrangeWebhookPayloadBuilder createtime format: yyyy-MM-dd HH:mm:ss in WAT (Africa/Douala) with no offset — matches OrangeTimeUtil.parseOrangeTimestamp expectations"
  - "FraudSignalBuilder.build() returns Map<String, String> with sourceIp and deviceFingerprint — sourceIp goes in HTTP header, deviceFingerprint in PaymentRequest"
  - "ReconciliationReportBuilder uses TSID.fast().toLong() for primary key (consistent with @Tsid entity strategy)"
  - "io.hypersistence.tsid.TSID (not io.hypersistence.utils.tsid.Tsid) — the TSID class lives in the hypersistence-tsid jar"

patterns-established:
  - "Builder explicit-commit pattern: fluent field accumulation → .create(jdbcTemplate) for DB write, .build() for DTO construction"
  - "No static mutable state in any builder — each is instantiated fresh per test"

# Metrics
duration: ~45min
completed: 2026-03-27
---

# Plan 19-02: Builder Classes Summary

**Nine test data builders (8 builders + DeterministicUuidFactory) enabling deterministic, reproducible payment scenario seeding for all Phase 20-23 E2E tests.**

## Performance

- **Completed:** 2026-03-27
- **Tasks:** 2
- **Files created:** 9

## Accomplishments

- DeterministicUuidFactory with per-class seed + instance-level counter — fully reproducible UUID sequences
- TenantBuilder delegates to TenantService (not hand-rolled SQL) — reuses production API-key hashing
- ApiKeyBuilder inserts with DigestUtils.sha256Hex, returning rawKey for Authorization headers
- PaymentRequestBuilder supports both MTN (237690000001) and Orange (237690000002) MSISDN prefixes
- MtnWebhookPayloadBuilder produces SUCCESSFUL/FAILED callbacks with correct nulling of financialTransactionId on failure
- OrangeWebhookPayloadBuilder produces WAT createtime in `yyyy-MM-dd HH:mm:ss` format (no offset) via Jackson ObjectMapper.convertValue
- FraudSignalBuilder can delete `fraud:velocity:{signal}:{identifier}` Redis keys to reset Bucket4j velocity counters
- ReconciliationReportBuilder inserts both report and discrepancy rows using TSID primary keys, handles UNIQUE(report_date, provider) constraint

## Commits

- `88255f0` feat(19-02): add foundational builder classes — DeterministicUuidFactory, TenantBuilder, ApiKeyBuilder, PaymentRequestBuilder
- `4467dfa` feat(19-02): add scenario builders — MtnWebhookPayloadBuilder, OrangeWebhookPayloadBuilder, FraudSignalBuilder, ReconciliationReportBuilder

## Deviations

- **ReconciliationReportBuilder ID generation**: Used `io.hypersistence.tsid.TSID.fast().toLong()` (hypersistence-tsid jar) rather than a DB sequence, since the reconciliation_report DDL uses a plain BIGINT PRIMARY KEY (no SERIAL/sequence). This is consistent with the `@Tsid` strategy used by BaseEntity everywhere else.
- **OrangeWebhookPayload construction**: Production class has no setters (Jackson-only DTO). Used `ObjectMapper.convertValue(Map, OrangeWebhookPayload.class)` to populate fields via @JsonProperty mappings rather than modifying production code.
