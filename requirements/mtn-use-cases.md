Here is a **structured local documentation (use case document)** for the **MTN MoMo Collections API**, based directly on the official MTN MoMo use-case flows you provided. I’ve rewritten it into a **clean, developer-friendly specification with sequence diagrams** (text-based so you can easily convert to UML / PlantUML).

---

# 📘 MTN MoMo Collections API – Use Case Documentation

## 1. Overview

The **Collections API** enables merchants (providers) to **collect payments from customers (payers)** via MTN Mobile Money.

### Key Characteristics

* Asynchronous processing (`POST → 202 Accepted → callback`)
* Customer authorization required for debit operations
* Callback + polling (GET) for final status
* OAuth2 + Subscription Key authentication

---

## 2. Actors

* **Provider System** (Merchant backend)
* **Wallet Platform** (MTN MoMo)
* **Customer (Payer)**
* **Authorization Service** (for identity/KYC flows)

---

## 3. Common Flow Pattern

Most transactional APIs follow this pattern:

```
Provider → Wallet Platform: POST request
Wallet Platform → Provider: 202 Accepted
Wallet Platform → Customer: Authorization request
Customer → Wallet Platform: Approve/Reject
Wallet Platform → Provider: Callback (SUCCESS/FAILED)
Provider → Wallet Platform: GET status (optional)
```

---

# 4. Use Cases

---

## 4.1 Request To Pay

### Description

Request a payment from a customer who must approve it on their device.

### API Flow

1. Customer selects products and checkout
2. Provider collects MSISDN + amount
3. Provider sends `POST /requesttopay`
4. Wallet returns `202 Accepted`
5. Customer receives prompt (USSD/app)
6. Customer approves/rejects
7. Wallet processes transaction
8. Callback sent (optional)
9. Provider checks status via `GET`

### Sequence Diagram

```
Customer → Provider: Checkout
Provider → Wallet: POST /requesttopay
Wallet → Provider: 202 Accepted
Wallet → Customer: Payment request
Customer → Wallet: Approve/Reject
Wallet → Provider: Callback (SUCCESS/FAILED)
Provider → Wallet: GET /requesttopay/{id}
```

### Notes

* Transaction remains **PENDING** until user action
* Callback is sent **once only**

---

## 4.2 Validate Account Holder

### Description

Check if a customer account is **active and able to transact**.

### API Flow

1. Provider sends `GET /accountholder/{id}`
2. Wallet validates account
3. Returns `200 OK` if active

### Sequence Diagram

```
Provider → Wallet: GET /accountholder/{id}
Wallet → Provider: 200 OK (Active)
```

### Notes

* Does NOT check balance or limits
* Used before initiating transactions

---

## 4.3 Get Balance

### Description

Retrieve the balance of the **default account** linked to the API user.

### API Flow

1. Provider sends `GET /account/balance`
2. Wallet returns account balance

### Sequence Diagram

```
Provider → Wallet: GET /account/balance
Wallet → Provider: Balance
```

---

## 4.4 Get Balance in Specific Currency

### Description

Retrieve account balance in a **specified currency**.

### API Flow

1. Provider sends `GET /account/balance?currency=XXX`
2. Wallet converts/returns balance

### Sequence Diagram

```
Provider → Wallet: GET /account/balance?currency=EUR
Wallet → Provider: Balance (EUR)
```

---

## 4.5 Validate Consumer Identity (KYC)

### Description

Retrieve **customer KYC data with consent** (name, DOB, etc.).

### API Flow

1. Provider initiates identity request
2. Customer authenticates & consents
3. Wallet issues short-lived token
4. Provider retrieves user info

### Sequence Diagram

```
Provider → Wallet: Request identity validation
Wallet → Customer: Request consent
Customer → Wallet: Approve
Wallet → Provider: Token
Provider → Wallet: GET /userinfo
Wallet → Provider: Customer data
```

### Notes

* Requires **explicit customer consent**
* Used for compliance (KYC, AML, etc.)

---

## 4.6 Create Pre-Approval

### Description

Set up **recurring/auto-debit authorization**.

### API Flow

1. Provider sends `POST /preapproval`
2. Wallet returns `202 Accepted`
3. Customer approves/rejects
4. Wallet finalizes authorization
5. Callback sent
6. Provider checks via `GET`

### Sequence Diagram

```
Provider → Wallet: POST /preapproval
Wallet → Provider: 202 Accepted
Wallet → Customer: Approval request
Customer → Wallet: Approve/Reject
Wallet → Provider: Callback (SUCCESS/FAILED)
Provider → Wallet: GET /preapproval/{id}
```

### Notes

* Once approved → **future debits don’t require customer interaction**
* Same async pattern as RequestToPay

---

# 5. Cross-Cutting Concerns

## 5.1 Asynchronous Processing

* `POST` always returns **202 Accepted**
* Final result delivered via:

    * Callback (preferred)
    * GET polling fallback

---

## 5.2 Callback Mechanism

```
Wallet → Provider: HTTP PUT (callback URL)
```

* Sent once per transaction
* No retry if failed
* Must enable PUT/POST on callback endpoint

---

## 5.3 Authentication

Required headers:

* `Ocp-Apim-Subscription-Key`
* `Authorization: Bearer <token>`

OAuth flow:

```
Provider → Wallet: Generate API Key
Provider → Wallet: Get Access Token
Provider → Wallet: Call APIs
```

---

## 6. Summary Table

| Use Case                   | Type          | Sync/Async | Customer Interaction |
| -------------------------- | ------------- | ---------- | -------------------- |
| RequestToPay               | Payment       | Async      | Yes                  |
| Validate Account Holder    | Validation    | Sync       | No                   |
| Get Balance                | Query         | Sync       | No                   |
| Get Balance (Currency)     | Query         | Sync       | No                   |
| Validate Consumer Identity | KYC           | Async      | Yes                  |
| Pre-Approval               | Authorization | Async      | Yes                  |

---

# 7. Implementation Tips (Important)

* Always **store `X-Reference-Id`** for tracking
* Implement **idempotency**
* Handle **PENDING → SUCCESS/FAILED transitions**
* Build **retry logic for GET polling**
* Secure callback endpoint (signature validation recommended)

