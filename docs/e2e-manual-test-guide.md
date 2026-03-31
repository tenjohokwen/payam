# Comprehensive E2E Manual Testing Guide

This document defines the protocol for manual end-to-end (E2E) testing of the Payam platform. It ensures that business logic, transaction integrity, and system invariants are preserved across the full payment lifecycle.

---

## 1. Prerequisites & Tools

To perform these tests, you will need:
*   **API Client:** Postman, Insomnia, or `curl`.
*   **Database Access:** A SQL client (DBeaver, pgAdmin) to verify internal state.
*   **Redis Access:** `redis-cli` or a GUI to verify counters and idempotency keys.
*   **Provider Sandboxes:** Access to MTN MoMo Developer and Orange Money Partner portals.
*   **Log Viewer:** Access to application logs (Loki or terminal).

---

## 2. Business Flow Correctness

### 2.1 Tenant Registration & API Key Management
**Goal:** Verify a new merchant can register and use the system.
1.  **Step:** Call `POST /v1/tenants` with a new name and callback URL.
2.  **Step:** Generate a new API Key for this tenant.
3.  **Verify:** The key is stored as a hash in `main.tenant_api_key`. The raw key is only shown once.
4.  **Step:** Call `GET /v1/payments` using the new raw key in the `X-Api-Key` header.
5.  **Verify:** The request is authorized (HTTP 200/404) and not rejected (HTTP 401).

### 2.2 MTN MoMo: Request To Pay (Happy Path)
1.  **Step:** `POST /v1/payments` with `provider: "MTN_MOMO"`, valid MSISDN, and unique `idempotencyKey`.
2.  **Verify:** Response is HTTP 202 with a `transactionId`.
3.  **Step:** Manually simulate a successful callback from MTN (**PUT** request to `/v1/callbacks/mtn`).
    *   **Note:** Use `externalId` matching the `transactionId` in the payload.
4.  **Verify:** Transaction status changes to `SUCCESS`.
5.  **Verify:** Check `main.transaction` for `external_txn_id` (from provider).

### 2.3 Orange Money: Merchant Payment (Happy Path)
1.  **Step:** `POST /v1/payments` with `provider: "ORANGE_MONEY"`.
2.  **Verify:** Status is `AUTH_PENDING` initially.
3.  **Step:** Simulate Orange callback (**POST** to `/v1/callbacks/orange`).
    *   **Note:** Use `payToken` from the initiation response.
4.  **Verify:** Transaction status transitions to `SUCCESS`.

---

## 3. Admin Operations & Investigation

### 3.1 Admin Authentication
1.  **Step:** Call `POST /authenticate` with admin credentials.
2.  **Verify:** Receive a JWT token. Use this in the `Authorization: Bearer <token>` header for subsequent admin calls.

### 3.2 Transaction Search & Isolation
**Goal:** Verify admins can find transactions across tenants but also filter by tenant.
1.  **Step:** Call `GET /v1/admin/transactions?transactionId={id}`.
2.  **Step:** Call `GET /v1/admin/transactions?externalReference={msisdn}` (URL encode '+' as `%2B`).
3.  **Step:** Call `GET /v1/admin/transactions?traceId={traceId}`.
4.  **Step:** Call `GET /v1/admin/transactions?tenantId={tenantPk}`.
5.  **Verify:** Results are returned correctly for each search.
6.  **Verify Isolation:** Search with `tenantId` of Tenant B must NOT return transactions belonging to Tenant A.

---

## 4. Transaction Integrity & Invariants

### 4.1 Hash Chain Verification
**Goal:** Ensure the immutable event log is tamper-evident.
1.  **Test:** Perform 3 payments.
2.  **Step:** Run SQL: `SELECT id, transaction_id, event_type, hash_value, prev_hash_value FROM main.payment_event_log WHERE transaction_id = '...' ORDER BY created_date ASC;`
3.  **Verify:** 
    *   The first event (INITIATED) has `prev_hash_value` matching the system genesis seed.
    *   For every subsequent event, `prev_hash_value` matches the `hash_value` of the immediately preceding row.
    *   Manually calculate the hash of an event data + `prev_hash_value` and confirm it matches `hash_value`.

### 4.2 Double-Entry Ledger Consistency
**Goal:** Ensure every success results in a balanced book.
1.  **Test:** Complete a payment of 500 XAF with a 10 XAF fee.
2.  **Step:** Run SQL: `SELECT account_code, debit_amount, credit_amount FROM main.ledger_entry WHERE transaction_id = '...';`
3.  **Verify:** 
    *   `CUSTOMER_WALLETS` (Debit: 510)
    *   `FEE_REVENUE` (Credit: 10)
    *   `PROVIDER_CLEARING` (Credit: 500)
    *   **Invariant:** Sum(Debit) == Sum(Credit).

### 4.3 Idempotency & Replay Protection
#### 4.3.1 Payment Idempotency (Request Level)
1.  **Step:** Send the exact same `POST /v1/payments` request twice within 5 seconds.
2.  **Verify:** The second response is identical to the first (HTTP 202) but NO second transaction row is created in the DB.
3.  **Verify:** Logs show "Idempotency hit for key ...".

#### 4.3.2 Webhook Replay Protection (Provider Level)
1.  **Step:** Send the exact same successful webhook twice for the same transaction.
2.  **Verify:** Both requests return HTTP 200/204, but only ONE `PROVIDER_SUCCESS` event is recorded in `main.payment_event_log`.
3.  **Verify Redis:** Check for dedup keys:
    *   MTN: `webhook:mtn:{transactionId}:SUCCESSFUL`
    *   Orange: `webhook:orange:{payToken}:{createtime}`

---

## 5. Flow State Machine Testing

### 5.1 Legal Transitions
Test the sequence: `INITIATED` → `PROCESSING` → `SUCCESS`.
*   **Step:** Trigger each step via API/Webhook.
*   **Verify:** State transitions correctly and `updated_date` is refreshed.

### 5.2 Illegal Transitions (Invariant Preservation)
**Goal:** Ensure the state machine rejects invalid moves.
1.  **Step:** Manually attempt to move a `SUCCESS` transaction back to `PROCESSING` via a rogue SQL or mocked callback.
2.  **Verify:** Application logic (Service layer) must throw `IllegalStateTransitionException`.
3.  **Verify:** Database state remains `SUCCESS`.

---

## 6. Failure Recovery

### 6.1 Webhook Failure & Polling Fallback
**Goal:** System must recover if the provider never calls the webhook.
1.  **Step:** Initiate a payment but BLOCK the webhook from arriving.
2.  **Step:** Wait for the `PollingJob` (Quartz) to trigger (or trigger manually via Admin API if available).
    *   **Note:** Poller usually has a 2-minute cutoff (only polls transactions older than 2 mins).
3.  **Verify:** The system queries the provider's Status API and moves the transaction to `SUCCESS` automatically.

### 6.2 Provider Timeout (Circuit Breaker)
1.  **Step:** Mock the provider API to time out (e.g., 30s delay in WireMock).
2.  **Step:** Initiate 10 payments.
3.  **Verify:** Resilience4j Circuit Breaker moves to `OPEN`. Subsequent requests fail immediately with `PROVIDER_UNAVAILABLE` (HTTP 503) without even attempting the network call.

### 6.3 Orange Money Pay Token Expiry
1.  **Step:** Initiate an Orange Money payment.
2.  **Step:** Manually backdate `pay_token_issued_at` in `main.transaction` to > 8 minutes ago.
3.  **Step:** Wait for the `OrangeStatusPollerJob` to run.
4.  **Verify:** `poll_attempts` is incremented, but the transaction remains in `PROCESSING`.
5.  **Verify:** No ledger entries are created yet.

---

## 7. Event & Integration Correctness

### 7.1 Webhook Double-Check Pattern
1.  **Step:** Send a fake successful webhook for a real pending transaction.
2.  **Verify Logs:** Ensure the `WebhookDoubleCheckHandler` fires a `GET` request to the provider's status API *before* updating the DB.
    *   **Spelling Note:** MTN uses `SUCCESSFUL` (single L), Orange uses `SUCCESSFULL` (double L).
3.  **Verify:** If the provider API says "FAILED", the transaction must move to `FAILED` despite the webhook saying "SUCCESS".

### 7.2 Outbound Webhook Delivery
1.  **Step:** Provide a tenant callback URL.
2.  **Step:** Complete a payment.
3.  **Verify:** Your callback listener receives a POST request.
4.  **Verify:** The `X-Payam-Signature` header is present and valid (HMAC-SHA256).

---

## 8. Fraud Engine Verification

### 8.1 Velocity Blocking
1.  **Step:** Send multiple payment requests from the same MSISDN or IP within 60 seconds.
2.  **Verify:** Once the threshold is reached (e.g., 5 for MSISDN), subsequent requests return `HTTP 422` with code `FRAUD_BLOCKED`.
3.  **Verify:** No request was sent to the provider for the blocked attempts.

### 8.2 Risk Rules
Verify the following rules are active in `main.fraud_rule`:
*   `IP_VELOCITY`
*   `MSISDN_VELOCITY`
*   `APP_VELOCITY`
*   `MSISDN_HOUSEHOLD`
*   `BLOCK_THRESHOLD` (Blocks if risk score >= threshold)

### 8.3 Risk Score Persistence
1.  **Step:** Perform a payment.
2.  **Verify SQL:** `SELECT risk_score, device_fingerprint FROM main.transaction WHERE transaction_id = '...';`
3.  **Verify:** `risk_score` is between 0 and 100.

---

## 9. Mutation Testing Strategy (Manual)

Manual mutation testing involves "breaking" the code locally to verify that the test suite or manual checks would catch the bug.

### 9.1 Logic "Bypass" Simulation
1.  **Mutation:** Comment out the `ledgerService.recordPayment(...)` call in `PaymentOrchestrator`.
2.  **Test:** Perform a successful payment.
3.  **Verification:** The `Final System State Consistency` check (Ledger Balance) should fail, revealing the mutation.

### 9.2 Boundary Mutation
1.  **Mutation:** Change a `>= threshold` check to `> threshold` in `FraudScoringService`.
2.  **Test:** Send exactly `threshold` number of requests.
3.  **Verification:** The manual velocity test should reveal that the "at-limit" request is wrongly allowed.

---

## 10. Final System State Consistency

At the end of a testing cycle, run the **Reconciliation Job**:
1.  **Step:** Execute the reconciliation service via Admin API.
2.  **Verify:** Check the `main.reconciliation_report` table.
3.  **Verify:** `discrepancy_count` should be 0 if all manual tests were clean.
4.  **Verify Invariant:** `Total System Balance (Ledger) == Sum of all SUCCESS transaction amounts`.
