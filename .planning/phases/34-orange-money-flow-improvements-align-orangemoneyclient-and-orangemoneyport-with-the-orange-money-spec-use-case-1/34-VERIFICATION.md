---
phase: 34-orange-money-flow-improvements
verified: 2026-04-10T00:00:00Z
status: passed
score: 17/17 must-haves verified
gaps: []
---

# Phase 34: Orange Money Flow Improvements — Verification Report

**Phase Goal:** Align OrangeMoneyClient and OrangeMoneyPort with the Orange Money spec Use Case 1 (Initiate a Payment & Receive Notification)
**Verified:** 2026-04-10
**Status:** passed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| #  | Truth                                                                                        | Status     | Evidence                                                                  |
|----|----------------------------------------------------------------------------------------------|------------|---------------------------------------------------------------------------|
| 1  | PaymentRequest has nullable String description as 7th field                                  | VERIFIED   | PaymentRequest.java line 50: `String description` (no validation)         |
| 2  | PaymentCommand has nullable String description as 13th field                                 | VERIFIED   | PaymentCommand.java line 18: `String description`                         |
| 3  | PaymentOrchestrator.initiate() passes request.description() as last PaymentCommand arg       | VERIFIED   | PaymentOrchestrator.java line 181: `request.description()`                |
| 4  | PlatformConfigService.findByProvider(String) exists, @Transactional(readOnly=true), throws   | VERIFIED   | PlatformConfigService.java lines 53-60                                    |
| 5  | PlatformConfigServiceTest has 2 new tests for findByProvider                                 | VERIFIED   | Lines 59-86: happy path + not-found using assertThatThrownBy               |
| 6  | PaymentRequestBuilder has description field, withDescription(), 7-arg build()                | VERIFIED   | PaymentRequestBuilder.java lines 48, 80-83, 106                           |
| 7  | 8 PaymentCommand construction sites fixed in test files (null as 13th arg)                  | VERIFIED   | OrangeMoneyPortIT: 4 sites; MtnMoMoPortIT: 1; FraudScoringServiceIT: 1; FraudThresholdGuardTest: 2 |
| 8  | PayRequest.java has exactly 7 fields: payToken, subscriberMsisdn, channelUserMsisdn, amount, orderId, description, notifUrl | VERIFIED | PayRequest.java lines 9-15 with matching @JsonProperty |
| 9  | InitTransactionResponse.java exists with nested Data class and getPayToken() delegate        | VERIFIED   | InitTransactionResponse.java lines 18-33                                  |
| 10 | MerchantInfoResponse.java is deleted                                                         | VERIFIED   | File does not exist in dto directory                                      |
| 11 | OrangeMoneyClient has initTransaction(String) calling POST /mp/init; no getMerchantInfo()   | VERIFIED   | OrangeMoneyClient.java lines 105-121; no getMerchantInfo method found     |
| 12 | OrangeMoneyConfig has callbackUrl; callbackHmacSecret is removed                            | VERIFIED   | OrangeMoneyConfig.java lines 18, 61-62; no callbackHmacSecret present     |
| 13 | OrangeMoneyPort injects PlatformConfigService; buildPayRequest() calls findByProvider("ORANGE") and sets all 7 PayRequest fields | VERIFIED | OrangeMoneyPort.java lines 53, 62, 70, 271-275 |
| 14 | OrangeCallbackController.handleCallback() has no HttpServletRequest, no HMAC block          | VERIFIED   | OrangeCallbackController.java lines 54-56: only @RequestBody + @RequestHeader |
| 15 | application-dev.yaml has orange.callback-url                                                 | VERIFIED   | application-dev.yaml line 273: `orange: callback-url: ${baseurl}/v1/callbacks/orange` |
| 16 | WireMock stubs for /infos/merchant replaced with POST /mp/init in test files                | VERIFIED   | OrangeMoneyPortIT line 121; OrangePaymentInitiationE2ETest line 71; OrangePayTokenExpiryE2ETest line 96; no /infos/merchant references remain |
| 17 | HMAC test deleted from OrangeCallbackControllerIT; orange.callback-hmac-secret removed from @TestPropertySource in 4+ files | VERIFIED | OrangeCallbackControllerIT has no HMAC test (127 lines, ends at test 4); AbstractPayamE2ETest, WebhookDoubleCheckIT, WebhookDeliveryIT, OutboundWebhookDeliveryE2ETest all clean |

**Score:** 17/17 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/softropic/payam/payment/contract/PaymentRequest.java` | 7-field record with nullable description last | VERIFIED | description at line 50, no @NotBlank |
| `src/main/java/com/softropic/payam/common/payment/PaymentCommand.java` | 13-field record with nullable description last | VERIFIED | description at line 18 with inline comment |
| `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` | Passes request.description() as 13th arg | VERIFIED | Line 181 |
| `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java` | findByProvider with @Transactional(readOnly=true) | VERIFIED | Lines 53-60 |
| `src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java` | 2 new findByProvider tests | VERIFIED | Lines 59-86 |
| `src/test/java/com/softropic/payam/e2e/builder/PaymentRequestBuilder.java` | description field + withDescription() + 7-arg build() | VERIFIED | Lines 48, 80-83, 106 |
| `src/main/java/com/softropic/payam/orange/contract/dto/PayRequest.java` | 7 correct fields for /mp/pay | VERIFIED | Lines 9-15 |
| `src/main/java/com/softropic/payam/orange/contract/dto/InitTransactionResponse.java` | Nested Data + getPayToken() delegate | VERIFIED | Lines 18-33 |
| `src/main/java/com/softropic/payam/orange/contract/dto/MerchantInfoResponse.java` | Deleted | VERIFIED | File absent |
| `src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java` | initTransaction() POST /mp/init; no getMerchantInfo() | VERIFIED | Lines 38-39 (constructor), 105-121 |
| `src/main/java/com/softropic/payam/orange/config/OrangeMoneyConfig.java` | callbackUrl present; callbackHmacSecret absent | VERIFIED | Lines 18, 61-62 |
| `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` | PlatformConfigService injected; buildPayRequest() correct | VERIFIED | Lines 53, 62, 70, 270-276 |
| `src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java` | No HttpServletRequest, no HMAC block | VERIFIED | Lines 54-56 |
| `src/main/resources/application-dev.yaml` | orange.callback-url property | VERIFIED | Line 273 |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| OrangeMoneyPort.buildPayRequest() | PlatformConfigService.findByProvider("ORANGE") | Direct call | WIRED | Line 271 |
| OrangeMoneyPort.initiateMerchantPayment() | OrangeMoneyClient.initTransaction() | Direct call | WIRED | Line 96 |
| OrangeMoneyClient.initTransaction() | POST /mp/init | makeHttpRequest + HttpMethod.POST | WIRED | Lines 109-110 |
| OrangeMoneyClient constructor | /mp/init base path | super(…, "/mp/init") | WIRED | Line 39 |
| PaymentOrchestrator.initiate() | PaymentCommand(…, request.description()) | 13th constructor arg | WIRED | Line 181 |
| buildPayRequest() | config.getCallbackUrl() | OrangeMoneyConfig.callbackUrl | WIRED | Line 275 |
| OrangeMoneyPortIT | POST /mp/init stub | post(urlPathEqualTo("/mp/init")) | WIRED | Line 121 |
| OrangePaymentInitiationE2ETest | POST /mp/init stub | post(urlPathEqualTo("/mp/init")) | WIRED | Line 71 |
| OrangePayTokenExpiryE2ETest | POST /mp/init stub | post(urlPathEqualTo("/mp/init")) | WIRED | Line 96 |

---

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| OrangeMoneyPort.buildPayRequest() | channelMsisdn | platformConfigService.findByProvider("ORANGE").platformMsisdn() | Yes — DB query via platformConfigRepository.findByProvider | FLOWING |
| OrangeMoneyPort.initiateMerchantPayment() | payToken | orangeMoneyClient.initTransaction(token).getPayToken() | Yes — HTTP POST /mp/init, delegates to data.payToken | FLOWING |
| PayRequest fields | 7 fields | PayRequest.of(payToken, nationalMsisdn, channelMsisdn, amount, orderId, desc, callbackUrl) | Yes — all populated from PaymentCommand + config | FLOWING |

---

### Behavioral Spot-Checks

Step 7b: SKIPPED for this verification pass — the phase produces server-side Spring Boot code requiring a running application context to execute endpoints. Static code analysis confirms all wiring is complete; runtime behavior is covered by the project's integration tests (OrangeMoneyPortIT, OrangeCallbackControllerIT, OrangePaymentInitiationE2ETest, OrangePayTokenExpiryE2ETest).

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| Add description to PaymentRequest/PaymentCommand | 34-01 | Nullable description field propagated end-to-end | SATISFIED | PaymentRequest.java:50, PaymentCommand.java:18, PaymentOrchestrator.java:181 |
| PlatformConfigService.findByProvider | 34-01 | Method added with @Transactional(readOnly=true) and IllegalStateException | SATISFIED | PlatformConfigService.java:53-60 |
| PlatformConfigServiceTest 2 new tests | 34-01 | Happy path + not-found | SATISFIED | PlatformConfigServiceTest.java:59-86 |
| PaymentRequestBuilder 7-arg build | 34-01 | description field + withDescription + 7-arg build() | SATISFIED | PaymentRequestBuilder.java:48,80,106 |
| 8 PaymentCommand construction sites (null 13th) | 34-01 | OrangeMoneyPortIT(4), MtnMoMoPortIT(1), FraudScoringServiceIT(1), FraudThresholdGuardTest(2) | SATISFIED | All files confirmed |
| PayRequest 7 correct fields | 34-02 | payToken, subscriberMsisdn, channelUserMsisdn, amount, orderId, description, notifUrl | SATISFIED | PayRequest.java:9-15 |
| InitTransactionResponse + delete MerchantInfoResponse | 34-02 | New DTO with nested Data; old DTO deleted | SATISFIED | InitTransactionResponse.java exists; MerchantInfoResponse.java absent |
| OrangeMoneyClient initTransaction POST /mp/init | 34-02 | No getMerchantInfo; constructor uses /mp/init | SATISFIED | OrangeMoneyClient.java:39,105-121 |
| OrangeMoneyConfig callbackUrl / remove callbackHmacSecret | 34-02 | callbackUrl present; callbackHmacSecret absent | SATISFIED | OrangeMoneyConfig.java:18,61-62 |
| OrangeMoneyPort PlatformConfigService injection + buildPayRequest | 34-02 | Injected; buildPayRequest uses findByProvider + all 7 PayRequest fields | SATISFIED | OrangeMoneyPort.java:53,62,70,270-276 |
| OrangeCallbackController remove HMAC + HttpServletRequest | 34-02 | Method signature clean; no HMAC block | SATISFIED | OrangeCallbackController.java:54-56 |
| application-dev.yaml orange.callback-url | 34-02 | Property present | SATISFIED | application-dev.yaml:273 |
| WireMock stubs replace /infos/merchant with POST /mp/init | 34-02 | All 3 test files updated | SATISFIED | OrangeMoneyPortIT:121, OrangePaymentInitiationE2ETest:71, OrangePayTokenExpiryE2ETest:96 |
| HMAC test deleted + @TestPropertySource cleanup (4+ files) | 34-02 | Test deleted; no callback-hmac-secret in any @TestPropertySource | SATISFIED | OrangeCallbackControllerIT:39-42; AbstractPayamE2ETest:36-40; WebhookDoubleCheckIT:59-63; WebhookDeliveryIT:67-71; OutboundWebhookDeliveryE2ETest:82-86 |

---

### Anti-Patterns Found

None. No TODO/FIXME/placeholder patterns detected in the modified production or test files. All PaymentCommand construction sites pass explicit `null` as the 13th `description` argument — these are intentional defaults (non-Orange adapters ignore description), not stubs. The `buildPayRequest()` fallback `desc = cmd.description() != null ? cmd.description() : "Payment"` is a deliberate default for non-null rendering, not a hardcoded empty.

---

### Human Verification Required

None. All must-haves are verifiable through static code inspection. The integration test suite (OrangeMoneyPortIT, OrangeCallbackControllerIT, OrangePaymentInitiationE2ETest, OrangePayTokenExpiryE2ETest) covers the runtime behavior that would otherwise require human observation.

---

### Gaps Summary

No gaps. All 17 must-haves pass at all verification levels (exists, substantive, wired, data-flowing). Phase 34 goal is fully achieved.

---

_Verified: 2026-04-10_
_Verifier: Claude (gsd-verifier)_
