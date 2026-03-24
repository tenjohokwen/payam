---
phase: 05
plan: 01
subsystem: payment-orchestration
tags: [payment, routing, msisdn, orchestrator, idempotency, circuit-breaker, rest]

dependency-graph:
  requires:
    - 03-orange-money-adapter  # OrangeMoneyPort, SubscriberInactiveException, CircuitBreaker(orange)
    - 04-mtn-momo-adapter      # MtnMoMoPort, MtnAccountInactiveException, CircuitBreaker(mtn)
    - 02-transaction-core      # TransactionService, IdempotencyService, EventLogService, TransactionRepository
    - 01-multi-tenant-foundation  # TenantPrincipal, API key auth, TenantSecurityConfig
  provides:
    - POST /v1/payments endpoint secured by API key
    - MsisdnRouter (MSISDN -> MobilePaymentProvider resolver)
    - PaymentOrchestrator (routing + dispatch + error normalization)
    - OrchestratorError (standardized error vocabulary)
    - PaymentRequest / PaymentResponse DTOs
  affects:
    - 05-02  # integration tests for the new endpoint
    - 06-webhook-processing  # webhook handler will query PROCESSING transactions created here
    - 07-reconciliation      # uses transactionId and providerRef from PaymentResponse

tech-stack:
  added: []
  patterns:
    - orchestrator-pattern: PaymentOrchestrator delegates to MobileMoneyPort; no circuit breaker on orchestrator itself
    - transactiontemplate-for-state-transitions: TransactionTemplate used outside @Transactional to commit state changes atomically
    - catch-ordering: CallNotPermittedException -> SubscriberInactiveException/MtnAccountInactiveException -> HttpClientException -> Exception

key-files:
  created:
    - src/main/java/com/softropic/payam/payment/contract/OrchestratorError.java
    - src/main/java/com/softropic/payam/payment/contract/PaymentRequest.java
    - src/main/java/com/softropic/payam/payment/contract/PaymentResponse.java
    - src/main/java/com/softropic/payam/payment/contract/exception/UnknownMsisdnPrefixException.java
    - src/main/java/com/softropic/payam/payment/service/MsisdnRouter.java
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/payment/api/PaymentResource.java
  modified: []

decisions:
  - id: no-transactional-on-initiate
    choice: "PaymentOrchestrator.initiate() has no @Transactional"
    why: "Holding a DB connection during outbound HTTP (Orange/MTN) exhausts the connection pool under load (P1.1/P8.1)"
  - id: transactiontemplate-for-state
    choice: "TransactionTemplate injected and used for INITIATED->AUTH_PENDING->AUTHORIZED->PROCESSING block"
    why: "Creates a new transaction scope without annotating the orchestrator method; avoids AOP proxy requirement"
  - id: catch-order-callnotpermitted-first
    choice: "CallNotPermittedException caught before HttpClientException before Exception"
    why: "CallNotPermittedException IS-A RuntimeException, not HttpClientException; wrong order silently swallows circuit-open as generic error"
  - id: circuit-breakers-on-ports-not-orchestrator
    choice: "@CircuitBreaker stays on OrangeMoneyPort.initiateMerchantPayment and MtnMoMoPort.initiateMerchantPayment"
    why: "Circuit breaker placement in Phases 3 and 4; orchestrator simply catches CallNotPermittedException"
  - id: payment-already-processing-returns-202
    choice: "PAYMENT_ALREADY_PROCESSING error code returns HTTP 202, not 4xx"
    why: "In-flight duplicate is semantically accepted — caller should retry after completion, not treat as failure"
  - id: three-applyTransition-calls
    choice: "INITIATED -> AUTH_PENDING -> AUTHORIZED -> PROCESSING requires three applyTransition() calls"
    why: "State machine guards: INITIATED->PROCESSING directly throws IllegalStateTransitionException"
  - id: msisdn-router-hardcoded
    choice: "MsisdnRouter uses hardcoded prefix rules (65X/69X = ORANGE, 6X = MTN)"
    why: "Config-driven prefix table is Phase 10 hardening concern; RESEARCH.md Pitfall 3 mitigation"
  - id: applyFailed-separate-transactiontemplate
    choice: "applyFailed() uses its own TransactionTemplate.execute() block"
    why: "Failure path must commit FAILED transition even when no outer transaction exists"

metrics:
  duration: "3 min"
  completed: "2026-03-24"
  tasks-total: 3
  tasks-completed: 3
---

# Phase 5 Plan 01: Payment Orchestration Core Summary

**One-liner:** POST /v1/payments with MSISDN prefix routing (65X/69X=Orange, 6X=MTN), idempotency replay, INITIATED->PROCESSING state machine via TransactionTemplate, and OrchestratorError normalization.

## What Was Built

Six new files create the `payment` module under `com.softropic.payam.payment`:

- **OrchestratorError** — enum with 5 error codes (PROVIDER_UNAVAILABLE, SUBSCRIBER_INACTIVE, PROVIDER_ERROR, UNKNOWN_MSISDN_PREFIX, PAYMENT_ALREADY_PROCESSING) implementing `ErrorCode`
- **PaymentRequest** — Java record with `@NotBlank`, `@NotNull @Positive`, `@Size(min=3,max=3)` validation annotations
- **PaymentResponse** — Java record with `accepted()` and `failed()` static factories
- **UnknownMsisdnPrefixException** — RuntimeException with `msisdn` field for routing failures
- **MsisdnRouter** — `@Service` resolving MSISDN to `MobilePaymentProvider`; strips +237/237 country code before prefix matching
- **PaymentOrchestrator** — `@Service` implementing the full payment dispatch sequence without `@Transactional`
- **PaymentResource** — `@RestController` for POST /v1/payments with correct HTTP status mapping

## Key Design Decisions

1. **No @Transactional on PaymentOrchestrator.initiate()** — holding a DB connection during outbound HTTP to Orange/MTN would exhaust the connection pool under load. TransactionTemplate used instead for discrete DB operations.

2. **Three state transitions required** — the state machine path is INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING. A single `applyTransition(PROCESSING)` from INITIATED throws `IllegalStateTransitionException`.

3. **Exception catch ordering** — `CallNotPermittedException` must be caught first because it IS-A `RuntimeException`; catching `Exception` or `HttpClientException` first would silently swallow circuit-open events as generic errors.

4. **Circuit breakers stay on the ports** — `@CircuitBreaker(name="orange")` and `@CircuitBreaker(name="mtn")` are on `OrangeMoneyPort.initiateMerchantPayment()` and `MtnMoMoPort.initiateMerchantPayment()` respectively (set in Phases 3 and 4). The orchestrator does not add its own `@CircuitBreaker`.

5. **PAYMENT_ALREADY_PROCESSING returns 202** — a duplicate in-flight request is semantically an accepted payment; returning 4xx would cause clients to retry unnecessarily.

## Task Results

| Task | Name | Commit | Result |
|------|------|--------|--------|
| 1 | OrchestratorError, PaymentRequest, PaymentResponse, MsisdnRouter | 06c8c61 | 5 files, compiles clean |
| 2 | PaymentOrchestrator and PaymentResource | ea97881 | 2 files, compiles clean |
| 3 | Verify poller-to-orchestrator integration (SC-4) | 07c6163 | No changes — verification passed |

## SC-4 Verification (Poller Integration)

Both `OrangeStatusPollerJob` and `MtnStatusPollerJob`:
- Are `@Component` beans extending `QuartzJobBean`
- Query `findByTxStatusAndProviderAndLastModifiedDateBefore(PROCESSING, provider, cutoff)`
- Have Quartz schedule config in `application.yaml` (interval-seconds: 300)

Because `PaymentOrchestrator` correctly sets `txStatus=PROCESSING` and `provider` on the Transaction row, both pollers will automatically pick up orchestrator-created transactions without any additional configuration.

## Deviations from Plan

None — plan executed exactly as written.

## Next Phase Readiness

Plan 05-02 (integration tests for POST /v1/payments) requires:
- WireMock stubs for Orange and MTN endpoints (already available from Phases 3 and 4 test infrastructure)
- Test tenant creation via TenantService (@BeforeEach)
- JWT secret seeded in main.sec for requests using TenantPrincipal (or use X-Api-Key path through TenantSecurityConfig)
- @AfterEach cleanup: payment_event_log -> transaction -> tenant (FK ordering)
- MTN IT tests: mtn.callback-ip-whitelist=empty in @TestPropertySource

No blockers for Plan 05-02.
