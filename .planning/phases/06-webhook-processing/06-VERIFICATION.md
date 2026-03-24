---
phase: 06-webhook-processing
verified: 2026-03-24T10:12:24Z
status: passed
score: 5/5 must-haves verified
---

# Phase 6: Webhook Processing Verification Report

**Phase Goal:** Inbound webhook receivers (Orange POST + MTN PUT), double-check verification, outbound delivery to tenants with retry
**Verified:** 2026-03-24T10:12:24Z
**Status:** passed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Orange POST + MTN PUT endpoints exist on separate paths with IP whitelist + HMAC | VERIFIED | `OrangeCallbackController` @PostMapping `/v1/callbacks/orange`; `MtnCallbackController` @PutMapping `/v1/callbacks/mtn`; `OrangeIpWhitelistInterceptor` + `MtnIpWhitelistInterceptor` registered via `OrangeWebConfig`/`MtnWebConfig`; HMAC guard in `OrangeCallbackController` lines 73–96 |
| 2 | No state transition from webhook alone — provider status API always re-queried first | VERIFIED | `WebhookDoubleCheckHandler.handleWebhookReceived()` calls `orangeMoneyPort.getTransactionStatus()` or `mtnMoMoPort.getTransactionStatus()` before any state change (lines 52–55); `@TransactionalEventListener(AFTER_COMMIT)` ensures event fires only after dedup commit, not inline in the callback controller |
| 3 | Duplicate webhook IDs rejected within 24h dedup TTL window via Redis | VERIFIED | `OrangeCallbackController` lines 100–107: `redis.opsForValue().setIfAbsent(dedupKey, "SEEN", Duration.ofHours(24))`; `MtnMoMoPort.processCallback()` lines 165–170: same pattern with key `webhook:mtn:{externalId}:{status}`; key includes `createtime` for Orange to allow distinct status transitions per payToken |
| 4 | Tenant webhook URL receives HMAC-SHA256-signed event on every final state change | VERIFIED | `WebhookTransitionService.applyFinalTransition()` calls `webhookDeliveryService.enqueue()` after every terminal transition (line 93–99); `WebhookDeliveryService.attemptDeliveryInternal()` computes `javax.crypto.Mac` HmacSHA256 signature as `sha256=<64 hex chars>` in `X-Payam-Signature` header (lines 151–165); `WebhookDeliveryIT` test 1 recomputes and verifies the signature |
| 5 | Failed deliveries retry minimum 3 times with exponential backoff; delivery status queryable | VERIFIED | `MAX_ATTEMPTS = 5` (exceeds minimum of 3); `scheduleRetry()` uses `2^attemptCount` minutes capped at 60; `WebhookDeliveryJob` (Quartz, 1-min interval) picks up rows where `nextRetryAt <= now`; `GET /v1/webhooks/deliveries/{transactionId}` returns delivery log via `WebhookDeliveryResource` |

**Score:** 5/5 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/resources/db/migration/V8__tenant_webhook_url.sql` | Adds webhook_url + webhook_secret to tenant table | VERIFIED | Exists, 6 lines, nullable VARCHAR columns with comments |
| `src/main/resources/db/migration/V9__webhook_delivery_log.sql` | Creates webhook_delivery_log table | VERIFIED | Exists, 29 lines, includes external_reference, attempt_count, next_retry_at, delivered columns + indexes |
| `src/main/java/.../orange/web/OrangeCallbackController.java` | POST /v1/callbacks/orange with HMAC + dedup | VERIFIED | 115 lines, real HMAC logic (javax.crypto.Mac), Redis dedup, delegates to port |
| `src/main/java/.../mtn/web/MtnCallbackController.java` | PUT /v1/callbacks/mtn | VERIFIED | 46 lines, @PutMapping correct, delegates to MtnMoMoPort.processCallback |
| `src/main/java/.../orange/web/OrangeIpWhitelistInterceptor.java` | IP whitelist enforcement for Orange path | VERIFIED | 76 lines, CIDR + exact match, sandbox mode when empty, registered via OrangeWebConfig |
| `src/main/java/.../mtn/web/MtnIpWhitelistInterceptor.java` | IP whitelist enforcement for MTN path | VERIFIED | 76 lines, same pattern, registered via MtnWebConfig |
| `src/main/java/.../orange/web/OrangeWebConfig.java` | Registers Orange interceptor for callback path only | VERIFIED | 26 lines, addPathPatterns("/v1/callbacks/orange") |
| `src/main/java/.../mtn/web/MtnWebConfig.java` | Registers MTN interceptor for callback path only | VERIFIED | 26 lines, addPathPatterns("/v1/callbacks/mtn") |
| `src/main/java/.../webhook/contract/WebhookReceivedEvent.java` | Internal Spring event bridging reception to double-check | VERIFIED | Created (confirmed by SUMMARY + file listing) |
| `src/main/java/.../webhook/service/WebhookDoubleCheckHandler.java` | @TransactionalEventListener AFTER_COMMIT calling provider status API | VERIFIED | 78 lines, @TransactionalEventListener(AFTER_COMMIT), calls provider getTransactionStatus, handles CallNotPermittedException, delegates DB writes to separate bean |
| `src/main/java/.../webhook/service/WebhookTransitionService.java` | @Transactional(REQUIRES_NEW) state transition with PESSIMISTIC_WRITE lock | VERIFIED | 114 lines, @Transactional(REQUIRES_NEW), findByTransactionIdForUpdate, applyTransition, eventLogService.append, webhookDeliveryService.enqueue |
| `src/main/java/.../webhook/service/WebhookDeliveryService.java` | enqueue + attempt + retry scheduling | VERIFIED | 230 lines, enqueue() inserts with nextRetryAt=null, inline first attempt, scheduleRetry with exponential backoff (2^n min, cap 60), MAX_ATTEMPTS=5 |
| `src/main/java/.../webhook/service/WebhookDeliveryJob.java` | Quartz retry job | VERIFIED | 47 lines, QuartzJobBean, 1-min interval via WebhookSchedulerConfig, calls findPendingDeliveries + attemptDelivery |
| `src/main/java/.../webhook/api/WebhookDeliveryResource.java` | GET /v1/webhooks/deliveries/{transactionId} | VERIFIED | 38 lines, real implementation, calls deliveryService.getDeliveries |
| `src/main/java/.../tenant/repo/Tenant.java` | webhookUrl + webhookSecret fields | VERIFIED | getWebhookUrl/setWebhookUrl + getWebhookSecret/setWebhookSecret at lines 49, 52, 86–89 |
| `src/main/java/.../security/config/AppEndpoints.java` | Both callback paths in PUBLIC_ENDPOINTS | VERIFIED | Lines 27–29: `/v1/callbacks/mtn` and `/v1/callbacks/orange` in PUBLIC_ENDPOINTS list |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `OrangeCallbackController` | `OrangeMoneyPort.processWebhook` | direct call line 110 | WIRED | Port call exists; response path continues to 200 return |
| `MtnCallbackController` | `MtnMoMoPort.processCallback` | direct call line 43 | WIRED | Port call exists |
| `OrangeMoneyPort.processWebhook` | `WebhookDoubleCheckHandler` | `eventPublisher.publishEvent(WebhookReceivedEvent)` inside `TransactionTemplate` | WIRED | Lines 190–198; TransactionTemplate ensures AFTER_COMMIT listener fires |
| `MtnMoMoPort.processCallback` | `WebhookDoubleCheckHandler` | `eventPublisher.publishEvent(WebhookReceivedEvent)` inside `TransactionTemplate` | WIRED | Lines 183–191 |
| `WebhookDoubleCheckHandler` | provider status API | `orangeMoneyPort.getTransactionStatus` / `mtnMoMoPort.getTransactionStatus` | WIRED | Lines 53–55; result.pending() guard prevents no-op on still-PROCESSING |
| `WebhookDoubleCheckHandler` | `WebhookTransitionService` | `webhookTransitionService.applyFinalTransition(event, result)` | WIRED | Line 76; separate bean ensures AOP proxy applies |
| `WebhookTransitionService` | `WebhookDeliveryService` | `webhookDeliveryService.enqueue(...)` | WIRED | Lines 93–99; called after every terminal state transition |
| `WebhookDeliveryService` | tenant webhook URL | `noRetryRestTemplate.exchange(delivery.getWebhookUrl(), HttpMethod.POST, ...)` | WIRED | Lines 171–173; HMAC header set before call |
| `WebhookDeliveryJob` | `WebhookDeliveryService` | `deliveryService.findPendingDeliveries()` + `deliveryService.attemptDelivery()` | WIRED | Lines 36–41 of WebhookDeliveryJob |
| `OrangeIpWhitelistInterceptor` | `/v1/callbacks/orange` | `OrangeWebConfig.addInterceptors` | WIRED | `addPathPatterns("/v1/callbacks/orange")` |
| `MtnIpWhitelistInterceptor` | `/v1/callbacks/mtn` | `MtnWebConfig.addInterceptors` | WIRED | `addPathPatterns("/v1/callbacks/mtn")` |

---

### Requirements Coverage

| Requirement | Status | Notes |
|-------------|--------|-------|
| Orange POST receiver with IP whitelist + HMAC | SATISFIED | `/v1/callbacks/orange`, `OrangeIpWhitelistInterceptor`, conditional HMAC-SHA256 |
| MTN PUT receiver with IP whitelist | SATISFIED | `/v1/callbacks/mtn`, `MtnIpWhitelistInterceptor`; no HMAC by design (MTN API contract) |
| Double-check: provider status API re-queried before any state transition | SATISFIED | `WebhookDoubleCheckHandler` always calls provider before delegating to `WebhookTransitionService` |
| Redis dedup within 24h TTL | SATISFIED | Both Orange (`payToken:createtime`) and MTN (`externalId:status`) dedup keys with 24h TTL |
| Outbound tenant webhook with HMAC-SHA256 | SATISFIED | `WebhookDeliveryService.attemptDeliveryInternal()` signs with `javax.crypto.Mac HmacSHA256` |
| Retry minimum 3 times with exponential backoff | SATISFIED | MAX_ATTEMPTS=5 (exceeds 3); `2^attemptCount` minute backoff, capped at 60 min |
| Delivery status queryable | SATISFIED | `GET /v1/webhooks/deliveries/{transactionId}` wired to `WebhookDeliveryLogRepository` |

---

### Anti-Patterns Found

None detected across all phase files. Scan covered: `TODO`, `FIXME`, placeholder patterns, empty returns, console-log-only handlers.

One note that is not a blocker: `OrangeMoneyPort.initiateCashout()` and `initiateC2C()` unconditionally throw `UnsupportedOperationException`. These are intentional stubs documented in comments as out-of-scope for Phase 3/6 (cashout/C2C require sandbox verification). They are unrelated to the webhook processing goal and were not introduced in this phase.

---

### Human Verification Required

The following items pass automated structural checks but benefit from human confirmation in a live environment:

**1. Orange HMAC in production**
- **Test:** Configure `orange.callback-hmac-secret` with a real Orange partner secret; send a signed callback from the Orange sandbox
- **Expected:** Controller accepts correctly signed requests and rejects incorrectly signed ones with 401
- **Why human:** Integration test uses a locally-set secret. Cannot verify the Orange partner HMAC header name (`X-Orange-Signature`) is correct against live Orange documentation without a partner account.

**2. MTN PUT method**
- **Test:** Confirm MTN actually sends PUT and not POST from their sandbox
- **Expected:** Callbacks arrive at `/v1/callbacks/mtn` with status 200
- **Why human:** MTN API documentation states PUT; cannot run live provider traffic in CI.

**3. Quartz JDBC durability across JVM restart**
- **Test:** Trigger a failed outbound delivery, restart the JVM, wait for Quartz job to fire
- **Expected:** Pending delivery row picked up and retried after restart
- **Why human:** Verifying JDBC-backed Quartz persistence requires an actual JVM restart; not achievable in unit/integration tests.

---

## Summary

All 5 must-haves are structurally verified. The implementation is complete and properly wired across all three sub-plans:

- **06-01** (Reception): Orange POST + MTN PUT endpoints with IP whitelist interceptors (separate WebMvcConfigurer per provider), conditional HMAC on Orange inbound, Redis dedup for both providers — all fully implemented and registered
- **06-02** (Double-check): `WebhookDoubleCheckHandler` enforces the P1.4 rule — the webhook is only a trigger; `getTransactionStatus()` is always called before any state change. The `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` propagation pattern is correctly structured to avoid self-invocation and transaction context issues
- **06-03** (Outbound delivery): `WebhookDeliveryService` inserts a delivery log row, makes a synchronous first attempt, and schedules exponential-backoff retries via Quartz JDBC. `MAX_ATTEMPTS=5` exceeds the minimum-3 requirement. `GET /v1/webhooks/deliveries/{transactionId}` is wired and authenticated. Three integration tests cover HMAC correctness, 503 retry scheduling, and the delivery status API.

The three human-verification items are operational concerns (live provider HMAC header name confirmation, live MTN PUT confirmation, JVM restart durability), not gaps in the implementation.

---

*Verified: 2026-03-24T10:12:24Z*
*Verifier: Claude (gsd-verifier)*
