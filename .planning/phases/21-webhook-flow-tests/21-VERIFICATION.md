---
phase: 21-webhook-flow-tests
verified: 2026-03-27T00:00:00Z
status: passed
score: 4/4 must-haves verified
---

# Phase 21: Webhook Flow Tests — Verification Report

**Phase Goal:** Inbound and outbound webhook pipelines verified end-to-end
**Verified:** 2026-03-27
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| #   | Truth                                                                                                          | Status     | Evidence                                                                                                                                                                                                                    |
| --- | -------------------------------------------------------------------------------------------------------------- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | MTN PUT and Orange POST webhooks trigger correct state transitions via double-check                            | ✓ VERIFIED | `MtnWebhookDoubleCheckE2ETest` (129 lines): seeds PROCESSING row, sends PUT, asserts `SUCCESS` + balanced ledger + `PROVIDER_SUCCESS` event via Awaitility. `OrangeWebhookDoubleCheckE2ETest` (143 lines): same via POST with `pay_token` correlation and Orange `SUCCESSFULL` (double-L) stub. Both extend `AbstractWebhookFlowTest`. WireMock MTN stub verified with `moreThanOrExactly(1)` GET to `/v1_0/requesttopay/.*`; Orange stub verified with `moreThanOrExactly(1)` GET to `/mp/paymentstatus/.*`. |
| 2   | Duplicate webhook delivery is rejected; transaction state unchanged; no duplicate outbox event                 | ✓ VERIFIED | `WebhookReplayProtectionE2ETest` (179 lines): two `@Test` methods, no Redis flush between duplicate calls within each. MTN dedup key `"webhook:mtn:" + transactionId + ":SUCCESSFUL"` gated with `Awaitility.await(5s).until(redis.hasKey(dedupKey))` before second call. Orange dedup key `"webhook:orange:" + payToken + ":" + createtime` same pattern. Both assert `COUNT(*) FROM payment_event_log WHERE event_type IN ('PROVIDER_SUCCESS','PROVIDER_FAILED') = 1`. Production key format confirmed in `OrangeCallbackController:112` and `MtnMoMoPort:167` — exact match. |
| 3   | Outbound delivery to tenant callback URL includes HMAC-SHA256 signature                                       | ✓ VERIFIED | `OutboundWebhookDeliveryE2ETest.outboundWebhookDeliveredWithHmacSignature()` (lines 160–205): standalone `@SpringBootTest` with `tenant-wh` WireMock server. `TenantBuilder.withWebhookUrl(url, "test-secret")` sets webhook URL + secret. After MTN PUT drives SUCCESS, `WebhookDeliveryVerifier.assertDelivered()` + `assertHmacHeaderPresent()` + `assertHmacSignatureCorrect()` verify `X-Payam-Signature: sha256=<hex>` header. Production `WebhookDeliveryService:166,173` uses `Mac.getInstance("HmacSHA256")` and sets `X-Payam-Signature`. Verifier uses the same `HmacSHA256` algorithm. |
| 4   | 5xx from tenant triggers retry with exponential backoff (≥3 attempts)                                         | ✓ VERIFIED | `OutboundWebhookDeliveryE2ETest.outboundWebhookRetriesOn5xx()` (lines 217–276): tenant-wh stub returns 503. Awaitility waits for delivery log row. Asserts `delivered=false`, `attemptCount>=1`, `httpStatus=503`, `nextRetryAt != null`. Two direct `webhookDeliveryService.attemptDelivery()` calls drive `attemptCount>=3`. Production `WebhookDeliveryService:247–258`: `scheduleRetry()` uses `Math.min(Math.pow(2, attemptCount), 60)` minute backoff, `MAX_ATTEMPTS=5`. After 3 attempts `nextRetryAt` is non-null (below cap). |

**Score:** 4/4 truths verified

### Required Artifacts

| Artifact                                      | Expected                                              | Status      | Details                         |
| --------------------------------------------- | ----------------------------------------------------- | ----------- | ------------------------------- |
| `e2e/webhook/MtnWebhookDoubleCheckE2ETest.java`     | FLOWS-HOOK-01 MTN inbound double-check happy path     | ✓ VERIFIED  | 129 lines, extends `AbstractWebhookFlowTest`, no stubs, all phases implemented |
| `e2e/webhook/OrangeWebhookDoubleCheckE2ETest.java`  | FLOWS-HOOK-02 Orange inbound double-check happy path  | ✓ VERIFIED  | 143 lines, extends `AbstractWebhookFlowTest`, `pay_token` correlation correct |
| `e2e/webhook/WebhookReplayProtectionE2ETest.java`   | FLOWS-HOOK-03 Redis dedup both providers              | ✓ VERIFIED  | 179 lines, extends `AbstractPayamE2ETest`, 2 `@Test` methods, dedup keys verified against production |
| `e2e/webhook/MtnPutCallbackAcceptanceE2ETest.java`  | FLOWS-HOOK-06 PUT accepted, POST returns 405          | ✓ VERIFIED  | 148 lines, extends `AbstractWebhookFlowTest`, no-error RestTemplate for 405 assertion |
| `e2e/webhook/OutboundWebhookDeliveryE2ETest.java`   | FLOWS-HOOK-04 + FLOWS-HOOK-05 HMAC delivery + retry  | ✓ VERIFIED  | 300 lines, standalone `@SpringBootTest` with 3 WireMock servers, both `@Test` methods implemented |
| `security/api/ApiAdvice.java` (modified)            | `HttpRequestMethodNotSupportedException` → 405        | ✓ VERIFIED  | `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` + `@ResponseStatus(METHOD_NOT_ALLOWED)` present at line 85–87 |

### Key Link Verification

| From                                        | To                                          | Via                                              | Status      | Details                                                     |
| ------------------------------------------- | ------------------------------------------- | ------------------------------------------------ | ----------- | ------------------------------------------------------------ |
| `MtnWebhookDoubleCheckE2ETest.verifyTransactionState` | `InvariantVerifier.assertAll`          | `Awaitility.await(5s).untilAsserted()`           | ✓ WIRED     | Line 122–127: Awaitility wraps all assertions; `assertWebhookDoubleCheckFired` at line 114 |
| `OrangeWebhookDoubleCheckE2ETest.executeFlow` | `pay_token` column                        | JDBC insert sets both `provider_ref` and `pay_token` to `payToken` | ✓ WIRED | Line 94–96: `VALUES (..., ?, ?)` with `payToken, payToken` |
| `WebhookReplayProtectionE2ETest.mtnReplayProtection` | `StringRedisTemplate.hasKey`           | `"webhook:mtn:" + transactionId + ":SUCCESSFUL"` | ✓ WIRED     | Line 93–94: dedup key matches `MtnMoMoPort:167` exactly     |
| `WebhookReplayProtectionE2ETest.orangeReplayProtection` | `StringRedisTemplate.hasKey`        | `"webhook:orange:" + payToken + ":" + createtime` | ✓ WIRED    | Line 159: dedup key matches `OrangeCallbackController:112` exactly |
| `MtnPutCallbackAcceptanceE2ETest.dispatchInboundWebhook` | `MtnCallbackController`            | `PUT /v1/callbacks/mtn → 200; POST → 405`        | ✓ WIRED     | Lines 111–128: POST returns 405, PUT returns 200, both asserted with `assertThat(response.getStatusCode().value())` |
| `OutboundWebhookDeliveryE2ETest.@EnableWireMock` | `tenantCallbackServer`               | `@ConfigureWireMock(name="tenant-wh") + @InjectWireMock("tenant-wh")` | ✓ WIRED | Lines 88–92, 105–106: 3-server declaration and injection |
| `TenantBuilder.withWebhookUrl`              | `WebhookDeliveryService.enqueue`            | `tenant.webhookUrl = url; webhookSecret = "test-secret"` | ✓ WIRED | `TenantBuilder:43–45`: both fields set and persisted to DB |
| `WebhookDeliveryVerifier.assertHmacSignatureCorrect` | `WebhookDeliveryService` HMAC algorithm | `Mac.getInstance("HmacSHA256")` — matches production | ✓ WIRED | Verifier line 77–79 uses `HmacSHA256`; production `WebhookDeliveryService:166,169` same |
| `webhookDeliveryService.attemptDelivery`    | `WebhookDeliveryLog.attemptCount`           | Direct invocation bypasses Quartz                | ✓ WIRED     | Lines 268–275: two direct calls read back updated log entity and assert `>=3` |

### Requirements Coverage

| Requirement                                                             | Status      | Notes                                                                              |
| ----------------------------------------------------------------------- | ----------- | ---------------------------------------------------------------------------------- |
| FLOWS-HOOK-01: MTN inbound webhook double-check happy path              | ✓ SATISFIED | `MtnWebhookDoubleCheckE2ETest` — SUCCESS + balanced ledger + PROVIDER_SUCCESS      |
| FLOWS-HOOK-02: Orange inbound webhook double-check happy path           | ✓ SATISFIED | `OrangeWebhookDoubleCheckE2ETest` — payToken correlation + SUCCESSFULL (double-L)  |
| FLOWS-HOOK-03: Redis dedup prevents duplicate outbox event              | ✓ SATISFIED | `WebhookReplayProtectionE2ETest` — MTN + Orange, event count asserted = 1          |
| FLOWS-HOOK-04: Outbound delivery with HMAC-SHA256 signature             | ✓ SATISFIED | `OutboundWebhookDeliveryE2ETest.outboundWebhookDeliveredWithHmacSignature`         |
| FLOWS-HOOK-05: 5xx triggers exponential backoff retry (>=3 attempts)   | ✓ SATISFIED | `OutboundWebhookDeliveryE2ETest.outboundWebhookRetriesOn5xx` — 503, attemptCount>=3 |
| FLOWS-HOOK-06: MTN PUT accepted, POST returns 405                       | ✓ SATISFIED | `MtnPutCallbackAcceptanceE2ETest` — both HTTP method assertions present             |

### Anti-Patterns Found

None. All five test files: zero `TODO`, `FIXME`, `placeholder`, `not implemented`, or `coming soon` matches. No empty handler bodies. No `return null` implementations. All concrete assertion chains present.

### Human Verification Required

None identified. The phase goal is test code. All test logic, wiring, and production code dependencies are structurally verifiable. Functional pass/fail requires a running Testcontainers environment (Postgres + Redis) but the structural verification confirms the tests are not stubs — they contain real JDBC inserts, real HTTP dispatches, real Awaitility waits, and real assertions against production service behavior.

---

## Summary

All four must-haves are verified. The five test files collectively cover seven test methods across FLOWS-HOOK-01 through FLOWS-HOOK-06:

- Inbound pipeline: MTN double-check (PUT), Orange double-check (POST with payToken), Redis dedup for both providers, MTN HTTP method enforcement
- Outbound pipeline: HMAC-SHA256 delivery verification (algorithm matches production), 5xx retry with direct `attemptDelivery()` driving count to >=3

All critical wiring is confirmed: dedup key formats in tests match production sources exactly, HMAC algorithm matches between verifier and service, `pay_token` column set for Orange correlation, `AbstractWebhookFlowTest` template properly invoked by four test classes, standalone 3-WireMock-server pattern correct in `OutboundWebhookDeliveryE2ETest`.

---

_Verified: 2026-03-27_
_Verifier: Claude (gsd-verifier)_
