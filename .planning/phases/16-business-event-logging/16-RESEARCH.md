# Phase 16: Business Event Logging - Research

**Researched:** 2026-03-27
**Domain:** Structured business event logging with SLF4J + logstash-logback-encoder `kv()`
**Confidence:** HIGH

---

## Summary

Phase 16 adds structured INFO/WARN log events at seven business-critical points in the payment
lifecycle. The logging infrastructure (Phase 14) and MDC/request correlation (Phase 15) are
already complete. Phase 16 is entirely code changes — no configuration or dependency additions
are required.

The existing pattern from `LoggingFilter` is the model: use
`StructuredArguments.kv("fieldName", value)` as variadic arguments to `log.info()`/`log.warn()`.
Every field required by LOG-BUS-01 through LOG-BUS-07 must appear as a `kv()` argument, not
embedded in the message string. Duration measurement follows the pattern already established in
`LoggingFilter` and `PaymentOrchestrator`: `long start = System.currentTimeMillis()` before the
operation, then `System.currentTimeMillis() - start` for `durationMs`.

There is no `StructuredLogger` wrapper class in the codebase. Each service uses its own
`Logger log = LoggerFactory.getLogger(...)` (or `@Slf4j`). Phase 16 maintains this pattern —
there is no need to introduce a shared utility class.

**Primary recommendation:** Add `kv()`-based log calls in-place in the seven target classes.
Each LOG-BUS requirement maps to exactly one class. No new classes, no new dependencies.

---

## Standard Stack

### Core (already present — no additions)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `net.logstash.logback:logstash-logback-encoder` | 8.1 | `StructuredArguments.kv()` | Already used in `LoggingFilter`; deployed by Phase 14 |
| `org.slf4j:slf4j-api` | managed by Spring Boot 3.5.x | `Logger`, `MDC` | Standard SLF4J |

### No New Dependencies

Everything required is already in `pom.xml`. No `mvn` changes for Phase 16.

---

## Architecture Patterns

### Logging Pattern

All business event logs follow the same structure established by `LoggingFilter`:

```java
// Source: LoggingFilter.java (already in production)
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("Human readable message",
    kv("operation", "operation_name"),
    kv("transactionId", transactionId),
    kv("field1", value1),
    kv("durationMs", durationMs),
    kv("status", "SUCCESS"));
```

Key rules:
- Logger type: `private static final Logger log = LoggerFactory.getLogger(...)` (or `@Slf4j`)
- `kv()` import: `import static net.logstash.logback.argument.StructuredArguments.kv;`
- Every queryable field is a `kv()` argument — NOT embedded in the message string
- `durationMs` is measured with `System.currentTimeMillis()` — no Timer utility exists
- `status` field values: `"SUCCESS"` for INFO events, `"BLOCKED"` or `"FAILED"` for WARN events

### Duration Measurement Pattern

```java
// Source: PaymentOrchestrator.java (line 202-209) and LoggingFilter.java (line 88, 93)
long start = System.currentTimeMillis();
// ... operation ...
long durationMs = System.currentTimeMillis() - start;
```

### MSISDN Masking Pattern (LOG-BUS-01)

The requirement specifies "last 4 digits only". No utility method exists — implement inline:

```java
// Last 4 digits of msisdn — safe when msisdn length < 4
String msisdnLast4 = msisdn != null && msisdn.length() >= 4
    ? msisdn.substring(msisdn.length() - 4)
    : "****";
```

### Anti-Patterns to Avoid

- **String interpolation for queryable fields:** `log.info("transactionId={}", id)` — use `kv()` instead
- **MDC.put() for per-event fields:** Fields like `durationMs`, `riskScore`, `status` belong as `kv()` arguments, not in MDC. MDC is for correlation context that spans all log lines in a thread.
- **Custom StructuredLogger wrapper:** The codebase pattern is direct `log.info()` with `kv()`. Do not introduce an abstraction layer.
- **Calling `MDC.clear()`:** Only `MDC.remove()` is permitted — preserves `traceId`/`spanId` from micrometer-tracing-bridge-otel.

---

## Requirement-to-Source Mapping

### LOG-BUS-01: Payment Initiation

**Requirement:** Structured INFO event with `operation=initiate_payment`, `tenantId`, `transactionId`, `provider`, `msisdn` (last 4 digits only), `durationMs`, `status`

**Primary class:** `PaymentOrchestrator.java`
`src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java`

**Method:** `initiate(Long tenantId, PaymentRequest request)`

**Insertion point:** After the provider dispatch succeeds (line 229 area), before `return response`.
Also needs a WARN/ERROR path for failures (line 248-259 area).

**Available data at insertion point:**
- `tenantId` — method parameter (Long DB PK; but LOG-BUS-01 requires `tenantId` — decision 15-01 means the MDC `tenantId` holds `tenantRef` UUID, but the log event field should also be `tenantRef` — see Open Questions)
- `tx.getTransactionId()` — available after step 3
- `provider.name()` — available after step 1
- `request.msisdn()` — available from PaymentRequest
- `durationMs` — needs a `start` timer at method entry (currently `providerStart` only covers provider call)
- `status` — "SUCCESS" on the happy path, "FAILED" on all error branches

**Note on `tenantId` value:** The MDC `tenantId` key holds `tenantRef` (UUID string) per decision 15-01. The LOG-BUS-01 `tenantId` field in the log event should be consistent — use the `tenantRef` obtained from `TenantContext.get()` (which is already populated by `ApiKeyAuthenticationFilter` at this point in the request thread).

**Note on timing:** A top-of-method timer `long start = System.currentTimeMillis()` is needed. The existing `providerStart` only covers provider HTTP latency, not total operation duration.

---

### LOG-BUS-02: Transaction State Change

**Requirement:** Structured INFO event with `operation=transaction_state_change`, `transactionId`, `fromState`, `toState`, `actor`

**Primary class:** `Transaction.java` (entity) — `applyTransition()` method
`src/main/java/com/softropic/payam/transaction/repo/Transaction.java`

**Alternative class:** `TransactionService.java`
`src/main/java/com/softropic/payam/transaction/service/TransactionService.java`

**State machine:** `TransactionStatus` enum with `transitionTo()`. Allowed transitions:
- `INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING` (happy path in `PaymentOrchestrator`)
- `INITIATED → FAILED` (fraud block, circuit open, subscriber inactive)
- `PROCESSING → SUCCESS / FAILED / REVERSED` (via `WebhookTransitionService` and pollers)

**All callers of `applyTransition()` / state transitions:**
1. `PaymentOrchestrator.initiate()` — via `transactionTemplate.execute()` at line 219-222 (three transitions)
2. `PaymentOrchestrator.applyFailed()` — locked.applyTransition(FAILED) at line 287
3. `WebhookTransitionService.applyFinalTransition()` — `tx.applyTransition(target)` at line 76
4. `MtnStatusPollerJob.pollTransaction()` — `locked.applyTransition()` at lines 82 (FAILED) and 96 (terminal)
5. `OrangeStatusPollerJob.pollTransaction()` — `locked.applyTransition()` at lines 89 (FAILED) and 103 (terminal)

**Recommended approach:** Add a logger to `Transaction.applyTransition()` itself. This captures ALL state changes regardless of caller, avoids duplicating the log call across every caller site:

```java
// Transaction.java — applyTransition() with logging
public void applyTransition(TransactionStatus next) {
    TransactionStatus previous = this.txStatus;
    this.txStatus = this.txStatus.transitionTo(next);
    // LOG-BUS-02 emitted here
}
```

**Constraint:** `Transaction` is a JPA entity (not a Spring bean). It has no injected logger or MDC access. Adding a static logger is straightforward (`LoggerFactory.getLogger(Transaction.class)`). The `transactionId` is available as `this.transactionId`. The `actor` field is NOT available inside `Transaction.applyTransition()` — the actor comes from the caller. This makes the entity approach incomplete for `actor`.

**Alternative approach:** Add the log call at each caller site where `actor` context is known. This is more verbose (4+ sites) but satisfies the `actor` requirement cleanly.

**Recommended approach (confirmed):** Log in `Transaction.applyTransition()` for `fromState` and `toState`, but `actor` cannot be sourced there. Instead, log at each caller site using `kv()` on the `actor` string. The callers are:
- `PaymentOrchestrator` (actor = "ORCHESTRATOR")
- `WebhookTransitionService` (actor = "WEBHOOK_DOUBLE_CHECK")
- Pollers (actor = "MTN_POLLER" / "ORANGE_POLLER")

The `EventLogService.append()` already receives `actor` as a parameter — a structured log call at the same points as the `eventLogService.append()` calls would co-locate the structured log with the domain event record.

---

### LOG-BUS-03: Inbound Webhook Receipt

**Requirement:** Structured INFO event with `operation=webhook_received`, `provider`, `transactionId`, `externalReference`, `providerStatus`

**MTN path:** `MtnMoMoPort.processCallback(MtnCallbackPayload payload)`
`src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java`

Line 172: existing `log.info("MTN callback received: ...")` — replace with `kv()` form.

Available data:
- `payload.getExternalId()` = `transactionId` (MTN convention)
- `payload.getStatus()` = `providerStatus`
- `payload.getFinancialTransactionId()` = could serve as `externalReference` (or use providerRef)
- Provider = MTN (hardcoded string "MTN")

**Orange path:** `OrangeMoneyPort.processWebhook(OrangeWebhookPayload payload, String notifToken)`
`src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java`

Line 176: existing `log.info("Orange webhook received: ...")` — replace with `kv()` form.

Available data:
- `payload.getPayToken()` = Orange providerRef (NOT the transactionId — must look up tx)
- `payload.getStatus()` = `providerStatus`
- `payload.getTxnid()` = Orange-internal; NOT our transactionId
- `transactionId` is only available inside the `ifPresentOrElse` lambda (line 186+)

**Note:** For Orange, the `transactionId` is only available after the `transactionRepository.findByPayToken()` lookup inside the lambda. The structured log must be emitted inside the lambda where `txId` is available, OR emit with a null transactionId outside and a second log inside. The cleaner approach is to emit the single event inside the `ifPresentOrElse` present-branch, and log a WARN with payToken only for the not-found case.

**`externalReference` mapping:**
- MTN: `payload.getFinancialTransactionId()` (nullable until confirmed) or use `payload.getExternalId()` as the correlation reference
- Orange: `payload.getPayToken()` serves as the provider-side reference; the actual `externalReference` stored on `tx` is `tx.getExternalReference()`

---

### LOG-BUS-04: Outbound Webhook Delivery

**Requirement:** Structured INFO/WARN event with `operation=webhook_delivery`, `transactionId`, `tenantId`, `durationMs`, `httpStatus`, `status`, `retryCount`

**Primary class:** `WebhookDeliveryService.attemptDeliveryInternal(WebhookDeliveryLog delivery, Tenant tenant)`
`src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java`

**All delivery outcomes to log (in `attemptDeliveryInternal`):**
1. Line 184: success `log.info("Webhook delivered: ...")` — replace with `kv()` form (INFO, status=SUCCESS)
2. Line 188: non-2xx `log.warn("Webhook delivery non-2xx: ...")` — replace with `kv()` form (WARN, status=FAILED)
3. Line 195: HTTP error catch `log.warn("Webhook delivery HTTP error: ...")` — replace with `kv()` form (WARN, status=FAILED)
4. Line 198: network error catch `log.warn("Webhook delivery failed: ...")` — replace with `kv()` form (WARN, status=FAILED)

**Available data:**
- `delivery.getTransactionId()` = transactionId
- `delivery.getTenantId()` = tenantId (Long DB PK — but requirement says `tenantId`; use as-is or look up tenantRef)
- `delivery.getAttemptCount()` = retryCount (already incremented before the POST at line 170)
- `delivery.getHttpStatus()` (set after response, line 179)
- `durationMs` — needs a `start` timer wrapping the `restTemplate.exchange()` call
- `status` — "SUCCESS" for 2xx, "FAILED" for non-2xx/exception

**Note on tenantId:** `delivery.getTenantId()` is a `Long` (DB PK). Decision 15-01 specified `tenantRef` (UUID) for the MDC `tenantId` key. For the log event field, the LOG-BUS-04 requirement says `tenantId` but does not specify format. Use `delivery.getTenantId().toString()` (Long as string) consistently, OR look up the tenantRef from the Tenant object that is already loaded in scope. The `tenant` parameter in `attemptDeliveryInternal` contains `tenant.getTenantRef()` if available. Prefer `tenantRef` for consistency with MDC.

**`durationMs` placement:** Wrap only the `restTemplate.exchange()` call (lines 174-176), not the entire method. This measures actual HTTP delivery latency.

---

### LOG-BUS-05: Fraud Evaluation

**Requirement:** Structured INFO/WARN event with `operation=fraud_evaluation`, `transactionId`, `riskScore`, `blocked`, `durationMs`

**Primary class:** `FraudScoringService.evaluate(PaymentCommand cmd)`
`src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java`

**Current logging (3 existing log calls):**
- Line 98-100: `log.warn("Payment blocked by fraud engine (direct velocity): ...")` — replace with `kv()` form (WARN, blocked=true)
- Line 111-112: `log.warn("Payment blocked by fraud engine (score): ...")` — replace with `kv()` form (WARN, blocked=true)
- Line 115: `log.debug("Fraud evaluation allowed: ...")` — **upgrade to INFO** and replace with `kv()` form (INFO, blocked=false)

**Available data:**
- `cmd.transactionId()` = transactionId
- `riskScore` (computed at line 90)
- `fraud.blocked()` = blocked (true/false)
- `durationMs` — add `long start = System.currentTimeMillis()` at method entry; compute at each return point

**Note:** Three return paths exist — `FraudDecision.block()` at line 100, `FraudDecision.block()` at line 112, and `FraudDecision.allow()` at line 116. A single log call just before each `return` statement is the cleanest approach.

---

### LOG-BUS-06: Provider Adapter HTTP Calls

**Requirement:** Structured INFO event with `externalService`, `operation`, `externalLatencyMs`, `status`

**MTN class:** `MtnMoMoClient.java` — all public methods
`src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java`

**Orange class:** `OrangeMoneyClient.java` — all public methods
`src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java`

**Current approach:** Both classes extend `AbstractClient` and call `makeHttpRequest()`. The `RestRequestInterceptor` already logs at INFO level (line 82: `"Response method: {} url: {} headers: {} status: {} Payload: {}"`) but using string interpolation format, not `kv()`, and it logs raw headers/body — not the structured `externalService`, `operation`, `externalLatencyMs` fields required.

**Recommended approach — Option A: log in each public method of MtnMoMoClient / OrangeMoneyClient:**

Add a `start` timer before `makeHttpRequest()` and emit a `kv()` log after return in each method.

```java
// Example for MtnMoMoClient.requestToPay()
long start = System.currentTimeMillis();
ResponseEntity<Void> response = makeHttpRequest(url, HttpMethod.POST, request, Void.class, headers);
log.info("Provider HTTP call",
    kv("externalService", "MTN_MOMO"),
    kv("operation", "requestToPay"),
    kv("externalLatencyMs", System.currentTimeMillis() - start),
    kv("status", "SUCCESS"));
```

**Recommended approach — Option B: log in MtnMoMoPort / OrangeMoneyPort caller methods:**

Add timing around the `mtnMoMoClient.X()` / `orangeMoneyClient.X()` calls in the Port classes, which already have loggers.

**Assessment:** Option A (in the Client classes) is the correct approach. LOG-BUS-06 is specifically about "adapter HTTP calls" — the Client class IS the adapter. Each public method of `MtnMoMoClient` and `OrangeMoneyClient` represents one external HTTP call. The Port classes (`MtnMoMoPort`, `OrangeMoneyPort`) add business logic around the calls.

**Methods needing LOG-BUS-06 in MtnMoMoClient:**
- `fetchCollectionToken()` — operation="fetchCollectionToken", externalService="MTN_MOMO"
- `fetchDisbursementToken()` — operation="fetchDisbursementToken"
- `requestToPay()` — operation="requestToPay"
- `getRequestToPayStatus()` — operation="getRequestToPayStatus"
- `validateAccountHolder()` — operation="validateAccountHolder"
- `getBalance()` — operation="getBalance"
- `disburse()` — operation="disburse"

**Methods needing LOG-BUS-06 in OrangeMoneyClient:**
- `fetchToken()` — operation="fetchToken", externalService="ORANGE_MONEY"
- `getSubscriberInfo()` — operation="getSubscriberInfo"
- `getMerchantInfo()` — operation="getMerchantInfo"
- `pay()` — operation="pay"
- `getPaymentStatus()` — operation="getPaymentStatus"
- `cashout()` — operation="cashout"
- `c2c()` — operation="c2c"

**`externalLatencyMs` vs `RestRequestInterceptor`:** The `RestRequestInterceptor.logRequestMetrics()` (line 112) logs `"RESPONSE method: {} url: {} status: {} latency: {}"` at INFO. This is NOT the LOG-BUS-06 format. Both logs will co-exist — the interceptor's log is useful for debugging, while LOG-BUS-06's `kv()` event is queryable in Loki. No conflict; both serve different purposes.

---

### LOG-BUS-07: Daily Reconciliation

**Requirement:** Structured INFO event with `operation=reconciliation_run`, `date`, `totalChecked`, `discrepancyCount`, `durationMs`, `status`

**Primary class:** `ReconciliationService.runForDate(LocalDate reportDate)`
`src/main/java/com/softropic/payam/reconciliation/service/ReconciliationService.java`

**Insertion point:** After the per-provider loop completes (after line 85). This is the single summary event that covers the entire daily run across all providers.

**Available data after the loop:**
- `reportDate` = date
- `totalChecked` — must be accumulated across all providers (currently logged per-provider at line 138, not summed)
- `discrepancyCount` — must be accumulated across all providers
- `durationMs` — add `long start = System.currentTimeMillis()` at method entry
- `status` — "SUCCESS" if no exception reached; "FAILED" on unhandled exception path (which is currently swallowed per-provider)

**Note on total accumulation:** `runForProviderAndDate()` currently writes totals per provider to the database but does not return them. To produce the summary log, either:
- Change `runForProviderAndDate()` to return an int[] `[totalChecked, discrepancyCount]`
- Or re-query the repository after the loop (`reportRepository.findByReportDateIn(...)`)
- Or maintain running counters in `runForDate()` by passing `AtomicInteger` accumulators in

The simplest correct approach: change `runForProviderAndDate()` to return `int[]` `{totalChecked, totalDiscrepancies}` and accumulate in `runForDate()`.

**Alternative:** Emit the LOG-BUS-07 event in `ReconciliationJob.executeInternal()` which wraps the entire run. This has access to the start time and completion status. However, `totalChecked` and `discrepancyCount` are still not available without passing them back from `runForDate()`.

**Confirmed approach:** Modify `runForDate()` to return a summary record (or `int[]`) containing cross-provider totals, then emit the LOG-BUS-07 event at the end of `runForDate()`. The `ReconciliationJob` wrapper then simply calls `runForDate()`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Structured log fields | String interpolation in message | `StructuredArguments.kv()` | kv() creates top-level JSON fields; strings in message body are not Loki-queryable |
| MSISDN masking | Custom masking library | Inline `substring()` | Single-use, trivial implementation |
| Timing | `Thread.sleep` or custom Timer | `System.currentTimeMillis()` | Already used in LoggingFilter and PaymentOrchestrator; consistent with codebase |
| StructuredLogger wrapper | New shared utility class | Direct `log.info()` with `kv()` | No such class exists; pattern is mature and consistent; adding a wrapper increases complexity without benefit |

---

## Common Pitfalls

### Pitfall 1: Logging tenantId as Long vs tenantRef UUID

**What goes wrong:** `delivery.getTenantId()` returns a `Long` (DB PK). The MDC `tenantId` key holds `tenantRef` (UUID string) per decision 15-01. If the log event field `tenantId` uses the Long, it is inconsistent with MDC.

**How to avoid:** Where a `Tenant` object is already in scope (e.g., `attemptDeliveryInternal` has `tenant` parameter), use `tenant.getTenantRef()` for the `tenantId` field value. Where only a `Long tenantId` is available and no Tenant is loaded, use `TenantContext.get()` if the call is on a request thread — but note that delivery retry via Quartz job runs on a non-request thread where `TenantContext` is null. Use Long.toString() as fallback in that case, or accept inconsistency and document it.

### Pitfall 2: `actor` not available inside `Transaction.applyTransition()`

**What goes wrong:** If LOG-BUS-02 is added inside `Transaction.applyTransition()`, the `actor` parameter is unavailable.

**How to avoid:** Emit LOG-BUS-02 at each call site where `actor` is known, co-located with `eventLogService.append()` calls. The `actor` strings are: "ORCHESTRATOR", "MTN_ADAPTER", "ORANGE_ADAPTER", "WEBHOOK_DOUBLE_CHECK", "MTN_POLLER", "ORANGE_POLLER".

### Pitfall 3: durationMs scope in fraud evaluation

**What goes wrong:** `FraudScoringService.evaluate()` has three return paths. If `start` is declared before the first branch, `durationMs` computed at each branch is correct. But if `start` is declared inside a branch, it misses the upstream work.

**How to avoid:** Declare `long start = System.currentTimeMillis()` as the first line of `evaluate()`.

### Pitfall 4: Orange webhook log emitted outside the tx lookup lambda

**What goes wrong:** The LOG-BUS-03 Orange event needs `transactionId`. If the log is emitted before `transactionRepository.findByPayToken()`, `transactionId` is null.

**How to avoid:** Emit the structured LOG-BUS-03 event inside the `ifPresentOrElse` present-lambda where `txId` is available. For the not-found case, emit a WARN with only `payToken` (not required by LOG-BUS-03 but useful for debugging).

### Pitfall 5: RestRequestInterceptor double-logging

**What goes wrong:** `RestRequestInterceptor` already emits INFO/ERROR logs for every HTTP call (lines 66-83, 112). Adding LOG-BUS-06 events in the Client classes will produce two log lines per call.

**How to avoid:** This is acceptable. The Interceptor log is INFO with raw method/url/status/latency (not `kv()`); LOG-BUS-06 adds the structured `externalService` + `operation` + `externalLatencyMs` fields queryable in Loki. Both serve different purposes. Document in code comments that both co-exist intentionally.

### Pitfall 6: ReconciliationService totals across providers

**What goes wrong:** `runForDate()` currently does not aggregate totals. Emitting a summary log with `totalChecked=0` or reading stale database rows is incorrect.

**How to avoid:** Accumulate totals in `runForDate()` by changing `runForProviderAndDate()` to return totals, or by maintaining int accumulators passed by reference.

---

## Code Examples

### LOG-BUS-01 Pattern (PaymentOrchestrator)

```java
// Source: LoggingFilter.java pattern
import static net.logstash.logback.argument.StructuredArguments.kv;

// At method entry:
long start = System.currentTimeMillis();

// At success return:
log.info("Payment initiated",
    kv("operation", "initiate_payment"),
    kv("tenantId", TenantContext.get()),      // tenantRef UUID per decision 15-01
    kv("transactionId", tx.getTransactionId()),
    kv("provider", provider.name()),
    kv("msisdn", msisdnLast4(request.msisdn())),
    kv("durationMs", System.currentTimeMillis() - start),
    kv("status", "SUCCESS"));

// msisdnLast4 helper — inline or private static method:
private static String msisdnLast4(String msisdn) {
    if (msisdn == null || msisdn.length() < 4) return "****";
    return msisdn.substring(msisdn.length() - 4);
}
```

### LOG-BUS-02 Pattern (at caller sites)

```java
// Source: WebhookTransitionService.applyFinalTransition() — emit just before eventLogService.append()
log.info("Transaction state changed",
    kv("operation", "transaction_state_change"),
    kv("transactionId", tx.getTransactionId()),
    kv("fromState", TransactionStatus.PROCESSING.name()),
    kv("toState", target.name()),
    kv("actor", "WEBHOOK_DOUBLE_CHECK"));
```

### LOG-BUS-03 Pattern (MTN)

```java
// Source: MtnMoMoPort.processCallback() — replace existing log.info at line 172
log.info("Webhook received",
    kv("operation", "webhook_received"),
    kv("provider", "MTN"),
    kv("transactionId", payload.getExternalId()),
    kv("externalReference", payload.getFinancialTransactionId()),
    kv("providerStatus", payload.getStatus()));
```

### LOG-BUS-04 Pattern (success case)

```java
// Source: WebhookDeliveryService.attemptDeliveryInternal() — replace line 184 log
log.info("Webhook delivered",
    kv("operation", "webhook_delivery"),
    kv("transactionId", delivery.getTransactionId()),
    kv("tenantId", tenant.getTenantRef()),
    kv("durationMs", deliveryDurationMs),   // measured around restTemplate.exchange()
    kv("httpStatus", httpStatus),
    kv("status", "SUCCESS"),
    kv("retryCount", delivery.getAttemptCount()));
```

### LOG-BUS-05 Pattern

```java
// Source: FraudScoringService.evaluate() — emitted just before each return
// Blocked case (WARN):
log.warn("Fraud evaluation blocked",
    kv("operation", "fraud_evaluation"),
    kv("transactionId", cmd.transactionId()),
    kv("riskScore", riskScore),
    kv("blocked", true),
    kv("durationMs", System.currentTimeMillis() - start));

// Allowed case (INFO):
log.info("Fraud evaluation allowed",
    kv("operation", "fraud_evaluation"),
    kv("transactionId", cmd.transactionId()),
    kv("riskScore", riskScore),
    kv("blocked", false),
    kv("durationMs", System.currentTimeMillis() - start));
```

### LOG-BUS-06 Pattern (MtnMoMoClient)

```java
// Source: Client class public method — example requestToPay()
private static final Logger log = LoggerFactory.getLogger(MtnMoMoClient.class);

public void requestToPay(String referenceId, RequestToPayRequest request, String bearerToken) {
    // ... headers setup ...
    long start = System.currentTimeMillis();
    ResponseEntity<Void> response = makeHttpRequest(url, HttpMethod.POST, request, Void.class, headers);
    log.info("Provider HTTP call",
        kv("externalService", "MTN_MOMO"),
        kv("operation", "requestToPay"),
        kv("externalLatencyMs", System.currentTimeMillis() - start),
        kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));
    // ... existing exception check ...
}
```

### LOG-BUS-07 Pattern

```java
// Source: ReconciliationService.runForDate() — emitted after provider loop
log.info("Reconciliation run completed",
    kv("operation", "reconciliation_run"),
    kv("date", reportDate.toString()),
    kv("totalChecked", totalCheckedAcrossProviders),
    kv("discrepancyCount", totalDiscrepanciesAcrossProviders),
    kv("durationMs", System.currentTimeMillis() - start),
    kv("status", "SUCCESS"));
```

---

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| `log.info("MTN callback received: externalId={}, status={}", ...)` | `log.info("...", kv("operation", ...), kv(...))` | Fields become top-level JSON, queryable in Loki |
| `log.debug("Fraud evaluation allowed: ...")` | `log.info(...)` with `kv()` | Fraud events visible in production INFO logs |
| No duration on adapter calls | `externalLatencyMs` via `System.currentTimeMillis()` | Provider latency queryable in Loki/Grafana |

---

## Open Questions

1. **LOG-BUS-01 tenantId field value**
   - What we know: Decision 15-01 uses `tenantRef` (UUID) for the MDC `tenantId` key. `TenantContext.get()` returns `tenantRef`. `PaymentOrchestrator.initiate()` receives `Long tenantId`.
   - What's unclear: Should the `tenantId` kv() field in the LOG-BUS-01 event be the Long DB PK or the UUID tenantRef?
   - Recommendation: Use `TenantContext.get()` (UUID tenantRef) for consistency with MDC and decision 15-01. This is already populated on the request thread by `ApiKeyAuthenticationFilter`.

2. **LOG-BUS-04 tenantId in Quartz retry path**
   - What we know: `WebhookDeliveryService.attemptDelivery()` (called by Quartz job) runs on a non-request thread. `TenantContext` is not populated. The `tenant` parameter has `tenant.getTenantRef()`.
   - What's unclear: Is `tenant.getTenantRef()` always populated on the Tenant entity? (Likely yes — it's set at tenant creation in `TenantService`.)
   - Recommendation: Use `tenant.getTenantRef()` in `attemptDeliveryInternal()` for the `tenantId` field since `tenant` is always in scope.

3. **LOG-BUS-02 poller caller sites** — RESOLVED
   - Confirmed: `MtnStatusPollerJob` has `applyTransition()` at lines 82 and 96; `OrangeStatusPollerJob` at lines 89 and 103. Both also call `eventLogService.append()` at the same points.
   - LOG-BUS-02 log calls must be added at all 4 poller sites in addition to the orchestrator and webhook transition service sites.
   - Actor strings: `"MTN_POLLER"` and `"ORANGE_POLLER"`.

---

## Sources

### Primary (HIGH confidence)

- Codebase: `LoggingFilter.java` — canonical `kv()` pattern used in production
- Codebase: `PaymentOrchestrator.java` — `System.currentTimeMillis()` timing pattern
- Codebase: `TransactionService.java` — MDC.put("transactionId", ...) confirmed as camelCase (decision 15-02)
- Codebase: `ApiKeyAuthenticationFilter.java` — MDC.put("tenantId", tenantRef) confirms decision 15-01
- Codebase: `FraudScoringService.java` — all three return paths identified
- Codebase: `WebhookDeliveryService.java` — all four log sites identified
- Codebase: `ReconciliationService.java` — cross-provider aggregation gap identified
- Codebase: `MtnMoMoClient.java`, `OrangeMoneyClient.java` — all public methods enumerated
- Codebase: `MtnMoMoPort.java`, `OrangeMoneyPort.java` — webhook receipt paths identified
- `requirements/logging.md` — `kv()` pattern confirmed as official standard

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; all patterns verified in codebase
- Architecture: HIGH — exact insertion points identified per-file
- Pitfalls: HIGH — all pitfalls are concrete code observations, not speculation

**Research date:** 2026-03-27
**Valid until:** 2026-04-27 (stable codebase; no fast-moving dependencies)
