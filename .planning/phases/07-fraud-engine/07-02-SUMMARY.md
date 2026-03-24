---
phase: 07-fraud-engine
plan: 02
subsystem: payments
tags: [fraud, velocity, bucket4j, redis, spring-security, spring-data-jpa, wiremock, testcontainers]

# Dependency graph
requires:
  - phase: 07-01
    provides: VelocityCheckService (Bucket4j-Redis), FraudScoringService, FraudRuleCache, FraudDecision record, PaymentCommand.clientIp/userAgent/deviceFingerprint fields
  - phase: 05-payment-orchestration
    provides: PaymentOrchestrator.initiate() method, PaymentCommand, OrchestratorError, PaymentResponse
  - phase: 02-transaction-core
    provides: Transaction entity, TransactionRepository, Flyway V3 transaction schema
provides:
  - OrchestratorError.FRAUD_BLOCKED enum entry — maps to HTTP 422 via PaymentResource catch-all
  - FraudScoringService wired as pre-dispatch hook in PaymentOrchestrator Step 4.5
  - Transaction.riskScore (Integer) and Transaction.deviceFingerprint (TEXT) fields with setters
  - Risk score and device fingerprint persisted on every allowed transaction
  - application.yaml fraud: block-threshold / rule-cache.refresh-interval-ms config block
  - FraudEngineIT: 2 end-to-end tests via POST /v1/payments (velocity block + score persistence)
  - FRAUD-01 requirement closed end-to-end
affects: [08-admin-dashboard, 10-hardening]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Pre-dispatch fraud hook: fraudScoringService.evaluate(cmd) fires between PaymentCommand construction (Step 4) and port.initiateMerchantPayment() dispatch (Step 5) — provider never called for blocked payments"
    - "Fraud score persistence: TransactionTemplate wraps setRiskScore()+setDeviceFingerprint() after evaluate() — separate from provider dispatch transaction"
    - "@NotAudited on riskScore and deviceFingerprint: Hibernate Envers audit table lacks these columns; @NotAudited excludes them from audit trail"
    - "MSISDN_VELOCITY bucket manipulation in IT: lower threshold via JDBC+transactionTemplate then fraudRuleCache.refreshRules() — ForwardedHeaderFilter strips Forwarded header so IP injection unreliable; MSISDN key stable and predictable"

key-files:
  created:
    - src/test/java/com/softropic/payam/fraud/FraudEngineIT.java
  modified:
    - src/main/java/com/softropic/payam/payment/contract/OrchestratorError.java
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/transaction/repo/Transaction.java
    - src/main/resources/application.yaml

key-decisions:
  - "07-02 decision: setRiskScore() and setDeviceFingerprint() made public (not package-private as plan stated) — PaymentOrchestrator is in payment.service package; Transaction is in transaction.repo package; package-private is inaccessible cross-package; existing setProviderRef() pattern is also public"
  - "07-02 decision: MSISDN_VELOCITY used for velocity block IT test (not IP_VELOCITY) — ForwardedHeaderFilter (registered in SecurityConfiguration) strips the Forwarded header before RequestMetadataProvider.initRequestMetadata() reads it; MSISDN is stable per-request and reliable as bucket key"
  - "07-02 decision: JDBC threshold update wrapped in transactionTemplate.execute() before fraudRuleCache.refreshRules() — ensures DB commit is visible to Spring Data JPA repository query inside refreshRules()"
  - "07-02 decision: @NotAudited on riskScore and deviceFingerprint fields — Transaction entity is @Audited; these two fields added by V10 migration but not present in Envers audit table schema; @NotAudited excludes them from revision tracking"

patterns-established:
  - "Fraud-before-dispatch: any new payment flow entry point MUST call FraudScoringService.evaluate() before dispatching to provider; velocity block + score persistence is the two-step minimum"
  - "ForwardedHeaderFilter awareness: IT tests relying on IP injection via Forwarded header will fail — ForwardedHeaderFilter removes the header before request metadata is read; use MSISDN/tenantId-based signals for velocity testing"

# Metrics
duration: 30min
completed: 2026-03-24
---

# Phase 7 Plan 2: Fraud Engine — Orchestrator Integration Summary

**FraudScoringService wired as pre-dispatch hook in PaymentOrchestrator: velocity blocks fire before any provider HTTP call, risk_score persisted on every transaction, FRAUD-01 closed end-to-end**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-03-24T11:20:00Z
- **Completed:** 2026-03-24T11:40:00Z
- **Tasks:** 2
- **Files modified:** 5 (4 main + 1 test created)

## Accomplishments

- FRAUD-01 closed: velocity checks (Bucket4j-Redis), risk scoring (0-100), device fingerprinting wired end-to-end into POST /v1/payments
- FraudScoringService injected into PaymentOrchestrator; evaluate() fires between Step 4 (PaymentCommand build) and Step 5 (port dispatch); blocked payments never reach provider
- Transaction entity gains riskScore and deviceFingerprint fields with @NotAudited (Envers audit table doesn't include them); score persisted via TransactionTemplate after every allowed evaluation
- Real IP/UA populated from RequestMetadataProvider.getClientInfo() and deviceFingerprint from PaymentRequest into PaymentCommand (replaces null placeholders from Phase 5)
- FraudEngineIT: 2 IT tests via POST /v1/payments — MSISDN velocity block returns 422 FRAUD_BLOCKED with zero WireMock provider calls; normal payment has non-null risk_score in DB
- All 7 PaymentOrchestratorIT tests still pass (no regression)

## Task Commits

Each task was committed atomically:

1. **Task 1: OrchestratorError.FRAUD_BLOCKED + Transaction fields + PaymentOrchestrator hook + application.yaml** - `199ebc9` (feat)
2. **Task 2: FraudEngineIT end-to-end velocity block + risk score persistence** - `31a3692` (feat)

**Plan metadata:** _(to be committed)_

## Files Created/Modified

- `src/main/java/com/softropic/payam/payment/contract/OrchestratorError.java` - Added FRAUD_BLOCKED enum entry
- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` - Injected FraudScoringService, wired real IP/UA/fingerprint, added Step 4.5 fraud check hook
- `src/main/java/com/softropic/payam/transaction/repo/Transaction.java` - Added riskScore + deviceFingerprint fields with @NotAudited; public setters
- `src/main/resources/application.yaml` - Added fraud: block-threshold + rule-cache.refresh-interval-ms config block
- `src/test/java/com/softropic/payam/fraud/FraudEngineIT.java` - Created: 2 end-to-end tests via POST /v1/payments

## Decisions Made

- **setRiskScore/setDeviceFingerprint are public (not package-private):** Plan said "package-private per established pattern" but PaymentOrchestrator is in `payment.service` package while Transaction is in `transaction.repo`. Package-private is inaccessible cross-package. The actual established pattern (setProviderRef, setMtnFinancialTxId) is already public. Made them public to match the real pattern.

- **MSISDN_VELOCITY used in velocity block IT test:** Plan suggested lowering IP_VELOCITY threshold and injecting IP via `Forwarded: IP` header. However, `SecurityConfiguration` registers a `ForwardedHeaderFilter` that strips/rewrites the `Forwarded` header before `RequestMetadataProvider.initRequestMetadata()` reads it. IP injection via headers is unreliable. MSISDN is a stable, predictable bucket key that doesn't depend on header injection.

- **JDBC update in transactionTemplate before refreshRules():** Plain JDBC without transaction boundary isn't guaranteed visible to Spring Data JPA's `findByEnabledTrue()` immediately. Wrapping in `transactionTemplate.execute()` ensures the UPDATE commits before the cache refresh reads it.

- **@NotAudited on riskScore and deviceFingerprint:** Transaction entity is `@Audited` (Hibernate Envers). Flyway V10 adds the columns to the main table but not to the `_AUD` Envers audit table. Adding `@NotAudited` excludes these fields from revision tracking, preventing schema validation errors on the audit table.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] setRiskScore/setDeviceFingerprint made public instead of package-private**
- **Found during:** Task 1 (compilation)
- **Issue:** Plan instructed package-private setters but PaymentOrchestrator (payment.service) calls them on Transaction (transaction.repo) — cross-package access fails with package-private visibility
- **Fix:** Changed to `public void` matching the existing `setProviderRef()` pattern which is also public
- **Files modified:** src/main/java/com/softropic/payam/transaction/repo/Transaction.java
- **Verification:** mvn compiler:compile exits 0
- **Committed in:** 199ebc9 (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 bug: incorrect access modifier recommendation in plan)
**Impact on plan:** Required for correctness. The established pattern was already public.

## Issues Encountered

- **ForwardedHeaderFilter strips Forwarded header:** IT test for velocity block initially used IP injection via `Forwarded: 10.99.0.1` header. Both requests returned 202 (not 422) because the filter removed the header before RequestMetadataProvider read it. Switched to MSISDN_VELOCITY signal — MSISDN is reliable and doesn't depend on header processing. Test passes with this approach.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- FRAUD-01 complete: velocity checks, risk scoring, device fingerprinting all wired into payment flow
- Phase 8 (admin dashboard) can query `risk_score` from `main.transaction` table directly
- FraudRuleCache.refreshRules() is public — Phase 10 hardening can add scheduled refresh mechanism if needed
- Blockers: None

---
*Phase: 07-fraud-engine*
*Completed: 2026-03-24*
