# Phase 34: Orange Money Flow Improvements - Context

**Gathered:** 2026-04-10
**Status:** Ready for planning
**Source:** PRD Express Path (requirements/orange-money-flow-improvements.md)

<domain>
## Phase Boundary

Align `OrangeMoneyClient`, `OrangeMoneyPort`, and supporting types with the Orange Money spec (Use Case 1 — Initiate a Payment & Receive Notification). The current implementation uses a wrong endpoint, ignores required fields, and includes HMAC verification logic not in the spec. This phase fixes all seven root-cause issues.

**In scope:**
- Replace `PayRequest` DTO with the correct `/mp/pay` body shape
- Fix payToken init endpoint: `POST /mp/init` (not `GET /infos/merchant`)
- Add `PlatformConfigService.findByProvider(String)` to supply `channelUserMsisdn`
- Add `notifUrl` via `orange.callback-url` config property
- Add optional `description` field to `PaymentRequest` and `PaymentCommand`
- Fix `OrangeMoneyPort.buildPayRequest()` to populate all required fields
- Remove HMAC verification from `OrangeCallbackController` and `OrangeMoneyConfig`
- Update all test stubs and `PaymentCommand` construction sites

**Out of scope:** MTN adapter changes, new error codes, webhook delivery logic, cashout flows.

</domain>

<decisions>
## Implementation Decisions

### 1. Replace PayRequest DTO
- **LOCKED:** Delete all current fields (`merchant_key`, `currency`, `order_id`, `return_url`, `cancel_url`, `lang`, `reference`) from `PayRequest.java`
- **LOCKED:** New fields: `payToken`, `subscriberMsisdn`, `channelUserMsisdn`, `amount`, `orderId`, `description`, `notifUrl` — annotated with `@JsonProperty` matching exact JSON field names from spec
- File: `src/main/java/com/softropic/payam/orange/contract/dto/PayRequest.java`

### 2. Fix payToken init endpoint
- **LOCKED:** Rename `getMerchantInfo()` to `initTransaction()` in `OrangeMoneyClient.java`
- **LOCKED:** Change from `GET /infos/merchant` to `POST /mp/init` (no request body)
- **LOCKED:** Response shape: `{ "data": { "payToken": "MP-XXXXXXXXXXXXXXXX" }, "message": "OK" }` — parse via new `InitTransactionResponse` DTO (with inner `Data` class)
- **LOCKED:** Delete `MerchantInfoResponse.java` — replaced by `InitTransactionResponse`
- **LOCKED:** Update `OrangeMoneyPort.initiateMerchantPayment()` to call `initTransaction()` instead of `getMerchantInfo()`
- File: `src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java`

### 3. Add PlatformConfigService.findByProvider(String)
- **LOCKED:** Add `@Transactional(readOnly = true)` method `findByProvider(String provider)` to `PlatformConfigService`
- **LOCKED:** Uses existing `platformConfigRepository.findByProvider(provider.toUpperCase())`
- **LOCKED:** Returns `PlatformConfigDto` or throws `IllegalStateException("Platform MSISDN not configured for provider: " + provider)` when not found
- **LOCKED:** `channelUserMsisdn` is stored and passed in national format (no `stripCountryCode()` needed)
- **LOCKED:** Missing config propagates as `PROVIDER_ERROR` (HTTP 502) via existing orchestrator `catch (Exception e)` — no new error codes
- File: `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java`

### 4. Add notifUrl via config
- **LOCKED:** Add `callbackUrl` field, getter, and setter to `OrangeMoneyConfig.java`
- **LOCKED:** Add `orange.callback-url: ${baseurl}/v1/callbacks/orange` to `application-dev.yaml` (and prod equivalent)
- **LOCKED:** `buildPayRequest()` reads `config.getCallbackUrl()` and sets it as `notifUrl`
- File: `src/main/java/com/softropic/payam/orange/config/OrangeMoneyConfig.java`

### 5. Add description field
- **LOCKED:** Add optional `String description` (no `@NotBlank`, nullable) to `PaymentRequest.java`
- **LOCKED:** Add `String description` as last field in `PaymentCommand` record (after `deviceFingerprint`)
- **LOCKED:** `PaymentOrchestrator.initiate()` passes `request.description()` when constructing `PaymentCommand`
- **LOCKED:** MTN adapter ignores the new field — no changes to `MtnMoMoPort`
- Files: `src/main/java/com/softropic/payam/payment/contract/PaymentRequest.java`, `src/main/java/com/softropic/payam/common/payment/PaymentCommand.java`

### 6. Fix buildPayRequest() in OrangeMoneyPort
- **LOCKED:** Inject `PlatformConfigService` into `OrangeMoneyPort` via constructor
- **LOCKED:** New implementation calls `platformConfigService.findByProvider("ORANGE")` for `channelUserMsisdn`
- **LOCKED:** `orderId` = `cmd.externalReference()` if non-null, else `cmd.transactionId()`
- **LOCKED:** `description` = `cmd.description()` if non-null, else `"Payment"`
- **LOCKED:** Use `PayRequest.of(...)` factory or constructor with all 7 fields
- File: `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java`

### 7. Remove HMAC verification
- **LOCKED:** Remove entire HMAC verification block from `OrangeCallbackController.handleCallback()` (currently lines ~71–106)
- **LOCKED:** Remove `HttpServletRequest request` parameter from `handleCallback()`
- **LOCKED:** Remove `callbackHmacSecret` field, getter, setter from `OrangeMoneyConfig.java`
- **LOCKED:** Remove `orange.callback-hmac-secret` from `application-dev.yaml` and any other config files
- **LOCKED:** IP whitelist (`orange.callback-ip-whitelist`) is NOT removed — it is a valid operational control
- File: `src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java`

### 8. Update test helper PaymentRequestBuilder
- **LOCKED:** Add `description` field (default `null`) and `withDescription(String)` setter to `PaymentRequestBuilder`
- **LOCKED:** Update `build()` to pass it to `PaymentRequest` constructor
- File: `src/test/java/com/softropic/payam/e2e/builder/PaymentRequestBuilder.java`

### 9. Update all PaymentCommand construction sites
- **LOCKED:** Add `null` as `description` argument at all non-Orange construction sites:
  - `OrangeMoneyPortIT.java` (4 call sites)
  - `MtnMoMoPortIT.java`
  - `FraudScoringServiceIT.java`
  - `FraudThresholdGuardTest.java` (2 call sites)
- **LOCKED:** `PaymentOrchestrator.java` passes `request.description()`

### 10. Update WireMock stubs for /mp/init
- **LOCKED:** Change all `GET /infos/merchant` stubs to `POST /mp/init` returning `{ "data": { "payToken": "tok-abc-123" }, "message": "OK" }`
- **LOCKED:** Files to update: `OrangeMoneyPortIT.java`, `OrangePaymentInitiationE2ETest.java`, `OrangePayTokenExpiryE2ETest.java` (if it stubs `/infos/merchant`)

### 11. Add PlatformConfigServiceTest coverage
- **LOCKED:** Add test for `findByProvider()` happy path (returns correct DTO)
- **LOCKED:** Add test for `findByProvider()` not-found path (throws `IllegalStateException`)
- **LOCKED:** Delete `shouldReject401WhenHmacSecretConfiguredAndSignatureMissing()` from `OrangeCallbackControllerIT`
- **LOCKED:** Remove `OrangeMoneyConfig` injection and `@TestPropertySource` HMAC property from `OrangeCallbackControllerIT`
- **LOCKED:** Remove `orange.callback-hmac-secret=` from `WebhookDoubleCheckIT`, `WebhookDeliveryIT`, `AbstractPayamE2ETest`, `OutboundWebhookDeliveryE2ETest`

### Claude's Discretion
- Exact placement of `InitTransactionResponse` (top-level DTO file vs inner class in `OrangeMoneyClient`) — recommend separate file under `contract/dto/`
- Whether to add `PayRequest.of(...)` factory or just use constructor
- Import cleanup (remove unused `java.nio.charset.StandardCharsets` if it becomes unused after HMAC removal)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Orange Money Spec
- `requirements/orange-money-integration-guide.md` — Authoritative spec for Use Case 1 (POST /mp/init, POST /mp/pay, callback format); defines exact field names and response shapes

### Source Files to Modify
- `src/main/java/com/softropic/payam/orange/contract/dto/PayRequest.java` — Replace entirely
- `src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java` — Fix endpoint + rename method
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` — Fix buildPayRequest(), inject PlatformConfigService
- `src/main/java/com/softropic/payam/orange/config/OrangeMoneyConfig.java` — Add callbackUrl, remove callbackHmacSecret
- `src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java` — Remove HMAC block
- `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java` — Add findByProvider()
- `src/main/java/com/softropic/payam/payment/contract/PaymentRequest.java` — Add description
- `src/main/java/com/softropic/payam/common/payment/PaymentCommand.java` — Add description field
- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` — Pass description in command

### Config Files to Modify
- `src/main/resources/application-dev.yaml` — Add orange.callback-url, remove orange.callback-hmac-secret

### New DTOs to Create
- `src/main/java/com/softropic/payam/orange/contract/dto/InitTransactionResponse.java` — Response wrapper for POST /mp/init

### DTOs to Delete
- `src/main/java/com/softropic/payam/orange/contract/dto/MerchantInfoResponse.java` — Replaced by InitTransactionResponse

### Test Files to Update
- `src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java` — Update stubs + PaymentCommand sites
- `src/test/java/com/softropic/payam/e2e/OrangePaymentInitiationE2ETest.java` — Update stubs
- `src/test/java/com/softropic/payam/e2e/OrangePayTokenExpiryE2ETest.java` — Check/update stubs
- `src/test/java/com/softropic/payam/orange/web/OrangeCallbackControllerIT.java` — Remove HMAC test
- `src/test/java/com/softropic/payam/e2e/builder/PaymentRequestBuilder.java` — Add description
- `src/test/java/com/softropic/payam/platform/PlatformConfigServiceTest.java` — Add findByProvider tests
- `src/test/java/com/softropic/payam/e2e/MtnMoMoPortIT.java` — Add null description arg
- `src/test/java/com/softropic/payam/fraud/FraudScoringServiceIT.java` — Add null description arg
- `src/test/java/com/softropic/payam/fraud/FraudThresholdGuardTest.java` — Add null description args
- Various E2E tests with `orange.callback-hmac-secret=` in `@TestPropertySource` — remove property

</canonical_refs>

<specifics>
## Specific Ideas

### Fallback Rules (from PRD)
| Situation | Behaviour |
|---|---|
| `cmd.externalReference()` is null | `orderId` falls back to `cmd.transactionId()` |
| `cmd.description()` is null | `description` defaults to `"Payment"` |
| `PlatformConfig` row for ORANGE is missing | `IllegalStateException` thrown, caught by orchestrator, returned as `PROVIDER_ERROR` (HTTP 502) |

### PayRequest new shape (exact)
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public class PayRequest {
    @JsonProperty("payToken")           private String payToken;
    @JsonProperty("subscriberMsisdn")   private String subscriberMsisdn;
    @JsonProperty("channelUserMsisdn")  private String channelUserMsisdn;
    @JsonProperty("amount")             private String amount;
    @JsonProperty("orderId")            private String orderId;
    @JsonProperty("description")        private String description;
    @JsonProperty("notifUrl")           private String notifUrl;
}
```

### InitTransactionResponse shape (exact)
```java
public class InitTransactionResponse {
    @JsonProperty("data") private Data data;
    @JsonProperty("message") private String message;
    public String getPayToken() { return data != null ? data.payToken : null; }
    public static class Data {
        @JsonProperty("payToken") private String payToken;
    }
}
```

### buildPayRequest() implementation (exact)
```java
private PayRequest buildPayRequest(PaymentCommand cmd, String payToken, String nationalMsisdn) {
    String channelMsisdn = platformConfigService.findByProvider("ORANGE").platformMsisdn();
    String orderId = cmd.externalReference() != null ? cmd.externalReference() : cmd.transactionId();
    String description = cmd.description() != null ? cmd.description() : "Payment";
    return PayRequest.of(payToken, nationalMsisdn, channelMsisdn,
                         cmd.amount().toPlainString(), orderId, description, config.getCallbackUrl());
}
```

### WireMock stub change (from → to)
```java
// BEFORE:
stubFor(get(urlEqualTo("/infos/merchant")).willReturn(aResponse().withStatus(200)
    .withBody("{\"payToken\":\"tok-abc-123\"}")));
// AFTER:
stubFor(post(urlEqualTo("/mp/init")).willReturn(aResponse().withStatus(200)
    .withBody("{\"data\":{\"payToken\":\"tok-abc-123\"},\"message\":\"OK\"}")));
```

</specifics>

<deferred>
## Deferred Ideas

None — PRD covers phase scope.

</deferred>

---

*Phase: 34-orange-money-flow-improvements*
*Context gathered: 2026-04-10 via PRD Express Path*
