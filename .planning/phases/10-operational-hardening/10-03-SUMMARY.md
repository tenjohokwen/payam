---
phase: 10-operational-hardening
plan: 03
subsystem: api
tags: [resilience4j, circuit-breaker, hash-chain, tls, startup-assertion, spring-events, admin-api]

# Dependency graph
requires:
  - phase: 02-transaction-core
    provides: PaymentEventLog + EventLogService.verifyChain() — hash chain infrastructure reused directly
  - phase: 03-orange-money-adapter
    provides: CircuitBreaker "orange" wired to OrangeMoneyPort via Resilience4j
  - phase: 04-mtn-momo-adapter
    provides: CircuitBreaker "mtn" wired to MtnMoMoPort via Resilience4j
  - phase: 08-admin-dashboard
    provides: /v1/admin/** JWT security exclusion (NegatedRequestMatcher pattern) — new admin endpoints inherit this

provides:
  - TlsStartupAssertion: ApplicationReadyEvent listener that fails fast in non-dev when checkCertificate=false
  - GET /v1/admin/providers/status: circuit breaker state per provider (CLOSED/OPEN/HALF_OPEN + metrics)
  - GET /v1/admin/audit/hash-chain/{transactionId}: single-transaction SHA-256 chain verification
  - GET /v1/admin/audit/hash-chain: full audit across all transactions with violation list
  - PaymentEventLogRepository.findAllDistinctTransactionIds(): JPQL query for full audit

affects:
  - future-phases: any operational hardening or monitoring additions should follow ProviderStatusResource pattern

# Tech tracking
tech-stack:
  added: []
  patterns:
    - ApplicationListener<ApplicationReadyEvent> for startup assertions with profile guard (dev skip)
    - CircuitBreakerRegistry.circuitBreaker("name") force-create before getAllCircuitBreakers() to avoid lazy-init empty map
    - Thin controller delegating to existing service (AuditResource → EventLogService.verifyChain)
    - Java records for admin DTOs (ProviderStatusDto, HashChainResultDto, HashChainAuditSummaryDto)

key-files:
  created:
    - src/main/java/com/softropic/payam/ops/TlsStartupAssertion.java
    - src/main/java/com/softropic/payam/admin/api/ProviderStatusResource.java
    - src/main/java/com/softropic/payam/admin/contract/ProviderStatusDto.java
    - src/main/java/com/softropic/payam/admin/api/AuditResource.java
    - src/main/java/com/softropic/payam/admin/contract/HashChainResultDto.java
    - src/main/java/com/softropic/payam/admin/contract/HashChainAuditSummaryDto.java
    - src/test/java/com/softropic/payam/ops/OperationalIT.java
  modified:
    - src/main/java/com/softropic/payam/transaction/repo/PaymentEventLogRepository.java

key-decisions:
  - "TlsStartupAssertion uses Environment.getProperty('client.momo.tcp-config.check-certificate') — OrangeMoneyConfig and MtnMoMoConfig do NOT have getTcpConfig(); the checkCertificate flag lives in the legacy client.momo.tcpConfig YAML node (defaultTcpConfig anchor); Environment read is the correct integration point"
  - "TlsStartupAssertion dev-profile guard: Arrays.asList(env.getActiveProfiles()).contains('dev') — returns early before any assertion; application.yaml has checkCertificate:false in sandbox mode so dev assertion would always fail"
  - "ProviderStatusResource force-creates orange+mtn CBs before getAllCircuitBreakers() — Resilience4j creates CBs lazily on first use; without force-create the map is empty on a freshly-started instance that has processed no payments"
  - "AuditResource delegates directly to EventLogService.verifyChain() — no hash logic re-implemented in controller; thin controller pattern"
  - "AuditResource.verifyChain() returns 404 with valid:false on exception — covers both 'transaction not found' and unexpected errors without distinguishing them; avoids leaking existence information"
  - "OperationalIT seeds hash-chain data via EventLogService.append() — guarantees correct chain hashing without reproducing hash logic in test; JdbcTemplate tamper via UPDATE used only for the tampered-chain test"

patterns-established:
  - "Startup assertion pattern: ApplicationListener<ApplicationReadyEvent> + dev-profile guard + AppSetupException throw"
  - "Circuit breaker status endpoint pattern: force-create known CBs + getAllCircuitBreakers() iteration"

# Metrics
duration: 10min
completed: 2026-03-25
---

# Phase 10 Plan 03: TLS Assertion + Provider Circuit Breaker Status + Hash Chain Audit Summary

**Three operational visibility endpoints: TLS startup fail-fast guard, circuit breaker health map per provider, and SHA-256 hash chain audit REST surface exposing existing EventLogService.verifyChain()**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-03-25T00:06:45Z
- **Completed:** 2026-03-25T01:12:14Z
- **Tasks:** 2/2
- **Files modified:** 8 (7 created, 1 modified)

## Accomplishments

- TlsStartupAssertion closes the gap between `checkCertificate:false` in application.yaml and production deployments — fails fast on startup unless dev profile is active
- GET /v1/admin/providers/status surfaces Resilience4j circuit breaker state (CLOSED/OPEN/HALF_OPEN) with failure rate and call counts for orange and mtn providers
- GET /v1/admin/audit/hash-chain/{txId} and GET /v1/admin/audit/hash-chain expose the Phase 2 SHA-256 event log chain to admin operators without re-implementing any hash logic
- OperationalIT 5/5 pass: auth gate, CB state, valid chain, tampered chain detection, TLS dev-skip

## Task Commits

Each task was committed atomically:

1. **Task 1: TlsStartupAssertion + ProviderStatusResource + AuditResource** - `13cffe9` (feat)
2. **Task 2: OperationalIT integration test** - `567477c` (test)

## Files Created/Modified

- `src/main/java/com/softropic/payam/ops/TlsStartupAssertion.java` - ApplicationReadyEvent listener; skips in dev; throws AppSetupException if checkCertificate=false in non-dev
- `src/main/java/com/softropic/payam/admin/api/ProviderStatusResource.java` - GET /v1/admin/providers/status; force-creates orange+mtn CBs; returns LinkedHashMap
- `src/main/java/com/softropic/payam/admin/contract/ProviderStatusDto.java` - Record: state, failureRate, bufferedCalls, failedCalls
- `src/main/java/com/softropic/payam/admin/api/AuditResource.java` - GET /v1/admin/audit/hash-chain/{txId} + GET /v1/admin/audit/hash-chain (full audit)
- `src/main/java/com/softropic/payam/admin/contract/HashChainResultDto.java` - Record: transactionId, valid
- `src/main/java/com/softropic/payam/admin/contract/HashChainAuditSummaryDto.java` - Record: total, valid, violations list
- `src/main/java/com/softropic/payam/transaction/repo/PaymentEventLogRepository.java` - Added findAllDistinctTransactionIds() JPQL query
- `src/test/java/com/softropic/payam/ops/OperationalIT.java` - 5 IT tests covering all three operational endpoints

## Decisions Made

- **TlsStartupAssertion reads `client.momo.tcp-config.check-certificate` via Environment** — the plan assumed OrangeMoneyConfig and MtnMoMoConfig would have `getTcpConfig()`, but reading the actual source revealed neither has a `tcpConfig` field. The `checkCertificate: false` flag is in the legacy `defaultTcpConfig` YAML anchor used by `client.momo.tcpConfig`. Environment property read is the correct integration point.
- **Dev-profile guard uses `Arrays.asList(env.getActiveProfiles()).contains("dev")`** — Pitfall 1 fix; application.yaml sets `checkCertificate:false` globally in sandbox; without guard the assertion would always fail in tests.
- **ProviderStatusResource force-creates "orange" and "mtn" CBs** — Pitfall 5 fix; Resilience4j creates circuit breakers lazily on first use; freshly-started instances would return empty map without explicit `circuitBreakerRegistry.circuitBreaker("orange")` call.
- **AuditResource delegates directly to EventLogService.verifyChain()** — thin controller; no hash logic re-implemented.
- **OperationalIT seeds via EventLogService.append()** — guarantees correct hash chain without reproducing canonical string logic in test code.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] TlsStartupAssertion uses Environment.getProperty() instead of OrangeMoneyConfig.getTcpConfig()**
- **Found during:** Task 1 (reading OrangeMoneyConfig and MtnMoMoConfig source)
- **Issue:** Plan spec said "read OrangeMoneyConfig: the config references ClientConfiguration; read source to find the exact path to TcpConfiguration.checkCertificate before implementing." Reading the actual source revealed `OrangeMoneyConfig` has no `tcpConfig` or `ClientConfiguration` reference — it's a simple properties class with URL/credential fields only. The RESEARCH.md had an incorrect assertion at line 203 ("OrangeMoneyConfig and MtnMoMoConfig both reference TcpConfiguration via ClientConfiguration").
- **Fix:** Used `Environment.getProperty("client.momo.tcp-config.check-certificate", Boolean.class, true)` to read the actual property path where `checkCertificate: false` lives in application.yaml (via the `*DEFAULT_TCP` YAML anchor at `client.momo.tcpConfig.checkCertificate`). Default of `true` means assertion passes if property is absent (safe-by-default).
- **Files modified:** `src/main/java/com/softropic/payam/ops/TlsStartupAssertion.java`
- **Verification:** OperationalIT test_tlsAssertionSkippedInDevProfile passes; context starts without error
- **Committed in:** `13cffe9` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 — adapted implementation to match actual codebase state)
**Impact on plan:** Fix necessary because RESEARCH.md had incorrect assertion about config structure. The TLS assertion still achieves the intended goal: detect `checkCertificate:false` in non-dev environments.

## Issues Encountered

None beyond the deviation above.

## Next Phase Readiness

- Phase 10 plans 01 and 02 are still pending (fee rules, alert rules) — this plan (03) was wave 1 and had no dependencies on them
- All three success criteria for plans 01-03 of Phase 10 are satisfied by this plan: TLS assertion (SC3), provider status endpoint (SC4), hash chain audit endpoint (SC5)
- No new infrastructure dependencies introduced

---
*Phase: 10-operational-hardening*
*Completed: 2026-03-25*
