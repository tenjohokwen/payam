# Phase 51: Orchestrator & Public API — Research

**Researched:** 2026-04-25
**Domain:** Spring Boot disbursement orchestration — REST API, idempotency, fraud scoring, provider routing, step-up confirmation, Quartz expiry job
**Confidence:** HIGH (all findings are from direct codebase inspection; no external sources needed for this internal-pattern phase)

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| DISB-01 | POST /v1/disbursements → 202 with disbursementId + status; MSISDN routing; recipient validation | DisbursementOrchestrator (new) mirrors PaymentOrchestrator pattern; MsisdnRouter + MobileMoneyPort.validateSubscriber already exist |
| DISB-02 | GET /v1/disbursements/{id} — tenant-scoped 404 if wrong tenant | DisbursementRepository.findByDisbursementId exists; tenant-scope check is a 2-line guard |
| DISB-03 | GET /v1/disbursements — paginated, filterable by status + date range | JPA Page + JPQL with optional predicates, same pattern as adminSearch on TransactionRepository |
| DISB-04 | POST /v1/disbursements/{id}/confirm — only PENDING_CONFIRMATION → PROCESSING; triggers provider | DisbursementStatus.PENDING_CONFIRMATION.allowedTransitions() already includes PROCESSING |
| PROV-01 | MTN MoMo via MtnMoMoPort.initiateDisbursement(); separate OAuth2 disbursement token; poll fallback | MtnMoMoPort.initiateDisbursement() and getDisbursementTransactionStatus() already implemented |
| PROV-02 | Orange Money via OrangeMoneyPort.initiateDisbursement() → /cashout; poll fallback | OrangeMoneyPort.initiateDisbursement() already calls /cashout — confirmed from source read |
| PROV-03 | validateAccountHolder via MobileMoneyPort.validateSubscriber() before transfer; 422 RECIPIENT_NOT_FOUND | MobileMoneyPort.validateSubscriber() exists on both ports; SubscriberStatus record available |
| SEC-01 | Idempotency-Key header; Redis namespace idempotency:dsb:<tenantId>:<key>; 24h TTL | IdempotencyService exists but uses KEY_PREFIX "idempotency:" — disbursement orchestrator must use a distinct "idempotency:dsb:" prefix OR pass namespace-qualified key; new DisbursementIdempotencyService is the cleanest approach |
| SEC-02 | Velocity limits: >20/min or >200/hr per tenant → 429; >10/day same MSISDN → 422 DAILY_LIMIT_EXCEEDED | VelocityCheckService exists with Bucket4j Redis; needs new FraudSignal variants or parallel disbursement-specific velocity check |
| SEC-03 | Disbursement fraud signals: new recipient +15, amount outlier +30, known-fraud MSISDN +80; score >80 = FRAUD_BLOCK | FraudScoringService.evaluate(PaymentCommand) currently uses IP/MSISDN/APP/HOUSEHOLD signals — disbursement needs 3 new signals. Cleanest: extend FraudScoringService with a second evaluate() overload or create DisbursementFraudScoringService |
| SEC-04 | amount > 500,000 XAF → PENDING_CONFIRMATION; confirm endpoint triggers provider; 15-min expiry via Quartz | DisbursementStatus machine supports PENDING_CONFIRMATION → PROCESSING already; need Quartz job to age-out to EXPIRED |
</phase_requirements>

---

## Summary

Phase 51 wires together the seven building blocks that Phase 50 put in place (Disbursement entity, DisbursementStatus state machine, WalletBalanceService, MerchantWalletBalance) into a production-ready public API. The pattern mirrors Phase 5 (PaymentOrchestrator + PaymentResource) almost exactly — the differences are: (a) a step-up confirmation gate for large amounts, (b) a distinct Redis idempotency namespace, (c) disbursement-specific fraud signals, (d) balance reservation before the provider call, and (e) a Quartz job to expire unconfirmed disbursements.

Both provider port methods (`MtnMoMoPort.initiateDisbursement()` and `OrangeMoneyPort.initiateDisbursement()`) already exist and are tested in `MtnMoMoPortIT` and `OrangeMoneyPortIT`. `Orange.initiateDisbursement()` calls `/cashout` — **not** `/ic2c/pay` — this is confirmed from direct source inspection of `OrangeMoneyClient.java` line: `buildClientURL("/cashout")`. The concern raised in STATE.md blocker note is now resolved: no new HTTP method is needed for Orange.

The key orchestration sequence for the happy path is: (1) idempotency check (dsb namespace), (2) MSISDN routing, (3) fraud + velocity check, (4) evaluate fee, (5) checkAndReserve wallet balance (inside TransactionTemplate — holds PESSIMISTIC_WRITE lock), (6) create Disbursement row (INITIATED or PENDING_CONFIRMATION), (7) if PENDING_CONFIRMATION — return 202 and wait; if normal — validate subscriber + call provider port, (8) transition to PROCESSING, (9) store idempotency response.

**Primary recommendation:** Model `DisbursementOrchestrator` directly on `PaymentOrchestrator` — reuse `MsisdnRouter`, `WalletBalanceService`, existing ports, `FeeEvaluationService`, and `TransactionTemplate` (no `@Transactional` on orchestrator methods that make HTTP calls). Create a parallel `DisbursementFraudEvaluationService` for the three new signals rather than modifying the existing `FraudScoringService` which must remain unchanged for the collection path.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot (Web + JPA) | 3.x (project baseline) | REST controllers, JPA repositories | Project baseline |
| Spring Data JPA | 3.x | DisbursementRepository queries, Page support | Already in use |
| Spring TransactionTemplate | 3.x | No-@Transactional orchestrator with scoped DB writes | Established pattern (PaymentOrchestrator) |
| Quartz Scheduler | 2.x via spring-boot-starter-quartz | PENDING_CONFIRMATION → EXPIRED expiry job | Already in use for MTN/Orange pollers |
| Bucket4j (Redis via Lettuce) | project baseline | Velocity checks (>20/min, >200/hr, >10/day MSISDN) | VelocityCheckService already uses this |
| Spring Data Redis (StringRedisTemplate) | 3.x | Idempotency key storage namespace, velocity probing | Already in use |
| Hibernate Envers | 3.x | Audit trail on Disbursement (already @Audited) | Entity is already annotated |
| Resilience4j Circuit Breaker / Retry | project baseline | Provider port wrapping | Ports already annotated |
| Lombok | project baseline | @Slf4j, @RequiredArgsConstructor | Project-wide |
| Jakarta Validation | 3.x | @Valid on request DTO | Project-wide |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| TSID (io.hypersistence.tsid) | project baseline | Generate disbursementId (UUID-format string, derived from TSID) | All entity creation |
| JsonUtil (internal) | — | Serialize/deserialize idempotency cache JSON | Same as PaymentOrchestrator |
| WireMock (test) | project baseline | Stub provider endpoints in integration tests | DisbursementOrchestratorIT |

### No new library dependencies
This phase introduces zero new library dependencies. All required capabilities already exist in the project.

---

## Architecture Patterns

### Recommended Project Structure

New files to create:

```
src/main/java/com/softropic/payam/disbursement/
├── api/
│   └── DisbursementResource.java            # REST: POST, GET, LIST, confirm
├── service/
│   ├── DisbursementOrchestrator.java        # Core orchestration (no @Transactional)
│   ├── DisbursementService.java             # DB writes: create, transition, findForTenant
│   ├── DisbursementFraudEvaluationService.java  # 3 new signals (SEC-03)
│   └── DisbursementExpiryJob.java           # Quartz: PENDING_CONFIRMATION → EXPIRED
├── config/
│   └── DisbursementSchedulerConfig.java     # Quartz job + trigger registration
├── contract/
│   ├── DisbursementRequest.java             # Inbound DTO: recipientMsisdn, amount, currency, reference, description, metadata
│   ├── DisbursementResponse.java            # Outbound: disbursementId, status, ...
│   └── DisbursementListResponse.java        # Paginated list item DTO
└── repo/
    └── (already exists from Phase 50)
```

### Pattern 1: No @Transactional on Orchestrator Methods That Call HTTP

**What:** Any orchestrator method that dispatches to a provider port must NOT carry `@Transactional`. Instead, use `TransactionTemplate.execute()` for each discrete DB write.

**When to use:** Always, in `DisbursementOrchestrator` — the same rule as `PaymentOrchestrator`.

**Example:**
```java
// Source: PaymentOrchestrator.java (project pattern — Phase 5)
// DO NOT add @Transactional on the method signature
public DisbursementResponse initiate(Long tenantId, DisbursementRequest request) {
    // Step 5: balance reservation — must be its own TransactionTemplate.execute() block
    // so the PESSIMISTIC_WRITE lock is released before the HTTP call
    transactionTemplate.execute(status -> {
        walletBalanceService.checkAndReserve(tenantId, totalAmount);
        return null;
    });

    // Step 6: provider HTTP call — outside any DB transaction
    ProviderResult result = port.initiateDisbursement(cmd);

    // Step 7: transition to PROCESSING — new TransactionTemplate.execute() block
    transactionTemplate.execute(status -> {
        Disbursement locked = disbursementRepository
            .findByDisbursementIdForUpdate(disbursementId).orElseThrow();
        locked.applyTransition(DisbursementStatus.PROCESSING);
        return null;
    });
}
```

### Pattern 2: Idempotency Namespace Isolation

**What:** Disbursement idempotency keys live in `idempotency:dsb:<tenantId>:<key>`, separate from payment keys (`idempotency:<tenantId>:<key>`). The existing `IdempotencyService` uses `KEY_PREFIX = "idempotency:"` — it cannot be reused directly without a namespace parameter.

**Implementation options (pick one):**
- Option A (recommended): Create `DisbursementIdempotencyService` extending or delegating to `IdempotencyService` logic but with `KEY_PREFIX = "idempotency:dsb:"`.
- Option B: Add a `checkAndReserve(Long tenantId, String key, String namespace)` overload to `IdempotencyService` and pass `"dsb"` from the orchestrator.

Option A is cleaner — less risk of accidentally sharing keys across products.

**Example:**
```java
// Source: IdempotencyService.java + STATE.md decision
// Disbursement-specific service using dsb-namespace
private static final String KEY_PREFIX = "idempotency:dsb:";

// Redis key format: idempotency:dsb:<tenantId>:<clientKey>
String redisKey = KEY_PREFIX + tenantId + ":" + idempotencyKey;
```

### Pattern 3: Step-Up Confirmation (SEC-04)

**What:** Amount > 500,000 XAF skips provider dispatch and records `PENDING_CONFIRMATION`. The confirm endpoint checks that state and then dispatches.

**Key constraint:** Balance reservation (checkAndReserve) happens at `POST /v1/disbursements` time — before the confirmation step. The wallet is debited immediately to prevent a concurrent normal disbursement from consuming the reserved funds while awaiting confirmation.

**State machine already supports this:**
- `INITIATED → PENDING_CONFIRMATION` (allowed in DisbursementStatus)
- `PENDING_CONFIRMATION → PROCESSING` (allowed)
- `PENDING_CONFIRMATION → EXPIRED` (allowed — Quartz expiry)

**Confirm endpoint flow:**
1. Load disbursement by id, verify tenantId matches (404 if not)
2. Assert `disbursementStatus == PENDING_CONFIRMATION` (422 INVALID_STATE if not)
3. Validate subscriber (PROV-03)
4. Dispatch to provider port
5. Transition to PROCESSING

### Pattern 4: DisbursementExpiryJob (Quartz)

**What:** A Quartz job queries `disbursement_status = PENDING_CONFIRMATION` AND `created_date < NOW() - 15 minutes` and transitions each to `EXPIRED`. Does NOT release wallet balance (BAL-03 — EXPIRED holds the reservation).

**Pattern (from MtnSchedulerConfig / OrangeSchedulerConfig):**
```java
// Source: MtnSchedulerConfig.java pattern
@Bean
public JobDetail disbursementExpiryJobDetail() {
    return JobBuilder.newJob(DisbursementExpiryJob.class)
        .withIdentity("disbursement-expiry-job")
        .storeDurably()
        .build();
}

@Bean
public Trigger disbursementExpiryTrigger(JobDetail disbursementExpiryJobDetail) {
    return TriggerBuilder.newTrigger()
        .forJob(disbursementExpiryJobDetail)
        .withSchedule(SimpleScheduleBuilder.simpleSchedule()
            .withIntervalInSeconds(60)    // run every 60 seconds
            .repeatForever())
        .build();
}
```

**In DisbursementExpiryJob.executeInternal:**
```java
// Query PENDING_CONFIRMATION disbursements older than 15 minutes
Instant threshold = Instant.now().minus(Duration.ofMinutes(15));
List<Disbursement> expired = disbursementRepository
    .findByDisbursementStatusAndCreatedDateBefore(DisbursementStatus.PENDING_CONFIRMATION, threshold);

for (Disbursement d : expired) {
    transactionTemplate.execute(status -> {
        Disbursement locked = disbursementRepository
            .findByDisbursementIdForUpdate(d.getDisbursementId()).orElseThrow();
        if (locked.getDisbursementStatus() == DisbursementStatus.PENDING_CONFIRMATION) {
            locked.applyTransition(DisbursementStatus.EXPIRED);
            // DO NOT call walletBalanceService.release() — BAL-03
        }
        return null;
    });
}
```

Note: `DisbursementRepository` needs two new methods: `findByDisbursementStatusAndCreatedDateBefore(...)` and `findByDisbursementIdForUpdate(...)` (pessimistic write lock).

### Pattern 5: Disbursement-Specific Fraud Signals (SEC-03)

**What:** Three new fraud signals on top of existing FraudScoringService:
- New recipient MSISDN (no previous disbursement to this MSISDN for this tenant): +15
- Amount > 3× tenant median payout (reuse concept from SEC-03 spec): +30
- Recipient on known-fraud list: +80

**Block threshold:** score > 80.

**Implementation:** Create `DisbursementFraudEvaluationService` (separate from the collection `FraudScoringService`). It receives a `DisbursementCommand` (or equivalent), evaluates the three signals, returns a `FraudDecision`. This avoids touching the existing collection fraud path.

The "known-fraud MSISDN" check requires a data store — the existing `FraudRule` DB table does not model a MSISDN blocklist. A Redis SET (`fraud:msisdn:blocklist`) is the simplest implementation. Alternatively, a new DB table. Given the phase scope, use Redis SET for speed.

**Velocity limits (SEC-02):**
These are separate from the fraud score — they are hard rate limits enforced before fraud scoring:
- >20 disbursements/minute per tenant → 429 Too Many Requests
- >200 disbursements/hour per tenant → 429 Too Many Requests
- >10 disbursements to same MSISDN/day → 422 DAILY_LIMIT_EXCEEDED

Use `VelocityCheckService`-style Bucket4j buckets with keys:
- `disb:velocity:tenant:minute:<tenantId>` — capacity=20, window=60s
- `disb:velocity:tenant:hour:<tenantId>` — capacity=200, window=3600s
- `disb:velocity:msisdn:day:<tenantId>:<msisdn>` — capacity=10, window=86400s

These can be implemented in `DisbursementVelocityService` (new) or reusing `VelocityCheckService.checkVelocity()` with new disbursement-specific signal names inserted into the fraud_rule DB, or directly via the `ProxyManager` in a new service. Direct ProxyManager usage is simpler since the limits are hardcoded per spec.

### Pattern 6: Paginated LIST Endpoint (DISB-03)

**What:** `GET /v1/disbursements?status=PROCESSING&from=2026-01-01&to=2026-01-31&page=0&size=20`

**Pattern (from AdminTransactionResource + TransactionRepository.adminSearch):**
```java
// Add to DisbursementRepository:
@Query("SELECT d FROM Disbursement d WHERE d.tenantId = :tenantId " +
       "AND (:status IS NULL OR d.disbursementStatus = :status) " +
       "AND (:from IS NULL OR d.createdDate >= :from) " +
       "AND (:to IS NULL OR d.createdDate <= :to) " +
       "ORDER BY d.createdDate DESC")
Page<Disbursement> findForTenant(
    @Param("tenantId") Long tenantId,
    @Param("status") DisbursementStatus status,
    @Param("from") Instant from,
    @Param("to") Instant to,
    Pageable pageable);
```

**Tenant-scope enforcement:** ALL queries on `DisbursementRepository` must include `WHERE tenantId = :tenantId`. The GET by id must verify `disbursement.getTenantId().equals(principal.getTenantId())` and throw `ResourceNotFoundException` if not matched (ApiAdvice maps this to 404).

### Pattern 7: Error Code → HTTP Status Mapping

Following PaymentResource pattern, DisbursementResource maps error codes to HTTP:
- `202` — successful dispatch (status PROCESSING) or idempotency replay
- `202` — large amount (status PENDING_CONFIRMATION)
- `422 INSUFFICIENT_BALANCE` — wallet check failed
- `422 RECIPIENT_NOT_FOUND` — validateSubscriber returned inactive
- `422 FRAUD_BLOCK` — fraud score > 80
- `422 DAILY_LIMIT_EXCEEDED` — >10/day MSISDN limit
- `422 INVALID_STATE` — confirm called on non-PENDING_CONFIRMATION disbursement
- `422 UNKNOWN_MSISDN_PREFIX` — unroutable MSISDN
- `429 VELOCITY_EXCEEDED` — >20/min or >200/hr tenant limit
- `503 PROVIDER_UNAVAILABLE` — circuit open
- `502 PROVIDER_ERROR` — 4xx/5xx from provider
- `404` — disbursement not found or belongs to another tenant

### Anti-Patterns to Avoid

- **@Transactional on orchestrator method:** Holds DB connection during provider HTTP call. Use `TransactionTemplate.execute()` for each discrete write instead.
- **Reusing `IdempotencyService` KEY_PREFIX directly:** The existing service uses `"idempotency:"` — disbursement must use `"idempotency:dsb:"`. Sharing the prefix would collide across products if a tenant uses the same key value for both a payment and a disbursement.
- **Calling `walletBalanceService.release()` on EXPIRED transition:** BAL-03 explicitly forbids this. EXPIRED means the provider may have accepted the transfer; releasing would allow overdraft.
- **Placing `findByDisbursementIdForUpdate` outside a TransactionTemplate block:** The PESSIMISTIC_WRITE lock is only valid inside an active transaction. Without TransactionTemplate or @Transactional, the lock is a no-op and the guard fails silently.
- **Triggering provider call from within a @Transactional method:** Any @Transactional span that includes an outbound HTTP call holds a DB connection for the full HTTP round-trip, exhausting the connection pool under load.
- **Not committing the Disbursement row before calling the provider:** If the provider accepts but the DB write fails on return, the system loses track of the disbursement. The Disbursement row must be committed (INITIATED status) before the provider call is made.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Token-bucket rate limiting | Custom Redis counter + Lua script | Bucket4j LettuceBasedProxyManager (VelocityCheckService pattern) | Already in codebase; handles atomic increment, expiry, and distributed correctness |
| Pessimistic DB lock | Application-level locking | `@Lock(PESSIMISTIC_WRITE)` + TransactionTemplate | Already used in WalletBalanceService and TransactionRepository; race-proof |
| Idempotency (race-safe) | Redis-only or DB-only custom solution | Existing `IdempotencyService` pattern (Postgres first, then Redis) | Proven correct under 20-thread concurrency (IDEM-02 test) |
| JSON serialization for idempotency cache | Custom serializer | `JsonUtil.toJson()` / `JsonUtil.toObject()` | Already used in PaymentOrchestrator |
| TSID generation | UUID.randomUUID() | `@Tsid` on entity (BaseEntity) | All entities use @Tsid via BaseEntity; auto-generated on persist |
| Quartz job registration | Spring @Scheduled | Quartz JobBuilder + TriggerBuilder (MtnSchedulerConfig pattern) | Cluster-safe, persistent via JDBC store; @Scheduled is not cluster-safe |
| State machine guard | if/else chains | `DisbursementStatus.transitionTo()` | Already implemented with `IllegalStateTransitionException` |

---

## Common Pitfalls

### Pitfall 1: Orange `/cashout` vs `/ic2c/pay` Confusion
**What goes wrong:** STATE.md blocker note says "read OrangeMoneyClient.cashout() HTTP path before writing any disbursement port code."
**Why it happens:** Earlier requirement text said `/ic2c/pay` but the existing code calls `/cashout`.
**How to avoid:** RESOLVED — confirmed from `OrangeMoneyClient.java`: `buildClientURL("/cashout")`. `OrangeMoneyPort.initiateDisbursement()` already exists and already calls `/cashout`. No new method is needed.
**Warning signs:** N/A — confirmed correct.

### Pitfall 2: Wallet Reserved But Disbursement Fails Before Provider Call
**What goes wrong:** `checkAndReserve` succeeds (balance decremented), but fraud check or subscriber validation then blocks the disbursement. The wallet balance is held but no provider call is made.
**Why it happens:** The reservation must happen before the provider call to prevent concurrent overdraft, but pre-call validation can still fail.
**How to avoid:** Sequence correctly: (1) idempotency check, (2) MSISDN routing, (3) fraud + velocity (fails fast before reservation), (4) reserve balance, (5) create Disbursement row in INITIATED, (6) validate subscriber, (7) call provider. For any failure after step 4, call `walletBalanceService.release()` and transition to FAILED. For fraud/velocity failures (step 3), no reservation has been made yet.
**Warning signs:** Test: fraud-blocked disbursement — wallet balance should be unchanged.

### Pitfall 3: Confirm Endpoint Race — Double Dispatch
**What goes wrong:** Two concurrent calls to `POST /v1/disbursements/{id}/confirm` both read `PENDING_CONFIRMATION` and both dispatch to the provider.
**Why it happens:** Without a pessimistic lock on the disbursement row during the confirm check+transition, both threads pass the status check and call the port.
**How to avoid:** In the confirm handler, use `disbursementRepository.findByDisbursementIdForUpdate()` (PESSIMISTIC_WRITE) inside a `TransactionTemplate.execute()` block. Only one thread will hold the lock at a time. The transition from `PENDING_CONFIRMATION → PROCESSING` inside the lock prevents the second thread from re-dispatching.
**Warning signs:** Add a concurrency IT test for the confirm endpoint.

### Pitfall 4: Balance Released for EXPIRED Disbursements
**What goes wrong:** A developer calls `walletBalanceService.release()` when transitioning a disbursement to `EXPIRED` (either from the Quartz expiry job or the provider-accepted-but-ledger-failed path).
**Why it happens:** EXPIRED and FAILED look similar; both are terminal states.
**How to avoid:** `release()` is only called when transitioning to `FAILED`. Explicitly document this in `DisbursementExpiryJob` and add a test assertion: after Quartz expires a PENDING_CONFIRMATION disbursement, wallet balance is UNCHANGED (reserved_amount remains).
**Warning signs:** WalletBalanceService.release() call sites — any caller passing a EXPIRED disbursement is a bug.

### Pitfall 5: AbstractPayamE2ETest Missing Disbursement WireMock Server
**What goes wrong:** Any disbursement E2E test that needs to stub the MTN disbursement endpoint (`mtn.disbursement-base-url`) will fail because `AbstractPayamE2ETest` only registers `mtn.collection-base-url`.
**Why it happens:** STATE.md blocker: "Phase 53: Add second @ConfigureWireMock for mtn.disbursement-base-url to E2E base class." This blocker is listed for Phase 53 but integration tests in Phase 51 also need it.
**How to avoid:** Phase 51's DisbursementOrchestratorIT (or a new base class) should add `mtn.disbursement-base-url` to `@ConfigureWireMock`. The existing `MtnMoMoPortIT` already does this correctly: `@ConfigureWireMock(name = "mtn", baseUrlProperties = {"mtn.collection-base-url", "mtn.disbursement-base-url"})`. Replicate this pattern for disbursement IT tests.
**Warning signs:** Test error: "Unexpected request to MTN disbursement endpoint" or "No stub for /disbursement/...".

### Pitfall 6: Idempotency Key Namespace Collision
**What goes wrong:** A tenant submits the same key string `"ref-001"` for a payment AND a disbursement. If both use the same Redis prefix, the disbursement gets the cached payment response (or vice versa).
**Why it happens:** `IdempotencyService` uses `KEY_PREFIX = "idempotency:"` — if reused as-is, both products share the same namespace.
**How to avoid:** The disbursement idempotency service (new) uses `"idempotency:dsb:"`. Never pass the disbursement key through the existing `IdempotencyService` directly.
**Warning signs:** Integration test: submit same key for payment and disbursement from same tenant — both should succeed independently.

### Pitfall 7: DisbursementExpiryJob Skips PENDING_CONFIRMATION Rows After Confirm
**What goes wrong:** Quartz runs, reads a row at `PENDING_CONFIRMATION`, fetches a PESSIMISTIC_WRITE lock, but by the time the lock is acquired another thread has already confirmed it (now at `PROCESSING`). The transition `PROCESSING → EXPIRED` is illegal and throws `IllegalStateTransitionException`.
**Why it happens:** Quartz queries stale rows before acquiring locks; the status may change between query and lock.
**How to avoid:** In the Quartz job, after acquiring the lock, re-check `getDisbursementStatus() == PENDING_CONFIRMATION` before calling `applyTransition(EXPIRED)`. Only proceed if the status is still `PENDING_CONFIRMATION`. Swallow `IllegalStateTransitionException` with a WARN log (idempotent Quartz tick behavior).

---

## Code Examples

### DisbursementRequest DTO
```java
// Pattern from PaymentRequest.java
public record DisbursementRequest(
    @NotBlank String recipientMsisdn,
    @NotNull @Positive BigDecimal amount,
    @NotBlank @Size(min=3, max=3) String currency,
    @NotBlank String reference,          // required
    String description,                  // optional
    String metadata                      // optional (JSON string stored as TEXT)
) {}
```

### DisbursementResponse DTO
```java
public record DisbursementResponse(
    String disbursementId,
    String status,                       // PROCESSING or PENDING_CONFIRMATION
    String providerRef,                  // null until provider confirms
    BigDecimal amount,
    String currency,
    String errorCode,                    // null on success
    String errorMessage                  // null on success
) {
    public static DisbursementResponse accepted(String id, String status, String providerRef,
                                                BigDecimal amount, String currency) { ... }
    public static DisbursementResponse failed(String id, String errorCode, String msg) { ... }
}
```

### DisbursementOrchestrator.initiate() Sequence
```java
// Source: PaymentOrchestrator.java pattern — adapted for disbursements
// NOT @Transactional
public DisbursementResponse initiate(Long tenantId, DisbursementRequest request, String idempotencyKey) {

    // 1. Idempotency check (dsb namespace)
    Optional<CachedResponse> cached = disbursementIdempotencyService.checkAndReserve(tenantId, idempotencyKey);
    if (cached.isPresent()) { return replayOrInProgress(cached.get()); }

    // 2. Route MSISDN
    MobilePaymentProvider provider = msisdnRouter.resolve(request.recipientMsisdn());

    // 3. Velocity check — BEFORE balance reservation
    disbursementVelocityService.checkTenantVelocity(tenantId);          // throws if >20/min or >200/hr
    disbursementVelocityService.checkMsisdnDailyLimit(tenantId, request.recipientMsisdn()); // throws if >10/day

    // 4. Fraud check — BEFORE balance reservation
    FraudDecision fraud = disbursementFraudService.evaluate(tenantId, request);
    if (fraud.blocked()) { return DisbursementResponse.failed(null, "FRAUD_BLOCK", fraud.reason()); }

    // 5. Evaluate fee
    BigDecimal fee = feeEvaluationService.evaluateFee(tenantId, request.amount());
    BigDecimal totalAmount = request.amount().add(fee);

    // 6. Reserve balance (PESSIMISTIC_WRITE lock inside TransactionTemplate)
    transactionTemplate.execute(status -> {
        walletBalanceService.checkAndReserve(tenantId, totalAmount);
        return null;
    });

    // 7. Determine flow — step-up confirmation gate
    boolean stepUp = request.amount().compareTo(STEP_UP_THRESHOLD) > 0;  // 500,000 XAF
    String disbursementId = UUID.randomUUID().toString();

    // 8. Create Disbursement row
    DisbursementStatus initialStatus = stepUp ? DisbursementStatus.PENDING_CONFIRMATION
                                              : DisbursementStatus.INITIATED;
    Disbursement dsb = disbursementService.create(tenantId, disbursementId, provider,
                                                   request, totalAmount, initialStatus, idempotencyKey);

    if (stepUp) {
        return DisbursementResponse.accepted(disbursementId, "PENDING_CONFIRMATION", null,
                                             request.amount(), request.currency());
    }

    // 9. Validate recipient (non-step-up path)
    SubscriberStatus subscriber = resolvePort(provider).validateSubscriber(request.recipientMsisdn());
    if (!subscriber.active()) {
        // Release reservation, transition to FAILED
        transactionTemplate.execute(st -> { walletBalanceService.release(tenantId, totalAmount); return null; });
        disbursementService.transitionToFailed(disbursementId);
        return DisbursementResponse.failed(disbursementId, "RECIPIENT_NOT_FOUND", "Recipient inactive");
    }

    // 10. Dispatch to provider (outside any DB transaction)
    PaymentCommand cmd = buildCommand(dsb, request, provider, fee);
    try {
        ProviderResult result = resolvePort(provider).initiateDisbursement(cmd);

        // 11. Transition to PROCESSING
        transactionTemplate.execute(st -> {
            Disbursement locked = disbursementRepository
                .findByDisbursementIdForUpdate(disbursementId).orElseThrow();
            locked.applyTransition(DisbursementStatus.PROCESSING);
            locked.setProviderRef(result.providerRef());
            return null;
        });

        DisbursementResponse response = DisbursementResponse.accepted(
            disbursementId, "PROCESSING", result.providerRef(), request.amount(), request.currency());
        disbursementIdempotencyService.store(tenantId, idempotencyKey, 202, JsonUtil.toJson(response));
        return response;

    } catch (Exception e) {
        transactionTemplate.execute(st -> { walletBalanceService.release(tenantId, totalAmount); return null; });
        disbursementService.transitionToFailed(disbursementId);
        return DisbursementResponse.failed(disbursementId, mapError(e), e.getMessage());
    }
}
```

### DisbursementRepository Additional Methods Needed
```java
// Add to existing DisbursementRepository:

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT d FROM Disbursement d WHERE d.disbursementId = :disbursementId")
Optional<Disbursement> findByDisbursementIdForUpdate(@Param("disbursementId") String disbursementId);

List<Disbursement> findByDisbursementStatusAndCreatedDateBefore(
    DisbursementStatus status, Instant before);

Optional<Disbursement> findByTenantIdAndDisbursementId(Long tenantId, String disbursementId);

@Query("SELECT d FROM Disbursement d WHERE d.tenantId = :tenantId " +
       "AND (:status IS NULL OR d.disbursementStatus = :status) " +
       "AND (:from IS NULL OR d.createdDate >= :from) " +
       "AND (:to IS NULL OR d.createdDate <= :to) " +
       "ORDER BY d.createdDate DESC")
Page<Disbursement> findForTenant(@Param("tenantId") Long tenantId,
                                  @Param("status") DisbursementStatus status,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  Pageable pageable);
```

### DisbursementResource Confirm Endpoint
```java
@PostMapping("/v1/disbursements/{disbursementId}/confirm")
public ResponseEntity<DisbursementResponse> confirm(
        @PathVariable String disbursementId,
        @AuthenticationPrincipal TenantPrincipal principal) {

    DisbursementResponse response = orchestrator.confirm(principal.getTenantId(), disbursementId);
    if (response.errorCode() == null) {
        return ResponseEntity.accepted().body(response);
    }
    return ResponseEntity.status(resolveHttpStatus(response.errorCode())).body(response);
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@Transactional` on orchestrator methods | `TransactionTemplate` for each discrete write; no `@Transactional` on HTTP-calling methods | Phase 5 (established) | Prevents connection pool exhaustion under load |
| Optimistic lock for concurrent balance control | `PESSIMISTIC_WRITE` (`SELECT FOR UPDATE`) | Phase 50 (explicit decision) | Optimistic retry allows second drain after first succeeds — defeats BAL-01 invariant |
| Single idempotency namespace | Separate namespaces per product (`idempotency:` vs `idempotency:dsb:`) | SEC-01 decision | Prevents cross-product key collision |

**Confirmed facts from codebase:**
- Orange `initiateDisbursement` calls `/cashout` (not `/ic2c/pay`) — STATE.md blocker is resolved
- Both `initiateDisbursement()` methods exist on both ports and are proven by `MtnMoMoPortIT` and `OrangeMoneyPortIT`
- `DisbursementStatus.PENDING_CONFIRMATION → PROCESSING` transition is valid in the existing state machine
- `WalletBalanceService.release()` already exists and is tested
- `DisbursementRepository.findByTenantIdAndIdempotencyKey()` exists (used by Phase 50's plans)
- `AbstractPayamE2ETest` only stubs `mtn.collection-base-url` — disbursement IT tests need `mtn.disbursement-base-url` added

---

## Open Questions

1. **Disbursement fraud known-fraud MSISDN list storage**
   - What we know: SEC-03 requires `+80` for recipient on known-fraud list
   - What's unclear: Where is this list stored? No existing blocklist table for MSISDNs
   - Recommendation: Use a Redis SET `fraud:dsb:msisdn:blocklist` for O(1) lookup. Populate via admin endpoint (out of Phase 51 scope) or seed in test setup. For Phase 51, implement the check against this key; it returns 0 score if the key doesn't exist (fail-open for new deployments).

2. **DisbursementIdempotencyService: extend or delegate?**
   - What we know: The existing `IdempotencyService` has all the logic needed; only the KEY_PREFIX differs
   - What's unclear: Whether to subclass, delegate, or add an overload
   - Recommendation: Delegate — create `DisbursementIdempotencyService` with `"idempotency:dsb:"` prefix that calls the same underlying Postgres + Redis operations. Do NOT modify `IdempotencyService` (risk of breaking collection path).

3. **Tenant median payout calculation for SEC-03 (+30 signal)**
   - What we know: "amount > 3× tenant median payout" requires historical data per tenant
   - What's unclear: Is there an existing aggregate query? The disbursement table is new — there will be no historical data for new tenants
   - Recommendation: For new tenants (or tenants with < 10 disbursements), skip the outlier signal (score contribution = 0). Once ≥ 10 disbursements exist, compute median from `disbursement` table. This avoids false-positive blocks for early adopters.

---

## Environment Availability

Step 2.6: SKIPPED (no new external dependencies — all required services are Redis, PostgreSQL, and the existing MTN/Orange sandbox endpoints, all of which are already confirmed available from Phase 50 execution).

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers |
| Config file | `src/test/java/com/softropic/payam/config/` (PostgresContainerConfig, RedisContainerConfig) |
| Quick run command | `mvn test -pl . -Dtest=DisbursementOrchestratorTest,DisbursementResourceTest,DisbursementExpiryJobTest -q` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| DISB-01 | POST /v1/disbursements → 202 PROCESSING; MSISDN routing; recipient validation | Integration | `mvn test -Dtest=DisbursementOrchestratorIT` | ❌ Wave 0 |
| DISB-02 | GET /v1/disbursements/{id} tenant-scoped | Integration | `mvn test -Dtest=DisbursementResourceIT#getById_wrongTenant_returns404` | ❌ Wave 0 |
| DISB-03 | GET /v1/disbursements paginated + filtered | Integration | `mvn test -Dtest=DisbursementResourceIT#list_filterByStatus` | ❌ Wave 0 |
| DISB-04 | POST /v1/disbursements/{id}/confirm — PENDING_CONFIRMATION only | Integration | `mvn test -Dtest=DisbursementOrchestratorIT#confirm_*` | ❌ Wave 0 |
| PROV-01 | MTN disbursement routing + poll | Integration | `mvn test -Dtest=DisbursementOrchestratorIT#mtn_happy_path` | ❌ Wave 0 |
| PROV-02 | Orange disbursement routing + poll | Integration | `mvn test -Dtest=DisbursementOrchestratorIT#orange_happy_path` | ❌ Wave 0 |
| PROV-03 | validateSubscriber → 422 RECIPIENT_NOT_FOUND | Unit + Integration | `mvn test -Dtest=DisbursementOrchestratorTest#recipientInactive_returns422` | ❌ Wave 0 |
| SEC-01 | Idempotency-Key header; dsb namespace; 24h cache | Integration | `mvn test -Dtest=DisbursementIdempotencyIT` | ❌ Wave 0 |
| SEC-02 | Velocity limits: 429 + 422 DAILY_LIMIT_EXCEEDED | Integration | `mvn test -Dtest=DisbursementVelocityIT` | ❌ Wave 0 |
| SEC-03 | Fraud signals; score >80 = FRAUD_BLOCK | Unit + Integration | `mvn test -Dtest=DisbursementFraudEvaluationServiceTest` | ❌ Wave 0 |
| SEC-04 | Step-up: PENDING_CONFIRMATION; confirm trigger; 15-min expiry | Integration | `mvn test -Dtest=DisbursementExpiryJobIT,DisbursementOrchestratorIT#stepUp_*` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=<new test class> -q`
- **Per wave merge:** `mvn verify -pl . -q`
- **Phase gate:** `mvn verify` full suite green before `/gsd:verify-work`

### Wave 0 Gaps
All test files are new — none exist yet. The following must be created as part of the phase plan:
- [ ] `DisbursementOrchestratorTest.java` — unit tests for orchestration logic (fraud block, balance release, step-up routing)
- [ ] `DisbursementOrchestratorIT.java` — integration tests with WireMock stubs for MTN + Orange disbursement endpoints; needs `mtn.disbursement-base-url` in `@ConfigureWireMock`
- [ ] `DisbursementResourceIT.java` — REST layer tests (tenant-scope 404, confirm endpoint, list filtering)
- [ ] `DisbursementIdempotencyIT.java` — idempotency namespace isolation test
- [ ] `DisbursementVelocityIT.java` — velocity limit enforcement tests
- [ ] `DisbursementFraudEvaluationServiceTest.java` — unit tests for all 3 new signals
- [ ] `DisbursementExpiryJobIT.java` — Quartz expiry: PENDING_CONFIRMATION → EXPIRED, wallet balance unchanged

---

## Sources

### Primary (HIGH confidence)
All findings are from direct source inspection of the project codebase — no external documentation was required. Key files read:

- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` — orchestration pattern
- `src/main/java/com/softropic/payam/payment/api/PaymentResource.java` — REST controller pattern
- `src/main/java/com/softropic/payam/disbursement/service/WalletBalanceService.java` — balance gate
- `src/main/java/com/softropic/payam/disbursement/contract/DisbursementStatus.java` — state machine
- `src/main/java/com/softropic/payam/disbursement/repo/Disbursement.java` — entity fields
- `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` — initiateDisbursement() exists
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` — initiateDisbursement() exists, calls /cashout
- `src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java` — `/cashout` confirmed
- `src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java` — KEY_PREFIX pattern
- `src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java` — signal evaluation pattern
- `src/main/java/com/softropic/payam/fraud/service/VelocityCheckService.java` — Bucket4j pattern
- `src/main/java/com/softropic/payam/mtn/config/MtnSchedulerConfig.java` — Quartz pattern
- `src/test/java/com/softropic/payam/e2e/AbstractPayamE2ETest.java` — WireMock base class
- `src/test/java/com/softropic/payam/config/TestDataCleaner.java` — disbursement tables already present
- `.planning/REQUIREMENTS.md` — requirement IDs and success criteria
- `.planning/STATE.md` — key decisions and blockers

### Secondary (MEDIUM confidence)
- STATE.md accumulated context section — architectural decisions made in previous phases

### Tertiary (LOW confidence)
None.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in use; no new dependencies
- Architecture: HIGH — patterns copied directly from working PaymentOrchestrator
- Orange endpoint: HIGH — confirmed `/cashout` from OrangeMoneyClient.java source; STATE.md blocker resolved
- Pitfalls: HIGH — derived from existing bugs caught in prior phases (IDEM-02, BAL-01, BAL-03)
- Test gaps: HIGH — all listed files confirmed absent by filesystem scan

**Research date:** 2026-04-25
**Valid until:** 2026-05-25 (codebase is stable between Phase 50 completion and Phase 51 start)
