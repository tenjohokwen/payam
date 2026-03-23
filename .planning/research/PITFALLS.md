# Domain Pitfalls: Payam Payment Gateway

**Domain:** Multi-tenant MTN MoMo + Orange Money wrapper, Cameroon market
**Stack:** Java 17 / Spring Boot 3.5, PostgreSQL, event-sourced audit log
**Researched:** 2026-03-23
**Scope:** Pitfalls the team has NOT yet addressed, or where planned mitigations are incomplete

---

## Reading Guide

The team has already planned: idempotency keys, HMAC + IP whitelist webhook verification, double-check pattern (re-verify via provider API), event-sourced hash chain audit log, velocity checks + risk scoring, daily reconciliation. Pitfalls already fully addressed by those plans are excluded.

Each pitfall below is something the planned mitigations either miss entirely or handle only partially.

**Severity scale:**
- **CRITICAL** — Can cause unrecoverable data loss, financial discrepancy, or silent double-charge
- **HIGH** — Causes customer-visible failures or significant technical debt if not addressed in the relevant phase
- **MEDIUM** — Causes edge-case bugs or operational pain; fixable post-launch but costly

---

## Section 1: Async Mobile Money Flow Handling

### P1.1 — The Webhook-Before-Database Race (CRITICAL)

**What goes wrong:** The provider webhook arrives and is processed before your own `INSERT` into the transactions table has committed. Your webhook handler queries for the transaction, finds nothing, and discards the webhook (or returns a non-200 which triggers a retry storm). The transaction is now in a permanently ambiguous state — the provider considers it SUCCESSFUL, your database has no record.

**Why it happens in this codebase:** `AbstractClient` makes the outbound `POST /requesttopay` or `POST /mp/pay` call synchronously on the HTTP request thread. Orange Money is known to deliver the `notifUrl` webhook within milliseconds of the init+pay step pair completing, sometimes before the calling thread has finished writing to the database and committing the Spring `@Transactional` boundary.

**Why the planned double-check does not solve it:** The double-check pattern fires after the webhook is received. If your transaction row does not yet exist at that moment, there is nothing to update. The double-check confirms a SUCCESS status from the provider but has no local row to write it to.

**Consequences:** Silent revenue reconciliation gap. Customer wallet was debited. Your system shows no transaction.

**Prevention:**
Write the transaction row to INIT status and commit that row before sending any request to the provider. In Spring Boot:

```java
// CORRECT ordering inside PaymentOrchestrator
@Transactional
public String initiatePayment(...) {
    Transaction tx = transactionRepo.save(buildInitRecord(...)); // COMMIT this first
    return tx.getId();
}

// Then in a separate, non-transactional method:
public void sendToProvider(String txId, ...) {
    // No @Transactional here — DB row already committed above
    providerClient.requestToPay(...);
}
```

Never wrap the full "persist + call provider" flow in a single `@Transactional`. The `INSERT` must commit before the outbound HTTP call fires.

**Warning signs:** Any service method annotated `@Transactional` that contains both a `repository.save()` and an outbound HTTP call.

**Phase:** Phase 1 (payment module foundation). This is a structural constraint that cannot be retrofitted easily.

---

### P1.2 — Polling Race Condition When Webhook and Poller Arrive Simultaneously (CRITICAL)

**What goes wrong:** Your polling scheduler and the provider webhook both arrive within milliseconds of each other. Both read the transaction as PENDING. Both call the provider status endpoint (double-check pattern). Both get SUCCESS. Both attempt to update state to SUCCESS and emit a `PaymentCompleted` event. You get two events in the audit log, potentially two notifications to the client tenant.

**Why the planned mitigation is incomplete:** The spec calls for idempotency on write operations from clients, but does not address concurrent internal writers (webhook handler + polling scheduler) racing on the same transaction row.

**Prevention:**
Use a database-level optimistic lock or `SELECT FOR UPDATE` on the transaction row when transitioning state. In Spring/JPA:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Transaction> findByIdForUpdate(String id);
```

Alternatively, use a conditional `UPDATE ... WHERE status = 'PENDING'` and check the affected row count. If count is 0, another process already transitioned the row; log and exit silently.

**Warning signs:** Polling scheduler and webhook handler both call `transactionService.complete(txId)` without any mutual exclusion. Test by writing a concurrency test with `CountDownLatch`.

**Phase:** Phase 1 (payment module foundation).

---

### P1.3 — Orange Money payToken Expiry Between Init and Pay (HIGH)

**What goes wrong:** The Orange Money flow requires two calls: `POST /mp/init` (get payToken) then `POST /mp/pay` (submit details). If anything delays the `/mp/pay` call — a slow database write, a network hiccup, a GC pause — the payToken may expire. The `/mp/pay` call returns an error, but your database already has a row in INIT status with a payToken that is now dead. A retry from the client gets a new payToken (new `/mp/init`), but your idempotency logic may return the stale INIT record keyed on the client's `Idempotency-Key`, preventing the retry from reaching the provider.

**Why idempotency keys make this worse:** An idempotency key that returns a cached INIT response (with stale payToken) on retry blocks the client from ever getting a live transaction through.

**Prevention:** Track payToken creation timestamp. When an idempotency key replay is detected, check whether the cached transaction has been in INIT status longer than the payToken TTL (empirically, Orange Money payTokens appear to expire in approximately 5–10 minutes based on field reports — treat this as LOW confidence, verify with your Orange partner). If expired and the status is still INIT, the idempotency key should be treated as expired and the new request should create a fresh transaction. Document this business rule explicitly.

**Warning signs:** An idempotency key store with no TTL on INIT-state entries, or no distinction between "transaction is PENDING at provider" and "transaction is stuck in local INIT."

**Phase:** Phase 1 (payment module). Requires designing idempotency key TTL rules before implementation.

---

### P1.4 — MTN MoMo Callback Delivered via PUT, Not POST (HIGH)

**What goes wrong:** The MTN MoMo documentation (see `mtn-use-cases.md`, section 5.2) explicitly states: "Wallet sends callback via HTTP PUT." Many developers implement their webhook endpoint as `@PostMapping`, which Spring rejects with HTTP 405 Method Not Allowed. MTN's retry logic on non-200 then hammers your endpoint repeatedly.

**Why it matters here:** The Orange Money integration guide (`orange-money-integration-guide.md`) shows `@PostMapping` in the example controller. If a developer copies that pattern for MTN without reading the MTN use-case doc, all MTN callbacks are silently discarded.

**Prevention:**
```java
// MTN uses PUT; Orange uses POST — must be separate endpoints or accept both
@RequestMapping(value = "/mtn", method = {RequestMethod.POST, RequestMethod.PUT})
public ResponseEntity<Void> handleMtnCallback(...) { ... }

@PostMapping("/orange")
public ResponseEntity<Void> handleOrangeCallback(...) { ... }
```

Test webhook receipt in integration tests using `MockMvc` with explicit `PUT` requests for the MTN path.

**Warning signs:** MTN webhook endpoint defined with `@PostMapping` only. No integration test that sends a PUT request.

**Phase:** Phase 1. Validate during provider adapter implementation with a contract test against the sandbox.

---

### P1.5 — Provider "PENDING" Status That Never Resolves (HIGH)

**What goes wrong:** MTN MoMo returns PENDING indefinitely when the customer's phone is off, the USSD session times out silently, or there is a platform issue. Your polling scheduler keeps retrying for hours or days. The transaction is never marked FAILED. Customers who retry hit idempotency key conflicts (the transaction is "in progress" but dead). Your reconciliation window passes while the transaction is still PENDING.

**Why the planned mitigation is incomplete:** The requirements mention retry mechanisms but do not define a maximum pending duration or a forced timeout state.

**Prevention:** Define a `TIMED_OUT` terminal state distinct from `FAILED`. Implement a scheduled job that moves any transaction stuck in PENDING beyond a configured threshold (e.g., 15 minutes for MTN, configurable) to `TIMED_OUT`. Log a `TransactionTimedOut` event in the audit chain. Expire the associated idempotency key so retries can create a fresh transaction. The threshold must be tuned against provider SLAs — MTN's sandbox vs. production behavior differs.

**Warning signs:** No `TIMED_OUT` state in the state machine. No scheduled job with a maximum-age query. Idempotency key expiry not tied to transaction state transitions.

**Phase:** Phase 1 (state machine design). Hard to add after the schema is settled.

---

## Section 2: Idempotency Implementation

### P2.1 — Idempotency Key Scope Collision Across Tenants (CRITICAL)

**What goes wrong:** Two different tenants (clients) independently generate the same idempotency key string (e.g., both use `"payment-001"` or a UUID that collides). Your idempotency store uses only the key value as the lookup. Tenant A's request returns Tenant B's cached response — exposing transaction details across tenant boundaries and potentially returning Tenant B's `payToken` to Tenant A.

**Why the planned mitigation is incomplete:** The security architecture document describes idempotency keys as a flat store without tenant scoping. Multi-tenancy data isolation is mentioned separately, but the intersection of the two is not addressed.

**Prevention:** The idempotency key storage must always be scoped to `(tenantId, idempotencyKey)` as a compound key, never on `idempotencyKey` alone.

```java
// WRONG
idempotencyStore.get(idempotencyKey);

// CORRECT
idempotencyStore.get(tenantId + ":" + idempotencyKey);
// or as a compound DB key: UNIQUE(tenant_id, idempotency_key)
```

**Warning signs:** An idempotency key table or Redis key space that does not include `tenant_id` in the primary key or key prefix.

**Phase:** Phase 1 (idempotency module design). This is a schema decision — fixing it after go-live requires a migration and re-keying all in-flight transactions.

---

### P2.2 — The "Init Succeeded, Pay Failed" Partial Idempotency Problem — Orange Money (CRITICAL)

**What goes wrong:** For Orange Money, Step 1 (`/mp/init`) succeeds and returns a `payToken`. Step 2 (`/mp/pay`) fails with a network timeout. The client retries with the same `Idempotency-Key`. Your idempotency store returns the cached response from the first attempt, which shows status INIT (the transaction never reached the provider). The client, seeing an INIT status, retries again. Your system creates a new payToken (new `/mp/init`) but the idempotency layer blocks that from reaching the provider.

Alternatively: your system does retry `/mp/pay` but with a stale payToken that Orange has already invalidated. The provider returns an error. Your system now has a record that will never resolve.

**Why idempotency keys alone do not solve this:** Idempotency on the inbound client request does not help when the problem is internal — the partial execution between your two outbound calls.

**Prevention:** Model the two Orange Money steps as a single internal saga with explicit compensation:
1. On `/mp/pay` failure, immediately attempt `/mp/paymentstatus/{payToken}` to determine whether the payment was received by Orange before the network error.
2. If status is PENDING or SUCCESS, use that — don't retry the pay call.
3. If status is not found or FAILED, mark the internal transaction as FAILED and expire the idempotency key immediately so the client can retry with a fresh request.

Store the payToken and the internal step reached (`INIT_COMPLETE`, `PAY_SENT`, `PAY_CONFIRMED`) as explicit state fields. The idempotency layer should return FAILED (not INIT) to the client when Step 2 definitively failed, so the client knows to retry.

**Warning signs:** No step-level state tracking for Orange Money's 2-step flow. Idempotency key TTL not reduced on definitive failures. No status-check call on network timeout from `/mp/pay`.

**Phase:** Phase 1 (Orange adapter design). Must be designed before implementation begins.

---

### P2.3 — Idempotency Key Expiry Causing Silent Double-Charge on Retry (CRITICAL)

**What goes wrong:** You set a 24-hour TTL on idempotency keys. A customer initiates payment at 23:50 and the network times out. They retry at 00:10 the next day. The idempotency key has expired. Your system sends a new payment request to the provider. The original payment from 23:50 was actually SUCCESSFUL at the provider — it just never returned a webhook. The customer is charged twice.

**Why this matters for Cameroon networks:** Mobile internet latency and session drops in Cameroon create exactly this scenario with higher frequency than in markets with more stable connectivity.

**Prevention:** Never expire an idempotency key for a transaction that is in PENDING state at the provider. The idempotency key TTL must be conditional on the transaction's resolved status:
- PENDING → key does not expire (or expires at a very long window, e.g., 7 days)
- SUCCESS → key can have a shorter TTL for client retry deduplication (e.g., 48 hours)
- FAILED / TIMED_OUT → expire immediately so the client can retry with a new payment

Implement this as a scheduled cleanup job that only deletes idempotency entries where the linked transaction is in a terminal state.

**Warning signs:** A fixed-TTL idempotency expiry (e.g., Redis `EXPIRE 86400`) with no check on the linked transaction status.

**Phase:** Phase 1 (idempotency module). Requires coordination with the transaction state machine.

---

### P2.4 — Idempotency Key Stored in Redis Without Durability (HIGH)

**What goes wrong:** Idempotency keys are stored in Redis for performance. Redis is configured without persistence (AOF disabled, no RDB snapshot). A Redis restart between a client request and the provider callback clears all in-flight idempotency state. The client retries (their key now appears new), the provider delivers the webhook, and you get a duplicate transaction.

**Prevention:** Either (a) persist idempotency keys to PostgreSQL with a Redis cache layer for read performance, or (b) configure Redis AOF persistence with `appendfsync everysec`. Option (a) is safer for a payment gateway. The PostgreSQL row is the source of truth; Redis is a cache that can be rebuilt.

**Warning signs:** Idempotency logic that stores only to Redis with no PostgreSQL backup. Redis configuration without AOF or RDB persistence.

**Phase:** Phase 1 (infrastructure decisions). Make the persistence decision before building the idempotency module.

---

## Section 3: Webhook Security

### P3.1 — SSRF via notifUrl Parameter (CRITICAL)

**What goes wrong:** A malicious or compromised tenant calls your API with `notifUrl: "http://169.254.169.254/latest/meta-data/"` (AWS IMDSv1) or `notifUrl: "http://internal-service.company.local/admin"`. Orange Money forwards your notifUrl to the customer confirmation and calls it directly. If your system also makes an outbound call to the notifUrl (e.g., for double-checking), you have SSRF.

More concretely for this system: if Payam ever forwards the tenant-supplied `notifUrl` value to Orange's API, and Orange then calls that URL, Orange is acting as an SSRF proxy on your behalf. If a compromised tenant provides a notifUrl that points to another tenant's internal webhook endpoint, they can receive other tenants' payment confirmations.

**Why it matters here:** The Orange Money spec requires a `notifUrl` per pay request. In a multi-tenant system, each tenant likely supplies their own notifUrl. Your gateway should be the notifUrl from the provider's perspective — you receive the webhook and then forward it to the tenant. If you pass tenant-supplied notifUrls directly to the provider, you lose control of the webhook path.

**Prevention:**
1. Your system should always be the `notifUrl` registered with providers. Construct the notifUrl internally: `https://gateway.payam.cm/webhooks/orange/{internalTxId}`.
2. After verifying the provider webhook, Payam then calls the tenant's registered callback URL (stored in your database, not provided per-request).
3. Validate tenant callback URLs at registration time against an allowlist or at minimum reject RFC-1918 addresses, loopback addresses, and link-local addresses.
4. Use an outbound HTTP client with SSRF protection (deny RFC-1918 ranges).

**Warning signs:** Any code path that reads a `notifUrl` from the API request body and passes it unchanged to `providerClient.requestToPay(...)`.

**Phase:** Phase 1 (webhook architecture design). The notifUrl architecture decision affects provider adapter design.

---

### P3.2 — Webhook Replay Window Too Generous (HIGH)

**What goes wrong:** The security architecture document specifies replay protection with a "timestamp window" but does not define the window size. If the window is 5 minutes (a common default), an attacker who captures a legitimate webhook can replay it at any point within 5 minutes. For a payment gateway, 5 minutes is long enough for an attacker to replay a SUCCESS callback after you have manually reversed or flagged a transaction.

**Why the nonce approach has its own problem:** Storing nonces in Redis to reject replays works until Redis is restarted (see P2.4). If nonces are in-memory only, they vanish on restart and the entire replay protection disappears.

**Prevention:**
1. Set the timestamp window to 30 seconds maximum for provider webhooks.
2. Persist nonces to PostgreSQL (or use a combination: Redis for fast rejection, PostgreSQL as authoritative store).
3. For Orange Money specifically: their webhook payload does not include a standard HMAC signature field. Replay protection for Orange Money relies entirely on the double-check pattern (re-querying provider status) — make this explicit in the implementation. The double-check must match `payToken`, `amount`, `msisdn`, and `status`.

**Warning signs:** A comment like `// TODO define timestamp window` or a magic number `300` (seconds) in webhook validation code. Nonce storage backed only by a local in-memory map.

**Phase:** Phase 1 (webhook security implementation).

---

### P3.3 — IP Whitelist Bypassed via Load Balancer X-Forwarded-For (HIGH)

**What goes wrong:** Your IP whitelist logic reads the remote IP from `request.getRemoteAddr()` or the first value of `X-Forwarded-For`. When Payam runs behind a reverse proxy or load balancer (likely in production), `getRemoteAddr()` is always the proxy IP. `X-Forwarded-For` can be spoofed by the caller — any request can claim to originate from an MTN IP address by setting the header.

**Why the planned mitigation is incomplete:** The security architecture document says "accept only MTN/Orange IP ranges" but does not address the proxy layer.

**Prevention:**
1. Configure Spring's `RemoteAddressFilter` or use `ForwardedHeaderFilter` correctly, trusting only a known proxy IP range, not the raw header value.
2. In `application.yaml` for production: `server.forward-headers-strategy: NATIVE` or `FRAMEWORK` (Spring Boot 3 supports this). Do not use `X-Forwarded-For` from untrusted proxies.
3. The IP whitelist check should use the real remote addr after trusted proxy unwinding, not the first value in `X-Forwarded-For`.

**Warning signs:** Any IP whitelist code that reads `X-Forwarded-For` as a trusted string without first validating the proxy chain. Tests that only send requests without a proxy.

**Phase:** Phase 1 (webhook security). Add integration tests that simulate a proxied request with a spoofed header.

---

### P3.4 — Timing Attack on HMAC Comparison (MEDIUM)

**What goes wrong:** HMAC verification code uses `String.equals()` or `Arrays.equals()` to compare the expected and received signatures. These methods return early on the first differing byte, leaking the number of correct bytes via timing differences. An attacker can statistically determine the correct HMAC byte-by-byte.

**Prevention:** Use `MessageDigest.isEqual()` (Java 7+) or `HMac.bytesEqual()` (Bouncy Castle). In Spring Security, `CryptographicKeyedHashAlgorithm` is also safe. Never use `String.equals()` for HMAC comparison.

```java
// WRONG
if (expectedHmac.equals(receivedHmac)) { ... }

// CORRECT
if (MessageDigest.isEqual(
        expectedHmac.getBytes(StandardCharsets.UTF_8),
        receivedHmac.getBytes(StandardCharsets.UTF_8))) { ... }
```

**Warning signs:** Any `hmac.equals(...)` or `Arrays.equals(hmacBytes, receivedBytes)` in webhook processing code.

**Phase:** Phase 1 (webhook security). Simple one-line fix but easy to miss in code review.

---

## Section 4: Multi-Tenancy Data Leaks

### P4.1 — Transaction Lookup Without Tenant Scope Enforcement (CRITICAL)

**What goes wrong:** An endpoint like `GET /v1/transactions/{transactionId}` loads the transaction directly by ID without asserting that the transaction belongs to the requesting tenant. Tenant A who knows (or guesses) Tenant B's transaction ID can retrieve Tenant B's full payment details — MSISDN, amount, provider transaction ID.

**Why this is a real risk here:** The existing codebase uses TSIDs (time-sortable IDs) for primary keys. TSIDs are sequential and guessable. Tenant A can enumerate transaction IDs for transactions made around the same time as their own.

**Prevention:** All transaction queries must include a `tenantId` predicate:

```java
// WRONG
transactionRepo.findById(txId)

// CORRECT
transactionRepo.findByIdAndTenantId(txId, currentTenantId())
```

In JPA, enforce this via a base repository with a Spring Data `@Query` annotation or by using Hibernate's multitenancy discriminator feature. Add an integration test that verifies a 404 (not a 403) is returned when Tenant A queries Tenant B's transaction ID — 403 leaks information about existence.

**Warning signs:** Any `findById` call in payment-related service code that does not also filter by `tenantId`. TSID-based transaction IDs without access-scoped queries.

**Phase:** Phase 1. Foundational requirement. Retrofit after go-live is extremely risky.

---

### P4.2 — API Key Leakage via Shared Error Messages (HIGH)

**What goes wrong:** When a request is rejected for an authentication failure, the error message includes context that reveals which API key was used, what tenant it belongs to, or what permissions it lacks. A bad actor testing keys can confirm valid key prefixes via differential error responses.

This is distinct from the general security concern — the specific risk is that in a multi-tenant system, one tenant's error handling reveals information about another tenant's configuration (e.g., "key X is valid but belongs to a suspended tenant" vs "key X is not found").

**Prevention:** All authentication failures must return the same HTTP 401 response body regardless of the failure reason (key not found, key revoked, key belongs to wrong tenant, tenant suspended). The internal reason is logged with the `traceId`, not surfaced to the caller.

**Warning signs:** Multiple distinct error codes for authentication failures (e.g., `AUTH_KEY_NOT_FOUND`, `AUTH_KEY_SUSPENDED`, `AUTH_TENANT_INACTIVE`) returned directly in the HTTP response.

**Phase:** Phase 1. Establish a uniform auth error response contract from day one.

---

### P4.3 — Rate Limit Bypass Across Tenants (HIGH)

**What goes wrong:** Rate limits are enforced per API key or per IP. A tenant can bypass per-tenant rate limits by registering multiple API keys (if key creation is not rate-limited) or by distributing requests across multiple IPs.

More critically: the existing codebase already has documented in-memory rate limiting (Bucket4j) that is not shared across nodes (see CONCERNS.md). In a multi-node deployment, rate limits are per-node, meaning effective per-tenant limits are multiplied by the number of nodes.

**Prevention:**
1. Migrate rate limiting to Redis-backed Bucket4j (Bucket4j Redis integration exists natively). The existing CONCERNS.md already flags this — it must be prioritized for the payment module, not deferred.
2. Enforce rate limits at the tenant level (aggregate across all API keys for that tenant) in addition to per-key limits.
3. Rate-limit API key creation itself.

**Warning signs:** `RateLimitingService` backed by in-memory `Bucket4j` with no Redis backend in a multi-node deployment.

**Phase:** Phase 1 (before production launch). The fix exists; the risk is deferring it.

---

### P4.4 — Webhook Forwarding to Tenant Callback Without Tenant Isolation (HIGH)

**What goes wrong:** After verifying a provider webhook, your system forwards the notification to the tenant's registered callback URL. If the forwarding logic looks up the tenant callback URL using only the `payToken` or `externalId` without verifying that the payload's transaction belongs to that tenant, a crafted payload could trigger callbacks to the wrong tenant.

**Prevention:** The webhook forwarding lookup must be: find transaction by payToken where tenantId matches the payToken's registered tenant. Never look up a tenant callback URL from the payload's content alone.

**Warning signs:** Callback forwarding code that queries `callbackUrlRepo.findByPayToken(payToken)` without a tenant scope check.

**Phase:** Phase 1. Design the webhook routing table with tenant scoping from the start.

---

## Section 5: Reconciliation Failures

### P5.1 — Timezone Handling: MTN and Orange Use Different Timezone References (CRITICAL)

**What goes wrong:** MTN MoMo returns timestamps in UTC. Orange Money returns timestamps without a timezone specifier in the `createtime` field (format observed: `2024-01-15T10:30:00` with no Z or offset). If your reconciliation treats Orange timestamps as UTC when they are actually WAT (West Africa Time, UTC+1), every Orange transaction appears 1 hour earlier in your records than in Orange's records. During daily reconciliation at midnight WAT, the last hour of transactions is misaligned.

**Why this causes a systematic reconciliation gap:** Transactions initiated between 23:00 and 00:00 WAT appear in your UTC-normalized records on the previous day. Over 30 days, this accumulates to a non-trivial number of "missing" transactions in your daily report.

**Prevention:**
1. Treat all incoming timestamps as WAT (UTC+1) unless the provider explicitly signals otherwise.
2. Store all timestamps internally as UTC with full timezone information (`TIMESTAMPTZ` in PostgreSQL, not `TIMESTAMP`).
3. During reconciliation, align on WAT calendar days (the operator's business day), not UTC days.
4. In Java: use `ZonedDateTime` everywhere in the payment module, never `LocalDateTime`. The existing codebase uses `LocalDateTime.now()` in some places (flagged in CONCERNS.md as a tech debt item) — this must not be carried into the payment module.

**Warning signs:** Any `LocalDateTime.parse(orangeTimestamp)` without explicit `ZoneId.of("Africa/Douala")`. Any reconciliation query using `DATE(created_at)` on a UTC column without timezone conversion.

**Phase:** Phase 2 (reconciliation module). But the timestamp storage decision (always TIMESTAMPTZ) must be made in Phase 1.

---

### P5.2 — Provider Clock Drift and the "Same Day" Boundary Problem (HIGH)

**What goes wrong:** MTN and Orange servers have clocks that drift independently. A transaction your system records at 23:58:45 WAT may appear at 00:01:12 WAT in MTN's records due to clock skew. During daily reconciliation, your batch reports the transaction in day N; MTN's report places it in day N+1. The reconciliation sees a "missing" transaction on your side for day N+1 and an "unexpected" transaction on MTN's side for day N.

**Why this is hard to detect:** Each individual transaction appears to be present — it is just on the wrong day. Manual investigation is required unless your reconciliation handles day-boundary tolerance.

**Prevention:**
1. Apply a ±5 minute tolerance window at day boundaries during reconciliation. Transactions within 5 minutes of midnight on either side should be matched by `providerTxId` regardless of which day they fall on in each system's records.
2. Store the provider's own timestamp in a separate column alongside your internal timestamp. Use the provider timestamp (not yours) when generating reports intended for provider comparison.

**Warning signs:** Reconciliation code that does a strict `DATE(your_ts) = DATE(provider_ts)` equality check with no boundary tolerance.

**Phase:** Phase 2 (reconciliation module implementation).

---

### P5.3 — Late-Resolving Transactions: Status Changes After the Reconciliation Window (HIGH)

**What goes wrong:** A transaction recorded as PENDING in your system at the time of reconciliation is later resolved by the provider (SUCCESS or FAILED) — sometimes hours or days later. MTN MoMo in Cameroon has documented cases of PENDING transactions resolving as SUCCESSFUL after 24–48 hours when platform issues clear. Your reconciliation marked it as a discrepancy. The next day's reconciliation needs to "heal" the prior day's report.

**Why the planned daily reconciliation is insufficient:** A single daily run does not account for late resolution. If the reconciliation produces a final report each day, that report will have false discrepancies that never self-correct.

**Prevention:**
1. Never produce final reconciliation reports until N days after the transaction date (e.g., T+3 for completeness).
2. Run a "late transactions" reconciliation pass that re-evaluates all PENDING transactions older than 1 hour but newer than 7 days.
3. Distinguish three reconciliation states: `MATCHED`, `DISCREPANCY_OPEN`, `DISCREPANCY_RESOLVED`. A discrepancy that self-heals moves to `DISCREPANCY_RESOLVED`, not silently disappears.

**Warning signs:** Reconciliation that runs once per day and marks results as final. No concept of "open" vs "resolved" discrepancy status.

**Phase:** Phase 2. Design the reconciliation data model with open/resolved discrepancy states.

---

### P5.4 — Reconciliation Against Provider Settlement Report vs. Transaction Report (MEDIUM)

**What goes wrong:** MTN and Orange provide two different types of reports: transaction-level reports (every individual transaction) and settlement reports (net amounts transferred to your account). Teams build reconciliation against the settlement report because it maps neatly to a bank transfer. But the settlement report is net of fees and is batched — it does not let you detect individual transaction discrepancies. A missing transaction or a fee error is invisible until the settlement amount is off.

**Prevention:** Reconcile at the transaction level, not the settlement level. Request daily transaction-level reports from your Orange and MTN account managers. Settlement reconciliation is a secondary check, not a substitute.

**Warning signs:** Reconciliation code that compares daily net amounts rather than per-transaction status.

**Phase:** Phase 2. Confirm report format availability with providers during Phase 1 provider onboarding.

---

## Section 6: Event Sourcing Pitfalls

### P6.1 — Hash Chain Verification Performance Degrades Linearly (HIGH)

**What goes wrong:** The design calls for `hash = SHA256(event_data + previous_hash)`, forming a chain. To verify the integrity of any event, you must traverse the entire chain from the genesis event to that point. For a transaction with 6 events, this is trivial. For an audit log verification job that scans 1 million events per day, this becomes a table scan with SHA256 computation on every row — dominated by I/O and CPU, not by the hash computation itself.

**Why this matters for operations:** When a regulator or auditor requests proof of tamper-evidence for 6 months of transactions, your verification job takes hours and blocks the database.

**Prevention:**
1. Issue periodic checkpoint hashes (e.g., one checkpoint per 1,000 events or per hour). A checkpoint event contains `hash(all_events_since_last_checkpoint + last_checkpoint_hash)`. Verification then works in segments, not from genesis.
2. Store checkpoint events in a separate table so they can be verified independently.
3. Alternatively, use a Merkle tree structure where individual event verification requires only O(log N) lookups.

**Warning signs:** Hash verification code that starts from event sequence number 1 and walks forward without any segmentation.

**Phase:** Phase 1 (event sourcing design). The checkpoint structure must be part of the initial schema design.

---

### P6.2 — Event Schema Evolution: Old Event Types Cannot Be Deserialized (HIGH)

**What goes wrong:** You store events as JSON in the audit log. After 6 months, you add a new required field to `PaymentInitiatedEvent`. Your deserialization code now fails on all historical events that lack the new field. Reading the audit history for old transactions throws `JsonMappingException`. Regulatory audit requests fail.

**Why this is worse in an event-sourced system:** In a standard CRUD system, you migrate the column and all rows update. In an event-sourced system, old events are immutable — they cannot be updated. You must handle the old schema forever.

**Prevention:**
1. All event payloads must be deserializable in all future code versions. Enforce two rules: (a) all new fields must have defaults or be Optional, (b) unknown fields must be ignored during deserialization (`@JsonIgnoreProperties(ignoreUnknown = true)` on all event classes).
2. Assign a `schemaVersion` field to every event type. Write an explicit deserializer (upcaster) for each version transition.
3. Add a migration test: serialize a JSON fixture of each event at version N, deserialize with version N+1 code. This test must fail before you release schema changes, forcing you to write upcasters.

**Warning signs:** Event POJOs without a `version` or `schemaVersion` field. No migration tests for event deserialization. No `@JsonIgnoreProperties(ignoreUnknown = true)`.

**Phase:** Phase 1 (event design). The `schemaVersion` field must be in the first schema version.

---

### P6.3 — Event Ordering Guarantees Under Concurrent Writes (HIGH)

**What goes wrong:** Events are assigned sequence numbers using a PostgreSQL sequence. Two threads concurrently processing events for the same transaction can interleave their sequence numbers. Thread A gets sequence 101, Thread B gets sequence 102, but Thread B commits first. Thread A's event, which logically happened earlier, has a lower sequence number but is committed later. Readers see events in sequence-number order and reconstruct an incorrect state.

**Prevention:**
1. Assign sequence numbers at commit time, not at sequence-generation time. Use PostgreSQL's `SERIAL` or `BIGSERIAL` and ensure the sequence is inserted as part of the same transaction that commits the event data — this is the default behavior and is correct. The problem arises only if sequence numbers are generated separately from insertion (e.g., `nextval` called before the transaction starts).
2. For events on the same transaction, enforce ordering by `transaction_id` + `created_at` with millisecond precision as a secondary sort key. Do not rely on sequence number alone for intra-transaction ordering.
3. Use `SELECT FOR UPDATE` on the parent transaction row when appending events to prevent concurrent event writes for the same transaction (the lock acquired in P1.2 serves double duty here).

**Warning signs:** Event sequence numbers generated outside of the database transaction (e.g., fetched via a `nextval()` call before the `INSERT` starts). No locking on the parent transaction when appending events.

**Phase:** Phase 1 (event sourcing implementation).

---

### P6.4 — Audit Log Queried Directly From Application Code Without Read Replica (MEDIUM)

**What goes wrong:** Your audit log table grows at a rate of roughly 6–10 events per transaction. At moderate volume (10,000 transactions/day), that is 60,000–100,000 events/day, roughly 2 million/month. Dashboard queries, reconciliation runs, and investigative queries all hit the same PostgreSQL primary that handles live transaction writes. Long analytical queries from the dashboard block the write path.

**Prevention:**
1. Route all read-path queries (audit log reads, reconciliation reports, dashboard queries) to a PostgreSQL read replica. Spring Boot supports this via `@Transactional(readOnly = true)` combined with a read-replica `DataSource` routing configuration.
2. Alternatively, stream events to a separate analytics store (ClickHouse, TimescaleDB) for reporting, keeping the primary for transactional writes only.

**Warning signs:** Reconciliation queries running on the primary write DataSource. Dashboard queries with `GROUP BY` and date ranges on the audit event table.

**Phase:** Phase 2 (when volume becomes measurable). But the routing architecture decision should be in Phase 1.

---

## Section 7: Fraud Engine False Positives

### P7.1 — SIM Card Sharing in Cameroon Households (HIGH)

**What goes wrong:** Cameroon has high household SIM sharing — one mobile number is used by multiple family members. Your velocity checks flag accounts where the same MSISDN initiates 5+ transactions per day from different IP addresses or device fingerprints. In a household where a parent and two adult children share one Orange Money number and pay separate merchants throughout the day, all legitimate transactions trigger `REQUIRE_OTP` or `BLOCK` rules.

**Why the planned mitigation is incomplete:** The fraud scoring model (`paymentApi_security_architecture.md`, section 4.1) flags "new device/app" at +20 and "high frequency" at +30. In a shared-SIM household, every transaction from a different device triggers the +20 score. Three family members transacting in an hour will aggregate to a score > 80 and hit the block threshold.

**Prevention:**
1. Add an explicit "household/shared account" flag that tenants can set on MSISDNs via an API call. Shared-account MSISDNs should have elevated thresholds and be immune to the "new device" signal.
2. Reduce the "new device" signal weight for the Cameroon market — consider a baseline of +5 rather than +20 for the first occurrence, graduating to +20 only after 3+ distinct devices within 24 hours.
3. Build a manual whitelist API for tenants to pre-approve known frequent-user MSISDNs.
4. Track false positive rate per rule as a first-class metric from day one. If a rule has > 15% false positives in a rolling 7-day window, alert the operations team to review the threshold.

**Warning signs:** Fraud scoring that treats all device signals as equal regardless of whether the MSISDN is known to be shared. No mechanism for legitimate bulk-use MSISDNs to bypass strict thresholds.

**Phase:** Phase 1 (fraud engine design). The scoring weights should be configurable from a database table, not hardcoded — this is mandatory for a Cameroon deployment.

---

### P7.2 — Phone Number Portability Breaking MSISDN-Based Identity Assumptions (HIGH)

**What goes wrong:** A customer ports their number from Orange to MTN (or vice versa). Your system has historical transaction data, velocity baselines, and trust scores tied to that MSISDN. After porting, the same MSISDN now routes to a completely different provider. Your transaction records for that MSISDN mix Orange Money transactions (before port) with MTN MoMo transactions (after port) — the behavioral profile is now incoherent.

More critically: a velocity rule that detects "same MSISDN used on both providers within 1 hour" will fire on every ported number's first transaction on the new network.

**Why this is specific to Cameroon:** Cameroon's ARTP (regulator) has mandated mobile number portability. Portability adoption is growing. The Orange/MTN cross-provider dual-wallet scenario is common for users who port but keep their old SIM for some time.

**Prevention:**
1. Track `(MSISDN, provider)` as the identity unit, not MSISDN alone, for behavioral profiling.
2. Do not flag "same MSISDN on both providers" as suspicious — it is a normal portability transition state.
3. Add portability detection: when a transaction for MSISDN X comes in on provider B, but you have a history of transactions for MSISDN X on provider A, consider it a portability event rather than fraud. Log it for review, do not block.

**Warning signs:** Fraud rules that use MSISDN as a cross-provider identity key. A rule that increases risk score for "MSISDN used on multiple providers."

**Phase:** Phase 1 (fraud engine design). The portability scenario must be documented as an explicit rule exclusion.

---

### P7.3 — Merchant Payroll to Staff (Bulk Transactions to Known Recipients) (HIGH)

**What goes wrong:** A merchant tenant pays 50 employees their weekly wages via mobile money. This triggers every velocity rule: high transaction count per hour, multiple distinct MSISDNs, potentially geographic spread if staff are in different towns. The batch is blocked at the 5th transaction.

**Why the planned mitigation is incomplete:** The fraud scoring model has no concept of "bulk payment batch" or "payroll-pattern transaction." All high-frequency scenarios are treated as suspicious.

**Prevention:**
1. Support a "batch payment" or "disbursement" intent flag in the API. Transactions submitted with this flag are evaluated against disbursement-specific rules (higher per-batch limits, pre-approved MSISDN list).
2. Allow tenants to pre-register payroll MSISDN lists (employees) with amount ranges. Transactions to pre-approved MSISDNs within declared amount ranges score near zero for velocity fraud.
3. Alert on, do not block, the first payroll-pattern batch from a new tenant — let operations confirm legitimacy and then whitelist the pattern.

**Warning signs:** Fraud engine with no distinction between collection transactions (customer → merchant) and disbursement transactions (merchant → customer). No "approved recipient list" concept.

**Phase:** Phase 1 (fraud engine). Disbursement vs. collection rules must be separate from day one.

---

### P7.4 — Geographic Impossibility Rule Miscalibrated for Cameroon's Infrastructure (MEDIUM)

**What goes wrong:** The fraud scoring design flags geographic impossibility: "Yaoundé → Douala in 5 minutes → flag." But IP geolocation in Cameroon is highly unreliable. Many MTN and Orange subscribers in Douala are geolocated to Yaoundé because the ISP's gateway IP is registered to the capital. A legitimate Douala customer will appear to "teleport" on every transaction.

**Prevention:**
1. Do not use IP geolocation as a primary fraud signal for Cameroonian mobile money transactions — it is too noisy to be useful as a blocking criterion.
2. Use MSISDN prefix as a weak geographic signal instead (Orange and MTN Cameroon assign number ranges to regions), but treat it as informational, not scoring.
3. If geolocation signals are used, score on impossible speed (Cameroon to Europe in 1 second), not within-country movement.

**Warning signs:** Geographic fraud rules that compare sequential transaction geolocations without accounting for ISP gateway IP centralization.

**Phase:** Phase 1 (fraud engine design). The Cameroon-specific IP geolocation limitation should be explicitly documented in the fraud engine configuration.

---

## Section 8: Performance Under Load

### P8.1 — PostgreSQL Connection Exhaustion Under High Transaction Volume (CRITICAL)

**What goes wrong:** The existing codebase is documented to hit PostgreSQL's connection limit at 4 nodes (25 connections × 4 = 100 = PostgreSQL default max). When adding the payment module, each transaction involves multiple database operations: idempotency lookup, transaction insert, event append (2–3 inserts), provider call (with connection held open waiting for response), webhook receipt, event append again. Each in-flight transaction holds a connection for the duration of the provider call — which can take 15–30 seconds for mobile money. Connection starvation under moderate load is near-certain.

**Why the provider call duration is the core problem:** Your `AbstractClient` makes synchronous `RestTemplate` calls. While waiting for the provider HTTP response, the Spring MVC thread — and its HikariCP database connection — is held open. At 25 connections per node and 15-second average provider response times, you saturate connections at roughly 100 concurrent in-flight transactions per node.

**Prevention:**
1. Never hold a database connection open while making an outbound provider HTTP call. The pattern must be: acquire connection → write to DB → release connection → call provider → acquire connection → write result. Spring's `@Transactional` automatically holds the connection for the transaction duration.
2. Separate the outbound provider call from the database transaction: complete the `@Transactional` write, then — outside any transaction — call the provider.
3. Deploy PgBouncer in transaction pooling mode in front of PostgreSQL regardless of node count. This is already flagged in CONCERNS.md but must be done before the payment module is live.
4. Migrate from `RestTemplate` to `WebClient` (already flagged as tech debt in CONCERNS.md) for non-blocking outbound HTTP — this allows a single thread to manage many in-flight provider calls without holding a connection per call.

**Warning signs:** Any `@Transactional` method that calls `providerClient.requestToPay(...)`. `RestTemplate` still in use for provider calls in a payment module.

**Phase:** Phase 1 (fundamental architectural constraint). Must be resolved before any load testing.

---

### P8.2 — Event Sourcing Write Amplification in PostgreSQL (HIGH)

**What goes wrong:** Each payment transaction generates 5–8 events in the audit log (INITIATED, FRAUD_CHECK, PROVIDER_SENT, WEBHOOK_RECEIVED, STATUS_VERIFIED, COMPLETED). Each event row includes a `previous_hash` computation and a cryptographic hash. At 1,000 transactions per hour, you are inserting 5,000–8,000 rows per hour into the event table. The hash chain requires each insert to read the previous event's hash. Under PostgreSQL's default settings with no tuning, sequential hash-chain inserts are serialized by the previous-hash lookup.

**Prevention:**
1. Maintain the "last event hash" in a separate `transaction_hash_state` table or as a column on the transaction row. Update it atomically with each event insert in the same transaction. This avoids a `SELECT max(sequence_number) ... WHERE transaction_id = ?` on every insert.
2. Partition the event table by month. PostgreSQL's partition pruning prevents month-range queries from scanning the full history.
3. Use PostgreSQL's `COPY` batching for bulk event inserts in the reconciliation module.

**Warning signs:** `SELECT max(event_seq) FROM audit_events WHERE transaction_id = ?` executed on every event insert. No event table partitioning in the schema design.

**Phase:** Phase 1 (schema design). Partitioning is hard to add after the fact.

---

### P8.3 — In-Memory Rate Limiting Not Scaling to Multi-Node (HIGH)

**What goes wrong:** Already documented in CONCERNS.md. Rate limits are in-memory Bucket4j with no Redis backend. In a multi-node payment gateway, rate limits are effectively multiplied by the node count. A tenant rate-limited to 100 TPS per key can achieve 100 × N TPS by hitting each node.

**Why this is CRITICAL for the payment module:** Rate limiting in the context of payment APIs is not just DoS protection — it is a fraud control mechanism. An attacker testing stolen MSISDNs at high speed bypasses velocity controls in a multi-node setup.

**Prevention:** Migrate Bucket4j to its Redis backend (`bucket4j-redis`) before the payment module goes live. This is a known fix with available implementation (Bucket4j Redis support is mature as of version 8.x — the codebase already uses `bucket4j-core 8.10.1`). Add `bucket4j-redis` to the Maven POM and configure a distributed `ProxyManager`.

**Warning signs:** Rate limiting that uses `Bandwidth.simple(...)` stored in a local `HashMap` or `ConcurrentHashMap`. No Redis connection used by `RateLimitingService`.

**Phase:** Phase 1, before multi-node deployment.

---

## Section 9: Provider API Instability

### P9.1 — Circuit Breaker Configuration Does Not Distinguish Network Failure From Provider Business Error (HIGH)

**What goes wrong:** The codebase uses Resilience4j circuit breakers (already present for email sending). If applied naively to provider calls, the circuit breaker trips on both network timeouts AND provider business errors (e.g., "insufficient funds," "invalid MSISDN"). A brief surge of invalid MSISDN errors from bad input data opens the circuit breaker, blocking all payment requests to that provider for the circuit's open duration — including legitimate transactions.

**Prevention:**
1. Configure the circuit breaker to trip only on technical failures (connection timeout, 500-series responses, `IOException`). Business errors (4xx responses from the provider) should be treated as normal flow, not circuit-breaker events.
2. Use `CircuitBreakerConfig.recordExceptions(IOException.class, TimeoutException.class)` and explicitly `ignoreExceptions(ProviderBusinessException.class)`.
3. Track business error rates separately as metrics (e.g., "rate of INVALID_MSISDN responses") for operational alerting, distinct from technical error rates.

**Warning signs:** Circuit breaker configuration that records all exceptions without an explicit ignore list for business errors. Provider business errors (`400` with structured error body) treated as circuit-breaker signals.

**Phase:** Phase 1 (provider adapter implementation).

---

### P9.2 — No Outbound Queue for Provider Calls During Provider Downtime (HIGH)

**What goes wrong:** MTN MoMo and Orange Money both have maintenance windows and unplanned downtime. When the provider is down, all in-flight payment requests fail immediately. Your clients receive errors and retry — potentially against a rate limit that you hit because all retries arrive simultaneously. No transactions are queued for delivery when the provider recovers.

**Prevention:**
1. Implement an outbound provider call queue (using a persistent message queue — Spring's `@Async` with a database-backed task queue, or a lightweight broker like RabbitMQ if already available). Transactions that fail due to provider downtime (circuit breaker open or 5xx) are placed in the queue.
2. The queue processor retries with exponential backoff. Retries are controlled by the circuit breaker state.
3. Distinguish "provider down" (queue the transaction) from "business error" (fail immediately with clear error to client).
4. Cap the queue depth to prevent unbounded memory growth during extended downtime.

**Warning signs:** Provider call failures that immediately return HTTP 503 to the client with no queuing or retry. No distinction between transient provider failure and permanent business failure in the error handling path.

**Phase:** Phase 2 (reliability hardening). Design the queue interface in Phase 1 so Phase 2 can implement it.

---

### P9.3 — MTN MoMo OAuth Token Expiry Under High Concurrency (HIGH)

**What goes wrong:** MTN MoMo access tokens expire in 3,600 seconds (1 hour). Your token refresh logic fetches a new token when it detects expiry. Under load, if 50 concurrent threads detect expiry at the same time, they all fetch new tokens simultaneously — 50 OAuth token requests in one second. MTN rate-limits the token endpoint. Some token requests fail, some threads proceed with stale tokens, and you get a cascade of 401 errors followed by another wave of token refresh attempts.

**Prevention:**
1. Implement a single shared token cache with double-checked locking or a Caffeine async refresh:
```java
// Use Caffeine refreshAfterWrite, not expireAfterWrite
.refreshAfterWrite(50, TimeUnit.MINUTES)  // refresh before expiry, not after
```
2. Proactively refresh the token at 80% of its TTL (after 48 minutes of a 60-minute token), not on expiry.
3. Use a `CompletableFuture` or `Supplier<AccessToken>` wrapped in a lock so only one thread fetches a new token while others wait.

**Warning signs:** Token refresh triggered by catching a 401 exception per-request. No shared token cache. Token TTL of exactly 3600 seconds with no proactive refresh.

**Phase:** Phase 1 (MTN adapter implementation).

---

## Section 10: Security Mistakes Specific to African Fintech Context

### P10.1 — SIM Swap Attack Window Not Addressed (CRITICAL)

**What goes wrong:** SIM swap fraud is the dominant fraud vector in Cameroon and West Africa broadly. An attacker bribes or social-engineers a mobile operator store agent to reassign a victim's phone number to a new SIM card. For 15–60 minutes during the swap window, the attacker controls the MSISDN. During this window, they can receive and approve mobile money push prompts.

**Why the planned mitigations are incomplete:** The fraud scoring adds +50 for "non +237 number" but adds nothing for SIM swap indicators. Velocity checks detect high transaction counts but not a sudden new approval pattern on a previously quiet number.

**Prevention:**
1. Integrate with MTN and Orange's SIM swap detection APIs (both operators expose these for partners in Cameroon — confirm availability with your account manager; this is MEDIUM confidence based on West African operator patterns). These return the "days since last SIM swap" for a given MSISDN.
2. Apply a transaction hold for MSISDNs that have had a SIM swap within the last 48 hours: require SMS OTP or reject transactions above a threshold (e.g., > 10,000 XAF).
3. Track the ratio of "first-ever approval within 30 minutes of SIM swap" as a fraud signal — this pattern is nearly always fraud.

**Warning signs:** No SIM swap check in the transaction authorization path. No "days since last SIM swap" query to the provider before approving high-value transactions.

**Phase:** Phase 1 (fraud engine, high-value transaction path). Requires partner-level API access — negotiate this during provider onboarding.

---

### P10.2 — PIN Exposure in C2C and IC2C Flows for Orange Money (HIGH)

**What goes wrong:** The Orange Money C2C and IC2C flows require submitting the channel PIN in the request body (see `orange-money-integration-guide.md`, section 6). This PIN is stored in your application configuration. If the PIN is logged (the `AbstractClient` base class logs request/response bodies for debugging), the PIN appears in your structured logs and is shipped to Loki (already configured in the stack). Anyone with Loki read access can extract the Orange Money channel PIN.

**Why this is specific to this codebase:** The existing `CONCERNS.md` already documents credential leakage risks (`User.toString()` leaks activation keys). The pattern of sensitive data appearing in logs is already a known weakness. The payment module will introduce new categories of sensitive data (channel PINs, merchant MSISDNs) that are not currently covered by `BodySanitizer`.

**Prevention:**
1. Extend `BodySanitizer` to mask `pin`, `channelPin`, `channelUserMsisdn`, `subscriberMsisdn`, and `payToken` fields in all log output from the payment module.
2. Store the channel PIN using the existing `jasypt` encryption library (already in the stack) — never as a plain config value.
3. Add a CI check that scans log output from integration tests for known sensitive field patterns.

**Warning signs:** `AbstractClient` logging full request bodies for provider calls. No `BodySanitizer` coverage of payment-specific fields. Channel PIN stored in `application.yaml` in plaintext.

**Phase:** Phase 1 (provider adapter implementation). Must be in place before any integration test that calls the Orange API.

---

### P10.3 — TLS Certificate Verification Disabled for MoMo Client (CRITICAL)

**What goes wrong:** `CONCERNS.md` already documents this: `checkCertificate: false` is set in `defaultTcpConfig`. This is currently justified as "sandbox only." However, if the production configuration profile is created by copying the sandbox config (a common practice), this setting survives into production and all MTN MoMo payment calls are subject to man-in-the-middle interception.

**Why this is a pitfall even with the existing flag:** The risk is not that a developer will consciously choose to disable TLS in production — it is that the configuration copy workflow does not include a check for this flag. There is no compile-time or startup-time assertion that `checkCertificate` must be `true` in non-sandbox environments.

**Prevention:**
1. Add a startup-time assertion in `PayamApplication` or a `@PostConstruct` in `TcpConfiguration`:
```java
@Value("${momo.check-certificate:true}")
private boolean checkCertificate;

@PostConstruct
public void validateConfig() {
    String env = System.getenv("SPRING_PROFILES_ACTIVE");
    if (!checkCertificate && !"dev,sandbox".contains(env)) {
        throw new IllegalStateException(
            "checkCertificate=false is not permitted in environment: " + env);
    }
}
```
2. Add a production profile (`application-prod.yaml`) that explicitly sets `checkCertificate: true` and include a verification test.

**Warning signs:** No startup assertion. Production deployment from a copied sandbox config without a certificate flag review step.

**Phase:** Phase 1 (before any production deployment). This fix takes 10 minutes and prevents catastrophic payment interception.

---

### P10.4 — Tenant API Key Rotation Without Transaction In-Flight Protection (HIGH)

**What goes wrong:** A tenant rotates their API key (revokes the old one, activates the new one). Any payment transactions initiated with the old key that are still PENDING at the provider — awaiting customer approval — are now orphaned. When the provider delivers the webhook for those transactions, your system can match them by `payToken` or `externalId`. But if your authorization model checks the API key on webhook receipt (it should not, but some implementations do), those webhooks are rejected. The in-flight transactions never complete.

**Prevention:**
1. Webhooks are matched by `payToken` or internal transaction ID, never by the API key that initiated the transaction. The API key is for client authentication at the API boundary, not for transaction ownership.
2. Implement a key rotation grace period: after a key is revoked, it remains valid for signature verification of in-flight transactions for 30 minutes. New transactions cannot be initiated with a revoked key, but existing in-flight transactions can complete.
3. Add a "pending transaction count" check before allowing key revocation: warn the tenant if they have N transactions in PENDING state.

**Warning signs:** Webhook processing that validates the originating API key. No grace period for key revocation. No warning on key revocation when in-flight transactions exist.

**Phase:** Phase 1 (API key management design).

---

### P10.5 — Orange Money Long-Lived Credentials With No Rotation Path (HIGH)

**What goes wrong:** The Orange Money API uses long-lived `X-AUTH-TOKEN` and `Bearer` credentials that "do not expire per-request" (as documented in `orange-money-integration-guide.md`, section 3). These are provisioned by Orange at partner registration. There is no documented API for rotating them — credential rotation requires contacting Orange support. If these credentials are compromised (leaked in logs, in source control, or via an insider), there is no programmatic rotation path.

**Why this is specific to this codebase:** The existing `CONCERNS.md` documents a similar risk for the `BEQMN6SITHUDQEGIDWKZ` mail password comment. The pattern of sensitive credentials appearing in config files is already present in the codebase.

**Prevention:**
1. Store Orange Money credentials exclusively in a secrets manager (AWS Secrets Manager, HashiCorp Vault) or at minimum environment variables — never in `application.yaml`. This is already partially done for MTN (`${MOMO_SUBSCRIPTION_KEY}`) but must be verified for Orange Money.
2. Establish a manual rotation procedure (even if it requires contacting Orange support) and document it. Schedule a rotation reminder every 90 days.
3. Add credential access auditing: log every time the Orange credentials are loaded from the secrets store (not every use — just loading), so you can detect unusual loading patterns.
4. Design the `OrangeMoneyConfig` so that credential updates do not require an application restart (load from a `@RefreshScope` bean or implement a credential cache with a background refresh).

**Warning signs:** Orange Money credentials stored in `application.yaml`. No documented rotation procedure. No monitoring for credential age.

**Phase:** Phase 1 (provider configuration). Before any production deployment.

---

## Phase Mapping Summary

| Pitfall | Severity | Phase |
|---------|----------|-------|
| P1.1 Webhook-before-database race | CRITICAL | Phase 1 |
| P1.2 Polling/webhook race condition | CRITICAL | Phase 1 |
| P1.3 Orange payToken expiry between init and pay | HIGH | Phase 1 |
| P1.4 MTN callback via PUT not POST | HIGH | Phase 1 |
| P1.5 Perpetually PENDING transactions | HIGH | Phase 1 |
| P2.1 Idempotency key cross-tenant collision | CRITICAL | Phase 1 |
| P2.2 Init succeeded/pay failed Orange partial state | CRITICAL | Phase 1 |
| P2.3 Idempotency key expiry enables double-charge | CRITICAL | Phase 1 |
| P2.4 Idempotency keys in Redis without durability | HIGH | Phase 1 |
| P3.1 SSRF via notifUrl parameter | CRITICAL | Phase 1 |
| P3.2 Webhook replay window too generous | HIGH | Phase 1 |
| P3.3 IP whitelist bypassed via X-Forwarded-For | HIGH | Phase 1 |
| P3.4 Timing attack on HMAC comparison | MEDIUM | Phase 1 |
| P4.1 Transaction lookup without tenant scope | CRITICAL | Phase 1 |
| P4.2 API key leakage via error messages | HIGH | Phase 1 |
| P4.3 Rate limit bypass multi-tenant | HIGH | Phase 1 |
| P4.4 Webhook forwarding without tenant isolation | HIGH | Phase 1 |
| P5.1 Timezone: Orange timestamps without offset | CRITICAL | Phase 1 (storage) / Phase 2 (reconciliation) |
| P5.2 Provider clock drift at day boundaries | HIGH | Phase 2 |
| P5.3 Late-resolving transactions after reconciliation window | HIGH | Phase 2 |
| P5.4 Reconciling against settlement vs. transaction report | MEDIUM | Phase 2 |
| P6.1 Hash chain verification performance | HIGH | Phase 1 |
| P6.2 Event schema evolution deserialization failure | HIGH | Phase 1 |
| P6.3 Event ordering under concurrent writes | HIGH | Phase 1 |
| P6.4 Audit log queries hitting primary database | MEDIUM | Phase 2 |
| P7.1 SIM sharing household false positives | HIGH | Phase 1 |
| P7.2 Phone number portability breaks MSISDN identity | HIGH | Phase 1 |
| P7.3 Merchant payroll bulk transactions blocked | HIGH | Phase 1 |
| P7.4 IP geolocation miscalibrated for Cameroon | MEDIUM | Phase 1 |
| P8.1 PostgreSQL connection exhaustion under load | CRITICAL | Phase 1 |
| P8.2 Event sourcing write amplification | HIGH | Phase 1 |
| P8.3 In-memory rate limiting not multi-node | HIGH | Phase 1 (before multi-node) |
| P9.1 Circuit breaker trips on business errors | HIGH | Phase 1 |
| P9.2 No outbound queue during provider downtime | HIGH | Phase 2 |
| P9.3 MTN token expiry thundering herd | HIGH | Phase 1 |
| P10.1 SIM swap attack window | CRITICAL | Phase 1 |
| P10.2 PIN exposure in Orange C2C/IC2C logs | HIGH | Phase 1 |
| P10.3 TLS verification disabled in production | CRITICAL | Phase 1 |
| P10.4 API key rotation with in-flight transactions | HIGH | Phase 1 |
| P10.5 Orange long-lived credentials no rotation | HIGH | Phase 1 |

---

## Confidence Assessment

| Area | Confidence | Basis |
|------|------------|-------|
| Async flow pitfalls (P1.x) | HIGH | Derived directly from the provider API documentation in `/requirements/` combined with established distributed systems patterns |
| Idempotency pitfalls (P2.x) | HIGH | Derived from Orange Money 2-step flow documentation and known partial-failure patterns |
| Webhook security (P3.x) | HIGH | P3.1–P3.3 are well-established web security pitfalls. P3.4 is a documented timing attack with mitigations in Java stdlib |
| Multi-tenancy (P4.x) | HIGH | Standard multi-tenant SaaS pitfalls; P4.1 is directly implied by TSID usage in the existing codebase |
| Reconciliation (P5.x) | MEDIUM-HIGH | P5.1 (Orange timestamp format) is observed from the API guide; WAT vs UTC behavior for Orange is MEDIUM confidence (no authoritative source confirms timezone of Orange's `createtime` field — verify with Orange partner before implementing) |
| Event sourcing (P6.x) | HIGH | Well-documented patterns from event sourcing literature; P6.1 is a measurable performance concern |
| Fraud engine (P7.x) | MEDIUM | P7.1–P7.3 are based on West African mobile money operational patterns; SIM sharing and portability behaviors in Cameroon are reported by practitioners but not verified against official sources |
| Performance (P8.x) | HIGH | P8.1 is directly calculable from the existing codebase's connection pool configuration documented in CONCERNS.md |
| Provider instability (P9.x) | MEDIUM-HIGH | P9.3 (MTN token thundering herd) is a well-known OAuth pattern; provider downtime frequency is MEDIUM confidence based on West African operator field reports |
| Security (P10.x) | HIGH | P10.3 is directly documented in CONCERNS.md; P10.2 is directly observed from log infrastructure already in place; P10.1 (SIM swap API availability) is MEDIUM confidence |

---

## Items to Verify With Providers Before Phase 1

These pitfall mitigations depend on provider behavior that cannot be confirmed from documentation alone:

1. **Orange Money payToken TTL** (affects P1.3): Confirm exact expiry duration with your Orange Money partner account manager.
2. **Orange Money `createtime` timezone** (affects P5.1): Confirm whether `createtime` is WAT or UTC with Orange technical support.
3. **SIM swap detection API availability** (affects P10.1): Confirm whether MTN and Orange expose a SIM swap recency API to partners in Cameroon.
4. **MTN callback HTTP method in production** (affects P1.4): The use-case doc says PUT; verify against your MTN sandbox integration before coding the webhook endpoint.
5. **Provider settlement report format** (affects P5.4): Request sample reconciliation reports from both providers during partner onboarding.

---

*Pitfall research: 2026-03-23. Sources: project requirements files in `/requirements/`, codebase analysis in `.planning/codebase/`, knowledge of distributed payment systems, event sourcing literature, and West African mobile money integration patterns.*
