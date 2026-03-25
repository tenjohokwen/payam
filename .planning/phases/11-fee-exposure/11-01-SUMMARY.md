---
phase: 11-fee-exposure
plan: 01
status: complete
completed: 2026-03-25
subsystem: payment-api
tags: [fee, payment-response, webhook, dto, phase-11]
dependency-graph:
  requires: [10-01]  # FeeEvaluationService, Transaction.feeAmount/feeRuleId from Phase 10 OPS-01
  provides: [fee-in-api-response, fee-in-webhook-payload]
  affects: []
tech-stack:
  added: []
  patterns: [array-holder-capture-in-lambda, nullable-cache-entry-null-guard]
key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/payment/contract/PaymentResponse.java
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/webhook/contract/OutboundWebhookPayload.java
    - src/main/java/com/softropic/payam/webhook/repo/WebhookDeliveryLog.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java
    - src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java
    - src/test/java/com/softropic/payam/webhook/WebhookDeliveryIT.java
decisions:
  - "PaymentResponse gains feeAmount (BigDecimal, never null) and feeRuleId (Long, nullable) as 7-component record"
  - "Array holders BigDecimal[]{ZERO} and Long[]{null} capture fee values from transactionTemplate lambda for use in PaymentResponse.accepted() — lambda local variables cannot be effectively-final with mutation"
  - "cachedResponse (not cached) variable name used in idempotency replay — 'cached' already declared as Optional<CachedResponse> in same scope"
  - "fee_rule JDBC seed requires rule_name (NOT NULL) — FeeRule entity has mandatory ruleName field not visible from FeeEvaluationService alone"
  - "WebhookDeliveryLog.feeAmount mapped to nullable fee_amount column — no Flyway migration needed for dev create-drop test strategy; nullable so old rows map to null which enqueue() null-guards to ZERO"
metrics:
  duration: 27 min
  completed: 2026-03-25
commits:
  - a86e2e6  # feat(11-01): add feeAmount + feeRuleId to PaymentResponse, wire in PaymentOrchestrator
  - 48d0b9c  # feat(11-01): add feeAmount to OutboundWebhookPayload and propagate through webhook delivery pipeline
  - 146ec0b  # test(11-01): add feeAmount IT assertions in PaymentOrchestratorIT and WebhookDeliveryIT
---

# Phase 11 Plan 01: Fee Exposure Summary

**One-liner:** Fee fields wired from Transaction to POST /v1/payments response (feeAmount+feeRuleId) and to outbound webhook payload (feeAmount), closing OPS-01 gap.

## What Was Built

Phase 10 OPS-01 computed and stored `fee_amount` and `fee_rule_id` on `Transaction`, but neither field appeared in the API response or outbound webhook payload. Tenants had no way to reconcile charges. This plan surfaces both fields.

### Task 1 — PaymentResponse DTO + PaymentOrchestrator wiring

- `PaymentResponse` record extended from 5 to 7 components: added `BigDecimal feeAmount` and `Long feeRuleId`
- `accepted()` static factory gains two new parameters; null-guards `feeAmount` to `BigDecimal.ZERO`
- `failed()` static factory unchanged in signature; internally passes `BigDecimal.ZERO` and `null`
- `PaymentOrchestrator.initiate()` captures fee values from the `transactionTemplate` lambda via array holders (`BigDecimal[] capturedFee`, `Long[] capturedFeeRuleId`) — the only safe capture pattern for mutable values in lambdas
- `PaymentResponse.accepted(...)` call updated to pass `capturedFee[0]` and `capturedFeeRuleId[0]`
- Idempotency replay path null-guards `feeAmount` on pre-Phase-11 cached JSON entries

### Task 2 — OutboundWebhookPayload + WebhookDeliveryLog + delivery service propagation

- `OutboundWebhookPayload` record extended from 5 to 6 components: added `BigDecimal feeAmount`
- `WebhookDeliveryLog` entity gains nullable `fee_amount` column (BigDecimal, precision 20 scale 2) with `setFeeAmount()` setter
- `WebhookDeliveryService.enqueue()` gains `BigDecimal feeAmount` parameter; null-guards to `BigDecimal.ZERO` on log row
- `attemptDeliveryInternal()` passes `delivery.getFeeAmount()` (with null-guard) to `OutboundWebhookPayload` constructor
- `WebhookTransitionService.applyFinalTransition()` passes `tx.getFeeAmount()` to `enqueue()`; pre-Phase-10 transactions with null feeAmount handled by enqueue null-guard

### Task 3 — IT assertions

- `PaymentOrchestratorIT`: zero-fee path assertions added to both `orange_*_returns_202` and `mtn_*_returns_202` tests (`feeAmount` not null, equals ZERO, `feeRuleId` null)
- `PaymentOrchestratorIT`: new test `fee_rule_applied_returns_nonzero_fee_amount` seeds a FEE_FIXED 50 XAF rule via JDBC, forces cache refresh, asserts `feeAmount > 0` and `feeRuleId != null`
- `WebhookDeliveryIT`: `shouldDeliverWebhookWithCorrectHmacSignatureOnSuccessTransition` now asserts `feeAmount` present in JSON body and equals `0` for zero-fee path
- All 11 tests pass: PaymentOrchestratorIT 8/8, WebhookDeliveryIT 3/3

## Decisions Made

| Decision | Rationale |
|---|---|
| Array holders for lambda capture | Lambda variables must be effectively final; `BigDecimal[]` and `Long[]` array references are final while contents are mutable |
| `cachedResponse` variable name | `cached` already declared as `Optional<CachedResponse>` in same method scope — compiler error without rename |
| fee_rule seed requires rule_name | NOT NULL constraint on `rule_name` column discovered at test runtime; added `'TEST-FIXED-50'` to JDBC INSERT |
| No Flyway migration for fee_amount column | Dev profile uses `create-drop`; Hibernate auto-creates `fee_amount` column from entity; nullable so old rows return null |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Variable name collision in idempotency replay path**

- **Found during:** Task 1 compilation
- **Issue:** Plan suggested `PaymentResponse cached = ...` but `cached` was already declared as `Optional<CachedResponse>` in the enclosing scope — compiler error
- **Fix:** Renamed inner variable to `cachedResponse`
- **Files modified:** `PaymentOrchestrator.java`

**2. [Rule 1 - Bug] fee_rule JDBC INSERT missing required rule_name column**

- **Found during:** Task 3 IT run
- **Issue:** `rule_name` is NOT NULL in `FeeRule` entity but the plan's INSERT example omitted it — `DataIntegrityViolationException`
- **Fix:** Added `rule_name, 'TEST-FIXED-50'` to the INSERT statement
- **Files modified:** `PaymentOrchestratorIT.java`

**3. [Rule 1 - Bug] WebhookDeliveryIT Map wildcard incompatible type**

- **Found during:** Task 3 test compilation
- **Issue:** `Map<?,?>` does not accept `String` for `containsKey()` — compiler type error
- **Fix:** Changed to `Map<String, Object>` with `@SuppressWarnings("unchecked")`
- **Files modified:** `WebhookDeliveryIT.java`

## Next Phase Readiness

Phase 11 Plan 01 is complete. All must-have truths verified:
- POST /v1/payments response includes `feeAmount` (BigDecimal, >= 0, never null) and `feeRuleId` (Long, nullable)
- Zero-fee transactions return `feeAmount: 0` — not null
- Outbound webhook JSON contains `feeAmount` field
- No regressions — all 11 IT assertions pass
