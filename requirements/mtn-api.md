Below is a **clean, structured “local documentation”** for the **MTN MoMo Collections API** (plus required remittance/B2B endpoints), rewritten so you can store it internally (e.g., in Confluence, Markdown, or Swagger-style docs).

I’ve consolidated the official flows + SDK behavior + platform notes into a **complete endpoint reference + request/response schemas + flow diagrams**.

---

# 📘 MTN MoMo – Collections API (Local Documentation)

## 🔐 Base Configuration

**Base URL**

* Sandbox: `https://sandbox.momodeveloper.mtn.com`
* Production: Provided per region ([Hexdocs][1])

**Headers (Common to all endpoints)**

```http
Authorization: Bearer {access_token}
X-Reference-Id: {UUID}
X-Target-Environment: sandbox | production
Ocp-Apim-Subscription-Key: {primary_key}
Content-Type: application/json
```

---

# 🔑 AUTHENTICATION

## 1. Create API User

```http
POST /v1_0/apiuser
```

### Request

```json
{
  "providerCallbackHost": "example.com"
}
```

### Response

```http
201 Created
```

---

## 2. Generate API Key

```http
POST /v1_0/apiuser/{apiUserId}/apikey
```

### Response

```json
{
  "apiKey": "string"
}
```

---

## 3. Get Access Token

```http
POST /collection/token/
```

### Headers

```http
Authorization: Basic base64(apiUserId:apiKey)
```

### Response

```json
{
  "access_token": "string",
  "token_type": "string",
  "expires_in": 3600
}
```

---

# 💰 COLLECTION ENDPOINTS

---

# 1. Request To Pay

```http
POST /collection/v1_0/requesttopay
```

### Description

Requests payment from a customer. Transaction is **asynchronous**.

---

### Request Body

```json
{
  "amount": "100.00",
  "currency": "EUR",
  "externalId": "123456",
  "payer": {
    "partyIdType": "MSISDN",
    "partyId": "256774290781"
  },
  "payerMessage": "Payment request",
  "payeeNote": "Order #123"
}
```

---

### Response

```http
202 Accepted
```

---

### Get Payment Status

```http
GET /collection/v1_0/requesttopay/{referenceId}
```

### Response

```json
{
  "amount": "100.00",
  "currency": "EUR",
  "financialTransactionId": "string",
  "externalId": "123456",
  "status": "PENDING | SUCCESSFUL | FAILED",
  "reason": "string"
}
```

---

### 🔄 Flow Diagram

```
Client → POST requesttopay → MoMo
MoMo → 202 Accepted
Customer → Approves on phone
MoMo → Process transaction
MoMo → Callback OR Client polls status
```

---

# 2. Validate Account Holder

```http
GET /collection/v1_0/accountholder/{type}/{id}/active
```

### Example

```http
GET /accountholder/msisdn/256774290781/active
```

### Response

```http
200 OK (Active)
404 Not Found (Inactive)
```

✔ Used to confirm user exists and is active

---

# 3. Get Account Balance

```http
GET /collection/v1_0/account/balance
```

### Response

```json
{
  "availableBalance": "5000.00",
  "currency": "EUR"
}
```

---

# 4. Get Balance in Specific Currency

```http
GET /collection/v1_0/account/balance?currency=EUR
```

### Response

```json
{
  "availableBalance": "1000.00",
  "currency": "EUR"
}
```

---

# 5. Validate Consumer Identity (KYC)

```http
GET /collection/v1_0/accountholder/{type}/{id}/basicuserinfo
```

### Response

```json
{
  "name": "John Doe",
  "gender": "MALE",
  "birthdate": "1990-01-01",
  "locale": "en"
}
```

✔ Requires customer consent flow

---

# 6. Payment Status

(Same as RequestToPay status endpoint)

```http
GET /collection/v1_0/requesttopay/{referenceId}
```

---

# 🔔 CALLBACK / NOTIFY

## Callback (Configured per request)

```http
POST {callbackUrl}
```

### Payload

```json
{
  "financialTransactionId": "string",
  "externalId": "123456",
  "status": "SUCCESSFUL"
}
```

✔ Sent once after final state

---

# 📩 DELIVERY NOTIFICATION (Custom Notify)

```http
POST /collection/v1_0/notify
```

### Request

```json
{
  "message": "Your payment was successful",
  "externalId": "123456"
}
```

---

# 🔁 REFUND (via Disbursement / Reversal Pattern)

⚠️ No direct “refund” endpoint in Collections.

Use:

```http
POST /disbursement/v1_0/transfer
```

### Request

```json
{
  "amount": "100.00",
  "currency": "EUR",
  "externalId": "refund-123",
  "payee": {
    "partyIdType": "MSISDN",
    "partyId": "256774290781"
  },
  "payerMessage": "Refund",
  "payeeNote": "Refund issued"
}
```

---

# 🧾 PRE-APPROVAL (Tokenized Payments)

```http
POST /collection/v1_0/preapproval
```

### Request

```json
{
  "payer": {
    "partyIdType": "MSISDN",
    "partyId": "256774290781"
  }
}
```

### Response

```json
{
  "preApprovalId": "string",
  "status": "PENDING | ACTIVE"
}
```

---

# 🔄 B2B PAYMENT (From Remittance API)

```http
POST /remittance/v1_0/transfer
```

### Request

```json
{
  "amount": "500.00",
  "currency": "EUR",
  "externalId": "b2b-001",
  "payee": {
    "partyIdType": "MSISDN",
    "partyId": "256774290781"
  },
  "payerMessage": "B2B Payment",
  "payeeNote": "Invoice payment"
}
```

---

### Status Check

```http
GET /remittance/v1_0/transfer/{referenceId}
```

---

# 🔄 B2B FLOW

```
Business A → POST transfer → MoMo
MoMo → 202 Accepted
MoMo → Process transfer
MoMo → Callback OR poll status
```

---

# 🧠 FULL PAYMENT FLOW (END-TO-END)

```
1. Validate Account Holder
2. Request To Pay
3. Customer Approves
4. Receive Callback / Poll Status
5. (Optional) Send Notification
6. (Optional) Refund via Disbursement
```

---

# ⚙️ IMPORTANT BEHAVIOR

### Async Processing

* RequestToPay & Transfer return **202 Accepted**
* Final result comes via:

    * Callback OR
    * Status polling

---

### Transaction States

* `PENDING`
* `SUCCESSFUL`
* `FAILED` ([PyPI][2])

---

### Key Concepts

* `X-Reference-Id`: Unique per transaction
* `externalId`: Your internal reference
* `callbackUrl`: Optional but recommended

---

# 📊 SUMMARY TABLE

| Feature              | Endpoint                  |
| -------------------- | ------------------------- |
| Request Payment      | POST /requesttopay        |
| Check Payment Status | GET /requesttopay/{id}    |
| Validate Account     | GET /accountholder/...    |
| Get Balance          | GET /account/balance      |
| Get User Info (KYC)  | GET /basicuserinfo        |
| Callback             | Configured URL            |
| Notify               | POST /notify              |
| Refund               | Disbursement transfer     |
| PreApproval          | POST /preapproval         |
| B2B Payment          | POST /remittance/transfer |

---


[1]: https://hexdocs.pm/ex_mtn_momo/ExMtnMomo.Collection.html?utm_source=chatgpt.com "ExMtnMomo.Collection — ExMtnMomo v0.1.2"
[2]: https://pypi.org/project/mtnmomoapi/?utm_source=chatgpt.com "mtnmomoapi · PyPI"
