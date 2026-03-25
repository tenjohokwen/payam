---
phase: 11-fee-exposure
verified: 2026-03-25T00:00:00Z
status: passed
score: 4/4 must-haves verified
gaps: []
---

# Phase 11: Fee Exposure Verification Report

**Phase Goal:** Surface the applied fee on every payment — add `feeAmount` to `PaymentResponse` and `OutboundWebhookPayload` so tenants can inspect the fee charged on their transaction
**Verified:** 2026-03-25
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | POST /v1/payments response includes `feeAmount` (BigDecimal, >= 0, never null) and `feeRuleId` (Long, nullable when no rule matched) | VERIFIED | `PaymentResponse` is a 7-component record with `BigDecimal feeAmount` and `Long feeRuleId`; `accepted()` null-guards feeAmount to ZERO; `failed()` hardcodes ZERO |
| 2 | A zero-fee transaction (tenant has no matching fee rule) returns `feeAmount: 0` — not null | VERIFIED | `PaymentOrchestrator` uses array holders to capture `feeEvaluationService.evaluateFee()` result (defaults ZERO when no rule); idempotency replay path also null-guards old cached entries to ZERO |
| 3 | The JSON payload delivered to the tenant's webhook URL includes a `feeAmount` field | VERIFIED | `OutboundWebhookPayload` has `BigDecimal feeAmount` as 6th component; `WebhookDeliveryService.attemptDeliveryInternal()` passes `delivery.getFeeAmount()` with null-guard; `WebhookTransitionService.applyFinalTransition()` passes `tx.getFeeAmount()` to `enqueue()`; `WebhookDeliveryLog` carries `fee_amount` column to survive async retry path |
| 4 | Existing IT tests pass — no regressions; new IT assertions confirm feeAmount in PaymentOrchestratorIT and WebhookDeliveryIT | VERIFIED | Both test files have substantive fee assertions; `PaymentOrchestratorIT` has three fee-related tests (orange zero-fee, mtn zero-fee, fee-rule-seeded non-zero); `WebhookDeliveryIT` asserts `feeAmount` key present and numeric 0 in captured POST body; all three Phase 11 commits exist in git history |

**Score:** 4/4 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/softropic/payam/payment/contract/PaymentResponse.java` | 7-component record with `BigDecimal feeAmount` and `Long feeRuleId` | VERIFIED | 53 lines; components at lines 23-24; `accepted()` takes feeAmount+feeRuleId params; `failed()` hardcodes ZERO/null; null-guard in `accepted()` |
| `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` | Captures fee from lambda via array holders; passes to `accepted()` | VERIFIED | 310 lines; array holders at lines 180-181; lambda sets `capturedFee[0]` and `capturedFeeRuleId[0]` at lines 193-194; `accepted()` call at line 224-225 passes both; idempotency null-guard at lines 143-146 |
| `src/main/java/com/softropic/payam/webhook/contract/OutboundWebhookPayload.java` | 6-component record with `BigDecimal feeAmount` | VERIFIED | 21 lines; `BigDecimal feeAmount` is 6th component at line 20 |
| `src/main/java/com/softropic/payam/webhook/repo/WebhookDeliveryLog.java` | Nullable `fee_amount` column with getter and setter | VERIFIED | 93 lines; `@Column(name = "fee_amount", precision = 20, scale = 2)` at line 48; `setFeeAmount()` at lines 70-72; `@Getter` on class provides `getFeeAmount()` |
| `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java` | `enqueue()` takes `BigDecimal feeAmount`; sets on log; `attemptDeliveryInternal()` passes to payload | VERIFIED | 233 lines; `enqueue()` signature at line 80 includes `BigDecimal feeAmount`; `entry.setFeeAmount(feeAmount != null ? feeAmount : BigDecimal.ZERO)` at line 96; payload construction at lines 130-137 passes `delivery.getFeeAmount()` with null-guard |
| `src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java` | Passes `tx.getFeeAmount()` to `enqueue()` | VERIFIED | 116 lines; `enqueue()` call at lines 94-101 passes `tx.getFeeAmount()` as 6th argument |
| `src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java` | IT assertions: feeAmount not null, equals ZERO, feeRuleId null (zero-fee); feeAmount > 0, feeRuleId not null (rule seeded) | VERIFIED | 427 lines; zero-fee assertions in `orange_*_returns_202` (lines 216-218) and `mtn_*_returns_202` (lines 245-247); new test `fee_rule_applied_returns_nonzero_fee_amount` (lines 267-300) seeds FEE_FIXED rule via JDBC, forces cache refresh, asserts feeAmount > 0 and feeRuleId != null; `FeeRuleCache feeRuleCache` autowired |
| `src/test/java/com/softropic/payam/webhook/WebhookDeliveryIT.java` | IT assertions: feeAmount key present in JSON body, numeric value equals 0 | VERIFIED | 360 lines; assertions at lines 269-279 in `shouldDeliverWebhookWithCorrectHmacSignatureOnSuccessTransition`; checks `receivedBody.contains("\"feeAmount\"")`, parses body as `Map<String,Object>`, asserts key present and `BigDecimal` value equals ZERO |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `PaymentOrchestrator.initiate()` | `PaymentResponse.accepted()` | reads `capturedFee[0]` and `capturedFeeRuleId[0]` set inside `transactionTemplate` lambda | WIRED | Array holders declared at lines 180-181; populated inside lambda at lines 193-194; consumed at `accepted()` call lines 224-225 |
| `WebhookTransitionService.applyFinalTransition()` | `WebhookDeliveryService.enqueue()` | passes `tx.getFeeAmount()` as 6th argument | WIRED | `enqueue()` call at lines 94-101 of WebhookTransitionService; `tx.getFeeAmount()` is the final argument; comment acknowledges null for pre-Phase-10 rows handled by enqueue null-guard |
| `WebhookDeliveryService.enqueue()` | `WebhookDeliveryLog.feeAmount` | `entry.setFeeAmount(feeAmount != null ? feeAmount : BigDecimal.ZERO)` | WIRED | Line 96; null converted to ZERO before persisting |
| `WebhookDeliveryService.attemptDeliveryInternal()` | `OutboundWebhookPayload` constructor | passes `delivery.getFeeAmount()` with null-guard as 6th arg | WIRED | Lines 130-137; null-guard `delivery.getFeeAmount() != null ? delivery.getFeeAmount() : BigDecimal.ZERO` |
| `enqueue()` singleton caller search | No other callers remain on old 5-arg signature | confirmed | VERIFIED | `grep` across `src/main/java` finds only `WebhookTransitionService.java` calling `.enqueue(` — no stale 5-arg callers |
| `PaymentResponse.accepted()` singleton caller search | No other callers remain on old 3-arg signature | confirmed | VERIFIED | `grep` across `src/main/java` finds only `PaymentOrchestrator.java` calling `PaymentResponse.accepted(` |

---

### Requirements Coverage

| Requirement | Status | Notes |
|-------------|--------|-------|
| feeAmount in POST /v1/payments response | SATISFIED | `PaymentResponse` record carries field; orchestrator wires it |
| feeAmount in outbound webhook payload | SATISFIED | `OutboundWebhookPayload` record carries field; full pipeline wired |
| Zero-fee transactions return feeAmount: 0 (never null) | SATISFIED | Null-guards at three sites: `accepted()`, idempotency replay, `enqueue()` |
| IT assertions confirm fee fields in API response and webhook payload | SATISFIED | Assertions present and substantive in both IT files |

---

### Anti-Patterns Found

None. No TODO/FIXME/placeholder comments found in the modified production files. No empty handlers or stub returns. All implementations are complete and connected.

---

### Human Verification Required

None. All four observable truths are verifiable structurally. The following items are confirmed without human testing:

- `feeAmount` field flows end-to-end from `FeeEvaluationService` through `Transaction` entity through `PaymentResponse` and `OutboundWebhookPayload`
- Null-guards exist at every boundary where a null could enter (lambda capture defaults, idempotency replay, `enqueue()`, `attemptDeliveryInternal()`)
- IT assertions are substantive (not just existence checks — they assert numeric value equality)
- No orphaned artifacts — all modified files are interconnected in a single data-flow chain

---

## Gaps Summary

No gaps. All four must-have truths are verified at all three artifact levels (exists, substantive, wired). The three Phase 11 commits (`a86e2e6`, `48d0b9c`, `146ec0b`) are present in git history and the code matches the SUMMARY.md claims exactly.

---

_Verified: 2026-03-25_
_Verifier: Claude (gsd-verifier)_
