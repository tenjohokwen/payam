---
phase: 16-business-event-logging
verified: 2026-03-27T00:00:00Z
status: passed
score: 6/6 must-haves verified
---

# Phase 16: Business Event Logging Verification Report

**Phase Goal:** All payment lifecycle events are observable in Loki with structured fields
**Verified:** 2026-03-27
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Payment initiations are queryable by transactionId, provider, tenantId in Loki | VERIFIED | PaymentOrchestrator.initiate() emits `initiate_payment` with `kv("tenantId", TenantContext.get())`, `kv("transactionId", ...)`, `kv("provider", ...)` on success (line 261-267) and all 4 failure branches (fraud block, circuit open, subscriber inactive, HTTP error, generic exception) |
| 2 | Every transaction state change emits a log with fromState, toState, actor | VERIFIED | 9 call sites confirmed across 4 files: 3 inline + 1 in applyFailed() in PaymentOrchestrator (actor=ORCHESTRATOR); 1 in WebhookTransitionService (actor=WEBHOOK_DOUBLE_CHECK); 2 in MtnStatusPollerJob (actor=MTN_POLLER); 2 in OrangeStatusPollerJob (actor=ORANGE_POLLER) |
| 3 | Inbound webhook receipt and outbound delivery each emit a dedicated structured log event | VERIFIED | MtnMoMoPort.processCallback() emits `webhook_received` with provider=MTN, transactionId, externalReference, providerStatus. OrangeMoneyPort.processWebhook() emits `webhook_received` with provider=ORANGE inside the present-branch lambda where txId is resolved. WebhookDeliveryService.attemptDeliveryInternal() emits `webhook_delivery` on all 4 outcome paths (success, non-2xx, HttpStatusCodeException, generic Exception) with transactionId, tenantId, durationMs, httpStatus, status, retryCount |
| 4 | Fraud evaluation results (riskScore, blocked) appear as structured fields in every evaluation log | VERIFIED | FraudScoringService.evaluate() emits `fraud_evaluation` on all 3 return paths: velocity block WARN (kv("riskScore",...), kv("blocked", true)), score-threshold block WARN (same fields), and allow path INFO (kv("blocked", false)) — every path includes riskScore and blocked |
| 5 | All MTN/Orange adapter HTTP calls log externalService, externalLatencyMs, and status | VERIFIED | MtnMoMoClient: 7 methods (fetchCollectionToken, fetchDisbursementToken, requestToPay, getRequestToPayStatus, validateAccountHolder, getBalance, disburse) each emit `kv("externalService", "MTN_MOMO")`, `kv("externalLatencyMs", ...)`, `kv("status", ...)`. OrangeMoneyClient: 7 methods (fetchToken, getSubscriberInfo, getMerchantInfo, pay, getPaymentStatus, cashout, c2c) emit `kv("externalService", "ORANGE_MONEY")`, `kv("externalLatencyMs", ...)`, `kv("status", ...)` |
| 6 | Daily reconciliation run emits a single structured summary log with discrepancyCount and status | VERIFIED | ReconciliationService.runForDate() emits a single `reconciliation_run` event after the provider loop with kv("discrepancyCount", totalDiscrepancies), kv("status", "SUCCESS"), kv("totalChecked", ...), kv("date", ...), kv("durationMs", ...) |

**Score:** 6/6 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` | initiate_payment + transaction_state_change events | VERIFIED | 393 lines; static import kv(); TenantContext.get() for tenantId UUID; success + 4 failure branches + applyFailed(); 4 state change calls |
| `src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java` | fraud_evaluation events on all 3 paths | VERIFIED | 153 lines; static import kv(); start timer at top of evaluate(); 3 log calls — 2 WARN (block paths), 1 INFO (allow path); riskScore + blocked on every path |
| `src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java` | transaction_state_change with actor=WEBHOOK_DOUBLE_CHECK | VERIFIED | 138 lines; static import kv(); log placed between applyTransition() and eventLogService.append() at line 98-103 |
| `src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java` | transaction_state_change with actor=MTN_POLLER on 2 paths | VERIFIED | 127 lines; static import kv(); timeout path (lines 85-90) and terminal status path (lines 105-110) both emit fromState=PROCESSING |
| `src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java` | transaction_state_change with actor=ORANGE_POLLER on 2 paths | VERIFIED | 134 lines; static import kv(); timeout path (lines 92-97) and terminal status path (lines 112-117) both emit fromState=PROCESSING |
| `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` | webhook_received with provider=MTN | VERIFIED | 242 lines; static import kv(); processCallback() emits webhook_received after dedup check at lines 174-179 |
| `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` | webhook_received with provider=ORANGE inside present-branch lambda | VERIFIED | 271 lines; static import kv(); processWebhook() emits webhook_received at lines 189-194 inside ifPresentOrElse lambda where txId is available |
| `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java` | webhook_delivery on all 4 outcome paths | VERIFIED | 260 lines; static import kv(); deliveryStart timer at line 175; 4 log.info/warn calls covering 2xx success, non-2xx, HttpStatusCodeException, generic Exception |
| `src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java` | externalService + externalLatencyMs + status on 7 methods | VERIFIED | 262 lines; static import kv(); logger field present; all 7 methods have start timer immediately before makeHttpRequest() and kv log immediately after |
| `src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java` | externalService + externalLatencyMs + status on 7 methods | VERIFIED | 195 lines; static import kv(); logger field present; all 7 methods (fetchToken, getSubscriberInfo, getMerchantInfo, pay, getPaymentStatus, cashout, c2c) instrumented |
| `src/main/java/com/softropic/payam/reconciliation/service/ReconciliationService.java` | single reconciliation_run summary event with discrepancyCount | VERIFIED | 237 lines; static import kv(); start timer at line 77; cross-provider accumulation via int[] return from runForProviderAndDate(); single summary event at lines 96-102 |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| PaymentOrchestrator.initiate() | Loki (initiate_payment) | kv() on success + all 4 failure branches | WIRED | tenantId=TenantContext.get() (UUID), transactionId, provider all present on every exit path |
| PaymentOrchestrator.applyFailed() | Loki (transaction_state_change) | kv() inside transactionTemplate.execute() | WIRED | fromState passed as method param, toState=FAILED hardcoded, actor=ORCHESTRATOR |
| FraudScoringService.evaluate() | Loki (fraud_evaluation) | kv() on all 3 return paths | WIRED | riskScore computed before all 3 returns; blocked=true on block paths, blocked=false on allow path |
| MtnMoMoPort.processCallback() | Loki (webhook_received) | kv() after Redis dedup | WIRED | provider=MTN, transactionId=payload.getExternalId(), externalReference=financialTransactionId (nullable), providerStatus=payload.getStatus() |
| OrangeMoneyPort.processWebhook() | Loki (webhook_received) | kv() inside ifPresentOrElse present-branch | WIRED | provider=ORANGE, transactionId=txId (from repository lookup), externalReference=tx.getExternalReference(), providerStatus=payload.getStatus() |
| WebhookDeliveryService.attemptDeliveryInternal() | Loki (webhook_delivery) | kv() on 4 exit paths | WIRED | deliveryStart before try block; httpStatus=-1 sentinel for network errors; all 4 catch branches covered |
| MtnMoMoClient (all 7 methods) | Loki (Provider HTTP call) | kv() after makeHttpRequest() returns | WIRED | start declared immediately before call; log immediately after call returns, before null/status checks |
| OrangeMoneyClient (all 7 methods) | Loki (Provider HTTP call) | kv() after makeHttpRequest() returns | WIRED | same pattern; cashout/c2c use local result variable to allow log before return |
| ReconciliationService.runForDate() | Loki (reconciliation_run) | kv() after provider loop | WIRED | cross-provider int[] accumulation aggregates totalChecked and discrepancyCount from both MTN and Orange before single emit |

---

### Requirements Coverage

All 7 LOG-BUS requirements covered:

| Requirement | Status | Notes |
|-------------|--------|-------|
| LOG-BUS-01: initiate_payment event | SATISFIED | PaymentOrchestrator — success + 5 failure branches |
| LOG-BUS-02: transaction_state_change event | SATISFIED | 9 sites across 4 files with fromState, toState, actor |
| LOG-BUS-03: webhook_received event (inbound) | SATISFIED | MtnMoMoPort + OrangeMoneyPort |
| LOG-BUS-04: webhook_delivery event (outbound) | SATISFIED | WebhookDeliveryService — 4 outcome paths |
| LOG-BUS-05: fraud_evaluation event | SATISFIED | FraudScoringService — 3 return paths, INFO on allow |
| LOG-BUS-06: provider HTTP call latency event | SATISFIED | 14 methods (7 MTN + 7 Orange) |
| LOG-BUS-07: reconciliation_run summary event | SATISFIED | ReconciliationService — single post-loop event |

---

### Anti-Patterns Found

No blocker anti-patterns found.

| File | Line | Pattern | Severity | Notes |
|------|------|---------|----------|-------|
| PaymentOrchestrator.java | 231 | TODO comment re: AUTH_PENDING state machine transitions | Info | Pre-existing design note; does not affect logging correctness |

---

### Human Verification Required

None — all structured logging is code-verifiable. The Loki queryability claim (that these fields actually reach Loki as indexed labels) depends on logstash-logback-encoder being configured on the classpath, which was established in Phase 14 and is a pre-condition of this phase.

---

## Detailed Findings

### Truth 1: Payment initiations queryable by transactionId, provider, tenantId

PaymentOrchestrator.initiate() emits `operation=initiate_payment` with tenantId, transactionId, and provider on every structured exit path. The tenantId field uses `TenantContext.get()` (UUID string), which is the MDC-consistent value established in Phase 15. The success path at lines 260-267 includes all three fields. All four failure branches (fraud blocked 178-186, circuit open 271-279, subscriber inactive 286-294, HTTP error 301-309, generic exception 316-325) also carry all three fields.

The unknown MSISDN prefix path and idempotency replay path intentionally omit the LOG-BUS-01 event: no transactionId is available on the former, and the replay is not a new initiation. This is a documented design decision and does not constitute a gap.

### Truth 2: Every transaction state change emits fromState, toState, actor

Nine call sites verified across four files:
- PaymentOrchestrator: INITIATED→AUTH_PENDING (line 233-238), AUTH_PENDING→AUTHORIZED (240-245), AUTHORIZED→PROCESSING (247-252), and from→FAILED in applyFailed() (364-369)
- WebhookTransitionService: PROCESSING→target with actor=WEBHOOK_DOUBLE_CHECK (lines 98-103)
- MtnStatusPollerJob: PROCESSING→FAILED timeout (lines 85-90), PROCESSING→next terminal (105-110), both with actor=MTN_POLLER
- OrangeStatusPollerJob: PROCESSING→FAILED timeout (lines 92-97), PROCESSING→next terminal (112-117), both with actor=ORANGE_POLLER

All sites use hardcoded `TransactionStatus.PROCESSING.name()` for fromState on poller and webhook sites — correct because post-transition the entity field is already mutated.

### Truth 3: Inbound webhook receipt and outbound delivery events

Inbound MTN: processCallback() emits after dedup gate passes. The externalReference field maps to financialTransactionId (may be null at callback time — null is valid JSON and omitted by Loki; no conditional logging needed).

Inbound Orange: processWebhook() emits inside the `ifPresentOrElse` present-branch lambda (not at method entry), where txId is resolved via `transactionRepository.findByPayToken()`. This is the correct placement — txId must be present in the structured event.

Outbound delivery: `deliveryStart` timer is declared at line 175, before the try block, ensuring durationMs covers all 4 exit paths. The httpStatus=-1 sentinel for network errors is consistent with the delivery log schema.

### Truth 4: Fraud evaluation riskScore and blocked in every evaluation log

All three return paths emit both fields. The velocity block path (lines 102-108) and score-threshold block path (118-124) use `log.warn` with `kv("blocked", true)`. The allow path (127-132) uses `log.info` with `kv("blocked", false)`. The upgrade from DEBUG to INFO on the allow path makes fraud outcomes visible in production logs for every payment.

### Truth 5: Adapter HTTP calls emit externalService, externalLatencyMs, status

MtnMoMoClient: 7 methods confirmed. The validateAccountHolder method places `start` inside the try block — the log does not emit on the exception path (404/inactive account case), which is documented as acceptable: the exception is the observable signal on that path.

OrangeMoneyClient: 7 methods confirmed. The cashout and c2c methods assign the makeHttpRequest() result to a local variable `result` before logging and returning — correct pattern when the method was originally a single-expression return.

### Truth 6: Reconciliation run emits single summary log

ReconciliationService.runForDate() accumulates totalChecked and totalDiscrepancies across both providers via the `int[]` return type of the private helper. The `status="SUCCESS"` field is emitted unconditionally after the loop — correct because provider exceptions are caught inside each iteration, so runForDate() always completes normally. The discrepancyCount communicates financial outcome independently of execution status.

---

*Verified: 2026-03-27*
*Verifier: Claude (gsd-verifier)*
