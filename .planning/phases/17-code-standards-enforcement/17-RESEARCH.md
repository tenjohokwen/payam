# Phase 17: Code Standards Enforcement - Research

**Researched:** 2026-03-27
**Domain:** Java SLF4J structured logging, PII sanitization, code-flow log removal
**Confidence:** HIGH

---

## Summary

Phase 17 has a narrow, well-bounded scope: audit every log call in the codebase and bring them into compliance with three requirements. The codebase already uses `net.logstash.logback.argument.StructuredArguments.kv()` in all business-critical paths (orchestrator, webhook, pollers, delivery service, clients). The violations are concentrated in older infrastructure and security service files that predate the logging standards work.

LOG-CODE-01 (no string interpolation) is the largest item: approximately 90 log calls across ~30 files still use SLF4J `{}` placeholder style rather than `kv()`. Most are diagnostic/infrastructure logs (cache refresh counts, job summaries, security service messages). The question for each is whether the data should be a structured field in a business-meaningful event, or whether the log should simply be removed (LOG-CODE-02 concern).

LOG-CODE-02 (no code-flow logs) has a small number of clear violations, mostly in `RequestIdProvider.java` (hash-prefixed debug markers) and `OrangeMoneyPort`/`MtnMoMoPort` (post-event redundant lines). The pollers and controllers have a few "transitioned to X" or "published for Y" lines that duplicate information already in the structured LOG-BUS-02 events.

LOG-CODE-03 (BodySanitizer coverage) is the most important to get right. `BodySanitizer.SENSITIVE_KEYS` currently does NOT include `payToken`, `msisdn`, `merchantKey`, `merchant_key`, `notif_token`, or `accessToken` (spelled `access_token` in Orange's token response). The `RestRequestInterceptor` logs full response bodies at INFO level — if a response body contains a payToken or an access_token, it will appear in logs unsanitized unless BodySanitizer covers those fields.

**Primary recommendation:** Work file-by-file. Remove code-flow logs first (they shrink the LOG-CODE-01 inventory). Then convert remaining `{}` placeholder logs to `kv()` structured events or remove them. Finally, expand `BodySanitizer.SENSITIVE_KEYS` to cover payment-domain fields and confirm `RestRequestInterceptor` routes all bodies through it.

---

## Standard Stack

The project already has the correct stack in place. No new dependencies are needed.

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| SLF4J | (Spring Boot managed) | Logging API | Required by standard |
| Logback | (Spring Boot managed) | Logger implementation | Required by standard |
| logstash-logback-encoder | 7.4 | JSON encoding + `kv()` structured arguments | Phase 14 decision |
| `net.logstash.logback.argument.StructuredArguments.kv()` | (part of logstash-logback-encoder) | Structured field arguments | Phase 14 decision |

### What kv() Does
`kv("field", value)` passed as a vararg to `log.info("message", kv(...), kv(...))` causes each pair to appear as a top-level JSON field in the Loki output via `<arguments/>` provider in `LoggingEventCompositeJsonEncoder`. This is the canonical pattern already in use in business event logs.

**Installation:** No changes needed — library is already on classpath.

---

## Architecture Patterns

### How Structured Logging Is Used in This Codebase

Every log call in the codebase falls into one of four categories:

1. **Correct: structured business event** — message is an event name, all contextual data as `kv()` args
   ```java
   // Source: PaymentOrchestrator.java, WebhookDeliveryService.java, etc.
   log.info("Payment initiated",
       kv("operation", "initiate_payment"),
       kv("transactionId", tx.getTransactionId()),
       kv("status", "SUCCESS"));
   ```

2. **Violation (LOG-CODE-01): SLF4J placeholder interpolation** — contextual data embedded in the message string
   ```java
   // Source: OrangeStatusPollerJob.java:62
   log.info("OrangeStatusPollerJob: found {} stuck PROCESSING transactions", stuck.size());
   // Correct form:
   log.info("Poller scan",
       kv("operation", "orange_poller_scan"),
       kv("stuckCount", stuck.size()));
   ```

3. **Violation (LOG-CODE-02): Code-flow log** — describes what the code is doing, not a business event
   ```java
   // Source: OrangeMoneyPort.java:205
   log.info("WebhookReceivedEvent published for transactionId={}", txId);
   // This should be removed — the structured webhook_received event on line 189-194 already covers this
   ```

4. **Violation (LOG-CODE-03): PII in log** — sensitive data appears in log output
   ```java
   // Source: PhoneNumberValidator.java:32
   log.warn("Invalid phone number: {}", phoneNumber.getPhone(), e);
   // phoneNumber.getPhone() is a full MSISDN
   ```

### Anti-Patterns to Avoid
- **Removing all `{}` logs without checking if the data is needed:** Some `{}` logs contain useful metrics (e.g., stuck transaction count) that should become structured fields, not simply deleted.
- **Adding `kv()` to code-flow logs instead of deleting them:** A log line that says "entering method" is not made better by adding structure — it should be removed.
- **Sanitizing at log call sites instead of in BodySanitizer:** Per-call sanitization is error-prone. All body sanitization belongs in `BodySanitizer`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON body sanitization | Per-call redaction logic | `BodySanitizer.sanitize()` | Already exists, recursive, handles nested JSON |
| MSISDN masking | Inline substring logic | `msisdnLast4()` helper (already in PaymentOrchestrator) | Pattern is already established |

**Key insight:** `BodySanitizer` is the single point of control for body redaction. Expand its `SENSITIVE_KEYS` set rather than adding per-call redaction at individual log sites.

---

## Common Pitfalls

### Pitfall 1: Conflating LOG-CODE-01 and LOG-CODE-02
**What goes wrong:** When auditing `{}` placeholder logs, some will look convertible to `kv()` when they should actually be deleted entirely (they are code-flow logs).
**Why it happens:** Both violations look like "bad log calls" so reviewers batch them together.
**How to avoid:** For each `{}` log, first ask "Is this a business event?" If the answer is no (it describes code mechanics, counts, or transitions already captured elsewhere), delete it. Only convert to `kv()` if it represents a genuinely observable business event.
**Warning signs:** Message strings containing words like "found", "loaded", "running", "completed", "still", "attempting", "refreshed", "initialized", "created", "activated", "deleted".

### Pitfall 2: Double-logging after structured event
**What goes wrong:** A structured `kv()` business event is added, but the original `{}` placeholder log is not removed, creating duplicate log entries.
**Where it happens now:** `OrangeStatusPollerJob.java:124` and `MtnStatusPollerJob.java:117` each have a `log.info("Transaction {} transitioned to {} via polling")` AFTER the structured `log.info("Transaction state changed", kv(...))` block. The `WebhookTransitionService.java:112` has `log.info("Double-check: transactionId={} transitioned to {}")` after the structured block.
**How to avoid:** When removing redundant lines, grep for the transactionId in surrounding lines to find the paired structured event.

### Pitfall 3: BodySanitizer does not cover payment-domain fields
**What goes wrong:** `RestRequestInterceptor` logs response bodies at INFO level. Orange's token response contains `access_token`. Orange's webhook payload contains `payToken` and `msisdn`. None of these are in `BodySanitizer.SENSITIVE_KEYS` currently.
**Why it happens:** `BodySanitizer` was written for user account endpoints (passwords, OTPs, JWTs). Payment-specific fields were added later and not retrofitted.
**How to avoid:** Add `payToken`, `msisdn`, `merchant_key`, `merchantKey`, `access_token`, `accessToken`, `notif_token`, `notifToken` to `SENSITIVE_KEYS` before any testing.
**Warning signs:** Run a grep for `payToken` in log output in a test — if it appears unredacted, the gap is confirmed.

### Pitfall 4: PhoneNumberValidator logs full MSISDN
**What goes wrong:** `PhoneNumberValidator.java:32` logs `phoneNumber.getPhone()` in a WARN on validation failure. This is a full MSISDN.
**Why it happens:** The validator predates the logging standard.
**How to avoid:** Replace with a non-PII log or remove entirely (validation framework provides the error path context).

### Pitfall 5: RequestIdProvider logs are code-flow with decorative prefixes
**What goes wrong:** `RequestIdProvider.java:47,50,55` contain log lines with `"####"` prefix markers. These are code-flow logs (they describe "creating", "found in request") and contain the reqId value inline.
**How to avoid:** Delete all three lines. The MDC put/remove operations do not need logging — their effect is observable in MDC fields on subsequent log lines.

---

## Code Examples

### Converting a placeholder log to structured event

```java
// BEFORE (LOG-CODE-01 violation)
log.info("OrangeStatusPollerJob: found {} stuck PROCESSING transactions", stuck.size());

// AFTER (structured, business-observable)
log.info("Poller scan",
    kv("operation", "orange_poller_scan"),
    kv("stuckCount", stuck.size()));
```

### Removing a code-flow log

```java
// BEFORE — LOG-CODE-02 violation — remove this line entirely
log.info("WebhookReceivedEvent published for transactionId={}", txId);
// The structured webhook_received event two lines above already captures this fact
```

### Removing a duplicate post-transition log

```java
// BEFORE — redundant after structured log.info("Transaction state changed", kv(...)) block
log.info("Transaction {} transitioned to {} via polling", tx.getTransactionId(), next);

// AFTER — delete the line; the structured event carries all the same data
```

### BodySanitizer SENSITIVE_KEYS expansion

```java
// Source: BodySanitizer.java
private static final Set<String> SENSITIVE_KEYS = Set.of(
    "password", "newPassword", "oldPassword", "currentPassword",
    "otp", "otpCode", "verificationCode", "activationKey", "resetKey",
    "token", "accessToken", "refreshToken", "jwt",
    "cvv", "cvc", "pin", "apiKey",
    // Payment-domain additions for LOG-CODE-03:
    "payToken",        // Orange payToken — bearer-like value
    "notifToken",      // Orange notif_token (also matched by "token" substring already, but explicit)
    "notif_token",     // snake_case variant in JSON
    "merchant_key",    // Orange PayRequest field
    "merchantKey",     // camelCase variant
    "msisdn"           // Full MSISDN is PII under Cameroon data protection norms
);
```

Note: `isSensitive()` uses `lowerKey.contains(s.toLowerCase())` for substring matching, so `"token"` already catches `"accessToken"`, `"payToken"`, `"notifToken"`. The `"msisdn"` addition is the material new coverage.

---

## File-by-File Audit

### LOG-CODE-01 Violations (placeholder `{}` interpolation)

**Payment domain (highest priority — these are in production hot paths)**

| File | Line(s) | Violation | Disposition |
|------|---------|-----------|-------------|
| `PaymentOrchestrator.java` | 128 | `"Unknown MSISDN prefix: msisdn={}"` with full msisdn | Convert to `kv()` WITHOUT msisdn (drop PII), or remove |
| `PaymentOrchestrator.java` | 139 | `"Payment already in progress: tenantId={}, idempotencyKey={}"` | Convert to `kv()` |
| `PaymentOrchestrator.java` | 145 | `"Replaying cached idempotency response: tenantId={}, idempotencyKey={}"` | Convert to `kv()` |
| `PaymentOrchestrator.java` | 390 | `"Failed to apply FAILED transition: transactionId={}"` | Convert to `kv()` |
| `OrangeStatusPollerJob.java` | 62 | `"OrangeStatusPollerJob: found {} stuck PROCESSING transactions"` | Convert to structured or code-flow? (count = observable metric) → Convert |
| `OrangeStatusPollerJob.java` | 71 | `"PROCESSING transaction {} has no payToken — skipping poll"` | Convert to `kv()` |
| `OrangeStatusPollerJob.java` | 79-80 | `"payToken expired for transaction {}"` | Convert to `kv()` |
| `OrangeStatusPollerJob.java` | 88 | `"Transaction {} exceeded max poll attempts — marking FAILED"` | Delete — covered by structured state_change log below |
| `OrangeStatusPollerJob.java` | 124 | `"Transaction {} transitioned to {} via polling"` | Delete — duplicate of structured state_change log |
| `OrangeStatusPollerJob.java` | 127 | `"Transaction {} still PENDING after poll attempt {}"` | Convert to `kv()` or delete (debug-level, low value) |
| `OrangeStatusPollerJob.java` | 130 | `"Orange API error polling transaction {}"` | Convert to `kv()` |
| `MtnStatusPollerJob.java` | 64 | `"MtnStatusPollerJob: found {} stuck PROCESSING transactions"` | Same as Orange equivalent |
| `MtnStatusPollerJob.java` | 74 | `"PROCESSING MTN transaction {} has no providerRef — skipping poll"` | Convert to `kv()` |
| `MtnStatusPollerJob.java` | 81 | `"Transaction {} exceeded max poll attempts — marking FAILED"` | Delete — duplicate |
| `MtnStatusPollerJob.java` | 117 | `"Transaction {} transitioned to {} via MTN polling"` | Delete — duplicate |
| `MtnStatusPollerJob.java` | 120 | `"Transaction {} still PENDING after MTN poll attempt {}"` | Convert or delete |
| `MtnStatusPollerJob.java` | 123 | `"MTN API error polling transaction {}"` | Convert to `kv()` |
| `OrangeMoneyPort.java` | 180-181 | `"Orange webhook notifToken mismatch"` with expected/got values | Convert to `kv()` — but do NOT log the token values, only boolean |
| `OrangeMoneyPort.java` | 205 | `"WebhookReceivedEvent published for transactionId={}"` | Delete — code flow, covered by LOG-BUS-03 structured event |
| `OrangeMoneyPort.java` | 206 | `"Orange webhook: no transaction found for payToken={}"` | Convert to `kv()` — omit payToken value (PII/sensitive) |
| `OrangeMoneyPort.java` | 225 | `"payToken expired for transaction={}, age={}min"` | Convert to `kv()` — omit payToken |
| `MtnMoMoPort.java` | 170 | `"MTN callback duplicate suppressed: externalId={}"` | Convert to `kv()` |
| `MtnMoMoPort.java` | 199 | `"WebhookReceivedEvent published for MTN transactionId={}"` | Delete — code flow |
| `WebhookTransitionService.java` | 70-71 | `"Double-check: transactionId={} already in terminal state {}"` | Convert to `kv()` |
| `WebhookTransitionService.java` | 112 | `"Double-check: transactionId={} transitioned to {}"` | Delete — duplicate of structured state_change log above |
| `WebhookDeliveryService.java` | 85 | `"No webhook URL configured for tenantId={}"` | Convert to `kv()` |
| `WebhookDeliveryService.java` | 115 | `"Tenant not found for delivery tenantId={}"` | Convert to `kv()` |
| `WebhookDeliveryService.java` | 145-146 | `"Failed to serialize outbound webhook payload for transactionId={}"` | Convert to `kv()` |
| `WebhookDeliveryService.java` | 166-167 | `"HMAC signing failed for transactionId={}"` | Convert to `kv()` |
| `WebhookDeliveryService.java` | 237-238 | `"Max delivery attempts ({}) reached for transactionId={}"` | Convert to `kv()` |
| `WebhookDoubleCheckHandler.java` | 46-47 | `"Double-check triggered: transactionId={}, provider={}"` | Convert to `kv()` |
| `WebhookDoubleCheckHandler.java` | 59 | `"Circuit open during double-check for transactionId={}"` | Convert to `kv()` |
| `WebhookDoubleCheckHandler.java` | 63 | `"Double-check failed for transactionId={}"` | Convert to `kv()` |
| `WebhookDoubleCheckHandler.java` | 69 | `"Double-check: transactionId={} still PROCESSING"` | Convert to `kv()` |
| `WebhookDeliveryJob.java` | 37 | `"WebhookDeliveryJob: {} pending deliveries to process"` | Convert to `kv()` or delete (job bookkeeping) |
| `WebhookDeliveryJob.java` | 42-43 | `"Delivery attempt failed for transactionId={}"` | Convert to `kv()` |

**Infrastructure / Reconciliation (medium priority)**

| File | Line(s) | Violation | Disposition |
|------|---------|-----------|-------------|
| `ReconciliationJob.java` | 36 | `"ReconciliationJob: running for date {}"` | Delete — code flow, LOG-BUS-07 structured event covers completion |
| `ReconciliationJob.java` | 39 | `"ReconciliationJob: completed successfully for date {}"` | Delete — code flow |
| `ReconciliationJob.java` | 42 | `"ReconciliationJob: fatal error for date {}"` | Convert to `kv()` |
| `ReconciliationService.java` | 78 | `"ReconciliationService: starting reconciliation for date={}"` | Delete — code flow |
| `ReconciliationService.java` | 89-90 | `"ReconciliationService: unexpected error reconciling provider={}"` | Convert to `kv()` |
| `ReconciliationService.java` | 94 | `"ReconciliationService: completed reconciliation for date={}"` | Delete — code flow (LOG-BUS-07 below covers completion) |
| `ReconciliationService.java` | 108 | `"no ProviderReportPort registered for provider={}"` | Convert to `kv()` |
| `ReconciliationService.java` | 128-129 | `"found {} transactions to reconcile"` | Delete — code flow |
| `ReconciliationService.java` | 154-155 | `"checked={}, matched={}, discrepancies={}"` | Delete — code flow; per-provider detail already computable from LOG-BUS-07 totals |
| `OrangeReportAdapter.java` | 53-54 | `"Orange reconciliation: failed to fetch status for providerRef={}"` | Convert to `kv()` |
| `MtnReportAdapter.java` | 44-45 | `"MTN reconciliation: failed to fetch status for providerRef={}"` | Convert to `kv()` |
| `IdempotencyService.java` | 64 | `"Redis unavailable for idempotency check"` | Convert to `kv()` |
| `IdempotencyService.java` | 80 | `"Redis unavailable for idempotency store"` | Convert to `kv()` |
| `IdempotencyService.java` | 105 | `"Successfully reserved idempotency key in PostgreSQL"` | Delete — code flow |

**Alert service (medium priority)**

| File | Line(s) | Violation | Disposition |
|------|---------|-----------|-------------|
| `AlertEvaluationService.java` | 59-60 | `"Alert threshold breached: metric={} actual={} threshold={}"` | Convert to `kv()` |
| `AlertNotificationListener.java` | 44-47 | `"ALERT FIRED: metric={} actual={} threshold={}"` | Convert to `kv()` |
| `AlertNotificationListener.java` | 72 | `"Failed to send alert email for metric {}"` | Convert to `kv()` |
| `AlertRuleCache.java` | 49 | `"Alert rule cache refreshed: {} rules loaded"` | Delete — code flow / cache bookkeeping |
| `FeeRuleCache.java` | 55 | `"Fee rule cache refreshed: {} rules loaded"` | Delete — code flow |
| `FraudRuleCache.java` | 53 | `"Fraud rule cache refreshed: {} rules loaded"` | Delete — code flow |
| `MsisdnPrefixRouteCache.java` | 52 | `"MSISDN prefix route cache refreshed: {} routes loaded"` | Delete — code flow |
| `VelocityCheckService.java` | 60 | `"VelocityCheckService initialized with LettuceBasedProxyManager at {}:{}"` | Delete — startup code flow |

**API / Filter / Validation (lower priority — lower traffic or debug level)**

| File | Line(s) | Violation | Disposition |
|------|---------|-----------|-------------|
| `ApiKeyAuthenticationFilter.java` | 110-111 | `"API key authentication failed for prefix [{}]"` | Convert to `kv()` |
| `OrangeCallbackController.java` | 97 | `"Orange callback HMAC verification error: {}"` | Convert to `kv()` |
| `OrangeCallbackController.java` | 109 | `"Orange webhook duplicate suppressed: payToken={}"` | Convert to `kv()` — drop payToken value |
| `OrangeCallbackController.java` | 118 | `"Orange callback processing failed, payToken={}"` | Convert to `kv()` — drop payToken value |
| `MtnCallbackController.java` | 55 | `"MTN callback processing failed: {}"` | Convert to `kv()` |
| `OrangeIpWhitelistInterceptor.java` | 70 | `"Orange callback rejected — IP not whitelisted: {}"` | Convert to `kv()` |
| `MtnIpWhitelistInterceptor.java` | 70 | `"MTN callback rejected — IP not whitelisted: {}"` | Convert to `kv()` |
| `MsisdnRouter.java` | 66 | `"MSISDN prefix '{}' not found in DB route table"` | Convert to `kv()` — drop MSISDN (PII) |
| `CamPhoneValidator.java` | 70 | `"Phone validation failed: {} - {}"` | Convert to `kv()` without phone value |
| `PhoneNumberValidator.java` | 32 | `"Invalid phone number: {}"` with full phone | Convert to `kv()` WITHOUT phone value (PII) |
| `BodySanitizer.java` | 50 | `"Failed to parse body as JSON for sanitization: {}"` | Convert to `kv()` |
| `RestRequestInterceptor.java` | 50-54 | Full request body at DEBUG | No change needed (DEBUG level, sanitized before this call) |
| `RestRequestInterceptor.java` | 66-72 | Full response headers at ERROR | Convert to `kv()` — verify no token in headers |
| `RestRequestInterceptor.java` | 82-88 | Full response body at INFO | Must verify BodySanitizer covers response before this path |
| `RestRequestInterceptor.java` | 112 | `"RESPONSE method: {} url: {} status: {} latency: {}"` | Convert to `kv()` |
| `RestRequestInterceptor.java` | 128 | `"Error occurred while trying to set txnId for http request with headers: {}"` | Convert to `kv()` — do NOT log headers (may contain auth tokens) |

**Security services (lower priority — not in payment hot path)**

| File | Line(s) | Violation | Disposition |
|------|---------|-----------|-------------|
| `RequestIdProvider.java` | 47 | `"################ Creating new request id... reqId: {}"` | Delete — code flow + decorative prefix |
| `RequestIdProvider.java` | 50 | `"############# requestId '{}' found in request"` | Delete — code flow |
| `RequestIdProvider.java` | 55 | `"################### About to remove reqId from thread"` | Delete — code flow, no data |
| `LoginAttemptsService.java` | 132-295 | Multiple `{}` log calls with LOG_TAG | Convert prominent ones to `kv()`, delete pure code-flow ones |
| `AdminLoginResource.java` | 49 | `"ADMIN ACTION: '{}' is clearing login-attempt locks for user '{}'"` | Convert to `kv()` |
| `SecurityAuditListener.java` | 59,64,102,107 | `"AUDIT_TRAIL: {}"` lines | These use object toString() — review for PII |
| `AccountChangeEventListener.java` | 31,58,63 | `"Account change event received: {}"` | Convert to `kv()` |
| `AuthorizationFailureListener.java` | 30 | `"The current user does not have permission. Client metadata: '{}'"` | Convert to `kv()` |
| `UserAdminService.java` | 46,91,105,112,136 | Multiple `{}` placeholder logs | Convert meaningful ones, delete code-flow ones |
| `UserProfileService.java` | 60,112,160,190,224 | `"Changed Information/email/password/phone/2FA for User: {}"` lines | Convert to `kv()` without user data |
| `UserRegistrationService.java` | 73,86,91 | `"Created Information for User: {}"` etc. | Convert to `kv()` |
| `LoadUserByUserNameService.java` | 33 | `"Authenticating {}"` with loginId | Delete — code flow + PII (loginId = email/username) |
| `PasswordResetService.java` | 64 | `"Reset user password for reset key {}"` | Convert to `kv()` — omit the key value (token-like) |
| `MailService.java` | 46,62,68 | Multiple debug logs with email addresses | Convert to `kv()` — omit email content |

---

## LOG-CODE-02 Violations (Code-Flow Logs)

Code-flow logs to delete entirely (not convert):

| File | Line | Message | Reason to Delete |
|------|------|---------|-----------------|
| `OrangeMoneyPort.java` | 205 | `"WebhookReceivedEvent published for transactionId={}"` | The structured `webhook_received` event on line 189 already covers this. |
| `MtnMoMoPort.java` | 199 | `"WebhookReceivedEvent published for MTN transactionId={}"` | Same — covered by structured `webhook_received` on line 174. |
| `OrangeStatusPollerJob.java` | 124 | `"Transaction {} transitioned to {} via polling"` | Covered by structured `transaction_state_change` log above it. |
| `MtnStatusPollerJob.java` | 117 | `"Transaction {} transitioned to {} via MTN polling"` | Covered by structured `transaction_state_change` log above it. |
| `WebhookTransitionService.java` | 112 | `"Double-check: transactionId={} transitioned to {}"` | Covered by structured `transaction_state_change` log above it. |
| `ReconciliationJob.java` | 36 | `"ReconciliationJob: running for date {}"` | Pure job lifecycle — no business value. Completion is already in LOG-BUS-07. |
| `ReconciliationJob.java` | 39 | `"ReconciliationJob: completed successfully for date {}"` | Same. |
| `ReconciliationService.java` | 78 | `"ReconciliationService: starting reconciliation for date={}"` | Job start is code flow. |
| `ReconciliationService.java` | 94 | `"ReconciliationService: completed reconciliation for date={}"` | Covered by structured `reconciliation_run` event. |
| `ReconciliationService.java` | 128-129 | `"found {} transactions to reconcile"` | Implementation detail. |
| `ReconciliationService.java` | 154-155 | `"checked={}, matched={}, discrepancies={}"` | Per-provider detail already redundant with reconciliation_run totals. |
| `IdempotencyService.java` | 105 | `"Successfully reserved idempotency key in PostgreSQL"` | Code flow — REQUIRES_NEW success path has no business observer. |
| `AlertRuleCache.java` | 49 | `"Alert rule cache refreshed: {} rules loaded"` | Cache lifecycle, not a business event. |
| `FeeRuleCache.java` | 55 | `"Fee rule cache refreshed: {} rules loaded"` | Same. |
| `FraudRuleCache.java` | 53 | `"Fraud rule cache refreshed: {} rules loaded"` | Same. |
| `MsisdnPrefixRouteCache.java` | 52 | `"MSISDN prefix route cache refreshed: {} routes loaded"` | Same. |
| `VelocityCheckService.java` | 60 | `"VelocityCheckService initialized with LettuceBasedProxyManager at {}:{}"` | Startup lifecycle. |
| `RequestIdProvider.java` | 47 | `"################ Creating new request id..."` | Pure code flow + decorative prefix. |
| `RequestIdProvider.java` | 50 | `"############# requestId '{}' found in request"` | Same. |
| `RequestIdProvider.java` | 55 | `"################### About to remove reqId from thread"` | Same. |
| `LoadUserByUserNameService.java` | 33 | `"Authenticating {}"` | Code flow, and loginId is PII. |

---

## LOG-CODE-03 Violations (PII / Sensitive Data in Logs)

### BodySanitizer — Current SENSITIVE_KEYS Coverage

Current set (HIGH confidence — read directly from source):
```
password, newPassword, oldPassword, currentPassword,
otp, otpCode, verificationCode, activationKey, resetKey,
token, accessToken, refreshToken, jwt,
cvv, cvc, pin, apiKey
```

**Key point:** `isSensitive()` does substring matching case-insensitively. So `"token"` already catches any field whose name contains "token" — including `payToken`, `notifToken`, `access_token` (no — wait: "access_token" has underscore but `lowerKey.contains("token")` is true for "access_token" since it contains "token"). Let's verify:

- `"payToken"` → lowerKey = `"paytoken"` → contains `"token"` → YES, caught by existing set
- `"notif_token"` → lowerKey = `"notif_token"` → contains `"token"` → YES, caught
- `"merchant_key"` → lowerKey = `"merchant_key"` → does NOT contain any key in the set → NOT caught
- `"merchantKey"` → lowerKey = `"merchantkey"` → does NOT contain any key in the set → NOT caught
- `"msisdn"` → lowerKey = `"msisdn"` → does NOT contain any key in the set → NOT caught
- `"accessToken"` → lowerKey = `"accesstoken"` → contains `"accesstoken"` which IS in the set → YES, caught
- `"access_token"` → lowerKey = `"access_token"` → contains `"token"` → YES, caught

**Conclusion:** The `token` substring catches most token-like fields. The critical gaps are:
1. `"msisdn"` — full MSISDN is PII (phone number)
2. `"merchant_key"` / `"merchantKey"` — Orange merchant key, sensitive credential

### Direct PII in Log Call Arguments

| File | Line | PII Present | Fix |
|------|------|------------|-----|
| `PhoneNumberValidator.java` | 32 | `phoneNumber.getPhone()` = full MSISDN | Remove the phone value from the log |
| `OrangeMoneyPort.java` | 206 | `payload.getPayToken()` = token | Caught by BodySanitizer? No — this is not a body log, it's a direct argument. Remove payToken from log. |
| `OrangeCallbackController.java` | 109 | `payload.getPayToken()` = token | Remove payToken from log argument |
| `OrangeCallbackController.java` | 118 | `payload.getPayToken()` = token | Remove payToken from log argument |
| `OrangeMoneyPort.java` | 180-181 | `notifToken`, `payload.getNotifToken()` logged in mismatch warning | Remove token values from log |
| `OrangeMoneyPort.java` | 225 | `transactionId` only, `age.toMinutes()` only — OK | No PII |
| `PaymentOrchestrator.java` | 128 | `request.msisdn()` = full MSISDN | Remove — msisdnLast4() helper exists for this |
| `MsisdnRouter.java` | 66 | `prefix` = MSISDN prefix (partial, not full number) | Low risk, keep prefix OR remove |
| `RestRequestInterceptor.java` | 82-88 | Full response body at INFO — may contain payToken in Orange responses | BodySanitizer covers `token` substring → payToken is caught. BUT `merchant_key` is not. Add to SENSITIVE_KEYS. |

### RestRequestInterceptor Body Logging Analysis

`RestRequestInterceptor.java` logs:
1. Line 50-54 (DEBUG): Request body — only for POST. Contains `PayRequest` which has `merchant_key`. BodySanitizer catches `merchant_key`? Currently NO.
2. Line 66-72 (ERROR): Response body for 4xx/5xx. Contains HTTP headers too — headers may have `Authorization: Bearer <token>`. This is logged at ERROR level. Headers are NOT passed through BodySanitizer.
3. Line 82-88 (INFO): Full response body for 2xx. Orange token response contains `access_token`. BodySanitizer catches `"token"` substring → `"access_token"` is caught. Orange webhook response would have `payToken` → caught by `"token"` substring.

**Critical gap:** Line 128 (`"Error occurred while trying to set txnId for http request with headers: {}"`) logs `httpHeaders` object which may contain `Authorization: Bearer <token>`. Headers are not sanitized. Fix: remove headers from this log call.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `log.info("msg {}", value)` | `log.info("msg", kv("field", value))` | Phase 14-16 | All business events now use kv(); remainder of `{}` usage is the scope of Phase 17 |
| PatternLayoutEncoder | LoggingEventCompositeJsonEncoder | Phase 14 | JSON output with top-level fields from `kv()` |
| No BodySanitizer | BodySanitizer exists for user account paths | Phase 14 | Payment-domain fields not yet covered |

---

## Open Questions

1. **Should the per-provider reconciliation detail logs (ReconciliationService lines 128, 154) be kept as structured events?**
   - What we know: They use `{}` placeholder style. The LOG-BUS-07 structured event only covers the totals, not per-provider breakdown.
   - What's unclear: Whether Grafana users need per-provider reconciliation detail in Loki, or whether it belongs in a separate `reconciliation_provider_run` structured event.
   - Recommendation: Convert to a structured `reconciliation_provider_run` event with `kv()` fields if per-provider querying is needed; otherwise delete as code flow.

2. **RestRequestInterceptor body logging at INFO level for 2xx responses**
   - What we know: Orange token responses and status responses pass through this path. BodySanitizer handles `token` field names recursively.
   - What's unclear: Whether the full response body (even sanitized) at INFO level is desirable — it is high volume.
   - Recommendation: Downgrade 2xx response body log from INFO to DEBUG in `RestRequestInterceptor.java:82`. This is the existing code prior to Phase 14, and the new LOG-BUS-06 structured latency events replace the observability need.

3. **SecurityAuditListener AUDIT_TRAIL object logging**
   - What we know: Lines 59, 64, 102, 107 log `auditTrail` objects using `{}` (toString). The AuditTrail object contents are unknown without checking the class.
   - What's unclear: Whether the AuditTrail toString() includes PII like email or IP.
   - Recommendation: Review `AuditTrail` (or `SecurityAuditTrail`) class toString() before deciding. If it contains PII, convert to `kv()` with only the audit event type and ID.

---

## Sources

### Primary (HIGH confidence)
- Direct file reads: All Java source files listed above were read directly from the codebase
- `BodySanitizer.java` — SENSITIVE_KEYS set read directly, `isSensitive()` logic verified
- `PaymentOrchestrator.java`, `OrangeMoneyPort.java`, `MtnMoMoPort.java` — all log calls inventoried
- `OrangeStatusPollerJob.java`, `MtnStatusPollerJob.java` — all log calls inventoried
- `WebhookTransitionService.java`, `WebhookDeliveryService.java`, `WebhookDoubleCheckHandler.java` — inventoried
- `ReconciliationService.java`, `ReconciliationJob.java` — inventoried
- `RestRequestInterceptor.java` — full file read, body logging paths identified
- `requirements/logging.md` — official logging standard confirmed

### Secondary (MEDIUM confidence)
- `net.logstash.logback.argument.StructuredArguments.kv()` behavior: confirmed from prior phase research and requirements doc

---

## Metadata

**Confidence breakdown:**
- LOG-CODE-01 inventory: HIGH — all files read directly
- LOG-CODE-02 identification: HIGH — business events vs code-flow clearly distinguishable
- LOG-CODE-03 BodySanitizer gaps: HIGH — `isSensitive()` logic read directly, substring matching verified
- RestRequestInterceptor PII risk: HIGH — code read directly
- SecurityAuditListener PII risk: MEDIUM — AuditTrail toString() not read (Open Question 3)

**Research date:** 2026-03-27
**Valid until:** 2026-04-27 (stable domain, no fast-moving library changes)
