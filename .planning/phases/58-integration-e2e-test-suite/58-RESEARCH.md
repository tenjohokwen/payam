# Phase 58: Integration & E2E Test Suite — Research

**Researched:** 2026-05-05
**Domain:** Java/Spring Boot integration and E2E testing — JUnit 5, WireMock, Testcontainers, Awaitility, AssertJ
**Confidence:** HIGH

---

## Summary

Phase 58 is a pure test-writing phase. No production code is added or modified. The goal is to extend the existing E2E and integration test classes to assert **claim lifecycle correctness** at the HTTP and service layer — closing the gap between what the v10 tests proved (wallet-balance mechanics) and what v11 requires (claim-based locking via `DisbursementTransactionRef`).

The existing E2E tests (`MtnDisbursementE2EIT`, `OrangeDisbursementE2EIT`, `DisbursementExpiryE2EIT`, `StepUpConfirmationE2EIT`) were written before v11 claim logic existed. They call `seedTxnsForClaim()` and pass `transactionIds` in the POST body — so they compile and execute with real claim rows — but they never assert anything about `DisbursementTransactionRef` state transitions (PENDING → CLAIMED / RELEASED). Those assertions are the missing piece.

Likewise, a service-layer integration test proving the PENDING_ADMIN_APPROVAL → EXPIRED path at the HTTP layer (not just the job-invocation layer) does not yet exist. `DisbursementAdminApprovalExpiryJobIT` proves the job in isolation; Phase 58 needs an E2E test that drives the full HTTP → orchestrator → expiry-job path.

**Primary recommendation:** Extend the four existing E2E classes with new `@Test` methods asserting claim states. Add one new IT class for admin-approval expiry E2E. Verify `mvn verify` passes cleanly as the phase gate.

---

## Standard Stack

### Core (no new dependencies — all already on the classpath)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| JUnit 5 (JUnit Jupiter) | 5.x (Spring Boot managed) | Test runner | Project standard |
| Spring Boot Test | 3.x (project version) | `@SpringBootTest`, `MockMvc`, `TestRestTemplate` | Project standard |
| WireMock Spring Boot (`wiremock-spring-boot`) | project version | MTN + Orange provider stubs | Used in all existing E2E ITs |
| Testcontainers (PostgreSQL + Redis) | project version | Real DB + Redis in tests | Used in all existing ITs |
| AssertJ | project version | Fluent assertions | Project standard |
| Awaitility | project version | Async assertion with timeout | Used by existing E2E ITs for `@TransactionalEventListener(AFTER_COMMIT)` waits |
| Jackson (`ObjectMapper`) | project version | Parse JSON response bodies | Used by all existing E2E ITs |

**Installation:** No new dependencies. All libraries are already declared in `pom.xml`.

---

## Architecture Patterns

### Pattern 1: Standalone E2E IT with Dual WireMock Topology

All existing disbursement E2E tests use this exact pattern — standalone class, no abstract base:

```java
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    "orange.callback-ip-whitelist=",
    "mtn.collection-token-url=http://localhost:${wiremock.mtn.port}/token/collection",
    "mtn.disbursement-token-url=http://localhost:${wiremock.mtn.port}/token/disbursement"
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",
        baseUrlProperties = {"mtn.collection-base-url", "mtn.disbursement-base-url"},
        portProperties    = {"wiremock.mtn.port"}),
    @ConfigureWireMock(name = "orange",
        baseUrlProperties = {"orange.base-url", "orange.pay-url"},
        portProperties    = {"wiremock.orange.port"})
})
```

Both MTN and Orange servers are always required even if only one is exercised — startup validation for token endpoints will fail without the Orange WireMock stub being available.

### Pattern 2: Claim State Assertion via JdbcTemplate

The DisbursementTransactionRefRepository's `findAll()` is available in tests, but filtering by disbursement PK is the most reliable pattern (used by `DisbursementAdminApprovalExpiryJobIT` and `DisbursementIdempotencyRetryIT`):

```java
// Assert all claims for a disbursement transitioned to CLAIMED
var refs = refRepository.findAll().stream()
    .filter(r -> disbursementPk.equals(r.getDisbursementId()))
    .toList();
assertThat(refs).hasSize(expectedCount);
assertThat(refs).allMatch(r -> r.getRefStatus() == DisbursementRefStatus.CLAIMED);
```

To get the disbursement BIGINT PK from a disbursementId (UUID string):

```java
Long disbursementPk = disbursementRepository
    .findByDisbursementId(disbursementId).orElseThrow().getId();
```

Alternatively use a raw JdbcTemplate query on `disbursement_transaction_ref` directly, which is more explicit in E2E tests:

```java
List<String> claimStatuses = jdbcTemplate.queryForList(
    "SELECT ref_status FROM main.disbursement_transaction_ref " +
    "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
    String.class, disbursementId);
assertThat(claimStatuses).hasSize(1).containsOnly("CLAIMED");
```

### Pattern 3: Awaiting Async Claim Transitions

`DisbursementCallbackTransitionService` is `@Transactional(REQUIRES_NEW)` and is triggered via `@TransactionalEventListener(AFTER_COMMIT)`. Claims transition inside that service call, which fires after the callback response is returned. Use Awaitility to wait:

```java
// Wait for the claim to reach CLAIMED state
await().atMost(Duration.ofSeconds(10)).until(() -> {
    String refStatus = jdbcTemplate.queryForObject(
        "SELECT ref_status FROM main.disbursement_transaction_ref " +
        "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
        String.class, disbursementId);
    return "CLAIMED".equals(refStatus);
});
```

Important: do NOT use `@Transactional` on test methods — `@TransactionalEventListener(AFTER_COMMIT)` never fires when the test method itself is inside an uncommitted transaction.

### Pattern 4: seedTxnsForClaim Helper (Standard Cross-Test Pattern)

All five existing E2E classes duplicate the same `seedTxnsForClaim()` helper. It inserts `main.transaction` rows with `tx_status='SUCCESS'`, `flow='COLLECTION'`, a given `amount`, and `fee_amount=0`. This is the required seed pattern for v11 claim validation:

```java
private List<String> seedTxnsForClaim(Long tenantId, int count, BigDecimal eachAmount) {
    List<String> ids = new java.util.ArrayList<>();
    final ThreadLocalRandom rng = ThreadLocalRandom.current();
    for (int i = 0; i < count; i++) {
        Long id = rng.nextLong();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction " +
                "(id, transaction_id, trace_id, tenant_id, provider, tx_status, flow, " +
                " amount, fee_amount, currency, created_by, created_date, last_modified_by, " +
                " last_modified_date, request_id, status) " +
                "VALUES (?, ?, ?, ?, 'MTN', 'SUCCESS', 'COLLECTION', " +
                "       ?, 0, 'XAF', 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE')",
                id, id, id, tenantId, eachAmount);
            return null;
        });
        ids.add(String.valueOf(id));
    }
    return ids;
}
```

The `transaction_id` column is `VARCHAR(36)` — but the existing seeders use `Long` values cast to String. This is an established project convention; do not change it.

### Pattern 5: Admin-Approval IT with Direct Job Invocation

`DisbursementAdminApprovalExpiryJobIT` proves the admin expiry job in isolation. The Phase 58 integration test must prove the HTTP → PENDING_ADMIN_APPROVAL path AND the job expiry path together. Key patterns:

- `@TestPropertySource(properties = {"payam.disbursement.admin-approval-timeout-hours=1"})` — overrides the default (5,000,000 XAF threshold) for test speed
- `spring.quartz.auto-startup=false` — prevents Quartz scheduling from racing the direct job invocation
- Amount to trigger PENDING_ADMIN_APPROVAL: must be `> adminApprovalThreshold` (default 5,000,000 XAF; override to 500,001 XAF for convenience, or use a large amount like 6,000,000 XAF with default threshold)
- Backdate via DB-side INTERVAL (not JVM clock) to avoid timezone skew: `UPDATE main.disbursement SET created_date = NOW() - INTERVAL '120 minutes' WHERE disbursement_id = ?`
- `DisbursementAdminApprovalExpiryJob.executeInternal(null)` is a public method (unlike `DisbursementExpiryJob` which requires reflection) — confirmed by reading the source

### Pattern 6: TRANSACTION_CLAIMED 422 on Second Attempt

Phase 58 success criterion 1 requires asserting that a SECOND POST with the same `transactionIds` returns `422 TRANSACTION_CLAIMED`. The existing `MtnDisbursementE2EIT.mtnHappyPath_*` test does not assert this. The pattern is:

```java
// First disbursement succeeds — claims go PENDING → CLAIMED
// Second attempt with same transactionIds
ResponseEntity<String> second = postDisbursement(msisdn, amount, ref2, idem2, txnIds);
assertThat(second.getStatusCode().value()).isEqualTo(422);
String errorCode = parseErrorCode(second.getBody());
assertThat(errorCode).isEqualTo(DisbursementOrchestratorError.TRANSACTION_CLAIMED.getErrorCode());
```

Note: The CLAIMED state blocks the second attempt via the partial unique index AND the `TransactionClaimValidationService` pre-lock check. The test must use a different `Idempotency-Key` for the second request (same key would return the cached first response).

### Pattern 7: Orange FAILED Callback + Claims Released + Reuse

Phase 58 success criterion 2 requires:
1. Orange happy path → SUCCESS, claims → CLAIMED
2. Orange FAILED callback → FAILED, claims → RELEASED
3. Same transactionIds usable in a NEW disbursement (because RELEASED unblocks TXN-03)

This is a multi-step test. The pattern is to assert `ref_status = 'RELEASED'` after the FAILED callback and then confirm a new POST with the same `txnIds` succeeds (202 PROCESSING).

### Pattern 8: Using noErrorRestTemplate for 4xx Assertions

When expecting 4xx responses, `TestRestTemplate` by default does not throw. But `RestTemplate` does throw `HttpClientErrorException` on 4xx unless an error handler override is applied:

```java
private RestTemplate noErrorRestTemplate() {
    RestTemplate rt = new RestTemplate();
    rt.setErrorHandler(new DefaultResponseErrorHandler() {
        @Override public boolean hasError(HttpStatusCode statusCode) { return false; }
        @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
    });
    return rt;
}
```

Use `TestRestTemplate` (autowired via `@SpringBootTest`) for normal requests — it already suppresses exceptions for 4xx/5xx.

### Pattern 9: Wallet Seed Still Required (Compatibility)

Even though `WalletBalanceService` is retired in v11, the `merchant_wallet_balance` table still exists (it is dropped in V32 which is not yet applied in test). The existing test bootstraps insert a wallet row. This must continue in Phase 58 tests to avoid FK violations or missing-row errors from legacy code paths that may still be referenced. Check each test class's `setUp()` for the wallet insert pattern.

Actually: looking at `DisbursementAdminApprovalExpiryJobIT` — it does NOT seed a wallet. This is because the test only exercises the expiry job path, not the orchestrator initiate path. For E2E tests that go through `DisbursementOrchestrator.initiate()`, the wallet seed is still needed if the orchestrator code still references `WalletBalanceService` in any path. Review `DisbursementOrchestrator` to confirm whether walletBalanceService calls remain for non-retirement paths.

Confirmed from STATE.md: "All WalletBalanceService calls are removed from DisbursementOrchestrator and DisbursementCallbackTransitionService" (SCHEMA-03, Phase 54). So wallet seeding is NOT required for Phase 58 tests. The existing test classes that seed wallets do so for backward compatibility — Phase 58 tests can omit the wallet seed.

### Recommended Project Structure for Phase 58

No new source directories. Tests go in existing packages:

```
src/test/java/com/softropic/payam/
├── e2e/disbursement/
│   ├── MtnDisbursementE2EIT.java          ← ADD: claim PENDING→CLAIMED + TRANSACTION_CLAIMED guard
│   ├── OrangeDisbursementE2EIT.java        ← ADD: claim PENDING→CLAIMED on success + RELEASED on failed + reuse
│   └── DisbursementAdminApprovalE2EIT.java ← NEW FILE: HTTP-driven PENDING_ADMIN_APPROVAL + expiry + claims
└── disbursement/service/
    └── DisbursementIdempotencyRetryIT.java ← Already exists — may need minor enhancement
                                              (verify retry_count + claims via E2E HTTP path)
```

The idempotency retry IT (`DisbursementIdempotencyRetryIT`) already covers IDEM-01/02/03 at the service layer. Phase 58 SC-4 says "integration test verifies idempotency retry recovery" — this is already covered by `DisbursementIdempotencyRetryIT`. Review whether the existing three tests satisfy SC-4 literally, or if an HTTP-layer test is needed.

---

## Gap Analysis: What Tests Already Exist vs. Phase 58 Requirements

| Success Criterion | Existing Coverage | Gap |
|-------------------|-------------------|-----|
| SC-1: MTN happy path + PENDING→CLAIMED + TRANSACTION_CLAIMED on second | `MtnDisbursementE2EIT.mtnHappyPath_*` seeds claims and checks PROCESSING→SUCCESS, but never asserts `DisbursementTransactionRef.refStatus` | Missing: claim state assertions + second-attempt 422 test |
| SC-2: Orange happy path CLAIMED, FAILED→RELEASED, reuse same txnIds | `OrangeDisbursementE2EIT.orangeHappyPath_*` checks 202→SUCCESS but no claim assertions | Missing: all claim assertions + FAILED callback + reuse test |
| SC-3: PENDING_ADMIN_APPROVAL via HTTP → claims PENDING → expiry → EXPIRED + RELEASED | `DisbursementAdminApprovalExpiryJobIT` proves job in isolation with seeded data, not HTTP-driven | Missing: full HTTP → PENDING_ADMIN_APPROVAL → expiry job → EXPIRED + RELEASED E2E test |
| SC-4: IDEM retry recovery at integration level | `DisbursementIdempotencyRetryIT` covers all three IDEM cases at service level | Likely sufficient — but planner should verify SC-4 says "integration test", not "E2E test" |
| SC-5: `mvn verify` passes cleanly | Run as phase gate at end | No gap — this is the verification step |

**Conclusion:** Phase 58 requires:
1. New `@Test` methods in `MtnDisbursementE2EIT` asserting claim states
2. New `@Test` methods in `OrangeDisbursementE2EIT` asserting claim states + FAILED→RELEASED + reuse
3. A new `DisbursementAdminApprovalE2EIT` class for the HTTP-driven admin approval + expiry path
4. A final `mvn verify` plan to confirm the full suite passes

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Async state assertion after callback | Thread.sleep() loops | Awaitility `await().atMost(Duration.ofSeconds(10)).until(...)` |
| HTTP 4xx without exception | Custom catch blocks | `TestRestTemplate` (auto-suppresses) or `noErrorRestTemplate()` pattern |
| JSON parsing | Custom string splitting | `new ObjectMapper().readTree(body).path("field").asText()` |
| WireMock request counting | Log scanning | `mtnServer.verify(N, postRequestedFor(...))` |
| DB-side time backdating | JVM Instant math | `UPDATE ... SET created_date = NOW() - INTERVAL '120 minutes'` — keeps comparison entirely in Postgres |
| Job invocation in test | Quartz scheduling | Direct `job.executeInternal(null)` call (check access level; `DisbursementAdminApprovalExpiryJob.executeInternal` is public based on source inspection) |

---

## Common Pitfalls

### Pitfall 1: @Transactional on Test Methods Prevents AFTER_COMMIT Listeners

**What goes wrong:** Annotating a `@Test` method with `@Transactional` wraps the entire test in one transaction. `@TransactionalEventListener(phase = AFTER_COMMIT)` never fires because the outer transaction never commits during the test.

**Why it happens:** Spring's test `@Transactional` rolls back at test end, so AFTER_COMMIT events are suppressed.

**How to avoid:** Never annotate E2E test methods with `@Transactional`. Use Awaitility to poll for the expected state.

**Warning signs:** Test passes instantly with wrong state (claim never transitions, disbursement stuck at PROCESSING).

### Pitfall 2: Missing mtn.disbursement-base-url in WireMock Config

**What goes wrong:** `MtnMoMoPort.initiateDisbursement()` targets `mtn.disbursement-base-url`. If only `mtn.collection-base-url` is configured (as in older test base classes), disbursement calls go to the wrong WireMock port and the stub never matches.

**How to avoid:** Always include both `mtn.collection-base-url` and `mtn.disbursement-base-url` in `baseUrlProperties` of `@ConfigureWireMock`. This pattern is established in all five existing disbursement E2E ITs.

### Pitfall 3: Claim State Race — Asserting Before AFTER_COMMIT Completes

**What goes wrong:** Asserting `ref_status = 'CLAIMED'` immediately after the callback HTTP call returns, before the `@TransactionalEventListener(AFTER_COMMIT)` has committed the claim transition.

**Why it happens:** The callback controller returns 200 synchronously. The claim transition fires asynchronously via the event listener.

**How to avoid:** Use Awaitility to await the disbursement reaching SUCCESS status first (which commits in the same AFTER_COMMIT listener), then assert claim status.

### Pitfall 4: Second Disbursement Attempt Uses Same Idempotency Key

**What goes wrong:** Sending the second disbursement (SC-1: duplicate transactionIds check) with the same `Idempotency-Key` returns the cached first response (202 PROCESSING), not 422 TRANSACTION_CLAIMED.

**How to avoid:** Always use a distinct `Idempotency-Key` (e.g. `UUID.randomUUID()`) for the second POST.

### Pitfall 5: Admin Approval Threshold Confusion

**What goes wrong:** Test amount triggers `PENDING_CONFIRMATION` (step-up, > 500,000 XAF) instead of `PENDING_ADMIN_APPROVAL` (> adminApprovalThreshold, default 5,000,000 XAF).

**How to avoid:** Either use an amount above 5,000,000 XAF (default threshold), or override with `@TestPropertySource(properties = {"payam.disbursement.admin-approval-threshold=500001"})` and use amount 600,000 XAF. The threshold ordering invariant in `DisbursementOrchestrator` checks admin approval FIRST, so if `adminApprovalThreshold > STEP_UP_THRESHOLD`, an amount above the admin threshold will never land in `PENDING_CONFIRMATION`.

### Pitfall 6: wallet_balance table not seeded for orchestrator path

**What goes wrong:** Test fails with DataIntegrityViolationException or NullPointerException because the orchestrator path references `merchant_wallet_balance` via `WalletBalanceService`.

**Why it matters:** `WalletBalanceService` was removed from the orchestrator in Phase 54 (SCHEMA-03). Wallet seeding is NOT required for Phase 58 tests. But if a test calls `walletRepo.findByTenantId()` to assert on wallet state, and no wallet row was inserted, the `orElseThrow()` will throw.

**How to avoid:** Do not assert wallet state in Phase 58 tests. Claim state is the v11 guard; wallet is retired.

### Pitfall 7: DisbursementTransactionRef.disbursementId is the BIGINT PK, not the UUID

**What goes wrong:** Filtering by `disbursementId` (the UUID string) directly on `DisbursementTransactionRef` fails because `DisbursementTransactionRef.disbursementId` is the BIGINT PK of the `Disbursement` entity, not the UUID `disbursementId` column.

**How to avoid:** Always look up the Disbursement entity first to get its PK:
```java
Long pk = disbursementRepository.findByDisbursementId(uuidString).orElseThrow().getId();
// then: refRepository.findAll().stream().filter(r -> pk.equals(r.getDisbursementId()))...
```
Or use raw JDBC:
```java
jdbcTemplate.queryForList(
    "SELECT ref_status FROM main.disbursement_transaction_ref " +
    "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
    String.class, disbursementId);
```

---

## Code Examples

### Claim State Assertion via JdbcTemplate (most explicit for E2E tests)

```java
// Source: established pattern from DisbursementAdminApprovalExpiryJobIT + DisbursementIdempotencyRetryIT
private void assertClaimStatus(String disbursementId, String expectedStatus, int expectedCount) {
    List<String> statuses = jdbcTemplate.queryForList(
        "SELECT ref_status FROM main.disbursement_transaction_ref " +
        "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
        String.class, disbursementId);
    assertThat(statuses).hasSize(expectedCount);
    assertThat(statuses).containsOnly(expectedStatus);
}
```

### Await Disbursement Reaching a Terminal State

```java
// Source: MtnDisbursementE2EIT line 204
await().atMost(Duration.ofSeconds(10)).until(() ->
    disbursementRepository.findByDisbursementId(disbursementId).orElseThrow()
        .getDisbursementStatus() == DisbursementStatus.SUCCESS);
```

### Await Claim Transition (for CLAIMED or RELEASED)

```java
// Pattern: poll DB directly rather than loading JPA entity (avoids first-level cache)
await().atMost(Duration.ofSeconds(10)).until(() -> {
    List<String> statuses = jdbcTemplate.queryForList(
        "SELECT ref_status FROM main.disbursement_transaction_ref " +
        "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
        String.class, disbursementId);
    return statuses.stream().allMatch("CLAIMED"::equals);
});
```

### Admin Approval E2E: Post Above Threshold + Assert PENDING_ADMIN_APPROVAL

```java
// Amount must exceed admin-approval-threshold (default 5,000,000 XAF)
private static final BigDecimal ADMIN_APPROVAL_AMOUNT = new BigDecimal("6000000");

// POST, expect 202 with status PENDING_ADMIN_APPROVAL
ResponseEntity<String> response = postDisbursement(msisdn, ADMIN_APPROVAL_AMOUNT, ref, idem, txnIds);
assertThat(response.getStatusCode().value()).isEqualTo(202);
assertThat(parseStatus(response.getBody())).isEqualTo("PENDING_ADMIN_APPROVAL");

// Claims must be PENDING immediately after initiation
assertClaimStatus(disbursementId, "PENDING", txnIds.size());
```

### Backdating for Admin Approval Expiry (DB INTERVAL pattern)

```java
// Source: DisbursementAdminApprovalExpiryJobIT.backdateDisbursement()
private void backdateDisbursement(String disbursementId, long minutesAgo) {
    transactionTemplate.execute(st -> {
        jdbcTemplate.update(
            "UPDATE main.disbursement " +
            "SET created_date = NOW() - CAST(? || ' minutes' AS INTERVAL) " +
            "WHERE disbursement_id = ?",
            minutesAgo, disbursementId);
        return null;
    });
}
```

### Admin Approval Expiry Job Direct Invocation

```java
// DisbursementAdminApprovalExpiryJob.executeInternal() is a protected method
// on QuartzJobBean — confirmed pattern from DisbursementAdminApprovalExpiryJobIT
expiryJob.executeInternal(null);
// No reflection needed — executeInternal is package-visible from same-package IT
```

---

## State of the Art

| Old Approach (v10 E2E Tests) | v11 Approach (Phase 58) | Why Changed |
|------------------------------|--------------------------|-------------|
| Assert wallet balance before/after | Assert claim refStatus transitions | Wallet model retired in SCHEMA-03; claims are the new financial guard |
| `INSUFFICIENT_BALANCE` path tested | Disabled via `@Disabled` in OrangeDisbursementE2EIT | Wallet check removed; TRANSACTION_CLAIMED is the new guard |
| No `transactionIds` in POST body | `transactionIds` required field | v11 claim-based model |
| No `DisbursementTransactionRef` rows | One row per transactionId in PENDING state on accept | Claim lifecycle enforced |

---

## Open Questions

1. **Is `DisbursementIdempotencyRetryIT` sufficient for SC-4, or does SC-4 require HTTP-layer coverage?**
   - What we know: `DisbursementIdempotencyRetryIT` calls `orchestrator.initiate()` directly (service layer), not via HTTP. It covers all three IDEM cases.
   - What's unclear: Whether the planner will interpret SC-4's "integration test" as requiring HTTP-layer invocation (like the E2E tests) or service-layer is acceptable.
   - Recommendation: Treat the existing `DisbursementIdempotencyRetryIT` as satisfying SC-4. If the planner decides HTTP-layer coverage is needed, a new plan could add an HTTP-level idempotency retry test.

2. **Does `DisbursementAdminApprovalExpiryJob.executeInternal` need reflection in the E2E test?**
   - What we know: `DisbursementExpiryJob.executeInternal` is `protected` on `QuartzJobBean`, requiring reflection in `DisbursementExpiryE2EIT`. `DisbursementAdminApprovalExpiryJobIT` calls `expiryJob.executeInternal(null)` without reflection — confirmed in the source.
   - Resolution: Both jobs extend `QuartzJobBean` which declares `executeInternal` as `protected`. Direct invocation works from test classes in the same package (`com.softropic.payam.disbursement.service`). For E2E test in `com.softropic.payam.e2e.disbursement`, reflection will be needed (same as `DisbursementExpiryE2EIT`).

---

## Environment Availability

Step 2.6: SKIPPED — Phase 58 is pure test code changes. All external dependencies (PostgreSQL via Testcontainers, Redis via Testcontainers, WireMock) are already in use by the existing test suite. No new tooling required.

---

## Validation Architecture

> `workflow.nyquist_validation` key is absent from `.planning/config.json` — treated as enabled.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) via Spring Boot Test |
| Config file | None — Spring Boot auto-configures |
| Quick run command | `mvn test -pl . -Dtest=MtnDisbursementE2EIT -DfailIfNoTests=false` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CLAIM-01 | Claims created PENDING on initiate | E2E (add assertion to existing test) | `mvn test -Dtest=MtnDisbursementE2EIT` | ✅ (add assertion) |
| CLAIM-02 | Claims transition PENDING→CLAIMED on SUCCESS | E2E (add assertion to existing test) | `mvn test -Dtest=MtnDisbursementE2EIT` | ✅ (add assertion) |
| CLAIM-03 | Claims transition PENDING→RELEASED on FAILED | E2E (add assertion to existing test) | `mvn test -Dtest=OrangeDisbursementE2EIT` | ✅ (add assertion) |
| CLAIM-04 | Claims RELEASED on admin-approval EXPIRED | Integration (new E2E class) | `mvn test -Dtest=DisbursementAdminApprovalE2EIT` | ❌ Wave 0 |
| TXN-03 | Second attempt with claimed txnIds returns 422 | E2E (new test method) | `mvn test -Dtest=MtnDisbursementE2EIT` | ✅ (add method) |
| IDEM-01/02/03 | Retry recovery + terminal caching | Integration (existing) | `mvn test -Dtest=DisbursementIdempotencyRetryIT` | ✅ |
| Cross-cutting | Full suite green | Phase gate | `mvn verify` | N/A |

### Sampling Rate

- **Per task commit:** `mvn test -Dtest={classUnderTest}` for the specific test class modified
- **Per wave merge:** `mvn test -Dpl . -Dgroups=IT` (or equivalent)
- **Phase gate:** `mvn verify` full suite before closing phase

### Wave 0 Gaps

- [ ] `src/test/java/com/softropic/payam/e2e/disbursement/DisbursementAdminApprovalE2EIT.java` — new class for SC-3 (HTTP-driven PENDING_ADMIN_APPROVAL + expiry + claim release)

*(All other test files exist; Phase 58 adds methods to existing classes and one new class.)*

---

## Sources

### Primary (HIGH confidence)

- Source code — `MtnDisbursementE2EIT.java`, `OrangeDisbursementE2EIT.java`, `DisbursementExpiryE2EIT.java`, `StepUpConfirmationE2EIT.java`, `DisbursementFraudBlockE2EIT.java` — direct inspection of established test patterns
- Source code — `DisbursementAdminApprovalExpiryJobIT.java`, `DisbursementIdempotencyRetryIT.java` — direct inspection of integration test patterns for admin approval and retry
- Source code — `DisbursementClaimTransitionService.java`, `DisbursementTransactionRefRepository.java` — confirm claim transition behavior
- Source code — `DisbursementCallbackTransitionService.java` — confirm AFTER_COMMIT async behavior
- `.planning/REQUIREMENTS.md` — v11 requirement specifications and success criteria
- `.planning/STATE.md` — key decisions including SCHEMA-03 wallet retirement, retry classification, audit-trail-preserving reactivation

### Secondary (MEDIUM confidence)

- `.planning/ROADMAP.md` — Phase 58 success criteria mapping to test scenarios

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in use; no new dependencies
- Architecture: HIGH — test patterns directly read from existing source code
- Pitfalls: HIGH — derived from explicit code inspection + documented pitfalls in earlier RESEARCH.md files
- Gap analysis: HIGH — direct comparison of success criteria against actual test method coverage

**Research date:** 2026-05-05
**Valid until:** Stable — this is pure test infrastructure for a completed feature set; no risk of API churn
