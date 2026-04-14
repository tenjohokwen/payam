# Phase 38 — Transaction Boundary & Fraud Ordering — Sign-Off

**Completed:** 2026-04-14
**Status:** FAILED — Phase 38 regression found; sign-off blocked

## Scope

- TXN-01: Fee evaluation hoisted out of SELECT...FOR UPDATE lock (Plan 01)
- OPS-02: Velocity token consumption deferred until after idempotencyService.store() (Plan 02)

## mvn verify result

```
BUILD FAILURE
Surefire: Tests run: 253, Failures: 1, Errors: 0, Skipped: 0
Failsafe: Did not run — surefire failed first
```

**Failure:** `VelocityCounterFloodTest.hundredThreads_sameIp_atMostThresholdProviderCalls`

```
java.lang.AssertionError:
[At least 95/100 requests must be FRAUD_BLOCKED (IP_VELOCITY threshold=5)]
Expecting actual:
  0L
to be greater than or equal to:
  95L
```

## Critical tests — results

- [x] PaymentOrchestratorIT — 8 tests, all passing (incl. new feeEvaluationHappensBeforeLock — Plan 01)
- [x] FraudVelocityOrderingIT — 1 test, passing (new OPS-02 proof — Plan 02)
- [x] FraudEngineIT — 3 tests, all passing (velocityBlockReturns422 regression — Plan 02)
- [x] FraudScoringServiceIT — 3 tests, all passing
- [ ] WebhookEnqueueListenerIT — NOT RUN (failsafe did not execute; surefire halted build)
- [ ] IdempotencyServiceIT — NOT RUN (failsafe did not execute; surefire halted build)

## Phase 38 Regression: VelocityCounterFloodTest

**Test class:** `com.softropic.payam.e2e.domain.VelocityCounterFloodTest` (CONC-03)

**Root cause:** Plan 02's probe/consume split replaced the consuming `fraudScoringService.evaluate()` call with `fraudScoringService.probe()` (non-consuming). Under concurrent flood load, 100 threads all call `probe()` simultaneously — each sees the bucket as non-empty because no tokens have been consumed yet. All 100 threads pass fraud screening and reach the provider, defeating the IP_VELOCITY velocity guard.

**Invariant conflict:**
- `VelocityCounterFloodTest` (CONC-03) requires: consuming velocity check at fraud gate → concurrent requests blocked
- `FraudVelocityOrderingIT` (OPS-02) requires: non-consuming probe → failed store doesn't consume token

Both invariants are proven by specific tests. The probe/consume design satisfies OPS-02 but violates CONC-03. These requirements are architecturally incompatible with a simple non-consuming probe pattern.

**Design options that would satisfy both:**
1. Use consuming `evaluate()` at fraud gate + add Bucket4j token refund on store failure (Bucket4j 8.x has no refund API — requires over-provisioning or custom bucket manipulation)
2. Use consuming `evaluate()` + update `FraudVelocityOrderingIT` to test idempotency-key-based replay protection (different OPS-02 semantics: retry with same key gets cached response, no double consume)
3. Use a "reserve then confirm" two-phase approach (no Bucket4j native support)
4. Accept that CONC-03 and OPS-02 as currently specified are mutually exclusive and narrow one requirement

**Action required (Plan 02 must be revised):** This is a Plan 38-02 architectural issue. The `FraudVelocityOrderingIT` test proves OPS-02, but the OPS-02 implementation breaks CONC-03. The design choice between (a) protecting against velocity under concurrency and (b) not consuming tokens on store failure must be made explicitly.

## Pre-existing issues (not Phase 38 regressions)

- `OrangeCallbackControllerIT` — 4 errors when run as part of the full suite (ApplicationContext fails due to `The connection attempt failed` on Flyway DB connection init). **Pre-existing Docker/Testcontainers resource contention flake.** Confirmed passing in isolation (`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`). Same infrastructure flake as `Surefire is going to kill self fork JVM` noted in Phase 37 sign-off.

## First mvn verify run (main branch — Plans 01 only, Plan 02 not yet merged)

The first `mvn verify` run was against the main branch which only had Plan 01 changes (Plan 02 code was in worktree `agent-a9bdbbd8` only). That run produced:
```
BUILD FAILURE
Surefire: Tests run: 202, Failures: 0, Errors: 4, Skipped: 0
```
The 4 errors were all `OrangeCallbackControllerIT` — confirmed as Docker resource contention flake.

## Second mvn verify run (worktree — Plans 01+02 changes)

Run from `/.claude/worktrees/agent-a9bdbbd8` which has both Plan 01 and Plan 02 changes:
```
BUILD FAILURE
Surefire: Tests run: 253, Failures: 1, Errors: 0, Skipped: 0
```
Single failure: `VelocityCounterFloodTest` — Phase 38 regression (see above).

## Sign-off

**BLOCKED.** TXN-01 (Plan 01) is verified green — `PaymentOrchestratorIT` passes with fee evaluation hoisted above the lock. OPS-02 (Plan 02) satisfies `FraudVelocityOrderingIT` but breaks `VelocityCounterFloodTest` (CONC-03). Phase 38 cannot be closed until the CONC-03 / OPS-02 invariant conflict is resolved in Plan 02. See "Design options" above for resolution paths.

## Self-Check

- File `.planning/phases/38-transaction-boundary-fraud-ordering/38-03-SUMMARY.md` exists: FOUND
- Contains "FAILED": YES
- Contains "TXN-01": YES
- Contains "OPS-02": YES
- Contains "PaymentOrchestratorIT": YES
- Contains "FraudVelocityOrderingIT": YES
- Contains root cause analysis: YES
