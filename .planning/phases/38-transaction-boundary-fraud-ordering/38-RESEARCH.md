# Phase 38: Transaction Boundary & Fraud Ordering — Research

**Researched:** 2026-04-14
**Domain:** PaymentOrchestrator transaction scoping, fee evaluation ordering, fraud velocity token lifecycle
**Confidence:** HIGH — findings based entirely on reading the actual source files

---

## Summary

Phase 38 fixes two sequencing bugs in `PaymentOrchestrator.initiate()` and the associated fraud/fee path. Both bugs cause an operation with side effects to execute at the wrong point in the flow, producing either unnecessary DB lock contention (TXN-01) or incorrectly consumed rate-limit tokens on cache-write failure (OPS-02).

**TXN-01 — Fee evaluation inside the lock:** Fee computation (`feeEvaluationService.evaluateFee()`) currently executes inside a `transactionTemplate.execute()` block that opens a `SELECT ... FOR UPDATE` row lock. Fee evaluation reads from an in-memory cache (`FeeRuleCache`) — it performs zero I/O — yet it holds an open DB lock while doing so. The fix is to call `evaluateFee()` before the `transactionTemplate` block, capturing the result, and then only write the pre-computed value inside the locked section.

**OPS-02 — Fraud velocity consumed before idempotency cache write:** The current flow evaluates fraud (Step 4.5, including token consumption via `VelocityCheckService.checkVelocity()`) before the idempotency result is stored (Step 7, `idempotencyService.store()`). If `store()` fails — either the Postgres upsert or the Redis set throws — the velocity token has already been consumed from the Redis Bucket4j bucket, permanently decrementing the tenant/MSISDN/IP rate-limit slot. The correct ordering is: run `fraudScoringService.evaluate()` for the blocking decision only, defer token consumption, or restructure so token consumption occurs after the successful idempotency cache write. In practice this means moving the fraud evaluation call to after `idempotencyService.store()` succeeds, or splitting evaluate (score only, no token consumption) from consume (token deduction).

**Primary recommendation:** For TXN-01, hoist `feeEvaluationService.evaluateFee()` and `feeEvaluationService.findRuleForTenant()` to execute before the `transactionTemplate` block (both are pure in-memory operations). For OPS-02, move the fraud `evaluate()` call to after `idempotencyService.store()` completes — or restructure `FraudScoringService.evaluate()` to separate score-computation from token-consumption so the block decision can be made early but token consumption is deferred until the payment is confirmed cached.

---

## Current State Analysis

### PaymentOrchestrator.initiate() — Annotated Flow

```
Line 126: resolve MSISDN (pure computation)
Line 135: idempotencyService.checkAndReserve()   ← opens its own @Transactional, returns
Line 164: transactionService.initiate()           ← @Transactional, commits INITIATED row
Line 168: build PaymentCommand
Line 185: fraudScoringService.evaluate(cmd)       ← BUG OPS-02: consumes velocity tokens HERE
              └─ VelocityCheckService.checkVelocity x4  ← Bucket4j tryConsume(1) on Redis
Line 201: if blocked → applyFailed, return
Line 202: BigDecimal[] capturedFee / Long[] capturedFeeRuleId  (closure vars)
Line 207: transactionTemplate.execute() {         ← opens SELECT...FOR UPDATE lock
    Line 207:   findByTransactionIdForUpdate()
    Line 211:   feeEvaluationService.evaluateFee()  ← BUG TXN-01: pure cache read inside lock
    Line 213:   feeEvaluationService.findRuleForTenant()  ← BUG TXN-01: same, cache read in lock
    Line 215:   locked.setFeeAmount(fee)
}                                                 ← lock released on commit
Line 222: resolvePort()
Line 226: port.initiateMerchantPayment(cmd)       ← external HTTP call, no DB lock
Line 237: transactionTemplate.execute() {         ← state transitions AUTH_PENDING→AUTHORIZED→PROCESSING
}
Line 265: idempotencyService.store()              ← stores result in Postgres+Redis
```

### TXN-01 Root Cause — Fee Evaluation Inside Lock

`feeEvaluationService.evaluateFee(tenantId, request.amount())` is called at **line 211**, inside a `transactionTemplate.execute()` block that starts at line 207 with `findByTransactionIdForUpdate()`. This opens a Postgres `SELECT ... FOR UPDATE` row lock.

`FeeEvaluationService.evaluateFee()` delegates entirely to `FeeRuleCache.findForTenant()`, which reads from a `volatile List<FeeRule>` field. There is no I/O, no network call, no secondary DB query. The lock is held for the duration of this computation for no reason.

Same applies to `feeEvaluationService.findRuleForTenant()` at line 213 — also a pure in-memory cache read.

**What the lock should cover:** only the three write operations: `locked.setRiskScore()`, `locked.setDeviceFingerprint()`, `locked.setFeeAmount()`, `locked.setFeeRuleId()`.

### OPS-02 Root Cause — Velocity Token Consumed Too Early

`fraudScoringService.evaluate(cmd)` is called at line 185, which internally calls `velocityCheckService.checkVelocity()` four times. Each invocation calls `proxyManager.builder().build(key, () -> config).tryConsume(1)` on Bucket4j's Redis-backed `ProxyManager`. `tryConsume(1)` is destructive — it decrements the bucket counter in Redis immediately and permanently.

This fraud evaluation with token consumption happens **before** `idempotencyService.store()` at line 267.

If `idempotencyService.store()` fails (either the Postgres UPSERT or the Redis set throws), the velocity tokens are already gone. The next legitimate request from the same IP/MSISDN/tenant will find the bucket partially drained. If the velocity threshold is low (e.g., set to 1 for testing), this could block the retry of a payment that was never successfully cached.

The requirement is: "Fraud velocity token consumption occurs only after the idempotency result is successfully cached — a cache write failure does not consume a rate-limit slot."

---

## Problem Diagnosis

### TXN-01 Diagnosis

**File:** `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java`
**Lines:** 207–218 (the `transactionTemplate.execute()` block for risk/fee persistence)

Current code:
```java
transactionTemplate.execute(status -> {
    Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId()).orElseThrow();
    locked.setRiskScore(fraud.riskScore());
    locked.setDeviceFingerprint(cmd.deviceFingerprint());
    // Fee evaluation — after idempotency check, before provider dispatch (Pitfall 2)
    BigDecimal fee = feeEvaluationService.evaluateFee(tenantId, request.amount());  // ← BUG
    locked.setFeeAmount(fee);
    feeEvaluationService.findRuleForTenant(tenantId)                                // ← BUG
            .ifPresent(r -> locked.setFeeRuleId(r.getId()));
    capturedFee[0] = fee;
    capturedFeeRuleId[0] = locked.getFeeRuleId();
    return null;
});
```

**Problem:** `evaluateFee()` and `findRuleForTenant()` are pure in-memory cache reads. They hold the DB row lock for their duration unnecessarily. Under any load where fee rule computation takes non-trivial time (e.g., future complex rules), this extends lock hold time for no benefit.

**Fix:**
```java
// Compute fee BEFORE opening the lock — pure cache operation, no I/O
BigDecimal fee = feeEvaluationService.evaluateFee(tenantId, request.amount());
Optional<Long> feeRuleId = feeEvaluationService.findRuleForTenant(tenantId).map(FeeRule::getId);

transactionTemplate.execute(status -> {
    Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId()).orElseThrow();
    locked.setRiskScore(fraud.riskScore());
    locked.setDeviceFingerprint(cmd.deviceFingerprint());
    locked.setFeeAmount(fee);
    feeRuleId.ifPresent(locked::setFeeRuleId);
    capturedFee[0] = fee;
    capturedFeeRuleId[0] = locked.getFeeRuleId();
    return null;
});
```

Note: `capturedFee` and `capturedFeeRuleId` closure arrays are still needed since they are used in `PaymentResponse.accepted()` at line 266. They can be set from the pre-computed values before entering the block.

### OPS-02 Diagnosis

**File:** `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java`
**Line 185:** `FraudDecision fraud = fraudScoringService.evaluate(cmd);`

The `evaluate()` method performs token consumption (via `tryConsume(1)`) as a side effect of computing the block/allow decision. Token consumption is embedded inside `VelocityCheckService.checkVelocity()`, which is called unconditionally for all four signals (IP, MSISDN, APP, MSISDN_HOUSEHOLD) every time `evaluate()` is called.

`idempotencyService.store()` runs at line 267, after provider dispatch and state transitions. If it throws, tokens have been consumed 82 lines earlier.

**Two possible approaches:**

**Approach A — Move fraud evaluate to after store() (minimal code change):**
Move the entire `fraudScoringService.evaluate(cmd)` call and its blocked-branch to after `idempotencyService.store()` succeeds. This means the provider call already happened when fraud fires — not suitable, as the requirement says "before provider call" for fraud blocking.

**Approach B — Split evaluate into score-only pass + token-consume on store success (correct):**
The requirement is that velocity token consumption occurs *after* the idempotency result is cached. This means:
1. Call `fraudScoringService.evaluate()` before provider dispatch (current position, for blocking).
2. After `idempotencyService.store()` succeeds, the token was consumed as part of step 1 above, which means we need a different approach.

Re-reading the requirement: "Fraud velocity token consumption occurs only after the idempotency result is successfully cached — a cache write failure does not consume a rate-limit slot."

The cleanest solution is to move `fraudScoringService.evaluate()` to **after** `idempotencyService.store()` — but this would mean a fraudulent request reaches the provider. That is not acceptable.

The correct design is:
1. **Score-only fraud evaluation** (no token consumption) before the provider call — to make the block/allow decision without side effects.
2. **Token consumption** after `idempotencyService.store()` succeeds — only deduct the rate-limit slot if the payment was successfully cached.

This requires splitting `VelocityCheckService.checkVelocity()` into two operations:
- `peekVelocity(signal, identifier)` — checks if tokens are available without consuming (`availableTokens > 0` or a read-only probe)
- `consumeVelocity(signal, identifier)` — deducts one token (current `tryConsume(1)`)

OR: use Bucket4j's `tryConsumeAndReturnRemaining()` to probe without consuming, then call `tryConsume()` separately after store.

Bucket4j `ProxyManager` API provides `tryConsumeAndReturnRemaining(1)` which returns a `ConsumptionProbe` — this is a consuming operation. For a non-consuming probe, Bucket4j provides `estimateAbilityToConsume(1)` which does NOT consume a token.

**Recommended design for OPS-02:**

In `VelocityCheckService`:
- Add `boolean probeVelocity(FraudSignal, String)` — calls `estimateAbilityToConsume(1)`, returns true if allowed, false if would be blocked. No token deducted.
- Keep `boolean checkVelocity(FraudSignal, String)` — calls `tryConsume(1)`, consuming a token.

In `FraudScoringService`:
- Add `FraudDecision probe(PaymentCommand)` — calls `probeVelocity` for all signals, computes score. No side effects.
- Keep `FraudDecision evaluate(PaymentCommand)` — calls `checkVelocity` for all signals, consumes tokens.

In `PaymentOrchestrator.initiate()`:
- Replace `fraudScoringService.evaluate(cmd)` at line 185 with `fraudScoringService.probe(cmd)` — scores without consuming.
- After `idempotencyService.store()` succeeds at line 267, call `fraudScoringService.consume(cmd)` — actually deducts the tokens.

**Alternative simpler design (acceptable if Bucket4j supports it):**
Use `estimateAbilityToConsume(1)` in the probe path, then `tryConsume(1)` in the consume path. The existing `evaluate()` method can stay; add `probe()` that uses the non-consuming API.

---

## Required Changes

### Change 1: VelocityCheckService — add probeVelocity method

**File:** `src/main/java/com/softropic/payam/fraud/service/VelocityCheckService.java`

Add method:
```java
/**
 * Probe velocity for the given signal and identifier WITHOUT consuming a token.
 * Returns true if a token would be available (request would be allowed).
 * Uses Bucket4j estimateAbilityToConsume which is read-only (no side effect).
 */
public boolean probeVelocity(FraudSignal signal, String identifier) {
    return fraudRuleCache.findBySignalName(signal.getSignalName())
            .map(rule -> {
                BucketConfiguration config = BucketConfiguration.builder()
                        .addLimit(io.github.bucket4j.Bandwidth.builder()
                                .capacity(rule.getThreshold())
                                .refillIntervally(rule.getThreshold(), Duration.ofSeconds(rule.getWindowSeconds()))
                                .build())
                        .build();
                byte[] key = ("fraud:velocity:" + signal.getSignalName() + ":" + identifier)
                        .getBytes(StandardCharsets.UTF_8);
                var probe = proxyManager.builder().build(key, () -> config).estimateAbilityToConsume(1);
                return probe.canBeConsumed();
            })
            .orElse(true); // fail-open
}
```

Note: Must verify Bucket4j distributed bucket API has `estimateAbilityToConsume` available on the `Bucket` interface returned by `ProxyManager`. If not, alternative: use `tryConsumeAndReturnRemaining(0)` to check without consuming (consuming 0 tokens is a valid no-op probe in Bucket4j).

### Change 2: FraudScoringService — add probe() and consume() methods

**File:** `src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java`

Add:
- `FraudDecision probe(PaymentCommand cmd)` — identical logic to `evaluate()` but calls `probeVelocity` instead of `checkVelocity`. Returns block/allow decision with risk score, no side effects.
- `void consumeTokens(PaymentCommand cmd)` — calls `checkVelocity` for all four signals, discarding the boolean results (already decided by probe). Used only for the non-blocked, successfully-cached path.

OR simplify: rename current `evaluate()` to `consume()` and implement `evaluate()` as the probe-only version.

Keep current `evaluate()` signature for backward compatibility with `FraudEngineIT` and `FraudScoringServiceIT`.

### Change 3: PaymentOrchestrator — fee eval before lock, fraud consume after store

**File:** `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java`

**TXN-01 fix** (lines 202–218):
```java
// TXN-01: compute fee BEFORE acquiring the DB row lock — pure in-memory cache operation
BigDecimal fee = feeEvaluationService.evaluateFee(tenantId, request.amount());
Long feeRuleIdVal = feeEvaluationService.findRuleForTenant(tenantId)
        .map(r -> r.getId())
        .orElse(null);

// Capture for use in PaymentResponse closure
BigDecimal[] capturedFee = {fee};
Long[] capturedFeeRuleId = {feeRuleIdVal};

// Persist risk score, device fingerprint, and pre-computed fee (lock covers writes only)
transactionTemplate.execute(status -> {
    Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId()).orElseThrow();
    locked.setRiskScore(fraud.riskScore());
    locked.setDeviceFingerprint(cmd.deviceFingerprint());
    locked.setFeeAmount(fee);
    if (feeRuleIdVal != null) locked.setFeeRuleId(feeRuleIdVal);
    return null;
});
```

**OPS-02 fix** — Replace `fraudScoringService.evaluate(cmd)` at line 185 with probe, then consume after store:

```java
// Step 4.5: Fraud probe — scores without consuming tokens (OPS-02: no token side effects yet)
FraudDecision fraud = fraudScoringService.probe(cmd);
if (fraud.blocked()) {
    // ... existing blocked-branch unchanged ...
}

// ... rest of flow unchanged until after idempotencyService.store() ...

PaymentResponse response = PaymentResponse.accepted(...);
idempotencyService.store(tenantId, request.idempotencyKey(), 202, JsonUtil.toJson(response));

// OPS-02: consume velocity tokens only after idempotency result is successfully cached
fraudScoringService.consumeTokens(cmd);

metricsService.recordSuccess(provider.name());
```

Important: `consumeTokens()` must only be called on the success path. It should NOT be called in any of the catch blocks (PROVIDER_UNAVAILABLE, SUBSCRIBER_INACTIVE, HttpClientException, Exception).

---

## Risk Assessment

### Risk 1: Bucket4j estimateAbilityToConsume API availability

**Risk:** The distributed Bucket4j `ProxyManager`-backed bucket may not expose `estimateAbilityToConsume` in the version used by this project. Need to verify the exact Bucket4j version and API surface.

**Mitigation:** Check `pom.xml` for `bucket4j-redis` / `bucket4j-core` version. The `estimateAbilityToConsume` method is part of the `Bucket` interface in Bucket4j 8.x. If the version predates it, use `tryConsumeAndReturnRemaining(0)` (consuming 0 tokens) as a probe, which is supported in all Bucket4j 7+ versions.

**Confidence:** MEDIUM — needs pom.xml version check.

### Risk 2: FraudEngineIT velocity block test relies on token consumption in evaluate()

**Risk:** `FraudEngineIT.velocityBlockReturns422()` currently sends Request 1 (allowed, tokens consumed) then Request 2 (blocked). If `initiate()` now calls `probe()` instead of `evaluate()`, Request 1 will not consume tokens, and Request 2 will also be allowed — test breaks.

**Mitigation:** The test flow must match the production flow: Request 1 succeeds → tokens consumed via `consumeTokens()` after `store()` → Request 2 probes → finds bucket exhausted → blocked. This requires `consumeTokens()` to actually be called in the test path. Since the test goes through the full HTTP stack, it will call `consumeTokens()` on Request 1's success path. The test should still pass as long as `consumeTokens()` runs before the test's `mtnServer.resetRequests()` call. This should work correctly.

### Risk 3: consumeTokens on exception paths

**Risk:** If `consumeTokens()` is placed after `idempotencyService.store()` but before the catch blocks, an exception during `consumeTokens()` itself could prevent `metricsService.recordSuccess()` from running.

**Mitigation:** Wrap `consumeTokens()` in a try/catch that logs and continues, similar to how Redis failures are tolerated in `IdempotencyService.store()`. Rate-limit accounting is best-effort; it should not break payment success responses.

### Risk 4: Fee evaluation result changes between probe and lock

**Risk:** `FeeRuleCache` uses a `volatile` list that can be hot-refreshed. If a fee rule is updated between the pre-lock `evaluateFee()` call and the `transactionTemplate` block, the transaction will record the value computed at probe time, not at write time.

**Assessment:** This is an acceptable trade-off documented in the existing codebase (the cache is already a best-effort snapshot). The window is milliseconds. The previous code had the same eventual-consistency property — it evaluated inside the lock but the lock only spans the JDBC write, not the cache refresh scheduler. This risk is NOT introduced by Phase 38.

### Risk 5: Existing tests

Tests that must pass and are directly affected:
- `PaymentOrchestratorIT` — 7 tests: orange flow, MTN flow, unknown MSISDN, fee rule, idempotency, auth, subscriber inactive, circuit breaker
- `FraudEngineIT` — 3 tests: velocityBlock, normalPaymentHasRiskScore, deviceFingerprintPersisted
- `FraudScoringServiceIT` — 3 tests: ipVelocityBlock, msisdnVelocityBlock, scoreComputedWithinRange

`FraudScoringServiceIT` calls `fraudScoringService.evaluate()` directly. If the new design renames or changes `evaluate()` signature, the IT must be updated to call `probe()` or the new method name.

---

## Architecture Patterns

### Current sequence (with bugs annotated)

```
initiate()
  ├─ resolve MSISDN
  ├─ idempotencyService.checkAndReserve()  [own TX]
  ├─ transactionService.initiate()         [own TX, commits]
  ├─ build PaymentCommand
  ├─ fraudScoringService.evaluate()        ← BUG OPS-02: consumes tokens here
  │    └─ checkVelocity x4               ← tryConsume(1) x4
  ├─ if blocked → return
  ├─ transactionTemplate {                 ← BUG TXN-01: lock held during fee eval
  │    findByTransactionIdForUpdate()
  │    setRiskScore, setDeviceFingerprint
  │    evaluateFee()                      ← pure cache, no I/O, inside lock
  │    findRuleForTenant()               ← pure cache, no I/O, inside lock
  │    setFeeAmount, setFeeRuleId
  │  }
  ├─ port.initiateMerchantPayment()       [HTTP, no DB lock]
  ├─ transactionTemplate { state transitions }
  └─ idempotencyService.store()           ← tokens already consumed if this fails
```

### Target sequence (Phase 38)

```
initiate()
  ├─ resolve MSISDN
  ├─ idempotencyService.checkAndReserve()  [own TX]
  ├─ transactionService.initiate()         [own TX, commits]
  ├─ build PaymentCommand
  ├─ evaluateFee()                         ← TXN-01 fix: compute before lock
  ├─ findRuleForTenant()                  ← TXN-01 fix: compute before lock
  ├─ fraudScoringService.probe()           ← OPS-02 fix: score without consuming tokens
  ├─ if blocked → return (no tokens consumed)
  ├─ transactionTemplate {                 ← lock covers writes ONLY
  │    findByTransactionIdForUpdate()
  │    setRiskScore, setDeviceFingerprint
  │    setFeeAmount (pre-computed)
  │    setFeeRuleId (pre-computed)
  │  }
  ├─ port.initiateMerchantPayment()        [HTTP, no DB lock]
  ├─ transactionTemplate { state transitions }
  ├─ idempotencyService.store()            ← write succeeds
  └─ fraudScoringService.consumeTokens()   ← OPS-02 fix: consume only after store
```

### Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Non-consuming bucket probe | Custom Redis LLEN/GET | Bucket4j `estimateAbilityToConsume(1)` or `tryConsumeAndReturnRemaining(0)` |

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + WireMock Spring (failsafe runner) |
| Config file | `pom.xml` (failsafe plugin, `**/*IT.java` pattern) |
| Quick run command | `mvn test -pl . -Dtest=PaymentOrchestratorIT,FraudEngineIT,FraudScoringServiceIT` |
| Full suite command | `mvn verify` |

### Phase Requirements — Test Map

| REQ-ID | Behavior | Test Type | File | Command |
|--------|----------|-----------|------|---------|
| TXN-01 | Fee computation completes before `SELECT...FOR UPDATE` is issued | Unit / IT | `PaymentOrchestratorIT` (existing) + new assertion | `mvn verify -Dit.test=PaymentOrchestratorIT` |
| TXN-01 | Existing fee_rule_applied test still returns nonzero fee | IT (regression) | `PaymentOrchestratorIT#fee_rule_applied_returns_nonzero_fee_amount` | existing test |
| OPS-02 | Velocity token not consumed when idempotency store fails | IT (new) | `FraudVelocityOrderingIT` (new) | `mvn verify -Dit.test=FraudVelocityOrderingIT` |
| OPS-02 | Velocity block still fires on second request after successful first | IT (regression) | `FraudEngineIT#velocityBlockReturns422` | existing test |

### New Test: FraudVelocityOrderingIT

This test is the critical proof for OPS-02. It must verify:

1. A payment attempt where `idempotencyService.store()` fails does NOT consume a velocity token.
2. A subsequent retry of the same payment from the same MSISDN/IP is allowed (token still available).

**Implementation approach:**
- Use a mock/spy of `IdempotencyService` to make `store()` throw.
- OR: use a real flow where we force a store failure (e.g., shut down Redis container after `checkAndReserve` succeeds).
- After the forced-failure payment, check that the velocity bucket is not depleted (a second request succeeds).

Alternative simpler approach: set MSISDN_VELOCITY threshold to 1, submit a payment where store() fails (via forced exception), then submit a normal payment to the same MSISDN — it must succeed (not be blocked), proving no token was consumed.

**Consideration:** Forcing `store()` to fail requires either:
- Mocking at the service level (Mockito spy in a `@SpyBean`)
- Closing the Redis connection (fragile)
- Injecting a test double that throws on store

The cleanest approach in this Spring Boot IT context is `@SpyBean IdempotencyService idempotencySpy` + `doThrow(new RuntimeException("forced")).when(idempotencySpy).store(...)`.

### Wave 0 Gaps

- [ ] `src/test/java/com/softropic/payam/fraud/FraudVelocityOrderingIT.java` — covers OPS-02 (new file)
- [ ] `FraudScoringServiceIT` may need updating if `evaluate()` signature/behavior changes

---

## Common Pitfalls

### Pitfall 1: consumeTokens on all paths including failure

**What goes wrong:** If `consumeTokens()` is called unconditionally after `idempotencyService.store()`, it will also run when store throws — defeating OPS-02.

**Prevention:** Place `consumeTokens()` only after a successful `store()` call, not in a finally block. The try/catch structure around the provider call must be checked: `store()` is called inside the `try` block at line 267. `consumeTokens()` must be placed immediately after `store()`, before the `metricsService.recordSuccess()` call. A `RuntimeException` from `consumeTokens()` itself should be caught and logged but not rethrown (fail-open, like Redis errors elsewhere in the codebase).

### Pitfall 2: Fraud blocked path still needs no token consumption

**What goes wrong:** If `probe()` is implemented as a separate method but shares internal token-consuming code paths with `evaluate()`, the blocked branch at line 186 (`if (fraud.blocked())`) runs before provider dispatch — no tokens should be consumed for a blocked request.

**Prevention:** Ensure `probe()` is entirely non-consuming. The blocked path returns immediately at line 198 — at that point no `consumeTokens()` call should have happened. This is correct by design as long as `probe()` uses `estimateAbilityToConsume` or equivalent.

### Pitfall 3: FeeRule import for pre-lock extraction

**What goes wrong:** `feeEvaluationService.findRuleForTenant()` returns `Optional<FeeRule>`, but `FeeRule` is in `com.softropic.payam.fee.repo`. The `PaymentOrchestrator` currently accesses the rule only via `.ifPresent(r -> locked.setFeeRuleId(r.getId()))` inside the lambda (no explicit FeeRule variable). Extracting this before the lambda requires either importing `FeeRule` or extracting only the `Long` ID.

**Prevention:** Extract `Optional<Long> feeRuleIdVal = feeEvaluationService.findRuleForTenant(tenantId).map(r -> r.getId())` — this avoids importing `FeeRule` directly in `PaymentOrchestrator`. Cleaner and sufficient.

---

## Code Examples

### Bucket4j non-consuming probe (verified from Bucket4j documentation concept)

```java
// estimateAbilityToConsume returns a ConsumptionProbe — does NOT deduct tokens
ConsumptionProbe probe = bucket.estimateAbilityToConsume(1);
if (probe.canBeConsumed()) {
    // would be allowed — no token deducted
}

// tryConsume — deducts 1 token
boolean consumed = bucket.tryConsume(1);
```

The ProxyManager-backed bucket works identically:
```java
var bucket = proxyManager.builder().build(key, () -> config);
// Non-consuming check:
boolean canConsume = bucket.estimateAbilityToConsume(1).canBeConsumed();
// Consuming:
boolean consumed = bucket.tryConsume(1);
```

### Fee extraction before lock (pattern for TXN-01)

```java
// Pre-lock: pure cache reads — no I/O, no lock
BigDecimal fee = feeEvaluationService.evaluateFee(tenantId, request.amount());
Long feeRuleIdVal = feeEvaluationService.findRuleForTenant(tenantId)
        .map(r -> r.getId())
        .orElse(null);

// Lock section: writes only
transactionTemplate.execute(status -> {
    Transaction locked = transactionRepository
            .findByTransactionIdForUpdate(tx.getTransactionId()).orElseThrow();
    locked.setRiskScore(fraud.riskScore());
    locked.setDeviceFingerprint(cmd.deviceFingerprint());
    locked.setFeeAmount(fee);
    if (feeRuleIdVal != null) locked.setFeeRuleId(feeRuleIdVal);
    capturedFee[0] = fee;
    capturedFeeRuleId[0] = feeRuleIdVal;
    return null;
});
```

---

## Open Questions

1. **Bucket4j version — does it expose `estimateAbilityToConsume` on the distributed Bucket interface?**
   - What we know: `VelocityCheckService` uses `LettuceBasedProxyManager` and `tryConsume(1)`. Bucket4j 8.x added `estimateAbilityToConsume` to the `AsyncBucket` and `Bucket` interfaces.
   - What's unclear: exact Bucket4j version in `pom.xml` (not read during this research session).
   - Recommendation: Before implementing, run `grep -r "bucket4j" pom.xml` to confirm version. If < 8.x, use `tryConsumeAndReturnRemaining(0)` probe alternative.

2. **Should `consumeTokens()` be called on provider-failure paths?**
   - What we know: The requirement says tokens consumed only after idempotency cache write. Provider failures (SUBSCRIBER_INACTIVE, HttpClientException) return before `store()` is called — no token should be consumed on those paths.
   - What's unclear: Is this intended? A legitimate request that fails at the provider (subscriber inactive) would not consume a velocity slot, allowing unlimited retries for that scenario.
   - Recommendation: Align with the literal requirement — tokens consumed only after successful `store()`. Provider failure paths do not call `store()` so they naturally get no token consumption. This is the correct behavior.

---

## Sources

### Primary (HIGH confidence)
- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` — full source read
- `src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java` — full source read
- `src/main/java/com/softropic/payam/fraud/service/VelocityCheckService.java` — full source read
- `src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java` — full source read
- `src/main/java/com/softropic/payam/fee/service/FeeEvaluationService.java` — full source read
- `src/main/java/com/softropic/payam/fee/service/FeeRuleCache.java` — full source read
- `src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java` — full source read
- `src/test/java/com/softropic/payam/fraud/FraudEngineIT.java` — full source read
- `src/test/java/com/softropic/payam/fraud/FraudScoringServiceIT.java` — full source read
- `.planning/REQUIREMENTS.md` — TXN-01, OPS-02 requirements
- `.planning/ROADMAP.md` — Phase 38 success criteria

### Secondary (MEDIUM confidence)
- Bucket4j API design knowledge (from training data, Bucket4j 8.x `estimateAbilityToConsume` — needs pom.xml version verification)

---

## Metadata

**Confidence breakdown:**
- Current state analysis: HIGH — read actual source files line-by-line
- Problem diagnosis: HIGH — bugs are clearly visible in the code at specific line numbers
- Required changes: HIGH — changes are minimal, targeted, and follow existing codebase patterns
- Risk assessment: MEDIUM — Bucket4j API version needs verification before implementation
- Test strategy: HIGH — existing IT infrastructure understood, new test approach is clear

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (stable codebase)

---

## RESEARCH COMPLETE
