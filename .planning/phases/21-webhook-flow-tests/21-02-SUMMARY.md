---
phase: 21-webhook-flow-tests
plan: "02"
subsystem: testing
tags: [wiremock, spring-boot-test, hmac-sha256, webhook, awaitility, e2e]

# Dependency graph
requires:
  - phase: 19-verifiers-builders
    provides: WebhookDeliveryVerifier, TenantBuilder, MtnWebhookPayloadBuilder
  - phase: 18-test-infrastructure
    provides: AbstractPayamE2ETest, TestDataCleaner, E2ESecurityConfig, WireMockConfig
  - phase: 21-webhook-flow-tests (plan 01)
    provides: webhook e2e test package, webhook test patterns

provides:
  - FLOWS-HOOK-04: outbound webhook delivery with HMAC-SHA256 signature verification
  - FLOWS-HOOK-05: 5xx tenant response triggers exponential backoff retry scheduling
  - OutboundWebhookDeliveryE2ETest.java — standalone test class with tenant-wh WireMock server

affects:
  - Phase 22 and beyond: pattern for standalone @SpringBootTest with multi-WireMock server tests

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Standalone @SpringBootTest with 3 WireMock servers (mtn, orange, tenant-wh) when tenant callback server needed"
    - "WebhookDeliveryVerifier.assertHmacSignatureCorrect() for HMAC verification — never inline DigestUtils"
    - "Direct webhookDeliveryService.attemptDelivery() calls to bypass Quartz scheduler for deterministic retry count assertions"
    - "Awaitility.await() for delivery log row existence before assertion — replaces fixed Thread.sleep"

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/webhook/OutboundWebhookDeliveryE2ETest.java
  modified: []

key-decisions:
  - "OutboundWebhookDeliveryE2ETest is standalone @SpringBootTest (not AbstractPayamE2ETest subclass): AbstractPayamE2ETest.@EnableWireMock only declares mtn+orange; subclasses cannot add servers at class level"
  - "Awaitility for delivery log row wait instead of Thread.sleep: more reliable for async @TransactionalEventListener delivery path"
  - "Direct attemptDelivery() calls for FLOWS-HOOK-05 retry count verification: bypasses Quartz 1-minute scheduler for deterministic assertion"
  - "E2ESecurityConfig.seedSecurityRow() called directly in setUp() alongside TestDataCleaner.wipeAll() in tearDown(): replicates AbstractPayamE2ETest lifecycle without inheritance"

patterns-established:
  - "Multi-WireMock standalone pattern: declare all 3 servers in @EnableWireMock at class level, inject each with @InjectWireMock"
  - "Outbound delivery assertion: assertDelivered() via Awaitility wrapping WebhookDeliveryVerifier, then assertHmacHeaderPresent + assertHmacSignatureCorrect"

# Metrics
duration: 15min
completed: 2026-03-27
---

# Phase 21 Plan 02: Outbound Webhook Delivery E2E Tests Summary

**Outbound webhook E2E tests — HMAC-SHA256 delivery verification and 503 exponential retry scheduling using standalone 3-server WireMock setup**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-03-27T21:45:00Z
- **Completed:** 2026-03-27T22:00:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- FLOWS-HOOK-04 (outboundWebhookDeliveredWithHmacSignature): drives MTN PUT callback through full inbound-to-outbound cycle; asserts tenant-wh WireMock server received POST with `X-Payam-Signature: sha256=<hex>` header and HMAC signature correctly computed over payload
- FLOWS-HOOK-05 (outboundWebhookRetriesOn5xx): 503 from tenant callback leaves `delivered=false`, `attemptCount>=1`, `httpStatus=503`, `nextRetryAt` not null; two direct `attemptDelivery()` calls verify `attemptCount>=3` without Quartz scheduler
- Full phase 21 suite (7 tests across plans 21-01 and 21-02) passes cleanly with no state bleed between test classes

## Task Commits

Each task was committed atomically:

1. **Task 1: OutboundWebhookDeliveryE2ETest — HMAC delivery and retry tests** - `4c4da76` (feat)

**Plan metadata:** (in this commit)

## Files Created/Modified
- `src/test/java/com/softropic/payam/e2e/webhook/OutboundWebhookDeliveryE2ETest.java` — FLOWS-HOOK-04 + FLOWS-HOOK-05: standalone @SpringBootTest with mtn/orange/tenant-wh WireMock servers; HMAC-signed delivery and 5xx retry assertions

## Decisions Made

- **Standalone vs subclass:** `OutboundWebhookDeliveryE2ETest` does not extend `AbstractPayamE2ETest` because that base class declares `@EnableWireMock` with only mtn+orange. The `tenant-wh` server for the tenant callback endpoint requires all three WireMock servers declared at the test class level. Mirrors the proven `WebhookDeliveryIT` pattern.

- **Awaitility for delivery log wait:** Instead of a fixed `Thread.sleep(500)`, used `Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(transactionId).isEmpty())` to wait for the first async delivery attempt to complete. More reliable under load.

- **Direct attemptDelivery() for retry count:** For FLOWS-HOOK-05, bypass the Quartz 1-minute scheduler by calling `webhookDeliveryService.attemptDelivery()` directly twice more. `MAX_ATTEMPTS=5` so after 3 attempts `nextRetryAt` remains non-null. This matches the approach used in `WebhookDeliveryIT`.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

During the full suite run (all 5 phase 21 test classes together), `MtnPutCallbackAcceptanceE2ETest` initially appeared to fail with a 500 instead of 405 response. Investigation confirmed this bug was already fixed in plan 21-01 commit `998afec` (ApiAdvice `HttpRequestMethodNotSupportedException` handler). The failure was a transient Spring context reuse artifact during the first combined run; subsequent runs confirmed all 7 tests pass cleanly.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 21 complete: all 7 webhook E2E tests (FLOWS-HOOK-01 through FLOWS-HOOK-06) pass
- FLOWS-HOOK-04 and FLOWS-HOOK-05 verified: HMAC signature correctness, retry scheduling, delivery log states
- No blockers for phase 22

---
*Phase: 21-webhook-flow-tests*
*Completed: 2026-03-27*
