# Orange Money API — Spring Boot Integration Guide

A practical developer guide for wrapping the Orange Money Core APIs in a Spring Boot service.
All examples use Spring's `RestTemplate` or `WebClient` — no dependency on the bundled OkHttp SDK is required.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Base Configuration](#2-base-configuration)
3. [Authentication](#3-authentication)
4. [Use Case 1 — Initiate a Payment & Receive Notification](#4-use-case-1--initiate-a-payment--receive-notification)
5. [Use Case 2 — Validate an Account Holder](#5-use-case-2--validate-an-account-holder)
6. [Use Case 3 — B2B Transfer (Channel-to-Channel)](#6-use-case-3--b2b-transfer-channel-to-channel)
7. [Use Case 4 — Account Balance](#7-use-case-4--account-balance)
8. [Use Case 5 — Validate User Identity](#8-use-case-5--validate-user-identity)
9. [Use Case 6 — Cancel / Revert a Payment](#9-use-case-6--cancel--revert-a-payment)
10. [Use Case 7 — Identity Management](#10-use-case-7--identity-management)
11. [Transaction Status Reference](#11-transaction-status-reference)
12. [Error Handling](#12-error-handling)

---

## 1. Overview

**Base URL:** `https://api-s1.orange.cm/omcoreapis/1.0.2`

The Orange Money API follows a consistent pattern for all financial transactions:

```
Step 1 — Init   → POST /{type}/init          → receive a payToken
Step 2 — Pay    → POST /{type}/pay           → submit transaction details
Step 3 — Push   → GET  /{type}/push/{token}  → trigger customer confirmation prompt (optional)
Step 4 — Poll   → GET  /{type}/paymentstatus/{token}  → check outcome (or receive via webhook)
```

The `payToken` is the unique handle for every transaction — keep it in your database from the moment you receive it.

### Transaction types at a glance

| Type | Endpoint prefix | Direction | Auth required |
|------|----------------|-----------|---------------|
| Cashout | `/cashout` | Partner → Customer (withdrawal) | Yes |
| Merchant Payment | `/mp` | Customer → Merchant | Yes |
| Cash-in | `/cashin` | Customer → Partner (deposit) | Yes |
| C2C | `/c2c` | Channel → Channel | Yes |
| Inverse C2C | `/ic2c` | Channel → Customer | Yes |
| Agent Cashout | `/acashout` | Agent → Recipient | Yes |
| Subscriber Info | `/infos/subscriber` | Query only | Yes |
| Password Update | `/auth/updatepassword` | Auth management | No |
| Bulk Status | `/transactions/paymentstatus` | Query only | No |

---

## 2. Base Configuration

### `application.yml`

```yaml
orange:
  base-url: https://api-s1.orange.cm/omcoreapis/1.0.2
  pay-url: https://api-s1.orange.cm/omcoreapis/1.0.2
  token-url: https://api-s1.orange.cm/token
  consumer-key: YOUR_CONSUMER_KEY
  consumer-secret: YOUR_CONSUMER_SECRET
  api-username: YOUR_API_USERNAME
  api-password: YOUR_API_PASSWORD
  channel-msisdn: "6XXXXXXXX"    # Your registered Orange Money channel number
```

### Spring configuration class

```java
@Configuration
@ConfigurationProperties(prefix = "orange")
public class OrangeMoneyConfig {
    private String baseUrl;
    private String payUrl;
    private String tokenUrl;
    private String consumerKey;
    private String consumerSecret;
    private String apiUsername;
    private String apiPassword;
    private String callbackUrl;

    // Getters and setters...
}
```

---

## 3. Authentication

### Step 1 — Request Access Token

Before calling any financial endpoints, you must obtain an OAuth2 Bearer token. This is done by sending a POST request to the token URL using Basic Auth with your `consumer-key` and `consumer-secret`.

**Request**
```http
POST /token HTTP/1.1
Host: api-s1.orange.cm
Authorization: Basic <base64(consumer-key:consumer-secret)>
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

**Response**
```json
{ 
    "access_token": "26a28627-3799-3694-a108-226be1ce2649", 
    "scope": "am_application_scope default", 
    "token_type": "Bearer", 
    "expires_in": 3600 
}
```

### Step 2 — Subsequent Requests

Every subsequent request requires **both** of these headers:

| Header | Value | Description |
|--------|-------|-------------|
| `Authorization` | `Bearer <access_token>` | OAuth2 token from Step 1 |
| `X-AUTH-TOKEN` | `<base64(api-username:api-password)>` | Base64 of Orange API credentials |

The `X-AUTH-TOKEN` is the base64-encoded value of `orange.api-username:orange.api-password`.
Example: `OMSANDBOXAPI:OMS@NDBOX@PI` → `T01TQU5EQk9YQVBJOk9NU0BOREJPWEBQSQ==`

---

## 4. Use Case 1 — Initiate a Payment & Receive Notification

This is the primary payment collection flow. The customer receives a push notification on their phone and approves the deduction. Orange calls your `notifUrl` when the transaction completes (success or failure).

**Best fit:** `Merchant Payment` (`/mp`) — customer pays merchant.
Use `Cashout` (`/cashout`) if the direction is partner-to-customer (you are paying the customer).

### Flow diagram

```
Your server          Orange API              Customer phone
     |                   |                        |
     |-- POST /mp/init -->|                        |
     |<-- { payToken } ---|                        |
     |                   |                        |
     |-- POST /mp/pay --->|                        |
     |   (notifUrl, amt)  |-- push prompt -------->|
     |<-- { status } -----|                        |
     |                   |                        |
     |                   |<-- customer confirms ---|
     |                   |                        |
     |<-- POST notifUrl --|  (async webhook)
     |   (final status)  |
```

### Step 1 — Init: obtain a payToken

**Request**
```http
POST https://api-s1.orange.cm/omcoreapis/1.0.2/mp/init
X-AUTH-TOKEN: <base64(api-username:api-password)>
Authorization: Bearer <access_token>
```
No request body.

**Response**
```json
{
  "message": "Merchant payment request successfully initiated",
  "data": {
    "payToken": "MP-XXXXXXXXXXXXXXXX"
  }
}
```

### Step 2 — Pay: submit transaction details

**Request**
```http
POST https://api-s1.orange.cm/omcoreapis/1.0.2/mp/pay
Content-Type: application/json
X-AUTH-TOKEN: <base64(api-username:api-password)>
Authorization: Bearer <access_token>
```

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `payToken` | string | Yes | Token from Step 1 |
| `subscriberMsisdn` | string | Yes | Customer's Orange Money phone number |
| `channelUserMsisdn` | string | Yes | Your merchant channel number |
| `amount` | string | Yes | Amount to charge (XAF, no decimals) |
| `orderId` | string | Yes | Your internal order/reference ID |
| `description` | string | Yes | Human-readable payment description |
| `notifUrl` | string | Yes | Your webhook URL for async notification |
| `pin` | string | Yes | Channel PIN (mandatory) |

```json
{
  "payToken": "MP-XXXXXXXXXXXXXXXX",
  "subscriberMsisdn": "6XXXXXXXXX",
  "channelUserMsisdn": "6XXXXXXXXX",
  "amount": "10",
  "orderId": "ORDER-2024-001",
  "description": "Payment for order #001",
  "notifUrl": "https://your-app.com/api/webhooks/orange-money",
  "pin": "XXXX"
}
```

**Response**

```json
{
  "data": {
    "payToken": "MP-XXXXXXXXXXXXXXXX",
    "txnid": "OrangeTxnId",
    "txnstatus": "200",
    "status": "PENDING",
    "inittxnstatus": "200",
    "inittxnmessage": "Initiated",
    "confirmtxnstatus": null,
    "confirmtxnmessage": null,
    "amount": "5000",
    "subscriberMsisdn": "6XXXXXXXXX",
    "channelUserMsisdn": "6XXXXXXXXX",
    "orderId": "ORDER-2024-001",
    "description": "Payment for order #001",
    "notifyUrl": "https://your-app.com/api/webhooks/orange-money",
    "txnmode": "MP",
    "createtime": "2024-01-15T10:30:00"
  },
  "message": "Pending"
}
```

### Step 3 (optional) — Push: trigger the confirmation prompt

If the customer has not already received the USSD prompt, you can trigger it manually:

```
GET https://api-s1.orange.cm/omcoreapis/1.0.2/mp/push/{payToken}
```

Returns the same response shape as the Pay step.

### Step 4 — Receive the webhook notification

Orange POSTs to your `notifUrl` when the transaction is resolved. The payload mirrors the payment status response.

**Example Spring Boot webhook controller:**

```java
@RestController
@RequestMapping("/api/webhooks")
public class OrangeMoneyWebhookController {

    @PostMapping("/orange-money")
    public ResponseEntity<Void> handleNotification(
            @RequestBody Map<String, Object> payload) {

        String payToken = extractPayToken(payload);
        String status = extractStatus(payload);
        String txnId = extractTxnId(payload);

        if ("SUCCESS".equalsIgnoreCase(status)) {
            paymentService.markPaid(payToken, txnId);
        } else {
            paymentService.markFailed(payToken, status);
        }

        // Orange expects HTTP 200 — anything else causes a retry
        return ResponseEntity.ok().build();
    }
}
```

> **Important:** Always return HTTP 200 from your webhook endpoint. Orange retries delivery on non-200 responses. Make your handler idempotent — the same notification may arrive more than once.

### Spring service example

```java
@Service
public class PaymentService {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    @Value("${orange-money.channel-msisdn}")
    private String channelMsisdn;

    public PaymentService(RestTemplate orangeMoneyRestTemplate,
                          @Qualifier("orangeMoneyBaseUrl") String baseUrl) {
        this.restTemplate = orangeMoneyRestTemplate;
        this.baseUrl = baseUrl;
    }

    public String initiateMerchantPayment(String customerMsisdn, String amount,
                                          String orderId, String description,
                                          String notifUrl) {
        // Step 1: get payToken
        String payToken = initTransaction("/mp/init");

        // Step 2: submit payment details
        Map<String, String> body = new LinkedHashMap<>();
        body.put("payToken", payToken);
        body.put("subscriberMsisdn", customerMsisdn);
        body.put("channelUserMsisdn", channelMsisdn);
        body.put("amount", amount);
        body.put("orderId", orderId);
        body.put("description", description);
        body.put("notifUrl", notifUrl);

        restTemplate.postForObject(baseUrl + "/mp/pay", body, Map.class);

        return payToken; // store this — you'll need it to match the webhook
    }

    private String initTransaction(String path) {
        Map response = restTemplate.postForObject(baseUrl + path, null, Map.class);
        Map data = (Map) response.get("data");
        return (String) data.get("payToken");
    }
}
```

---

## 5. Use Case 2 — Validate an Account Holder

Verify that a subscriber exists and retrieve their registered name before initiating a transfer.

**Endpoint:** `POST /infos/subscriber/{usertype}/{msisdn}`

### Path parameters

| Parameter | Values | Description |
|-----------|--------|-------------|
| `usertype` | `customer` or `channel` | Whether looking up an end-customer or a channel/merchant account |
| `msisdn` | e.g. `6XXXXXXXXX` | Subscriber's Orange Money phone number (short format) |

### Request body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `channelMsisdn` | string | Yes | Your channel phone number (authorising the lookup) |

```json
{
  "channelMsisdn": "6XXXXXXXXX"
}
```

### Response

```json
{
  "data": {
    "firstname": "Jean",
    "lastname": "Dupont"
  },
  "message": "OK"
}
```

A successful response (non-null `firstname`/`lastname`) confirms the subscriber exists and is active.

### Spring service example

```java
public Optional<SubscriberInfo> validateAccountHolder(String msisdn, String userType) {
    String url = baseUrl + "/infos/subscriber/{usertype}/{msisdn}";

    Map<String, String> body = Map.of("channelMsisdn", channelMsisdn);

    try {
        Map response = restTemplate.postForObject(
            url,
            body,
            Map.class,
            userType,   // {usertype}
            msisdn      // {msisdn}
        );

        Map data = (Map) response.get("data");
        if (data == null) return Optional.empty();

        return Optional.of(new SubscriberInfo(
            (String) data.get("firstname"),
            (String) data.get("lastname"),
            msisdn
        ));
    } catch (HttpClientErrorException e) {
        return Optional.empty(); // subscriber not found
    }
}
```

---

## 6. Use Case 3 — B2B Transfer (Channel-to-Channel)

A B2B (Business-to-Business) transfer moves funds between two Orange Money channel accounts.
Use the **C2C** (Channel-to-Channel) endpoint.

For channel-to-customer transfers, use **Inverse C2C** (`/ic2c`) instead — it follows the same pattern but the destination is a subscriber MSISDN, and it supports a `notifUrl` callback.

### C2C Flow

**Step 1 — Init**
```
POST https://api-s1.orange.cm/omcoreapis/1.0.2/c2c/init
```
No body. Returns `{ "data": { "payToken": "C2C-XXX" } }`.

**Step 2 — Pay**
```
POST https://api-s1.orange.cm/omcoreapis/1.0.2/c2c/pay
```

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `payToken` | string | Yes | Token from init |
| `fromChannelMsisdn` | string | Yes | Source channel (your account) |
| `toChannelMsisdn` | string | Yes | Destination channel |
| `amount` | string | Yes | Amount in XAF |
| `orderId` | string | Yes | Your reference |
| `description` | string | Yes | Transfer description |
| `pin` | string | Yes | Source channel PIN |

```json
{
  "payToken": "C2C-XXXXXXXXXXXXXXXX",
  "fromChannelMsisdn": "6XXXXXXXXX",
  "toChannelMsisdn": "6YYYYYYYYY",
  "amount": "100000",
  "orderId": "B2B-TRANSFER-001",
  "description": "Monthly supplier payment",
  "pin": "XXXX"
}
```

**Response**

```json
{
  "data": {
    "payToken": "C2C-XXXXXXXXXXXXXXXX",
    "txnid": "OrangeTxnId",
    "txnstatus": "200",
    "status": "SUCCESS",
    "fromChannelMsisdn": "6XXXXXXXXX",
    "toChannelMsisdn": "6YYYYYYYYY",
    "amount": "100000",
    "txnmode": "C2C",
    "createtime": "2024-01-15T10:30:00"
  },
  "message": "OK"
}
```

**Step 3 — Check status (if needed)**
```
GET https://api-s1.orange.cm/omcoreapis/1.0.2/c2c/paymentstatus/{payToken}
```

### Inverse C2C (Channel → Customer)

Use `/ic2c` endpoints with the same three-step flow. The pay body uses `toChannelMsisdn` for the customer recipient and supports `notifUrl` for async callbacks:

```json
{
  "payToken": "IC2C-XXXXXXXXXXXXXXXX",
  "fromChannelMsisdn": "6XXXXXXXXX",
  "toChannelMsisdn": "6YYYYYYYYY",
  "amount": "5000",
  "orderId": "PAYOUT-001",
  "description": "Commission payout",
  "notifUrl": "https://your-app.com/api/webhooks/orange-money",
  "pin": "XXXX"
}
```

---

## 7. Use Case 4 — Account Balance

> **Note:** The Orange Money Core API (v1.0.2) does **not** expose a dedicated account balance endpoint. There is no `/balance` or equivalent resource in this API version.

**What is available:**
- Retrieve subscriber name to confirm an account exists (`/infos/subscriber`)
- Query the status of individual transactions (`/paymentstatus/{payToken}`)
- Query multiple transaction statuses in bulk (`/transactions/paymentstatus`)

**Workaround options:**
1. Maintain a running balance in your own database by recording all credits and debits via transaction records and webhook notifications.
2. Contact your Orange Money partner representative to enquire about balance API access — it may be available under a different API tier or require additional partner credentials.

### Bulk transaction status query

```
POST https://api-s1.orange.cm/omcoreapis/1.0.2/transactions/paymentstatus
Content-Type: application/json
```
*No authentication headers required for this endpoint.*

**Request body**
```json
{
  "payload": "payToken1,payToken2,payToken3"
}
```
`payload` is a comma-separated list of `payToken` values.

---

## 8. Use Case 5 — Validate User Identity

Identity validation uses the subscriber info endpoint to confirm that a phone number is a registered Orange Money account and to retrieve the account holder's name.

The same `/infos/subscriber` endpoint used for account validation (see Use Case 2) serves this purpose. A successful lookup with a non-null name confirms the identity is registered.

### Customer vs Channel lookup

```java
// Validate a regular customer (consumer)
validateAccountHolder("6XXXXXXXXX", "customer");

// Validate a merchant/business channel account
validateAccountHolder("6XXXXXXXXX", "channel");
```

### Using name matching for identity confirmation

```java
public boolean verifyIdentity(String msisdn, String expectedFirstName, String expectedLastName) {
    return validateAccountHolder(msisdn, "customer")
        .map(info ->
            info.getFirstname().equalsIgnoreCase(expectedFirstName) &&
            info.getLastname().equalsIgnoreCase(expectedLastName))
        .orElse(false);
}
```

---

## 9. Use Case 6 — Cancel / Revert a Payment

> **Note:** The Orange Money Core API (v1.0.2) does **not** include a dedicated transaction reversal or cancellation endpoint. There is no `/cancel`, `/reverse`, or `/refund` resource.

### Prevention (preferred approach)

The best strategy is to avoid the need for reversal by treating a transaction as pending until you receive a confirmed success from the webhook:

1. Do not fulfil the order until you receive a `SUCCESS` status via webhook or polling.
2. If no webhook arrives within your timeout window, poll `/paymentstatus/{payToken}` to determine outcome before acting.

### Polling for timeout handling

```java
public TransactionStatus pollUntilResolved(String transactionType, String payToken,
                                            int maxAttempts, Duration interval)
        throws InterruptedException {

    for (int attempt = 0; attempt < maxAttempts; attempt++) {
        String url = baseUrl + "/" + transactionType + "/paymentstatus/" + payToken;
        Map response = restTemplate.getForObject(url, Map.class);
        Map data = (Map) response.get("data");
        String status = (String) data.get("status");

        if ("SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
            return new TransactionStatus(payToken, status, (String) data.get("txnid"));
        }

        Thread.sleep(interval.toMillis());
    }

    return new TransactionStatus(payToken, "TIMEOUT", null);
}
```

### For actual reversals

If your business requires true payment reversal (refund), contact your Orange Money partner representative. This is typically handled via:
- A separate refund API available to licensed financial partners
- A manual back-office process for individual disputed transactions

---

## 10. Use Case 7 — Identity Management

### Update Password

The only credential management endpoint available in this API version updates the account password for a channel user.

**Endpoint:** `POST /auth/updatepassword`
*No authentication headers required.*

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `username` | string | Yes | Orange Money username |
| `password` | string | Yes | Current password |
| `newPassword` | string | Yes | New password |

```json
{
  "username": "your_om_username",
  "password": "currentPassword",
  "newPassword": "newSecurePassword"
}
```

**Response**

```json
{
  "message": "Password updated successfully"
}
```

### Spring service example

```java
public boolean updateChannelPassword(String username, String currentPassword,
                                      String newPassword) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("password", currentPassword);
    body.put("newPassword", newPassword);

    // Note: this endpoint does NOT use the auth interceptor headers
    RestTemplate plainRestTemplate = new RestTemplate();
    plainRestTemplate.getInterceptors().add((request, b, execution) -> {
        request.getHeaders().set("Content-Type", "application/json");
        return execution.execute(request, b);
    });

    try {
        Map response = plainRestTemplate.postForObject(
            baseUrl + "/auth/updatepassword", body, Map.class);
        return response != null && response.get("message") != null;
    } catch (HttpClientErrorException e) {
        return false;
    }
}
```

> **Note:** Session/token-based login is not part of this API version. Authentication is credential-based via `X-AUTH-TOKEN` and `Authorization: Bearer` headers configured at the partner level.

---

## 11. Transaction Status Reference

### Status fields

| Field | Description |
|-------|-------------|
| `payToken` | Unique transaction handle — your primary lookup key |
| `txnid` | Orange's internal transaction ID (use for support queries) |
| `status` | High-level status: `PENDING`, `SUCCESS`, `FAILED` |
| `txnstatus` | Numeric HTTP-style status code |
| `inittxnstatus` | HTTP status from the init step |
| `confirmtxnstatus` | HTTP status from the confirmation step |
| `inittxnmessage` | Message from the init step |
| `confirmtxnmessage` | Message from the confirmation step |
| `txnmessage` | Overall outcome message |

### Common status values

| `status` | Meaning |
|----------|---------|
| `PENDING` | Transaction initiated; awaiting customer confirmation |
| `SUCCESS` | Customer confirmed; funds transferred |
| `FAILED` | Transaction failed (declined, expired, or error) |
| `TIMEOUT` | Customer did not respond within the window |

### Status check endpoint (per type)

```
GET https://api-s1.orange.cm/omcoreapis/1.0.2/{type}/paymentstatus/{payToken}
```

Where `{type}` is one of: `mp`, `cashout`, `cashin`, `c2c`, `ic2c`, `acashout`.

### Bulk status check

```
POST https://api-s1.orange.cm/omcoreapis/1.0.2/transactions/paymentstatus
Content-Type: application/json

{
  "payload": "TOKEN-1,TOKEN-2,TOKEN-3"
}
```

---

## 12. Error Handling

### HTTP status codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 400 | Bad request — check request body fields |
| 401 | Unauthorised — check `X-AUTH-TOKEN` and `Bearer` headers |
| 404 | Resource not found — check payToken or MSISDN |
| 500 | Orange server error — retry with backoff |

### Spring exception handling pattern

```java
@Service
public class OrangeMoneyService {

    public <T> T callWithErrorHandling(Supplier<T> call, String context) {
        try {
            return call.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new OrangeMoneyAuthException("Authentication failed — check API credentials");
        } catch (HttpClientErrorException.BadRequest e) {
            throw new OrangeMoneyRequestException("Bad request for " + context + ": "
                + e.getResponseBodyAsString());
        } catch (HttpClientErrorException e) {
            throw new OrangeMoneyRequestException("Client error (" + e.getStatusCode()
                + ") for " + context);
        } catch (HttpServerErrorException e) {
            throw new OrangeMoneyServerException("Orange server error for " + context
                + " — retry later");
        } catch (ResourceAccessException e) {
            throw new OrangeMoneyServerException("Network error reaching Orange Money API");
        }
    }
}
```

### Idempotency

Orange Money does not guarantee idempotency on `/pay` calls. Always:
1. Store the `payToken` immediately after the `/init` call.
2. Before calling `/pay`, check your database to ensure you have not already submitted this `payToken`.
3. On timeout/network error on `/pay`, use `/paymentstatus/{payToken}` to determine whether the call succeeded before retrying.

### Recommended local transaction record

```sql
CREATE TABLE om_transactions (
    id            BIGSERIAL PRIMARY KEY,
    pay_token     VARCHAR(64)  UNIQUE NOT NULL,
    txn_id        VARCHAR(64),
    order_id      VARCHAR(128) NOT NULL,
    type          VARCHAR(16)  NOT NULL,  -- mp, cashout, c2c, etc.
    status        VARCHAR(16)  NOT NULL DEFAULT 'INIT',
    amount        NUMERIC(12,0) NOT NULL,
    msisdn        VARCHAR(20),
    notif_url     VARCHAR(512),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

Save the `payToken` immediately in `INIT` status after step 1 completes, before calling pay. Update `status` on webhook receipt or polling confirmation.
