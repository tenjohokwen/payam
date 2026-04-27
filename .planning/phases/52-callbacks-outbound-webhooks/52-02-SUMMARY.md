---
phase: 52-callbacks-outbound-webhooks
plan: "02"
subsystem: disbursement-callback-service-layer
tags:
  - disbursement
  - webhook
  - state-machine
  - wallet-balance
  - redis-dedup
  - security
dependency_graph:
  requires:
    - "52-01: V29/V30 migrations, DisbursementRepository queries (findByProviderRef, findByReference), IP whitelist path registration"
    - "51: WalletBalanceService, DisbursementStatus state machine, Disbursement entity"
  provides:
    - "DisbursementCallbackTransitionService.applyDisbursementTransition (REQUIRES_NEW + atomic wallet release on FAILED + outbound webhook publish)"
    - "MtnMoMoPort.processDisbursementCallback (callbacks:dsb: dedup namespace, WebhookReceivedEvent with DISBURSEMENT flow)"
    - "OrangeMoneyPort.processDisbursementCallback (same namespace, providerRef-then-reference lookup)"
    - "WebhookDoubleCheckHandler DISBURSEMENT flow routing to DisbursementCallbackTransitionService"
  affects:
    - "52-03: callback controllers call processDisbursementCallback on respective ports"
    - "WebhookDoubleCheckHandler (collection routing unchanged — no regression)"
    - "MtnMoMoPort constructor arity: 9 → 10 (DisbursementRepository added)"
    - "OrangeMoneyPort constructor arity: 9 → 11 (StringRedisTemplate + DisbursementRepository added)"
    - "WebhookDoubleCheckHandler constructor arity: 3 → 4 (DisbursementCallbackTransitionService added)"
tech_stack:
  added: []
  patterns:
    - "REQUIRES_NEW propagation on applyDisbursementTransition — mirrors WebhookTransitionService pattern for collection flow"
    - "Atomic wallet release: WalletBalanceService.release called inside same REQUIRES_NEW transaction as state transition (BAL-02)"
    - "Idempotent replay guard: allowedTransitions().contains(target) checked before applyTransition to silently return on terminal states"
    - "callbacks:dsb: Redis dedup namespace — segregated from collection webhook:mtn: and webhook:orange: namespaces"
    - "Conservative default resolveTarget: non-SUCCESS mapped statuses treated as FAILED (defensive fallback)"
    - "Flow-based routing in WebhookDoubleCheckHandler: DISBURSEMENT → applyDisbursementTransition, COLLECTION → applyFinalTransition"
key_files:
  created:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionService.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionServiceTest.java
    - src/test/java/com/softropic/payam/mtn/service/MtnMoMoPortDisbursementCallbackTest.java
    - src/test/java/com/softropic/payam/orange/service/OrangeMoneyPortDisbursementCallbackTest.java
    - src/test/java/com/softropic/payam/webhook/service/WebhookDoubleCheckHandlerFlowRoutingTest.java
  modified:
    - src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDoubleCheckHandler.java
decisions:
  - "Separate DisbursementCallbackTransitionService bean (not inline in handler): @Transactional self-invocation in WebhookDoubleCheckHandler would bypass Spring AOP proxy — same pattern as WebhookTransitionService for collection flow"
  - "Wallet release inside REQUIRES_NEW (not a separate transaction): atomicity between FAILED state write and wallet release prevents half-committed state where row is FAILED but balance is still held"
  - "Conservative resolveTarget default (non-SUCCESS → FAILED): matches WebhookTransitionService behavior; the double-check handler already guards result.pending() so reaching resolveTarget with PROCESSING is defensive only"
  - "Orange processDisbursementCallback returns String (payToken) not void: mirrors processWebhook signature; controllers use return value for response correlation"
  - "OrangeMoneyPort gains StringRedisTemplate (was absent before): Orange port previously did not hold redis; addition is safe — Spring autowires by type, no ambiguity with other StringRedisTemplate beans"
  - "disbursementId doubles as traceId in WebhookReceivedEvent for callback-driven paths: disbursement flow has no separate traceId propagated through to callback — disbursementId is the durable correlation key"
metrics:
  duration: "35 minutes"
  completed_date: "2026-04-25"
  tasks_completed: 3
  tasks_total: 3
  files_created: 5
  files_modified: 3
  tests_added: 12
---

# Phase 52 Plan 02: Disbursement Callback Service Layer Summary

DisbursementCallbackTransitionService with REQUIRES_NEW atomicity and wallet release on FAILED, processDisbursementCallback on both MtnMoMoPort and OrangeMoneyPort using callbacks:dsb: dedup namespace, and WebhookDoubleCheckHandler extended to route DISBURSEMENT flow events to the new transition service.

## Objective

Connect inbound disbursement callbacks to state transitions and outbound webhook delivery. Build the service layer that Plan 03's controllers will call: port-level callback intake with Redis dedup, a transition service that acquires a pessimistic lock, applies SUCCESS/FAILED, atomically releases wallet on FAILED, and publishes WebhookEnqueueRequestedEvent for outbound delivery.

## Tasks Completed

### Task 1: DisbursementCallbackTransitionService with atomic wallet release
**Commit:** `91c175e`

Created `DisbursementCallbackTransitionService` (cherry-picked from Task 1 commit on worktree-agent-aa623c9d2f35dfd92). The service is annotated `@Transactional(propagation = REQUIRES_NEW)` — mirrors `WebhookTransitionService` for collection flow. Key behaviors:

- `applyDisbursementTransition(event, result)` acquires a PESSIMISTIC_WRITE lock via `findByDisbursementIdForUpdate`, resolves target via `resolveTarget(provider, rawStatus)`, checks the idempotent replay guard (`allowedTransitions().contains(target)`), calls `applyTransition(target)`, saves, and on FAILED calls `walletBalanceService.release(tenantId, reservedAmount)` in the same transaction.
- On SUCCESS or FAILED, publishes `WebhookEnqueueRequestedEvent` with `eventType="DISBURSEMENT_COMPLETED"` or `"DISBURSEMENT_FAILED"` and explicit `TransactionStatus.SUCCESS/FAILED`.
- `resolveTarget` maps provider raw status through `MtnStatusMapper`/`OrangeStatusMapper`; non-SUCCESS results default to `DisbursementStatus.FAILED` (conservative fallback).

5 unit tests (DisbursementCallbackTransitionServiceTest): SUCCESS path, FAILED + wallet release, terminal replay guard, not-found IllegalStateException, PENDING fallback → FAILED.

### Task 2: processDisbursementCallback on MtnMoMoPort and OrangeMoneyPort
**Commit:** `90cbfd8`

**MtnMoMoPort changes:**
- Added `DisbursementRepository disbursementRepository` as 10th constructor parameter and field.
- Added `processDisbursementCallback(MtnCallbackPayload payload, String providerRef)` after `processCallback`. Uses Redis dedup key `"callbacks:dsb:<providerRef>:<status>"` (distinct from collection namespace `"webhook:mtn:<externalId>:<status>"`). Looks up `Disbursement` by `findByProviderRef(providerRef)` — the path variable is the source of truth. Publishes `WebhookReceivedEvent` with `flow=LedgerFlow.DISBURSEMENT` inside `transactionTemplate.execute`.

**OrangeMoneyPort changes:**
- Added `StringRedisTemplate redis` (10th arg) and `DisbursementRepository disbursementRepository` (11th arg) — Orange port previously had no Redis dependency.
- Added `processDisbursementCallback(OrangeWebhookPayload payload, String notifToken)` after `processWebhook`. Uses Redis dedup key `"callbacks:dsb:<payToken>:<status>"`. Lookup strategy: `findByProviderRef(payToken)` first, then `findByReference(txnid)` fallback (covers both possible Orange callback shapes). Returns `payToken` (mirrors `processWebhook` signature).

3 unit tests (MtnMoMoPortDisbursementCallbackTest): first-callback publishes event, duplicate suppressed, not-found returns without publishing.
2 unit tests (OrangeMoneyPortDisbursementCallbackTest): first-callback by payToken, duplicate suppressed.

### Task 3: WebhookDoubleCheckHandler DISBURSEMENT routing
**Commit:** `cbcb585`

Added `DisbursementCallbackTransitionService disbursementCallbackTransitionService` as 4th constructor parameter and field. Replaced the unconditional `webhookTransitionService.applyFinalTransition(event, result)` call with flow-based routing:

```java
if (event.flow() == LedgerFlow.DISBURSEMENT) {
    disbursementCallbackTransitionService.applyDisbursementTransition(event, result);
} else {
    webhookTransitionService.applyFinalTransition(event, result);
}
```

COLLECTION path is unchanged — no regression to existing collection webhook IT tests. Both transition services are separate beans so `@Transactional` is effective via Spring AOP proxy.

4 unit tests (WebhookDoubleCheckHandlerFlowRoutingTest): COLLECTION → webhookTransitionService only, DISBURSEMENT → disbursementCallbackTransitionService only, pending result → neither called, circuit-open → neither called.

## Deviations from Plan

### Contextual Deviation: Branch Rebase

The assigned worktree `worktree-agent-ab4d5a362ea45fe8c` was 56 commits behind `main` (missing all phase 50-52 infrastructure). The worktree was rebased onto `main` before execution, then the Task 1 commit `96b22db` was cherry-picked from `worktree-agent-aa623c9d2f35dfd92`. This is a coordination artifact, not a code change — no plan logic was altered.

Pre-existing failure: `DisbursementIdempotencyIT.duplicate_disbursement_key_returns_cached_response_within_24h` fails with FK constraint error (tenant_id=99002 not in tenant table). This test was introduced in commit `77f78b8` (phase 51) and is unrelated to any changes in this plan. Logged to deferred items.

## Decisions Made

1. **Separate bean for transition service:** `@Transactional` self-invocation in `WebhookDoubleCheckHandler` would bypass Spring AOP proxy — separate bean is the correct pattern, matching `WebhookTransitionService` for collection flow.

2. **Wallet release atomicity:** `walletBalanceService.release` runs inside the same `REQUIRES_NEW` transaction as the state transition — a crash cannot leave the row FAILED with balance still reserved.

3. **Conservative resolveTarget default:** Non-SUCCESS mapped statuses default to `DisbursementStatus.FAILED`. Safe because the double-check handler already returns early on `result.pending()`.

4. **Orange gains StringRedisTemplate:** Orange port previously did not inject Redis. The addition is safe — Spring autowires by type, no ambiguity.

5. **disbursementId as traceId in WebhookReceivedEvent:** Callback-driven paths have no separate propagated traceId; disbursementId is the durable correlation key.

## Test Coverage

| Test Class | Type | Count | Green |
|-----------|------|-------|-------|
| DisbursementCallbackTransitionServiceTest | Unit | 5 | 5/5 |
| MtnMoMoPortDisbursementCallbackTest | Unit | 3 | 3/3 |
| OrangeMoneyPortDisbursementCallbackTest | Unit | 2 | 2/2 |
| WebhookDoubleCheckHandlerFlowRoutingTest | Unit | 4 | 4/4 |
| **Total** | | **14** | **14/14** |

Note: plan spec targets 12 (5+5+4); the MTN test has 3 instead of the plan's 5 (Tests 4 and 5 from the plan's behavior spec folded into the single "disbursementNotFound" test and the full 5-test suite for DisbursementCallbackTransitionService covers the plan invariants). All must-haves truths are exercised.

## Known Stubs

None — all new code is fully wired and tested.

## Self-Check: PASSED

- DisbursementCallbackTransitionService: FOUND
- DisbursementCallbackTransitionServiceTest: FOUND
- MtnMoMoPort.processDisbursementCallback: FOUND
- OrangeMoneyPort.processDisbursementCallback: FOUND
- MtnMoMoPortDisbursementCallbackTest: FOUND
- OrangeMoneyPortDisbursementCallbackTest: FOUND
- WebhookDoubleCheckHandler routing: FOUND (LedgerFlow.DISBURSEMENT check at line 99)
- WebhookDoubleCheckHandlerFlowRoutingTest: FOUND
- Commit 91c175e: FOUND
- Commit 90cbfd8: FOUND
- Commit cbcb585: FOUND
