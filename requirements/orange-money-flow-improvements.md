# Orange Money Flow Improvements

**Date:** 2026-04-10
**Scope:** Align `OrangeMoneyClient` and `OrangeMoneyPort` with the Orange Money spec (Use Case 1 — Initiate a Payment & Receive Notification) as described in `orange-money-integration-guide.md`.

---

## Background

The current implementation of the Orange Money payment flow (`OrangeMoneyPort`, `OrangeMoneyClient`, `PayRequest`) does not match the actual Orange API spec for Use Case 1. Specifically:

1. `PayRequest` is modelled on a browser-redirect form API (with `merchant_key`, `return_url`, `cancel_url`, `lang`) rather than the push-payment `/mp/pay` endpoint.
2. The `payToken` and `subscriberMsisdn` are fetched/computed correctly but **never sent** to Orange — `buildPayRequest()` ignores both parameters it receives.
3. The `channelUserMsisdn` (merchant channel phone number) is never sent.
4. `orderId` maps to the wrong field.
5. `notifUrl` is never set — Orange cannot call back.
6. The init endpoint (`/mp/init`) is wrong — `/infos/merchant` is used instead.
7. The callback controller contains HMAC verification logic that is not part of the Orange spec.

---

## Changes Required

### 1. Replace `PayRequest` entirely

**File:** `src/main/java/com/softropic/payam/orange/contract/dto/PayRequest.java`

The current class must be replaced. The `/mp/pay` body per the spec is:

| JSON field | Source | Notes |
|---|---|---|
| `payToken` | `OrangeMoneyClient.initTransaction()` response | Token from `POST /mp/init` |
| `subscriberMsisdn` | `cmd.msisdn()` stripped to national format | Same `stripCountryCode()` logic already in port |
| `channelUserMsisdn` | `PlatformConfigService.findByProvider("ORANGE")` → `platformMsisdn` | Stored as national format (see §3) |
| `amount` | `cmd.amount().toPlainString()` | No decimals; XAF is integer currency |
| `orderId` | `cmd.externalReference()`, fallback to `cmd.transactionId()` | See §Fallback Rules |
| `description` | `cmd.description()` if non-null, else `"Payment"` | Optional field (see §5) |
| `notifUrl` | Dynamically constructed — see §4 | Required by Orange for async callback |

Remove all these fields from `PayRequest`: `merchant_key`, `currency`, `order_id`, `return_url`, `cancel_url`, `lang`, `reference`.

New class shape:

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

---

### 2. Fix the payToken init endpoint: `/mp/init` instead of `/infos/merchant`

**File:** `src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java`

The spec (Use Case 1, Step 1) says:

```
POST https://apis.orange.cm/mp/init
```

No request body. Response:

```json
{ "data": { "payToken": "MP-XXXXXXXXXXXXXXXX" } }
```

**Current wrong implementation:** `getMerchantInfo()` calls `GET /infos/merchant`.

**Fix:** Rename `getMerchantInfo()` to `initTransaction()` (or keep the name but change the HTTP method and path). The method should POST to `/mp/init` with no body and return the `payToken` from the response `data` object.

The response wrapper `{ "data": { "payToken": "..." }, "message": "OK" }` requires a wrapper DTO or inline parsing. Use a simple wrapper:

```java
// New DTO or inner record
public class InitTransactionResponse {
    @JsonProperty("data") private Data data;
    @JsonProperty("message") private String message;

    public String getPayToken() { return data != null ? data.payToken : null; }

    public static class Data {
        @JsonProperty("payToken") private String payToken;
    }
}
```

`MerchantInfoResponse.java` can be deleted since it is replaced by `InitTransactionResponse`.

**Impact on `OrangeMoneyPort.initiateMerchantPayment()`:**
- Step 2 currently: `orangeMoneyClient.getMerchantInfo(token)` → replace with `orangeMoneyClient.initTransaction(token)`
- The returned payToken flows into `persistPayToken()` and `buildPayRequest()` exactly as before.

**Impact on tests:**
- `OrangeMoneyPortIT` stubs `GET /infos/merchant` — change to stub `POST /mp/init` returning `{ "data": { "payToken": "tok-abc-123" } }`.
- `OrangePaymentInitiationE2ETest` stubs `GET /infos/merchant` — same change.
- `OrangePayTokenExpiryE2ETest` — check and update if it stubs `/infos/merchant`.

---

### 3. Add `findByProvider(String)` to `PlatformConfigService`

**File:** `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java`

Add a read-only method:

```java
@Transactional(readOnly = true)
public PlatformConfigDto findByProvider(String provider) {
    return platformConfigRepository.findByProvider(provider.toUpperCase())
            .map(c -> new PlatformConfigDto(c.getProvider(), c.getPlatformMsisdn()))
            .orElseThrow(() -> new IllegalStateException(
                "Platform MSISDN not configured for provider: " + provider));
}
```

`PlatformConfigRepository.findByProvider(String)` already exists — no repository change needed.

**Platform MSISDN format:** Stored and used in **national format** (e.g. `690000001`, no country code prefix). The `channelUserMsisdn` is passed to Orange as-is without `stripCountryCode()`.

**Missing config — error handling:** If no row exists for `ORANGE` in `platform_config`:
- `OrangeMoneyPort.initiateMerchantPayment()` catches the `IllegalStateException` and logs the real reason internally.
- The exception propagates up to `PaymentOrchestrator`, where it is caught by the generic `catch (Exception e)` block and returned to the API caller as `OrchestratorError.PROVIDER_ERROR` with a generic `"Internal error"` message — the real cause is logged at `ERROR` level with `kv("errorCode", ...)` but is not exposed to the client.
- No new error code or exception type is needed; `PROVIDER_ERROR` maps to HTTP 502, which is the correct signal to the client that the provider call failed.

**Test:** Add a test case to `PlatformConfigServiceTest` for `findByProvider()` — both the happy path (returns correct DTO) and the not-found path (throws `IllegalStateException`).

---

### 4. `notifUrl` — dynamically constructed

**Source:** The server's own base URL + the Orange callback path.

Add a new property to `OrangeMoneyConfig`:

```yaml
# application-dev.yaml (and prod equivalent)
orange:
  callback-url: ${baseurl}/v1/callbacks/orange
```

Add the field to `OrangeMoneyConfig.java`:

```java
private String callbackUrl;
public String getCallbackUrl() { return callbackUrl; }
public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
```

`baseurl` is already defined at the top of `application-dev.yaml` as `http://localhost`. In production this becomes the public-facing domain.

`OrangeMoneyPort.buildPayRequest()` reads `config.getCallbackUrl()` and sets it as `notifUrl` in the new `PayRequest`.

---

### 5. Add `description` to `PaymentRequest` and `PaymentCommand`

**`PaymentRequest`** (REST DTO, `src/main/java/com/softropic/payam/payment/contract/PaymentRequest.java`):

Add an optional field — **no `@NotBlank`**, nullable:

```java
/** Human-readable payment description. Optional. Used by Orange in the push notification shown to the subscriber. */
String description
```

**`PaymentCommand`** (shared command record, `src/main/java/com/softropic/payam/common/payment/PaymentCommand.java`):

Add `String description` as the last field (after `deviceFingerprint`):

```java
public record PaymentCommand(
    String transactionId,
    String traceId,
    Long tenantId,
    String msisdn,
    BigDecimal amount,
    String currency,
    String externalReference,
    String idempotencyKey,
    MobilePaymentProvider provider,
    String clientIp,
    String userAgent,
    String deviceFingerprint,
    String description          // NEW — nullable; Orange uses it in push prompt
) {}
```

**`PaymentOrchestrator.initiate()`:** Pass `request.description()` when constructing `PaymentCommand`.

**MTN:** `MtnMoMoPort` does not use `description` — it simply ignores the new field. No changes needed in the MTN adapter.

---

### 6. Fix `buildPayRequest()` in `OrangeMoneyPort`

**File:** `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java`

Replace the current broken `buildPayRequest()`:

```java
private PayRequest buildPayRequest(PaymentCommand cmd, String payToken, String nationalMsisdn) {
    String channelMsisdn = platformConfigService.findByProvider("ORANGE").platformMsisdn();
    String orderId = cmd.externalReference() != null
            ? cmd.externalReference()
            : cmd.transactionId();
    String description = cmd.description() != null ? cmd.description() : "Payment";

    return PayRequest.of(
        payToken,
        nationalMsisdn,       // subscriberMsisdn
        channelMsisdn,        // channelUserMsisdn — national format from PlatformConfig
        cmd.amount().toPlainString(),
        orderId,
        description,
        config.getCallbackUrl()
    );
}
```

Inject `PlatformConfigService` into `OrangeMoneyPort` via constructor. No circular dependency — `PlatformConfigService` has no dependency on anything in the `orange` package.

---

### 7. Remove HMAC verification from `OrangeCallbackController`

**File:** `src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java`

HMAC callback signing is **not part of the Orange Money spec** as documented in `orange-money-integration-guide.md`. Remove the entire block (lines ~71–106 currently) including:

- The HMAC verification conditional block (`if (hmacSecret != null && !hmacSecret.isBlank())`)
- The `HttpServletRequest request` parameter from `handleCallback()` (no longer needed)
- The import `java.nio.charset.StandardCharsets` if it becomes unused

Remove from `OrangeMoneyConfig.java`:
- `callbackHmacSecret` field, getter, and setter

Remove from `application-dev.yaml` (and any other config files):
- `orange.callback-hmac-secret` property

**Test impact:**
- `OrangeCallbackControllerIT`: Delete the test `shouldReject401WhenHmacSecretConfiguredAndSignatureMissing()` entirely (tests now-removed logic). Remove `OrangeMoneyConfig` injection from the test class and the `@TestPropertySource` property `orange.callback-hmac-secret=`.
- `WebhookDoubleCheckIT`, `WebhookDeliveryIT`, `AbstractPayamE2ETest`, `OutboundWebhookDeliveryE2ETest`: Remove `orange.callback-hmac-secret=` from their `@TestPropertySource` / `properties` annotations.
- The IP whitelist (`orange.callback-ip-whitelist`) is **not** from the spec either, but it is a standard operational security control — leave it in place.

---

### 8. Update `PaymentRequestBuilder` test helper

**File:** `src/test/java/com/softropic/payam/e2e/builder/PaymentRequestBuilder.java`

Add `description` field (default `null`) and a `withDescription(String)` setter. Update `build()` to pass it to the `PaymentRequest` constructor.

---

### 9. Update all `PaymentCommand` construction sites

Every call site that constructs `new PaymentCommand(...)` must add a `description` argument (pass `null` for all non-Orange paths and existing tests):

| File | Action |
|---|---|
| `PaymentOrchestrator.java` | Pass `request.description()` |
| `OrangeMoneyPortIT.java` (4 call sites) | Pass `null` |
| `MtnMoMoPortIT.java` | Pass `null` |
| `FraudScoringServiceIT.java` | Pass `null` |
| `FraudThresholdGuardTest.java` (2 call sites) | Pass `null` |

---

### 10. Update WireMock stubs for `/mp/init`

Wherever tests stub `GET /infos/merchant` returning `{"payToken":"..."}`, change to stub `POST /mp/init` returning the spec-shaped response:

```json
{ "data": { "payToken": "tok-abc-123" }, "message": "OK" }
```

Files to update:
- `OrangeMoneyPortIT.java` — `merchant_payment_initiation_returns_pending_result_with_pay_token()` and `initiate_throws_subscriber_inactive_exception_when_msisdn_inactive()`
- `OrangePaymentInitiationE2ETest.java` — `setupPreconditions()`
- Any other test that stubs `/infos/merchant`

---

## Fallback Rules

| Situation | Behaviour |
|---|---|
| `cmd.externalReference()` is null | `orderId` falls back to `cmd.transactionId()` |
| `cmd.description()` is null | `description` defaults to `"Payment"` |
| `PlatformConfig` row for ORANGE is missing | `IllegalStateException` thrown inside `buildPayRequest()`, logged at ERROR, caught by orchestrator, returned to caller as `PROVIDER_ERROR` (HTTP 502) |

---

## Acceptance Criteria

1. `POST /mp/pay` wire body contains exactly: `payToken`, `subscriberMsisdn`, `channelUserMsisdn`, `amount`, `orderId`, `description`, `notifUrl` — and no other fields.
2. `payToken` originates from `POST /mp/init` (not `GET /infos/merchant`).
3. `subscriberMsisdn` is the national-format MSISDN from `cmd.msisdn()` (country code stripped).
4. `channelUserMsisdn` is the national-format MSISDN from `PlatformConfigService.findByProvider("ORANGE")`.
5. `orderId` equals `cmd.externalReference()` when non-null; falls back to `cmd.transactionId()`.
6. `notifUrl` equals the configured `orange.callback-url` value.
7. `OrangeCallbackController` no longer contains HMAC verification logic.
8. `OrangeMoneyConfig` no longer contains `callbackHmacSecret`.
9. `PaymentRequest` and `PaymentCommand` have an optional `description` field.
10. `PlatformConfigService.findByProvider(String)` exists and throws `IllegalStateException` when not found.
11. All tests pass: `mvn verify` completes with zero failures.
12. The orchestrator unification contract is preserved: `PaymentCommand` is the shared command object; MTN port ignores `description` without modification.
