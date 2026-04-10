---
phase: 34
plan: "02"
subsystem: orange-adapter
tags: [orange-money, adapter, dto, refactor, payment-flow]
dependency_graph:
  requires: [34-01]
  provides: [correct-orange-mp-init-flow, correct-pay-request-shape]
  affects: [OrangeMoneyPort, OrangeMoneyClient, OrangeCallbackController, OrangeMoneyConfig]
tech_stack:
  added: []
  patterns: [PlatformConfigService injection for channelMsisdn lookup]
key_files:
  created:
    - src/main/java/com/softropic/payam/orange/contract/dto/InitTransactionResponse.java
  modified:
    - src/main/java/com/softropic/payam/orange/contract/dto/PayRequest.java
    - src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java
    - src/main/java/com/softropic/payam/orange/config/OrangeMoneyConfig.java
    - src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java
    - src/main/resources/application.yaml
    - src/main/resources/application-dev.yaml
    - src/main/resources/application-uat.yaml
  deleted:
    - src/main/java/com/softropic/payam/orange/contract/dto/MerchantInfoResponse.java
decisions:
  - "callbackHmacSecret removed from OrangeMoneyConfig entirely — HMAC verification was speculative; Orange does not confirm this header in v1.0.2"
  - "OrangeCallbackController now has 3-field constructor (port, redis, metricsService) — objectMapper and orangeMoneyConfig removed as no longer needed"
  - "OrangePathMatrixTest also updated (not in plan scope) — it had /infos/merchant stub that would have caused silent test failures"
metrics:
  duration_minutes: 15
  completed_date: "2026-04-10"
  tasks_completed: 10
  files_changed: 20
---

# Phase 34 Plan 02: Orange adapter rewrite — align with Use Case 1 spec Summary

**One-liner:** Replaced wrong PayRequest DTO and GET /infos/merchant with correct 7-field /mp/pay body and POST /mp/init flow per Orange Money spec Use Case 1.

## What Was Built

Seven root-cause fixes to the Orange Money adapter to correctly implement Use Case 1 (Initiate a Payment and Receive Notification) from the Orange Money spec:

1. **PayRequest.java** — replaced 9 wrong legacy fields (`merchant_key`, `currency`, `order_id`, `return_url`, `cancel_url`, `lang`, `reference`, etc.) with the correct 7-field `/mp/pay` body: `payToken`, `subscriberMsisdn`, `channelUserMsisdn`, `amount`, `orderId`, `description`, `notifUrl`.

2. **InitTransactionResponse.java** (new) — response DTO for `POST /mp/init` with nested `data.payToken` structure per Orange spec. Replaces `MerchantInfoResponse`.

3. **MerchantInfoResponse.java** (deleted) — superseded by `InitTransactionResponse`.

4. **OrangeMoneyClient** — `getMerchantInfo()` (GET /infos/merchant) replaced by `initTransaction()` (POST /mp/init). Constructor super-call path updated from `/infos/merchant` to `/mp/init`.

5. **OrangeMoneyConfig** — added `callbackUrl` field/getter/setter; removed `callbackHmacSecret` (HMAC verification was speculative and not confirmed by Orange).

6. **OrangeMoneyPort** — injected `PlatformConfigService`; `buildPayRequest()` rewritten to set all 7 fields correctly, fetching `channelUserMsisdn` from `platformConfigService.findByProvider("ORANGE")`, with fallback rules: `orderId = externalReference ?? transactionId`, `description = cmd.description() ?? "Payment"`.

7. **OrangeCallbackController** — removed HMAC verification block, `HttpServletRequest` parameter, `ObjectMapper`, `OrangeMoneyConfig` injections. Constructor reduced from 5 args to 3.

8. **Config files** — added `orange.callback-url` to application-dev.yaml, application-uat.yaml, application.yaml; removed `callback-hmac-secret` from all profiles.

9. **WireMock stubs** — updated in 5 test files: `OrangeMoneyPortIT`, `OrangePaymentInitiationE2ETest`, `OrangePayTokenExpiryE2ETest`, `PaymentOrchestratorIT`, `OrangePathMatrixTest`.

10. **Test cleanup** — removed HMAC test and OrangeMoneyConfig injection from `OrangeCallbackControllerIT`; removed `orange.callback-hmac-secret=` from `@TestPropertySource` in 4 files.

## Commits

| Hash | Description |
|------|-------------|
| 5d714ad | feat(34-02): align Orange adapter with Use Case 1 spec |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Updated OrangePathMatrixTest stub not in plan scope**
- **Found during:** Step 8 (WireMock stub update scan)
- **Issue:** `OrangePathMatrixTest.java` had `stubFor(get(urlPathEqualTo("/infos/merchant"))...)` not listed in plan's Step 8 targets — would have caused silent WireMock 404 and test failure
- **Fix:** Applied same POST /mp/init stub replacement
- **Files modified:** `src/test/java/com/softropic/payam/e2e/domain/OrangePathMatrixTest.java`
- **Commit:** 5d714ad

**2. [Rule 1 - Bug] OrangeCallbackController had unused fields after HMAC removal**
- **Found during:** Step 6 review
- **Issue:** After removing HMAC block, `ObjectMapper` and `OrangeMoneyConfig` injected fields became unused
- **Fix:** Removed both fields and their constructor parameters; constructor reduced from 5 to 3 args
- **Files modified:** `src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java`
- **Commit:** 5d714ad

**3. [Rule 1 - Bug] application.yaml and application-uat.yaml had callback-hmac-secret**
- **Found during:** Step 7 (grep scan)
- **Issue:** Plan only mentioned application-dev.yaml; application.yaml and application-uat.yaml also had `callback-hmac-secret` that would cause binding failure against the removed config field
- **Fix:** Removed `callback-hmac-secret` and added `callback-url` to both files
- **Files modified:** `src/main/resources/application.yaml`, `src/main/resources/application-uat.yaml`
- **Commit:** 5d714ad

## Known Stubs

None — all 7 PayRequest fields are populated from real runtime sources (no hardcoded empties flowing to provider calls).

## Acceptance Criteria Verification

1. PayRequest.java has exactly 7 fields: `payToken`, `subscriberMsisdn`, `channelUserMsisdn`, `amount`, `orderId`, `description`, `notifUrl` — all with matching `@JsonProperty`. PASS
2. InitTransactionResponse.java exists with nested Data class; `getPayToken()` delegates to `data.payToken`. PASS
3. MerchantInfoResponse.java is deleted. PASS
4. OrangeMoneyClient.initTransaction() calls POST /mp/init, returns InitTransactionResponse. No getMerchantInfo() method. PASS
5. OrangeMoneyConfig has callbackUrl field/getter/setter; callbackHmacSecret removed. PASS
6. OrangeMoneyPort constructor accepts PlatformConfigService; buildPayRequest() calls findByProvider("ORANGE"). PASS
7. buildPayRequest() fallback rules: orderId = externalReference ?? transactionId; description = cmd.description() ?? "Payment". PASS
8. OrangeCallbackController.handleCallback() has only @RequestBody + @RequestHeader parameters. No HMAC block. PASS
9. application-dev.yaml contains orange.callback-url: ${baseurl}/v1/callbacks/orange. PASS
10. All WireMock stubs for /infos/merchant replaced with POST /mp/init returning correct shape. PASS
11. OrangeCallbackControllerIT HMAC test deleted; OrangeMoneyConfig injection and hmac property removed. PASS
12. orange.callback-hmac-secret= removed from AbstractPayamE2ETest, WebhookDoubleCheckIT, WebhookDeliveryIT, OutboundWebhookDeliveryE2ETest. PASS
13. mvn compile + mvn test-compile: BUILD SUCCESS, zero errors. PASS (full mvn verify deferred to orchestrator)

## Self-Check: PASSED
