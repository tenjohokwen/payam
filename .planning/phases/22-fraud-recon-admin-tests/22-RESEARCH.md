# Phase 22: Fraud, Reconciliation, and Admin Flow Tests - Research

**Researched:** 2026-03-27
**Domain:** E2E testing — fraud engine, daily reconciliation, admin transaction investigation
**Confidence:** HIGH (all findings verified from source files)

---

## Summary

Phase 22 implements three E2E test classes: `FraudVelocityBlockE2ETest` (FLOWS-FRAUD-01/02/03), `DailyReconciliationE2ETest` (FLOWS-RECON-01/02/03/04), and `TransactionInvestigationE2ETest` (FLOWS-ADMIN-01). The fraud and admin tests extend `AbstractPayamE2ETest` directly via `AbstractFailureFlowTest` or a custom base. The reconciliation test has no existing template — it exercises `ReconciliationService.runForDate()` directly and inspects the resulting DB rows.

All production code for these three domains is fully implemented. Phase 22 only writes tests — no production code changes. The test patterns follow the same conventions as Phases 20 and 21.

**Primary recommendation:** Fraud tests extend `AbstractFailureFlowTest`. Reconciliation and admin tests extend `AbstractPayamE2ETest` directly with custom `@Test` methods. Use `@MockBean` on `MtnMoMoPort` and `OrangeMoneyPort` in the reconciliation test to inject controlled provider responses without WireMock.

---

## Standard Stack

### Core Test Infrastructure
| Class | Location | Purpose |
|-------|----------|---------|
| `AbstractPayamE2ETest` | `e2e/AbstractPayamE2ETest.java` | Base class: Testcontainers, WireMock, Redis flush, circuit-breaker reset, sec row seeding |
| `AbstractFailureFlowTest` | `e2e/AbstractFailureFlowTest.java` | Template for failure scenarios: setupPreconditions → injectFault → executeFlow → verifyFailureHandled |
| `TestDataCleaner` | `config/TestDataCleaner.java` | `wipeAll()` in `@AfterEach` — preserves fraud_rule ids 1-5 and fee_rule id 1 |
| `E2ESecurityConfig` | `config/E2ESecurityConfig.java` | Seeds `main.sec` JWT secret row; called in `baseSetUp()` |
| `InvariantVerifier` | `e2e/verify/InvariantVerifier.java` | `assertFraudEvaluatedBeforeProviderCall(transactionId)` |
| `ReconciliationReportBuilder` | `e2e/builder/ReconciliationReportBuilder.java` | Fluent builder for reconciliation report + discrepancy rows via JDBC |
| `FraudSignalBuilder` | `e2e/builder/FraudSignalBuilder.java` | Velocity counter resets; Redis key format: `fraud:velocity:{SIGNAL}:{identifier}` |
| `AdminLogin` | `common/AdminLogin.java` | Static helper: POST `/authenticate` → extract JWT cookies for admin calls |
| `TenantBuilder` | `e2e/builder/TenantBuilder.java` | Creates tenant + API key via `TenantService` |
| `PaymentRequestBuilder` | `e2e/builder/PaymentRequestBuilder.java` | Builds `PaymentRequest` with MSISDN, idempotency key, externalReference |

### Key Production Services Under Test
| Service | Location | Tested By |
|---------|----------|-----------|
| `FraudScoringService` | `fraud/service/FraudScoringService.java` | FraudVelocityBlockE2ETest |
| `VelocityCheckService` | `fraud/service/VelocityCheckService.java` | FraudVelocityBlockE2ETest (via Redis) |
| `FraudRuleCache` | `fraud/service/FraudRuleCache.java` | FraudVelocityBlockE2ETest (refreshRules()) |
| `ReconciliationService` | `reconciliation/service/ReconciliationService.java` | DailyReconciliationE2ETest |
| `LedgerSnapshotService` | `reconciliation/service/LedgerSnapshotService.java` | DailyReconciliationE2ETest |
| `AdminTransactionQueryService` | `admin/service/AdminTransactionQueryService.java` | TransactionInvestigationE2ETest |
| `AdminTransactionResource` | `admin/api/AdminTransactionResource.java` | TransactionInvestigationE2ETest |

---

## Architecture Patterns

### Pattern 1: Fraud E2E Test (AbstractFailureFlowTest)

The existing `FraudBlockedPaymentE2ETest` (FLOWS-PAY-06) covers MSISDN velocity blocking. Phase 22's `FraudVelocityBlockE2ETest` (FLOWS-FRAUD-01/02/03) must be a **distinct test** — it adds:
- Assertion that no provider call was made for the blocked request (FLOWS-FRAUD-01)
- Assertion that the allowed path records `PAYMENT_INITIATED` event (FLOWS-FRAUD-02)
- `invariantVerifier.assertFraudEvaluatedBeforeProviderCall(transactionId)` on the allowed path (FLOWS-FRAUD-03)

**Phase structure for fraud tests:**
```
setupPreconditions()  — seed tenant + fraud rules + MTN stubs
injectFault()         — lower MSISDN_VELOCITY threshold to 1 (calls fraudRuleCache.refreshRules())
executeFlow()         — POST request 1 (allowed) then POST request 2 (blocked)
verifyFailureHandled()— assert 422 FRAUD_BLOCKED + zero provider calls on request 2
                        assert PAYMENT_INITIATED event exists for request 1 (FLOWS-FRAUD-02/03)
```

**Fraud rule seeding (mandatory in dev profile — create-drop wipes Flyway seed data):**
```java
// In TransactionTemplate (required for JDBC commit before refreshRules reads)
jdbcTemplate.update(
    "INSERT INTO main.fraud_rule "
    + "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) "
    + "VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?, ?) "
    + "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold",
    id, signalName, weight, threshold, windowSeconds, enabled, signalName + " test rule");

// Seed all 5 rules: IP_VELOCITY(1), MSISDN_VELOCITY(2), APP_VELOCITY(3),
//                   MSISDN_HOUSEHOLD(4), BLOCK_THRESHOLD(5)
fraudRuleCache.refreshRules();
```

**Default rule values used in all existing tests:**

| ID | Signal | Weight | Threshold | Window |
|----|--------|--------|-----------|--------|
| 1 | IP_VELOCITY | 40 | 10 | 60s |
| 2 | MSISDN_VELOCITY | 35 | 5 | 60s |
| 3 | APP_VELOCITY | 25 | 20 | 60s |
| 4 | MSISDN_HOUSEHOLD | 15 | 8 | 3600s |
| 5 | BLOCK_THRESHOLD | 0 | 70 | 0s |

### Pattern 2: Reconciliation E2E Test (AbstractPayamE2ETest direct)

`ReconciliationJobIT` shows the exact pattern: `@MockBean` on `MtnMoMoPort` and `OrangeMoneyPort`, seed transactions via JDBC, call `reconciliationService.runForDate(YESTERDAY)` directly, assert `ReconciliationReport` and `ReconciliationDiscrepancy` rows.

**Discrepancy type logic (from ReconciliationService.compareTransaction):**
- Provider returns `null` status → `MISSING_IN_PROVIDER` (HIGH severity)
- Payam=SUCCESS, Provider=FAILED (both terminal) → `STATUS_MISMATCH` (MEDIUM severity)
- Provider API throws → `UNCONFIRMED` (LOW severity)
- Amounts match and statuses match → no discrepancy

**WAT timestamp for FLOWS-RECON-04:** The requirement is that an Orange transaction with a `createtime` in WAT (UTC+1) is correctly placed in the reconciliation date window. The production code uses `LedgerSnapshotService` which queries by `createdDate` (a UTC Instant stored in the DB). The WAT handling is in `OrangeTimeUtil.parseOrangeTimestamp()` (called from `OrangeWebhookPayload.getCreatetimeAsInstant()`). For the reconciliation E2E test, the date boundary must be verified: a transaction whose Orange-side WAT time is "2024-01-15T00:30:00" (WAT) maps to "2024-01-14T23:30:00Z" UTC — it falls on the PREVIOUS UTC day. Seeding a transaction with `created_date = '2024-01-14T23:30:00Z'` and reconciling for `2024-01-14` should include it, while treating it as UTC+1 would incorrectly place it on `2024-01-15`.

**RECON-04 test structure:** Seed a transaction with `created_date` near midnight UTC. Run reconciliation for the UTC date. Assert the transaction is in the report for that date (not the next day).

### Pattern 3: Admin Transaction Investigation (AbstractPayamE2ETest direct)

`ReconciliationApiIT` shows the authentication pattern: seed admin user rows, call `AdminLogin.loginAsAdmin(url, restTemplate)`, then use the returned cookie headers for `/v1/admin/transactions`.

**Admin search fields:**
- `transactionId` — exact match on `transaction.transaction_id`
- `traceId` — exact match on `transaction.trace_id`
- `externalReference` — exact match on `transaction.external_reference` (the Javadoc says "phone/MSISDN or other external ref")
- `tenantId` — explicit tenant scoping

**Critical: MSISDN is NOT stored in `external_reference`**. The admin search `externalReference` parameter matches `transaction.external_reference`, which is the merchant's own reference string (`PaymentRequest.externalReference()`). To search by phone number, the test must create a payment with the MSISDN set as the `externalReference` field in the `PaymentRequest`, then search with that value. The `transaction.msisdn` column does not exist — MSISDN is routed to determine the provider but is never persisted.

**Tenant isolation for FLOWS-ADMIN-01:** `TransactionRepository.adminSearch()` accepts a `tenantId` parameter. When passed, it adds `(:tenantId IS NULL OR t.tenantId = :tenantId)` to the JPQL query. The test must verify that tenant A's transactions are not returned when querying with tenant B's tenantId.

**Admin user seeding (required for JWT auth in E2E context):**
```java
// Authority rows (use known IDs from authorityData.sql)
jdbc.execute("INSERT INTO main.authority (id, name, ...) VALUES (6747751741842104908, 'ROLE_ADMIN', ...)
              ON CONFLICT DO NOTHING");
// User row
jdbc.execute("INSERT INTO main.\"user\" (id, ..., login, password_hash) VALUES (675373350208068096, 'queb@yahoo.com', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i') ON CONFLICT DO NOTHING");
// Authorities
jdbc.execute("INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 5418719445932238328) ON CONFLICT DO NOTHING");
jdbc.execute("INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 6747751741842104908) ON CONFLICT DO NOTHING");
```
Credentials: `queb@yahoo.com` / `admin*123!` (bcrypt hash matches the literal above).

**noRetryRestTemplate pattern for admin calls:**
```java
RestTemplate noRetryRestTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
noRetryRestTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
    @Override public boolean hasError(HttpStatusCode statusCode) { return false; }
    @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
});
```

### Recommended Package Structure

```
src/test/java/com/softropic/payam/e2e/
├── fraud/
│   └── FraudVelocityBlockE2ETest.java
├── reconciliation/
│   └── DailyReconciliationE2ETest.java
└── admin/
    └── TransactionInvestigationE2ETest.java
```

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Fraud rule seeding | Custom SQL helper | Copy the exact `seedFraudRule(id, name, weight, threshold, windowSeconds, enabled)` pattern from `FraudBlockedPaymentE2ETest` — the `ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold` clause is required |
| Velocity counter reset | Delete Redis keys manually | `redis.getConnectionFactory().getConnection().serverCommands().flushDb()` in `baseSetUp()` already clears all keys including velocity buckets |
| Admin JWT login | Build JWT token manually | `AdminLogin.loginAsAdmin(url, restTemplate)` — already a shared static helper |
| Reconciliation data seeding | Call `ReconciliationService` | `ReconciliationReportBuilder` for pre-existing state, or `reconciliationService.runForDate()` with `@MockBean` ports for job-driven scenarios |
| Provider call prevention | Real HTTP stubs | `@MockBean MtnMoMoPort` / `@MockBean OrangeMoneyPort` in the reconciliation test — same pattern as `ReconciliationJobIT` |

---

## Common Pitfalls

### Pitfall 1: Fraud rule cache not refreshed after JDBC update
**What goes wrong:** `jdbcTemplate.update("UPDATE main.fraud_rule SET threshold = 1 ...")` has no immediate effect because `FraudScoringService` reads from `FraudRuleCache`'s `AtomicReference<List<FraudRule>>`, not from the DB.
**How to avoid:** Always call `fraudRuleCache.refreshRules()` after any JDBC update to `main.fraud_rule`. Wrap the JDBC update in `transactionTemplate.execute(...)` first, THEN call `refreshRules()` outside the lambda so the DB transaction has committed.
**Confirmed in:** `FraudBlockedPaymentE2ETest.injectFault()` and `FraudEngineIT.velocityBlockReturns422()`

### Pitfall 2: Redis velocity counter TTL survives between tests in same JVM
**What goes wrong:** If a Redis key `fraud:velocity:MSISDN_VELOCITY:+237672000001` exists from a prior test, the bucket starts with fewer tokens than expected — the second request may be blocked even on the first test run call.
**How to avoid:** `AbstractPayamE2ETest.baseSetUp()` calls `redis.getConnectionFactory().getConnection().serverCommands().flushDb()` which clears ALL Redis keys. This runs before every test. Do not rely on targeted key deletion — flushDb is the correct baseline.

### Pitfall 3: Fraud rule IDs 1-5 are preserved by TestDataCleaner
**What goes wrong:** `TestDataCleaner.wipeAll()` runs `DELETE FROM main.fraud_rule WHERE id NOT IN (1,2,3,4,5)`. This means after `wipeAll()`, the 5 Flyway-seeded rows survive. But the dev profile uses `create-drop` — Flyway data is seeded once at the start of the full test suite, but Hibernate drops/recreates tables between Spring context startups.
**How to avoid:** Always explicitly seed fraud rules in `setupPreconditions()` using `ON CONFLICT (id) DO UPDATE` — do not assume Flyway seeded them. This is the pattern in `FraudBlockedPaymentE2ETest` and all `FraudEngineIT` tests.

### Pitfall 4: Reconciliation UNIQUE(report_date, provider) constraint
**What goes wrong:** `main.reconciliation_report` has a UNIQUE constraint on `(report_date, provider)`. Two tests using the same date and provider will conflict.
**How to avoid:** Use distinct dates per test (e.g., `LocalDate.now().minusDays(1)` for one, `minusDays(2)` for another), or rely on `TestDataCleaner.wipeAll()` which deletes `reconciliation_discrepancy` then `reconciliation_report` between tests. Documented in `ReconciliationReportBuilder` Javadoc.

### Pitfall 5: WAT timestamp boundary in reconciliation
**What goes wrong:** Orange `createtime` "2024-01-15T01:30:00" (WAT) = "2024-01-15T00:30:00Z" UTC. If naively treated as UTC, this places the transaction one hour later. `LedgerSnapshotService` queries by `createdDate` (UTC Instant). The boundary test (RECON-04) must seed a transaction at `created_date = YESTERDAY_DATE + 'T23:30:00Z'` and verify it falls in the reconciliation window for `YESTERDAY_DATE`, not `TODAY_DATE`.
**How to avoid:** Seed transaction `created_date` as a UTC Instant string: `YESTERDAY + "T23:30:00Z"`. Run `reconciliationService.runForDate(YESTERDAY)`. The `LedgerSnapshotService.findTransactionsForDateAndProvider()` queries `createdDate >= YESTERDAY 00:00Z AND createdDate < TODAY 00:00Z`.

### Pitfall 6: Admin search phone field is externalReference, not a msisdn column
**What goes wrong:** There is no `msisdn` column on `main.transaction`. The admin API docs say `externalReference` maps to "phone/MSISDN or other external ref". This only works if the payment was submitted with the phone number in the `externalReference` field of the `PaymentRequest`.
**How to avoid:** In `TransactionInvestigationE2ETest.setupPreconditions()`, use `PaymentRequestBuilder.withExternalReference("+237672000001")` so the MSISDN becomes searchable via the `externalReference` param in `GET /v1/admin/transactions?externalReference=+237672000001`.

### Pitfall 7: Admin endpoints require JWT, not API key
**What goes wrong:** `/v1/admin/transactions` is excluded from the API-key filter chain (`TenantSecurityConfig` NegatedRequestMatcher). Sending `X-Api-Key` header has no effect — the request returns 401/403.
**How to avoid:** Seed admin user rows, POST to `/authenticate` to get JWT cookies, forward cookie headers in subsequent requests. Use `AdminLogin.loginAsAdmin(url, restTemplate)`.

### Pitfall 8: AbstractPayamE2ETest does NOT import user/authority tables
**What goes wrong:** `E2ESecurityConfig.seedSecurityRow()` only inserts the `main.sec` JWT secret row. It does NOT seed user or authority rows. Admin tests must seed these manually in `setupPreconditions()`.
**How to avoid:** Copy the full user+authority seeding block from `ReconciliationApiIT.setUp()`. The `TestDataCleaner.wipeAll()` does NOT delete user/authority rows — the admin test teardown must delete them manually (or use `TransactionTemplate` cleanup).

### Pitfall 9: noRetryRestTemplate required for 4xx assertion
**What goes wrong:** The default `RestTemplate` (via `TestRestTemplate`) throws `HttpClientErrorException` on 422, 401, 403, etc., making it impossible to inspect the response body.
**How to avoid:** Construct a `RestTemplate` with `SimpleClientHttpRequestFactory` and a no-op `DefaultResponseErrorHandler` where `hasError()` always returns `false`. This is the established pattern from `FraudBlockedPaymentE2ETest` and `ReconciliationApiIT`.

---

## Code Examples

### FraudVelocityBlockE2ETest structure
```java
// Source: FraudBlockedPaymentE2ETest.java (the verified model)
public class FraudVelocityBlockE2ETest extends AbstractFailureFlowTest {

    @Autowired private TenantService tenantService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private FraudRuleCache fraudRuleCache;

    private TenantBuilder.CreatedTenant tenant;
    private InvariantVerifier invariant;
    private String allowedTransactionId;

    @Override
    protected void setupPreconditions() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        tenant = new TenantBuilder().withName("Fraud-E2E").create(tenantService, tenantRepository);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);

        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 10, 60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 5,  60,   true);
            seedFraudRule(3L, "APP_VELOCITY",     25, 20, 60,   true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 8,  3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0, 70, 0,    true);
            return null;
        });
        fraudRuleCache.refreshRules();
    }

    @Override
    protected void injectFault() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE main.fraud_rule SET threshold = 1 WHERE signal_name = 'MSISDN_VELOCITY'");
            return null;
        });
        fraudRuleCache.refreshRules();  // MUST follow the transactionTemplate block
    }
}
```

### assertFraudEvaluatedBeforeProviderCall usage
```java
// Source: InvariantVerifier.java
// Called on the ALLOWED (first) transaction only
invariant.assertFraudEvaluatedBeforeProviderCall(allowedTransactionId);
// This asserts PAYMENT_INITIATED event exists — proves fraud passed before provider call
```

### ReconciliationService with MockBean (reconciliation E2E test)
```java
// Source: ReconciliationJobIT.java — verified pattern
@MockBean MtnMoMoPort mtnMoMoPort;
@MockBean OrangeMoneyPort orangeMoneyPort;

// In test:
when(mtnMoMoPort.getTransactionStatus(anyString()))
    .thenReturn(ProviderResult.success("ref", "SUCCESSFUL"));
when(orangeMoneyPort.getTransactionStatus(anyString()))
    .thenReturn(ProviderResult.success("ref", "SUCCESSFULL")); // Orange double-L

reconciliationService.runForDate(YESTERDAY);
```

### Seeding a transaction directly for reconciliation
```java
// Source: ReconciliationJobIT.setUp()
long txId = System.nanoTime() & Long.MAX_VALUE;
transactionTemplate.execute(status -> {
    jdbcTemplate.update(
        "INSERT INTO main.transaction (id, created_by, created_date, last_modified_by, last_modified_date, " +
        "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref) " +
        "VALUES (?, 'SYSTEM', ?::TIMESTAMPTZ, 'SYSTEM', ?::TIMESTAMPTZ, ?, ?, ?, 'SUCCESS', 'ACTIVE', " +
        "'MTN', 500.00, 'XAF', ?)",
        txId,
        YESTERDAY + "T12:00:00Z",
        YESTERDAY + "T12:00:00Z",
        "tx-" + UUID.randomUUID(),
        UUID.randomUUID().toString(),
        tenantId,
        "provider-ref-" + UUID.randomUUID()
    );
    return null;
});
```

### Admin search with JWT auth
```java
// Source: AdminLogin.java + ReconciliationApiIT.java
HttpHeaders adminHeaders = AdminLogin.loginAsAdmin(
    "http://localhost:" + serverPort + "/authenticate", noRetryRestTemplate);

ResponseEntity<Map> response = noRetryRestTemplate.exchange(
    "http://localhost:" + serverPort + "/v1/admin/transactions?transactionId=" + txId,
    HttpMethod.GET,
    new HttpEntity<>(adminHeaders),
    Map.class);
```

### ReconciliationReportBuilder for pre-seeded state
```java
// Source: ReconciliationReportBuilder.java
Long reportId = new ReconciliationReportBuilder()
    .forDate(LocalDate.of(2026, 1, 1))
    .forProvider("MTN")
    .withMatchedTransaction(txId, "provider-ref-001")
    .withMissingTransaction(txId2)
    .withMismatchedTransaction(txId3, "pref-003", "SUCCESS", "FAILED")
    .create(jdbcTemplate);
```

---

## State of the Art

| Old Approach | Current Approach | Notes |
|---|---|---|
| `TestConfig` for containers | `AbstractPayamE2ETest` (Postgres + Redis + WireMock) | All E2E tests since Phase 18 extend `AbstractPayamE2ETest` |
| Inline user seeding per test | `AdminLogin.loginAsAdmin()` shared helper | Phase 22 admin test should use this helper, not inline it |
| Direct `ReconciliationService` call | `@MockBean` on ports | Verified in `ReconciliationJobIT` — do not use real WireMock HTTP for reconciliation provider calls |

---

## Open Questions

1. **WAT reconciliation test scope:** The requirement says "Orange WAT timestamp — createtime parsed as UTC+1, not UTC; reconciliation entries correct." The `OrangeReportAdapter` explicitly documents that `PayResponse` (the status API response) has NO `createtime` field, so the WAT guard does NOT apply to reconciliation provider calls. The WAT parsing applies to `OrangeWebhookPayload.getCreatetimeAsInstant()`. The RECON-04 test therefore verifies that the `LedgerSnapshotService` date window boundary is correct in UTC — a transaction whose Orange-side wall-clock time is early-morning WAT falls on the previous UTC day and must be included.
   - **Recommendation:** RECON-04 seeds a transaction at `YESTERDAY + "T23:30:00Z"` (UTC), runs `runForDate(YESTERDAY)`, verifies it is in the MTN or Orange report for `YESTERDAY` (not excluded or placed in a different day's bucket). This directly verifies the UTC boundary, which is the production truth.

2. **FraudVelocityBlockE2ETest vs FraudBlockedPaymentE2ETest overlap:** FLOWS-FRAUD-01 looks nearly identical to the existing FLOWS-PAY-06 (`FraudBlockedPaymentE2ETest`). The distinction is:
   - FLOWS-FRAUD-01: focus on "velocity limit exceeded blocks payment before provider call" as a standalone fraud flow test
   - FLOWS-FRAUD-02: adds the "allowed path" assertion — tests the successful payment that PRECEDES the blocked one, with `assertFraudEvaluatedBeforeProviderCall`
   - FLOWS-FRAUD-03: `invariantVerifier.assertFraudEvaluatedBeforeProviderCall` on every flow
   - **Recommendation:** `FraudVelocityBlockE2ETest` contains both cases in one class: (a) the blocked request assertions (FLOWS-FRAUD-01) and (b) the allowed request's event log assertion (FLOWS-FRAUD-02/03). This is how `FraudBlockedPaymentE2ETest` already works — extend the same pattern.

---

## Sources

### Primary (HIGH confidence)
- `FraudScoringService.java` — velocity blocking logic, block-before-provider ordering
- `VelocityCheckService.java` — Redis key format: `fraud:velocity:{SIGNAL}:{identifier}`, Bucket4j token-bucket algorithm
- `FraudRuleCache.java` — `refreshRules()` as mandatory post-JDBC call
- `ReconciliationService.java` — discrepancy type logic (MISSING_IN_PROVIDER, STATUS_MISMATCH, UNCONFIRMED)
- `LedgerSnapshotService.java` — UTC date boundary `[date 00:00Z, date+1 00:00Z)`
- `OrangeReportAdapter.java` — confirms WAT guard NOT in reconciliation adapter
- `AdminTransactionResource.java` + `AdminTransactionQueryService.java` — endpoint, JPQL query structure
- `TransactionRepository.adminSearch()` — tenant scoping via optional `tenantId` JPQL param
- `TransactionService.java` — confirms MSISDN is NOT stored; `externalReference` is merchant's own field
- `AbstractPayamE2ETest.java` — base class features: Redis flushDb, circuit-breaker reset, WireMock setup
- `AbstractFailureFlowTest.java` — phase contract for fraud tests
- `TestDataCleaner.java` — preserves fraud_rule ids 1-5; does NOT delete user/authority rows
- `InvariantVerifier.java` — `assertFraudEvaluatedBeforeProviderCall` checks PAYMENT_INITIATED event
- `ReconciliationReportBuilder.java` — builder API, UNIQUE constraint note
- `FraudSignalBuilder.java` — Redis key format confirmed, velocity counter reset pattern
- `AdminLogin.java` — shared JWT cookie extraction helper
- `FraudBlockedPaymentE2ETest.java` — complete model for fraud E2E test pattern
- `FraudEngineIT.java` — lower-level model confirming fraud rule seeding approach
- `ReconciliationJobIT.java` — complete model for reconciliation test with MockBean
- `ReconciliationApiIT.java` — complete model for admin auth + JDBC seeding pattern
- `userData.sql` — admin user fixture: `queb@yahoo.com` / `admin*123!`
- `OrangeTimeUtil.java` — WAT = `ZoneId.of("Africa/Douala")`, `parseOrangeTimestamp()` method

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all classes verified from source
- Architecture patterns: HIGH — patterns verified from existing passing tests
- Pitfalls: HIGH — root causes verified from source code and comments
- Code examples: HIGH — copied/derived directly from passing test files

**Research date:** 2026-03-27
**Valid until:** 2026-04-27 (production code stable; only test code changes expected in Phase 22)
