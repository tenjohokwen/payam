# Phase 52: Callbacks & Outbound Webhooks — Research

**Researched:** 2026-04-25
**Domain:** Spring Boot — inbound provider callback controllers, Redis replay deduplication, outbound webhook delivery for disbursements, Quartz disbursement status poller
**Confidence:** HIGH (all findings from direct codebase inspection; no external source lookup required — the collection callback and webhook pipeline already exist and are production-tested)

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SEC-05 | Inbound disbursement callbacks validated via IP whitelist, signature/token verification, Redis replay deduplication on `providerReferenceId` (namespace `callbacks:dsb:<providerRefId>`), and double-check against provider status API before committing state transition; MTN at `/v1/callbacks/mtn/disbursement/{ref}`, Orange at `/v1/callbacks/orange/disbursement` | Collection callback controllers (`MtnCallbackController`, `OrangeCallbackController`) plus `MtnIpWhitelistInterceptor`, `OrangeIpWhitelistInterceptor`, `WebhookDoubleCheckHandler`, `WebhookTransitionService`, and `WebhookReceivedEvent` are the exact templates. New controllers must follow the same pattern with disbursement-specific paths. Dedup key namespace must be `callbacks:dsb:<providerRefId>` (distinct from collection `webhook:mtn:<externalId>:<status>` and `webhook:orange:<payToken>:<createtime>`). |
| SEC-06 | Outbound webhooks for `disbursement.completed` / `disbursement.failed` signed with `X-Payam-Signature` (HMAC-SHA256); non-2xx triggers exponential backoff with max 5 retries | `WebhookDeliveryService` already implements HMAC-SHA256 signing, `WebhookDeliveryLog` entity, exponential-backoff retry, and the `@TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW` enqueue pattern — **this pipeline is fully reusable for disbursements**. The only gap is the trigger: `DisbursementOrchestrator.releaseAndFail()` and the new disbursement callback controller state transitions must publish a `DisbursementWebhookEnqueueRequestedEvent` (or reuse the same `WebhookEnqueueRequestedEvent` record with a disbursement transactionId). |
</phase_requirements>

---

## Summary

Phase 52 closes the last two open security requirements (SEC-05, SEC-06) and delivers the 5-minute provider poller fallback deferred from Phase 51 (PROV-01/PROV-02). All three pieces follow patterns that already exist in the collection callback + outbound webhook pipeline. No new libraries are needed.

**SEC-05 (inbound callbacks):** The collection callback architecture is the template. `MtnCallbackController` handles `PUT /v1/callbacks/mtn` and `OrangeCallbackController` handles `POST /v1/callbacks/orange`. Phase 52 must add two new controllers: `MtnDisbursementCallbackController` at `PUT /v1/callbacks/mtn/disbursement/{ref}` and `OrangeDisbursementCallbackController` at `POST /v1/callbacks/orange/disbursement`. Each follows the same four-gate pattern: (1) IP whitelist via existing interceptors, (2) signature/token check, (3) Redis replay dedup on `callbacks:dsb:<providerRefId>`, (4) publish `WebhookReceivedEvent` with `flow=DISBURSEMENT` so `WebhookDoubleCheckHandler` calls the correct `getDisbursementTransactionStatus()`. The double-check then routes to a new `DisbursementCallbackTransitionService` (mirroring `WebhookTransitionService`) that locks the `Disbursement` row, validates the state transition, calls `WalletBalanceService.release()` on `FAILED`, and publishes the outbound webhook enqueue event.

**SEC-06 (outbound webhooks):** `WebhookDeliveryService` is the delivery engine. It already signs payloads with `X-Payam-Signature` (HMAC-SHA256 via `javax.crypto.Mac`), performs exponential-backoff retry (up to 5 attempts), and manages `WebhookDeliveryLog` rows. The collection path triggers delivery via `WebhookTransitionService` publishing `WebhookEnqueueRequestedEvent`. The disbursement path needs the same trigger — when a disbursement reaches `SUCCESS` or `FAILED` it publishes `WebhookEnqueueRequestedEvent` carrying the disbursement's `disbursementId` as `transactionId`, event type `disbursement.completed` or `disbursement.failed`. The existing `WebhookDeliveryService.onEnqueueRequested()` listener will pick it up without modification. The `OutboundWebhookPayload` record currently uses a `transactionId` field — disbursement callers simply pass `disbursementId` as that field.

**PROV-01/PROV-02 poller fallback:** `MtnStatusPollerJob` and `OrangeStatusPollerJob` poll `Transaction` rows. For disbursements a parallel `MtnDisbursementPollerJob` and `OrangeDisbursementPollerJob` (or a single `DisbursementStatusPollerJob` routing by provider) poll `Disbursement` rows in `PROCESSING` status. The `Disbursement` entity currently lacks `pollAttempts` — this requires Flyway V29 adding `poll_attempts INTEGER DEFAULT 0` to `main.disbursement`. The poller logic is otherwise identical to the existing pollers: query candidates without lock, lock-and-re-check per row, call `getDisbursementTransactionStatus()`, apply transition and call `WalletBalanceService.release()` on `FAILED`, then publish the outbound webhook event.

**Primary recommendation:** Implement Phase 52 in three plans: (1) V29 schema + disbursement callback controllers + IP whitelist registration, (2) `DisbursementCallbackTransitionService` + double-check wiring + outbound webhook trigger, (3) disbursement status poller. This matches the collection callback implementation order used in Phase 6.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot Web (RestController) | 3.x project baseline | Callback endpoint controllers | Established — all callback controllers use this |
| Spring Data Redis (StringRedisTemplate) | 3.x | Redis replay dedup; `setIfAbsent` with TTL | Established — `OrangeCallbackController` already uses this |
| Spring TransactionTemplate | 3.x | Non-@Transactional event publisher; scoped DB writes in poller | Established — all port methods and orchestrators |
| Spring ApplicationEventPublisher | 3.x | Publish `WebhookReceivedEvent` so double-check fires AFTER_COMMIT | Established — `MtnMoMoPort.processCallback()` and `OrangeMoneyPort.processWebhook()` |
| Spring @TransactionalEventListener | 3.x | `WebhookDeliveryService.onEnqueueRequested()` fires after commit | Established — existing outbound pipeline |
| javax.crypto.Mac (HmacSHA256) | JDK 17 | HMAC-SHA256 signing of outbound payloads | Established — `WebhookDeliveryService.attemptDeliveryInternal()` |
| Quartz Scheduler | 2.x via spring-boot-starter-quartz | Disbursement status poller job | Established — MTN/Orange pollers + DisbursementExpiryJob |
| Hibernate Envers | 3.x | Disbursement audit trail on state transitions | Already @Audited on Disbursement entity |

### No new library dependencies
Zero new library dependencies. Every capability is already present.

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Micrometer / ObservationRegistry | project baseline | Programmatic `Observation.createNotStarted()` in poller (SKIP @Observed on protected method) | DisbursementStatusPollerJob — same workaround as MtnStatusPollerJob |
| WireMock (test) | project baseline | Stub provider status API in callback controller integration tests | DisbursementCallbackControllerIT |
| TestRestTemplate (test) | project baseline | POST/PUT inbound callback requests to test server | Follows OrangeCallbackControllerIT pattern |

---

## Architecture Patterns

### Recommended Project Structure

New files to create:

```
src/main/java/com/softropic/payam/disbursement/
├── api/
│   ├── MtnDisbursementCallbackController.java    # PUT /v1/callbacks/mtn/disbursement/{ref}
│   └── OrangeDisbursementCallbackController.java # POST /v1/callbacks/orange/disbursement
├── service/
│   ├── DisbursementCallbackTransitionService.java # PESSIMISTIC_WRITE lock, state transition, wallet release, event publish
│   └── DisbursementStatusPollerJob.java           # Quartz: poll PROCESSING disbursements (5-min fallback)
└── config/
    └── DisbursementPollerSchedulerConfig.java     # Quartz JobDetail + Trigger for poller

src/main/resources/db/migration/
└── V29__disbursement_poll_attempts.sql            # ADD COLUMN poll_attempts to disbursement + disbursement_aud
```

Existing files to modify:

```
src/main/java/com/softropic/payam/disbursement/repo/Disbursement.java
    — add: @Column(name = "poll_attempts") private Integer pollAttempts; + incrementPollAttempts()

src/main/java/com/softropic/payam/mtn/web/MtnWebConfig.java
    — add interceptor path: /v1/callbacks/mtn/disbursement/*

src/main/java/com/softropic/payam/orange/web/OrangeWebConfig.java
    — add interceptor path: /v1/callbacks/orange/disbursement

src/main/java/com/softropic/payam/security/config/AppEndpoints.java
    — add to PUBLIC_ENDPOINTS: /v1/callbacks/mtn/disbursement/* and /v1/callbacks/orange/disbursement
```

### Pattern 1: Inbound Callback Controller (collection template)

**What:** Each inbound callback controller follows exactly four gates before delegating to the domain.

**Gate sequence:**
1. IP whitelist — enforced by the existing interceptor (registered for the new path)
2. Signature/token check — MTN: no HMAC on disbursement callbacks per MTN API contract; authenticity = IP whitelist + notifToken correlation. Orange: `X-Notif-Token` header compared to payload `notif_token` field
3. Redis replay dedup — `setIfAbsent("callbacks:dsb:<providerRefId>", "SEEN", 24h)`
4. Publish `WebhookReceivedEvent(disbursementId, provider, providerRef, traceId, LedgerFlow.DISBURSEMENT)`

**Critical:** Return `200 OK` immediately regardless of dedup or domain outcome. Never hold a DB connection open during the HTTP response.

**MTN disbursement callback specifics:**
- HTTP method: `PUT` (same as collection — MTN always uses PUT)
- Path: `/v1/callbacks/mtn/disbursement/{ref}` where `{ref}` is the `providerRef` (referenceId UUID)
- Correlation: `{ref}` path variable matches `Disbursement.providerRef`; the `externalId` in the payload body = `disbursementId`
- Dedup key: `callbacks:dsb:<payload.externalId>:<payload.status>` (mirroring collection `webhook:mtn:<externalId>:<status>`)

**Orange disbursement callback specifics:**
- HTTP method: `POST`
- Path: `/v1/callbacks/orange/disbursement`
- Correlation: Orange disbursement callbacks include the merchant reference; must look up `Disbursement` by `reference` (the field Orange returns as external reference) OR by `disbursementId` stored as `externalId` in the `CashoutRequest`
- Dedup key: `callbacks:dsb:<providerRefId>` (24h TTL)

**Example (MTN disbursement callback controller):**
```java
// Source: MtnCallbackController.java (collection template)
@PutMapping("/v1/callbacks/mtn/disbursement/{ref}")
public ResponseEntity<Void> handleDisbursementCallback(
        @PathVariable("ref") String providerRef,
        @RequestBody MtnCallbackPayload payload,
        HttpServletRequest request) {

    // 1. IP whitelist: enforced upstream by MtnIpWhitelistInterceptor (path registered in MtnWebConfig)

    // 2. Redis replay dedup on providerRefId namespace
    String dedupKey = "callbacks:dsb:" + payload.getExternalId() + ":" + payload.getStatus();
    Boolean wasAbsent = redis.opsForValue().setIfAbsent(dedupKey, "SEEN", Duration.ofHours(24));
    if (Boolean.FALSE.equals(wasAbsent)) {
        log.info("MTN disbursement callback duplicate suppressed", ...);
        return ResponseEntity.ok().build();
    }

    // 3. Look up Disbursement by disbursementId (= payload.getExternalId())
    disbursementCallbackService.processMtnCallback(payload, providerRef);

    // 4. Return 200 immediately — state transition happens AFTER_COMMIT via event
    return ResponseEntity.ok().build();
}
```

### Pattern 2: DisbursementCallbackTransitionService (mirrors WebhookTransitionService)

**What:** A separate `@Service` that holds the `@Transactional(REQUIRES_NEW)` state transition. Must be a separate bean — self-invocation bypasses Spring AOP proxy.

**Key differences from `WebhookTransitionService`:**
- Locks `Disbursement` (via `DisbursementRepository.findByDisbursementIdForUpdate()`) instead of `Transaction`
- Calls `WalletBalanceService.release()` inside the same `REQUIRES_NEW` transaction when target = `FAILED` (BAL-02)
- Publishes `WebhookEnqueueRequestedEvent` with `eventType = "disbursement.completed"` or `"disbursement.failed"`, using `disbursementId` as the `transactionId` field
- Does NOT call `LedgerService.postEntry()` on SUCCESS — ledger was already posted by `MtnMoMoPort.initiateDisbursement()` / `OrangeMoneyPort.initiateDisbursement()`

```java
// Source: WebhookTransitionService.java (collection template)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void applyDisbursementTransition(String disbursementId, DisbursementStatus target,
                                         Long tenantId, String externalReference) {
    Disbursement locked = disbursementRepository
        .findByDisbursementIdForUpdate(disbursementId)
        .orElseThrow(() -> new IllegalStateException("Disbursement not found: " + disbursementId));

    if (!locked.getDisbursementStatus().allowedTransitions().contains(target)) {
        log.info("Disbursement double-check: transition not valid in current state", ...);
        return; // already terminal — silently skip
    }

    locked.applyTransition(target);

    if (target == DisbursementStatus.FAILED) {
        walletBalanceService.release(tenantId, locked.getReservedAmount()); // BAL-02
    }

    String eventType = target == DisbursementStatus.SUCCESS
        ? "disbursement.completed" : "disbursement.failed";

    eventPublisher.publishEvent(new WebhookEnqueueRequestedEvent(
        disbursementId, tenantId, eventType,
        target == DisbursementStatus.SUCCESS ? TransactionStatus.SUCCESS : TransactionStatus.FAILED,
        externalReference, null  // feeAmount: not carried through callbacks; null is fine
    ));
}
```

### Pattern 3: WebhookDoubleCheckHandler — flow routing for disbursements

`WebhookDoubleCheckHandler` already handles `LedgerFlow.DISBURSEMENT` in its flow-switch (see the existing code: `event.flow() == LedgerFlow.COLLECTION ? orangeMoneyPort.getCollectionTransactionStatus(...) : orangeMoneyPort.getDisbursementTransactionStatus(...)`). The handler is fully wired for disbursements — it will call the correct status poll method when `WebhookReceivedEvent.flow = DISBURSEMENT` is received.

**The gap:** `applyFinalTransition()` in `WebhookTransitionService` locks a `Transaction` row. Disbursement callbacks must go to `DisbursementCallbackTransitionService.applyDisbursementTransition()` instead. Two options:
1. Add a second listener method in `WebhookDoubleCheckHandler` that routes based on flow — DISBURSEMENT calls `disbursementCallbackTransitionService`, COLLECTION calls `webhookTransitionService`
2. Use a different event type (`DisbursementWebhookReceivedEvent`) handled by a separate `DisbursementDoubleCheckHandler`

**Recommendation:** Option 1. Modify `WebhookDoubleCheckHandler` to inject `DisbursementCallbackTransitionService` and route the `applyFinalTransition` call based on `event.flow()`. This keeps the double-check logic in one place and avoids a second event bus listener for the same event type.

### Pattern 4: Outbound webhook event type strings

The existing `WebhookDeliveryService.enqueue()` accepts `eventType` as a free-form String. Collection events use `TransactionEventType.PROVIDER_SUCCESS.name()` ("PROVIDER_SUCCESS") and similar values. For disbursements, use the tenant-facing strings `"disbursement.completed"` and `"disbursement.failed"` as the event type — these are what the tenant will see in their webhook payload.

The `OutboundWebhookPayload` constructs `status` via: `delivery.getEventType().contains("SUCCESS") ? "SUCCESS" : "FAILED"`. The new disbursement event types (`disbursement.completed`, `disbursement.failed`) must therefore encode SUCCESS/FAILED consistently:
- `"disbursement.completed"` → status: "SUCCESS" (contains no "SUCCESS" — **this is a bug risk**)
- Better: pass explicit status via the `WebhookEnqueueRequestedEvent.status` field — `WebhookDeliveryService.enqueue()` already receives `TransactionStatus status` and passes it directly to `OutboundWebhookPayload`. The `status` field on the payload comes from the event's `status`, not from the `eventType` string. Verify this interpretation is correct before coding.

**CRITICAL verification:** Read `OutboundWebhookPayload` constructor before writing disbursement enqueue code. The 6-arg constructor is:
```java
new OutboundWebhookPayload(
    delivery.getTransactionId(),
    delivery.getEventType().contains("SUCCESS") ? "SUCCESS" : "FAILED",  // status derived from eventType
    delivery.getEventType(),
    Instant.now().toString(),
    delivery.getExternalReference(),
    delivery.getFeeAmount()
)
```
The status IS derived from `eventType.contains("SUCCESS")`. So for disbursements:
- SUCCESS path: use `eventType = "disbursement.completed"` — but this contains no "SUCCESS" → status would be "FAILED" incorrectly
- **Fix:** Either (a) use `eventType = "DISBURSEMENT_SUCCESS"` / `"DISBURSEMENT_FAILED"` to preserve the existing derive logic, or (b) override `OutboundWebhookPayload` to use the `WebhookEnqueueRequestedEvent.status` field directly

**Recommendation:** Use event types `"DISBURSEMENT_COMPLETED"` / `"DISBURSEMENT_FAILED"` (all-caps matching the existing pattern) — then `contains("COMPLETED")` logic must be updated, OR simply check `status.contains("SUCCESS")` OR `status.contains("COMPLETED")`. Alternatively, extend `OutboundWebhookPayload` to derive status from the event's explicit `TransactionStatus` field. This must be resolved in Plan 1 before writing any transition service code.

### Pattern 5: Disbursement Status Poller (5-minute fallback, PROV-01/PROV-02)

**What:** Quartz job that polls `Disbursement` rows in `PROCESSING` status that have been waiting > N seconds without a callback. Mirrors `MtnStatusPollerJob` / `OrangeStatusPollerJob` pattern exactly.

**Key differences from collection pollers:**
- Queries `Disbursement` table, not `Transaction` table
- Uses `DisbursementRepository.findByDisbursementStatusAndProviderAndLastModifiedDateBefore()` (or a native query matching the SKIP LOCKED pattern from TransactionRepository)
- Calls `getDisbursementTransactionStatus(providerRef)` on the appropriate port
- On FAILED: calls `WalletBalanceService.release(tenantId, reserved_amount)` + transition to FAILED
- On SUCCESS: transition to SUCCESS (ledger already posted by port during initiateDisbursement)
- On terminal success/failure: publish `WebhookEnqueueRequestedEvent`

**Schema gap:** `Disbursement` entity has no `pollAttempts` column — Flyway V29 must add it. The Transaction entity pattern is:
```sql
-- V29: Add poll_attempts to disbursement for poller fallback (PROV-01, PROV-02)
ALTER TABLE main.disbursement ADD COLUMN IF NOT EXISTS poll_attempts INTEGER DEFAULT 0;
ALTER TABLE main.disbursement_aud ADD COLUMN IF NOT EXISTS poll_attempts INTEGER;
```

And in `Disbursement.java`:
```java
@Column(name = "poll_attempts")
private Integer pollAttempts;

public void incrementPollAttempts() {
    this.pollAttempts = (this.pollAttempts == null ? 0 : this.pollAttempts) + 1;
}
```

### Pattern 6: Path Registration for New Callback Endpoints

Two changes needed in existing files:

**MtnWebConfig.java** — add disbursement path to interceptor:
```java
registry.addInterceptor(mtnIpWhitelistInterceptor)
        .addPathPatterns("/v1/callbacks/mtn")
        .addPathPatterns("/v1/callbacks/mtn/disbursement/*");
```

**OrangeWebConfig.java** — add disbursement path:
```java
registry.addInterceptor(interceptor)
        .addPathPatterns("/v1/callbacks/orange")
        .addPathPatterns("/v1/callbacks/orange/disbursement");
```

**AppEndpoints.java** — add to `PUBLIC_ENDPOINTS` list:
```java
"/v1/callbacks/mtn/disbursement/*",    // MTN PUT disbursement callbacks
"/v1/callbacks/orange/disbursement"    // Orange POST disbursement callbacks
```

**TenantSecurityConfig** — if it uses path-based rules mirroring `AppEndpoints.PUBLIC_ENDPOINTS`, must also be updated. Verify this file before writing code.

### Anti-Patterns to Avoid

- **@Transactional on the callback controller method:** The existing collection controllers are deliberately NOT @Transactional. Holding a DB connection during HTTP response violates the established connection pool policy.
- **State transition inside the callback controller:** Controller returns 200 immediately. Transition happens in `DisbursementCallbackTransitionService` via `@TransactionalEventListener(AFTER_COMMIT)`.
- **Wallet release in the wrong transaction:** `WalletBalanceService.release()` MUST be in the same `REQUIRES_NEW` transaction as the `disbursementStatus` transition to FAILED — these two operations are atomic. Separate transactions risk a window where status = FAILED but wallet is still reserved.
- **Using the collection dedup key namespace:** Disbursement dedup key prefix must be `callbacks:dsb:` not `webhook:mtn:` or `webhook:orange:` — the namespaces are segregated to prevent cross-contamination.
- **Missing IP whitelist registration for new paths:** If the interceptor is only registered for `/v1/callbacks/mtn`, the new `/v1/callbacks/mtn/disbursement/*` path skips the whitelist.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Outbound HMAC-SHA256 signing | Custom signing logic | `WebhookDeliveryService.attemptDeliveryInternal()` | Already production-tested with `javax.crypto.Mac`; reuse by publishing `WebhookEnqueueRequestedEvent` |
| Exponential backoff retry for webhook delivery | Custom retry loop | `WebhookDeliveryService` + `WebhookDeliveryJob` | Already implemented: 2^n minute backoff, max 5 attempts, SKIP LOCKED, cluster-safe |
| Inbound IP whitelist logic | New whitelist interceptor | Extend existing `MtnIpWhitelistInterceptor` and `OrangeIpWhitelistInterceptor` by adding new paths | Same IP ranges, same config properties; no new bean needed |
| Redis dedup logic | Custom set-and-check | `StringRedisTemplate.opsForValue().setIfAbsent(key, value, TTL)` | Atomic NX operation — the only correct pattern for dedup under concurrent load |
| Provider status double-check | Skip or inline | `WebhookDoubleCheckHandler` + `getDisbursementTransactionStatus()` | Already handles flow routing; adding disbursement transition service is a small extension |

---

## Common Pitfalls

### Pitfall 1: Path Overlap with Existing Collection Callbacks
**What goes wrong:** New disbursement callback path `/v1/callbacks/mtn/disbursement/{ref}` must not overlap with existing `/v1/callbacks/mtn`. Spring MVC will match the more specific path first, but if the interceptor is registered only for `/v1/callbacks/mtn` the disbursement path bypasses IP whitelist enforcement.
**Root cause:** `MtnWebConfig.addInterceptors()` currently registers only the exact path `/v1/callbacks/mtn`. Pattern `/v1/callbacks/mtn/disbursement/**` is a different URL pattern.
**Prevention:** Explicitly add `.addPathPatterns("/v1/callbacks/mtn/disbursement/*")` to the interceptor registry in `MtnWebConfig`. Also add the disbursement paths to `AppEndpoints.PUBLIC_ENDPOINTS`.

### Pitfall 2: OutboundWebhookPayload Status Derivation from EventType String
**What goes wrong:** `OutboundWebhookPayload` derives `status` from `delivery.getEventType().contains("SUCCESS")`. If disbursement event type is `"disbursement.completed"`, status resolves to `"FAILED"` incorrectly.
**Root cause:** The existing derivation logic is hardcoded for collection event type naming conventions.
**Prevention:** Before writing `DisbursementCallbackTransitionService`, decide on event type string conventions for disbursements. Either extend `OutboundWebhookPayload` to derive status from a dedicated status field, or use naming that preserves the contains-check (e.g. `"DISBURSEMENT_SUCCESS"` / `"DISBURSEMENT_FAILED"`).

### Pitfall 3: WalletBalanceService.release() Outside the Transition Transaction
**What goes wrong:** If `release()` is called in a separate `TransactionTemplate` AFTER the status transition commits, a crash between the two calls leaves the disbursement in FAILED state with wallet still reserved.
**Root cause:** The orchestrator's `releaseAndFail()` method intentionally uses two separate templates (resilience over atomicity for the initiation path), but the callback transition path must be atomic because both operations are triggered by the same confirmed terminal outcome.
**Prevention:** Put `walletBalanceService.release()` and `disbursement.applyTransition(FAILED)` inside the same `@Transactional(REQUIRES_NEW)` method in `DisbursementCallbackTransitionService`. Single transaction = atomic commit or rollback.

### Pitfall 4: Dedup Key on Wrong Field (providerRefId vs disbursementId)
**What goes wrong:** SEC-05 requires dedup on `providerReferenceId` (the ID the provider uses), not on the Payam `disbursementId`. For MTN this is the merchant-generated UUID (`providerRef`). For Orange this is the `payToken` or merchant reference returned by the provider.
**Root cause:** Confusion between correlation fields. `MtnCallbackPayload.externalId` = our `disbursementId`; the providerRef = `{ref}` path variable = `Disbursement.providerRef`.
**Prevention:** Dedup key: `"callbacks:dsb:" + providerRef` (the path variable for MTN, or the Orange provider-specific ID). This ensures that a re-sent callback from the provider (same providerRef, same event) is silently ignored.

### Pitfall 5: Disbursement Poller Missing V29 Migration
**What goes wrong:** `DisbursementStatusPollerJob` reads and writes `poll_attempts` on `Disbursement` — but V28 doesn't include this column. Application fails at startup or the poller throws `Unknown column`.
**Root cause:** The poller was deferred from Phase 51 and the V28 schema was written without it.
**Prevention:** Flyway V29 MUST be the first task in the plan that includes the poller. Verify V28 does not already include `poll_attempts` (confirmed: it does not).

### Pitfall 6: Double Balance Release on FAILED
**What goes wrong:** The new callback transition service calls `WalletBalanceService.release()` when transitioning to FAILED. But `DisbursementOrchestrator.releaseAndFail()` also calls `release()` for initiation-path failures. If a callback arrives after an initiation-path failure already released the wallet, the callback's `release()` would over-release (balance goes negative or throws `IllegalStateException`).
**Root cause:** Two code paths can trigger `FAILED` for the same disbursement row. The initiation-path failure (e.g. subscriber inactive, provider HTTP error) calls `releaseAndFail()` which transitions the row to FAILED. A stale callback arriving after this would attempt a second release.
**Prevention:** The state transition guard in `DisbursementCallbackTransitionService` prevents this: `if (!locked.getDisbursementStatus().allowedTransitions().contains(target)) return;`. Since `FAILED.allowedTransitions()` is empty, any callback arriving after the row is already FAILED skips the transition AND skips the `release()`. The guard is sufficient — verify no `release()` call escapes it.

### Pitfall 7: Missing AbstractPayamE2ETest WireMock Server for Disbursement Callbacks
**What goes wrong:** E2E tests that send inbound disbursement callbacks need a WireMock stub for the provider status API (double-check call). `AbstractPayamE2ETest` already stubs `mtn.collection-base-url` but does NOT stub `mtn.disbursement-base-url` for the double-check poll.
**Root cause:** `AbstractPayamE2ETest` was written before disbursement callbacks existed. The `@ConfigureWireMock` annotation only covers `mtn.collection-base-url`.
**Prevention:** Disbursement callback integration tests (or E2E tests for Phase 53) must add `mtn.disbursement-base-url` to the WireMock `@ConfigureWireMock` — exactly as `DisbursementOrchestratorIT` already does. Do NOT extend `AbstractPayamE2ETest` for the callback IT — use a standalone `@SpringBootTest` class like `OrangeCallbackControllerIT`.

---

## Code Examples

### Verified pattern: Redis replay dedup (from OrangeCallbackController)
```java
// Source: OrangeCallbackController.java
String dedupKey = "webhook:orange:" + payToken + ":" + createtime;
Boolean wasAbsent = redis.opsForValue().setIfAbsent(dedupKey, "SEEN", Duration.ofHours(24));
if (Boolean.FALSE.equals(wasAbsent)) {
    log.info("Orange webhook duplicate suppressed", kv("status", "DUPLICATE"));
    return ResponseEntity.ok().build();
}
// For disbursements: use "callbacks:dsb:<providerRefId>" as namespace
```

### Verified pattern: TransactionTemplate event publish for AFTER_COMMIT (from MtnMoMoPort)
```java
// Source: MtnMoMoPort.processCallback()
transactionTemplate.execute(status -> {
    eventPublisher.publishEvent(new WebhookReceivedEvent(
        txId, MobilePaymentProvider.MTN, providerRef, traceId, flow
    ));
    return null;
});
// For disbursements: same pattern; flow = LedgerFlow.DISBURSEMENT
```

### Verified pattern: REQUIRES_NEW transition (from WebhookTransitionService)
```java
// Source: WebhookTransitionService.applyFinalTransition()
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void applyDisbursementTransition(WebhookReceivedEvent event, ProviderResult result) {
    Disbursement locked = disbursementRepository
        .findByDisbursementIdForUpdate(event.transactionId())
        .orElseThrow(...);
    if (!locked.getDisbursementStatus().allowedTransitions().contains(target)) return;
    locked.applyTransition(target);
    if (target == DisbursementStatus.FAILED) walletBalanceService.release(...); // BAL-02
    eventPublisher.publishEvent(new WebhookEnqueueRequestedEvent(...));
}
```

### Verified pattern: WebhookDoubleCheckHandler flow routing (already disbursement-aware)
```java
// Source: WebhookDoubleCheckHandler.handleWebhookReceived()
// Already routes DISBURSEMENT flow correctly:
result = event.flow() == LedgerFlow.COLLECTION
    ? orangeMoneyPort.getCollectionTransactionStatus(event.providerRef())
    : orangeMoneyPort.getDisbursementTransactionStatus(event.providerRef());
// The handler just needs to call disbursementCallbackTransitionService instead of
// webhookTransitionService when event.flow() == DISBURSEMENT
```

### Verified pattern: Quartz poller job (from MtnStatusPollerJob)
```java
// Source: MtnStatusPollerJob.executeInternal()
@Override
@Transactional(timeout = 300)
protected void executeInternal(JobExecutionContext context) {
    Observation.createNotStarted("quartz.dsb-poller", observationRegistry)
        .lowCardinalityKeyValue("job", "DisbursementStatusPollerJob")
        .observe(this::runPoller);
}
// Disbursement variant: query DisbursementRepository instead of TransactionRepository
// Add FOR UPDATE SKIP LOCKED native query to DisbursementRepository
```

### Verified pattern: HMAC-SHA256 signing (existing — no change needed)
```java
// Source: WebhookDeliveryService.attemptDeliveryInternal()
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(
    tenant.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
byte[] hmacBytes = mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
String signature = "sha256=" + Hex.encodeHexString(hmacBytes);
headers.set("X-Payam-Signature", signature);
// No changes needed — WebhookDeliveryService reuse handles this automatically
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Single `WebhookTransitionService` for all transitions | Extend to route DISBURSEMENT flow to `DisbursementCallbackTransitionService` | Phase 52 | Clean separation; collection path untouched |
| No disbursement poller (PROV-01/PROV-02 deferred) | `DisbursementStatusPollerJob` with Quartz + V29 schema | Phase 52 | Closes 5-minute callback timeout gap |
| `Disbursement` entity without `poll_attempts` | Add `poll_attempts` column via V29 | Phase 52 | Required by poller |

---

## Open Questions

1. **WebhookDoubleCheckHandler extension approach**
   - What we know: The handler already routes by `LedgerFlow`; modifying it to call `DisbursementCallbackTransitionService` vs `WebhookTransitionService` based on flow is minimal code
   - What's unclear: Whether injecting both transition services into the handler creates a circular dependency (depends on what `DisbursementCallbackTransitionService` injects)
   - Recommendation: Check for circular dependency at plan-write time. If circular, use a separate `DisbursementDoubleCheckHandler` that listens to the same `WebhookReceivedEvent` — Spring can have multiple `@TransactionalEventListener` methods for the same event type.

2. **OutboundWebhookPayload status derivation**
   - What we know: Current code derives `status` from `eventType.contains("SUCCESS")` — will fail for `"disbursement.completed"`
   - What's unclear: Whether to fix `OutboundWebhookPayload` (touch existing code) or use naming convention
   - Recommendation: Fix `OutboundWebhookPayload` to use the `WebhookEnqueueRequestedEvent.status` (a `TransactionStatus` enum value) as the authoritative source. This is a small change with no backward compatibility risk since the payload is an internal record.

3. **Orange disbursement callback correlation**
   - What we know: `OrangeMoneyPort.initiateDisbursement()` calls `/cashout` and `CashoutRequest` sets `reference = cmd.externalReference() != null ? cmd.externalReference() : cmd.transactionId()`. The Orange disbursement callback payload structure for `/cashout` is not confirmed from the existing code.
   - What's unclear: Which field in the Orange disbursement callback body to use as the correlation key to look up the `Disbursement` row. The collection path uses `payToken`; the disbursement path may use the merchant `reference` or an Orange-generated `txnid`.
   - Recommendation: Plan implementor must read the Orange Money cashout callback documentation or inspect the sandbox callback format before writing `OrangeDisbursementCallbackController`. The `Disbursement.reference` field is the safest correlation field (it equals `cmd.externalReference()` or `disbursementId`).

---

## Environment Availability

Step 2.6: SKIPPED (this phase is code-only changes; all external dependencies — PostgreSQL, Redis, Quartz JDBC store — were confirmed available in Phase 50 and 51).

---

## Validation Architecture

`workflow.nyquist_validation` key absent from `.planning/config.json` — treating as enabled.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers (PostgreSQL + Redis) |
| Config file | `src/test/java/com/softropic/payam/config/` |
| Quick run command | `mvn test -pl . -Dtest=MtnDisbursementCallbackControllerIT,OrangeDisbursementCallbackControllerIT,DisbursementCallbackTransitionServiceTest -am` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SEC-05 | MTN callback arrives at correct path, IP whitelist enforced, duplicate suppressed, state transitions to SUCCESS/FAILED | Integration | `mvn test -Dtest=MtnDisbursementCallbackControllerIT` | ❌ Wave 0 |
| SEC-05 | Orange callback arrives at correct path, duplicate suppressed, state transitions | Integration | `mvn test -Dtest=OrangeDisbursementCallbackControllerIT` | ❌ Wave 0 |
| SEC-05 | Replay dedup: second identical callback does not trigger second transition or webhook delivery | Integration | included in `MtnDisbursementCallbackControllerIT` | ❌ Wave 0 |
| SEC-06 | Terminal disbursement publishes `WebhookEnqueueRequestedEvent`; delivery signed with `X-Payam-Signature` | Integration | `mvn test -Dtest=DisbursementWebhookDeliveryIT` | ❌ Wave 0 |
| SEC-06 | Non-2xx tenant URL triggers retry with exponential backoff | Integration | included in `DisbursementWebhookDeliveryIT` (reuse WebhookDeliveryIT pattern) | ❌ Wave 0 |
| PROV-01 | MTN disbursement poller transitions PROCESSING → SUCCESS/FAILED after 5-min timeout | Integration | `mvn test -Dtest=DisbursementStatusPollerIT` | ❌ Wave 0 |
| PROV-02 | Orange disbursement poller transitions PROCESSING → SUCCESS/FAILED | Integration | included in `DisbursementStatusPollerIT` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn verify` (project constraint — runs all ITs including new ones)
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/disbursement/api/MtnDisbursementCallbackControllerIT.java` — covers SEC-05 (MTN path, IP whitelist, dedup, state transition)
- [ ] `src/test/java/com/softropic/payam/disbursement/api/OrangeDisbursementCallbackControllerIT.java` — covers SEC-05 (Orange path, dedup)
- [ ] `src/test/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionServiceTest.java` — unit test: correct state transitions, wallet release on FAILED, no release on SUCCESS, skip on already-terminal
- [ ] `src/test/java/com/softropic/payam/disbursement/service/DisbursementWebhookDeliveryIT.java` — covers SEC-06 delivery + retry (follow `WebhookDeliveryIT` pattern)
- [ ] `src/test/java/com/softropic/payam/disbursement/service/DisbursementStatusPollerIT.java` — covers PROV-01/PROV-02 poller (follow `MtnStatusPollerJob` test pattern)

---

## Sources

### Primary (HIGH confidence)
- Direct codebase inspection: `MtnCallbackController.java`, `OrangeCallbackController.java` — inbound callback controller pattern
- Direct codebase inspection: `MtnIpWhitelistInterceptor.java`, `OrangeIpWhitelistInterceptor.java`, `MtnWebConfig.java`, `OrangeWebConfig.java` — interceptor registration
- Direct codebase inspection: `WebhookDoubleCheckHandler.java`, `WebhookTransitionService.java` — double-check + REQUIRES_NEW transition pattern (already routes `LedgerFlow.DISBURSEMENT`)
- Direct codebase inspection: `WebhookDeliveryService.java` — HMAC-SHA256 signing, exponential backoff, `@TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW`, `WebhookDeliveryJob`
- Direct codebase inspection: `WebhookEnqueueRequestedEvent.java`, `OutboundWebhookPayload.java` — event contract
- Direct codebase inspection: `AppEndpoints.java` — PUBLIC_ENDPOINTS list
- Direct codebase inspection: `DisbursementOrchestrator.java`, `DisbursementRepository.java`, `Disbursement.java`, `DisbursementStatus.java` — disbursement domain model
- Direct codebase inspection: `MtnMoMoPort.java`, `OrangeMoneyPort.java` — `getDisbursementTransactionStatus()` already implemented
- Direct codebase inspection: `MtnStatusPollerJob.java`, `OrangeStatusPollerJob.java`, `MtnSchedulerConfig.java` — poller Quartz pattern
- Direct codebase inspection: `V28__disbursement_schema.sql` — confirmed no `poll_attempts` column; next migration is V29
- Direct codebase inspection: `DisbursementOrchestratorIT.java` — confirmed `mtn.disbursement-base-url` WireMock pattern
- Direct codebase inspection: `OrangeCallbackControllerIT.java` — standalone IT pattern (no AbstractPayamE2ETest dependency)

### No secondary or tertiary sources needed
All findings are HIGH confidence from direct source inspection. This phase is entirely an extension of existing patterns.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in use; zero new dependencies
- Architecture: HIGH — collection callback pattern is a 1:1 template; `WebhookDeliveryService` handles SEC-06 without modification
- Pitfalls: HIGH — derived from reading actual production code; known failure modes from States.md decisions

**Research date:** 2026-04-25
**Valid until:** Stable — internal-pattern phase with no external dependency; valid until codebase refactor
