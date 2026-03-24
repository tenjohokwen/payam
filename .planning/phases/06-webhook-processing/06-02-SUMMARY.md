---
phase: 06-webhook-processing
plan: 02
subsystem: payments
tags: [webhook, orange-money, mtn-momo, spring-events, transactional-event-listener, pessimistic-lock, circuit-breaker]

# Dependency graph
requires:
  - phase: 06-01
    provides: OrangeCallbackController, MtnMoMoPort.processCallback with dedup, Redis dedup foundation
  - phase: 05-payment-orchestration
    provides: PROCESSING state on transactions, PaymentOrchestrator pattern, circuit breaker config
  - phase: 03-orange-money-adapter
    provides: OrangeMoneyPort.getTransactionStatus, OrangeStatusMapper, OrangeTokenService
  - phase: 04-mtn-momo-adapter
    provides: MtnMoMoPort.getTransactionStatus, MtnStatusMapper
  - phase: 02-transaction-core
    provides: Transaction state machine, TransactionRepository.findByTransactionIdForUpdate, EventLogService
provides:
  - WebhookReceivedEvent record (internal Spring event bridging callback reception to double-check)
  - WebhookDoubleCheckHandler (@TransactionalEventListener AFTER_COMMIT — calls provider status API before any state transition)
  - WebhookTransitionService (separate @Service for @Transactional REQUIRES_NEW state transition — PESSIMISTIC_WRITE lock)
  - OrangeMoneyPort.processWebhook extended — looks up tx by payToken, publishes WebhookReceivedEvent in TransactionTemplate
  - MtnMoMoPort.processCallback extended — publishes WebhookReceivedEvent after dedup passes
  - TransactionRepository.findByPayToken(String) — Orange webhook correlation (Pitfall 3)
  - WebhookDoubleCheckIT — 3 integration tests (SUCCESS, still-PROCESSING, circuit-open)
affects:
  - future-phase: state transition coverage is now complete for webhook path; poller remains safety net for non-webhook cases

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@TransactionalEventListener(AFTER_COMMIT) pattern: event listener fires after commit synchronization — no active transaction at that point; separate @Service with REQUIRES_NEW required for DB writes"
    - "Spring AOP self-invocation bypass: @Transactional on a method called from within the same bean is ineffective — extract to separate @Service to enable proxy"
    - "Webhook double-check pattern: webhook is trigger only; never trust webhook payload alone; always re-check provider status API before state transition (P1.4)"
    - "TransactionTemplate wrapper for event publishing: publishEvent inside TransactionTemplate.execute() ensures AFTER_COMMIT listener fires — without it, no transaction commits and listener never fires"

key-files:
  created:
    - src/main/java/com/softropic/payam/webhook/contract/WebhookReceivedEvent.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDoubleCheckHandler.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java
    - src/test/java/com/softropic/payam/webhook/WebhookDoubleCheckIT.java
  modified:
    - src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java
    - src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java

key-decisions:
  - "WebhookTransitionService uses @Transactional(REQUIRES_NEW) — @TransactionalEventListener(AFTER_COMMIT) fires in afterCompletion phase where no transaction is active; REQUIRED propagation throws TransactionRequiredException; REQUIRES_NEW creates a fresh independent transaction"
  - "applyFinalTransition extracted to WebhookTransitionService (separate @Service) — calling @Transactional from within the same bean (self-invocation) bypasses Spring AOP proxy and has no effect"
  - "WebhookDoubleCheckHandler is NOT @Transactional — provider HTTP call (getTransactionStatus) must not hold DB connection open (P1.1); separate transactional service handles DB writes after HTTP call returns"
  - "resolveTarget uses OrangeStatusMapper/MtnStatusMapper — same mapper used by pollers ensures consistent status interpretation across webhook path and polling path"
  - "TransactionTemplate wrapper in OrangeMoneyPort.processWebhook and MtnMoMoPort.processCallback — publishEvent without an enclosing transaction never triggers AFTER_COMMIT listener"

patterns-established:
  - "Double-check service pattern: WebhookDoubleCheckHandler (no @Transactional, event listener) + WebhookTransitionService (@Transactional REQUIRES_NEW, DB writes) — clean separation of concerns"
  - "PESSIMISTIC_WRITE before applyTransition: always use findByTransactionIdForUpdate before calling tx.applyTransition() to prevent webhook+poller race (Pitfall 2)"
  - "Spring event propagation in non-transactional context: wrap publishEvent in TransactionTemplate.execute() when caller has no active transaction and AFTER_COMMIT listener is required"

# Metrics
duration: 12min
completed: 2026-03-24
---

# Phase 6 Plan 02: Webhook State Transition Summary

**@TransactionalEventListener double-check layer: webhook triggers provider status re-verification via PESSIMISTIC_WRITE before any Transaction state transition to SUCCESS or FAILED**

## Performance

- **Duration:** 12 min
- **Started:** 2026-03-24T09:24:40Z
- **Completed:** 2026-03-24T09:36:50Z
- **Tasks:** 2
- **Files modified:** 7 (4 created, 3 modified)

## Accomplishments
- WebhookReceivedEvent record and WebhookDoubleCheckHandler implement the P1.4 "never trust webhook alone" rule — each incoming webhook triggers a provider status API re-check before any state change
- WebhookTransitionService applies PESSIMISTIC_WRITE-locked state transitions in a fresh REQUIRES_NEW transaction (required because AFTER_COMMIT fires with no active transaction)
- Both OrangeMoneyPort.processWebhook and MtnMoMoPort.processCallback publish WebhookReceivedEvent inside TransactionTemplate so the @TransactionalEventListener fires correctly
- WebhookDoubleCheckIT: 3 tests covering SUCCESS transition, still-PROCESSING no-op, and circuit-open no-op; OrangeCallbackControllerIT 5 tests still passing

## Task Commits

Each task was committed atomically:

1. **Task 1: WebhookReceivedEvent + WebhookDoubleCheckHandler + findByPayToken** - `c99e2e3` (feat)
2. **Task 2: Wire event publishing + WebhookTransitionService + WebhookDoubleCheckIT** - `bbcdee9` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `src/main/java/com/softropic/payam/webhook/contract/WebhookReceivedEvent.java` — record with transactionId, provider, providerRef, traceId
- `src/main/java/com/softropic/payam/webhook/service/WebhookDoubleCheckHandler.java` — @TransactionalEventListener(AFTER_COMMIT); NOT @Transactional; catches CallNotPermittedException for circuit-open; delegates DB writes to WebhookTransitionService
- `src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java` — @Transactional(REQUIRES_NEW); findByTransactionIdForUpdate; applyTransition; eventLogService.append(PROVIDER_SUCCESS/PROVIDER_FAILED)
- `src/test/java/com/softropic/payam/webhook/WebhookDoubleCheckIT.java` — 3 IT tests with WireMock stubs for Orange status API
- `src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java` — added findByPayToken(String) (Pitfall 3: Orange webhook uses payToken not txnid)
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` — processWebhook extended with findByPayToken + publishEvent in TransactionTemplate; added ApplicationEventPublisher + TransactionTemplate to constructor
- `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` — processCallback extended with findByTransactionId + publishEvent in TransactionTemplate; added ApplicationEventPublisher + TransactionTemplate to constructor

## Decisions Made
- `@Transactional(REQUIRES_NEW)` on `WebhookTransitionService.applyFinalTransition` — the `@TransactionalEventListener(AFTER_COMMIT)` fires in the `afterCompletion` synchronization phase where no active transaction exists. `REQUIRED` propagation fails with `TransactionRequiredException`; `REQUIRES_NEW` creates a fresh independent transaction that is not bound to any synchronization chain.
- `applyFinalTransition` extracted to `WebhookTransitionService` (separate `@Service`) — when `WebhookDoubleCheckHandler` called a `@Transactional` method on itself, Spring AOP's CGLIB proxy was bypassed (self-invocation limitation). Extracting to a separate bean ensures the proxy is invoked and `@Transactional` is honoured.
- `TransactionTemplate.execute()` wrapper in `OrangeMoneyPort.processWebhook` and `MtnMoMoPort.processCallback` — `publishEvent()` outside any transaction never triggers `@TransactionalEventListener(AFTER_COMMIT)` because there is no transaction to observe committing. The wrapper provides the minimal transaction boundary.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Extracted applyFinalTransition to separate WebhookTransitionService**
- **Found during:** Task 2 (test failure: TransactionRequiredException in afterCompletion phase)
- **Issue:** Plan specified `applyFinalTransition` as a `protected @Transactional` method on `WebhookDoubleCheckHandler`. Two problems: (1) `@TransactionalEventListener(AFTER_COMMIT)` fires after commit with no active transaction — `@Transactional(REQUIRED)` cannot join a non-existent transaction; (2) calling `this.applyFinalTransition()` bypasses Spring AOP proxy, making `@Transactional` ineffective regardless.
- **Fix:** Created `WebhookTransitionService` as a separate `@Service` bean; used `@Transactional(REQUIRES_NEW)` to force a fresh transaction; `WebhookDoubleCheckHandler` injects and delegates to it.
- **Files modified:** `WebhookDoubleCheckHandler.java`, new `WebhookTransitionService.java`
- **Verification:** Test `shouldTransitionToSuccessOnOrangeWebhookWithSuccessStatus` passes; tx status is SUCCESS after callback
- **Committed in:** `bbcdee9` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 — implementation bug: @Transactional self-invocation bypass + wrong propagation for afterCompletion context)
**Impact on plan:** Fix was necessary for correctness — the plan's approach was architecturally sound in intent but required a standard Spring pattern adjustment. No scope creep; feature set is identical to plan spec.

## Issues Encountered
- `@TransactionalEventListener(AFTER_COMMIT)` fires in `afterCompletion` phase where no transaction is active. Spring's `@Transactional(REQUIRED)` silently fails to start a new transaction in this phase, resulting in `TransactionRequiredException` when JPA tries to execute a locked query. Solution: `REQUIRES_NEW` propagation creates an independent transaction.
- Spring AOP self-invocation bypass: `WebhookDoubleCheckHandler` calling `this.applyFinalTransition()` bypasses the CGLIB proxy even when the method is `@Transactional`. This is a well-known Spring limitation — solution is to inject a separate bean.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Webhook double-check layer complete for both Orange and MTN providers
- Phase 6 is fully complete: reception (06-01) + state transition (06-02) both done
- Phase 7 can build on the state machine completion coverage (SUCCESS/FAILED transitions now covered by both webhook path and polling path)
- No blockers

---
*Phase: 06-webhook-processing*
*Completed: 2026-03-24*
