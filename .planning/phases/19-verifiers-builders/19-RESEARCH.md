# Phase 19: Verifiers + Test Data Builders - Research

**Researched:** 2026-03-27
**Domain:** E2E test assertion helpers and builder-pattern test data factories
**Confidence:** HIGH

---

## Summary

Phase 19 builds two categories of test-support classes that Phase 20-23 E2E tests consume: verifiers (assertion objects that check DB, Redis, WireMock, and invariant state) and builders (fluent factories for test data seeding). All classes operate against the real running system via `JdbcTemplate` and `StringRedisTemplate` — not JPA repositories — to avoid loading Spring Data context into every E2E test assertion.

The domain model is fully implemented. Every entity, table, service, and Redis key convention needed by the verifiers and builders was found in the codebase. No gaps exist between what VERIF-01 through VERIF-10 and BUILD-01 through BUILD-08 require and what the running production code exposes. The research confirms exact field names, hash formulas, Redis key patterns, HMAC signing details, and outbound payload shapes.

**Primary recommendation:** Verifiers receive `JdbcTemplate` and `StringRedisTemplate` as constructor arguments (not `@Autowired`). Builders use static factory methods and a `.create(jdbcTemplate)` explicit-commit pattern. Deterministic UUID seeding uses a per-class seed passed to `new UUID(seed, counter)`.

---

## Standard Stack

### Core (all in pom.xml — no new dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-jdbc` | Spring Boot BOM | `JdbcTemplate` for verifier queries | Test-scope only; avoids JPA loading cost |
| `spring-data-redis` | Spring Boot BOM | `StringRedisTemplate` for CacheVerifier | Already wired in `AbstractPayamE2ETest` |
| `assertj-core` | 3.24.2 | Fluent assertions inside verifiers | Already standard across all ITs |
| `wiremock-spring-boot` | 4.0.9 | `WireMockServer` call-count checks | Already in `AbstractPayamE2ETest` |
| `commons-codec` | (transitive) | `DigestUtils.sha256Hex` for hash recomputation | Used by production `EventLogService` |
| `javax.crypto` | JDK | `Mac.getInstance("HmacSHA256")` for HMAC verify | Same algo as `WebhookDeliveryService` |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `awaitility` | 4.2.0 | Async wait in verifiers for async flows | `WebhookDeliveryVerifier` when checking retry |
| `testcontainers` | Spring Boot BOM | Container refs passed through from base test | Not used directly in verifiers/builders |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `JdbcTemplate` queries | JPA `Repository` injection | JPA requires full Spring Data context; `JdbcTemplate` is lighter and explicit |
| `StringRedisTemplate` | `RedisTemplate<String,Object>` | `StringRedisTemplate` already wired in E2E base; `StringRedisTemplate` uses `StringRedisSerializer` which matches production key writes |

**Installation:** No new dependencies. All artifacts already declared in pom.xml.

---

## Architecture Patterns

### Recommended Package Structure

```
src/test/java/com/softropic/payam/
├── e2e/
│   ├── verify/
│   │   ├── DatabaseVerifier.java        # VERIF-01
│   │   ├── HashChainVerifier.java       # VERIF-02
│   │   ├── InvariantVerifier.java       # VERIF-03
│   │   ├── EventVerifier.java           # VERIF-04
│   │   ├── LedgerVerifier.java          # VERIF-05
│   │   ├── ProviderCallVerifier.java    # VERIF-06
│   │   ├── WebhookDeliveryVerifier.java # VERIF-07
│   │   ├── TenantIsolationVerifier.java # VERIF-08
│   │   ├── CacheVerifier.java           # VERIF-09
│   │   └── QueryCountVerifier.java      # VERIF-10
│   └── builder/
│       ├── TenantBuilder.java           # BUILD-01
│       ├── ApiKeyBuilder.java           # BUILD-02
│       ├── PaymentRequestBuilder.java   # BUILD-03
│       ├── MtnWebhookPayloadBuilder.java  # BUILD-04
│       ├── OrangeWebhookPayloadBuilder.java # BUILD-05
│       ├── FraudSignalBuilder.java      # BUILD-06
│       ├── ReconciliationReportBuilder.java # BUILD-07
│       └── DeterministicUuidFactory.java # BUILD-08 support class
```

### Pattern 1: Verifier constructor injection

**What:** Each verifier is constructed in a test's `@BeforeEach` with infrastructure passed as constructor parameters.
**When to use:** All VERIF-* classes.

```java
// Verifier receives dependencies — not a Spring bean
public class DatabaseVerifier {
    private final JdbcTemplate jdbc;

    public DatabaseVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void assertPaymentRow(String transactionId, String expectedStatus) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT * FROM main.transaction WHERE transaction_id = ?", transactionId);
        assertThat(row.get("tx_status")).isEqualTo(expectedStatus);
    }
}
```

### Pattern 2: Builder explicit-commit pattern

**What:** Builder accumulates state; `create(jdbcTemplate)` inserts the row and returns an identifier.
**When to use:** All BUILD-* classes.

```java
public class TenantBuilder {
    private String name = "Test Tenant";
    private String webhookUrl = null;
    private String webhookSecret = null;
    private String environment = "LIVE";

    public TenantBuilder withName(String name) { this.name = name; return this; }
    public TenantBuilder withWebhookUrl(String url) { this.webhookUrl = url; return this; }

    /** Commits the tenant row and returns the raw API key string. */
    public CreatedTenant create(JdbcTemplate jdbc) {
        // Use TenantService via jdbcTemplate inserts — or delegate to TenantService if autowirable
        ...
    }

    public record CreatedTenant(Long tenantId, String rawApiKey) {}
}
```

### Pattern 3: Deterministic UUID seeding (BUILD-08)

**What:** Each test class declares a fixed `long` seed. `DeterministicUuidFactory.next()` produces `new UUID(seed, counter++)`.
**When to use:** All builders; guarantees reproducible, non-conflicting IDs across test runs.

```java
public class DeterministicUuidFactory {
    private final long seed;
    private long counter = 0;

    public DeterministicUuidFactory(long seed) { this.seed = seed; }

    public UUID next() { return new UUID(seed, counter++); }
}
```

### Anti-Patterns to Avoid

- **Auto-wiring verifiers as `@Component`**: Verifiers must not be Spring-managed beans; they are POJOs receiving infrastructure. Making them `@Component` would pollute the production context.
- **Sharing builder state between tests**: Builders must be instantiated fresh per test (or per `@BeforeEach`). Static mutable state causes cross-test pollution.
- **Using JPA repositories in verifiers**: Verifiers must use raw `JdbcTemplate` SQL. JPA first-level cache may hide stale state; raw JDBC reads committed data.
- **Calling `TenantService` via JPA in TenantBuilder**: The builder may use `JdbcTemplate` directly for inserts, or invoke `TenantService` injected as a Spring bean, but must not create a local JPA `EntityManager`. Prefer `TenantService` injection to reuse its key-hashing logic (`ApiKeyService.generateAndStore`).

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Hash chain verification | Custom SHA-256 loop | `DigestUtils.sha256Hex(canonical)` from commons-codec | Exact same formula as production `EventLogService.verifyChain` — must match |
| HMAC-SHA256 webhook signature | Custom hex encoding | `Mac.getInstance("HmacSHA256")` + `Hex.encodeHexString` | Exact same implementation as `WebhookDeliveryService.attemptDeliveryInternal` |
| WireMock call count assertions | HTTP log parsing | `WireMockServer.verify(postRequestedFor(...))` | WireMock provides built-in request counting |
| Redis key construction | String concatenation | Use the exact key patterns documented below | Production keys use specific prefixes; wrong prefix = miss |
| API key hashing in ApiKeyBuilder | Any other algorithm | `DigestUtils.sha256Hex(rawKey)` | `ApiKeyAuthenticationFilter` hashes the incoming raw key and compares against `key_hash` column |
| Transaction status transitions in builders | Manual SQL `tx_status` update | `TransactionStatus` enum `transitionTo()` for legal state seeding | Inserting illegal status directly bypasses state machine but may cause FK/constraint issues |

**Key insight:** Every verifier computation must use the identical algorithm as the production code it verifies. Hash chain, HMAC, and key hashing are verified-match requirements — not just "similar."

---

## Common Pitfalls

### Pitfall 1: Hash chain canonical string field ordering
**What goes wrong:** `HashChainVerifier` recomputes hashes but gets wrong results because the canonical string differs from production.
**Why it happens:** The canonical string format is non-obvious; developers may include timestamps or IDs.
**How to avoid:** Use exactly: `transactionId + "|" + eventType.name() + "|" + (statusFrom != null ? statusFrom.name() : "null") + "|" + statusTo.name() + "|" + actor + "|" + previousHash`. Do not include `traceId`, `metadata`, `createdDate`, or `id`.
**Warning signs:** Hash verification always returns false even for valid chains.

### Pitfall 2: Redis key prefix mismatch in CacheVerifier
**What goes wrong:** `CacheVerifier.assertIdempotencyKeyPresent` finds nothing because it uses the wrong key prefix.
**Why it happens:** Redis keys are hard-coded in production services — not exposed via constants.
**How to avoid:** Use exact key patterns:
- Idempotency: `"idempotency:" + tenantId + ":" + idempotencyKey`
- MTN token: `"mtn:token:cm"`
- Orange token: `"orange:token:cm"`
- MTN lock: `"mtn:token:lock"`
- Orange lock: `"orange:token:lock"`
- Velocity (Bucket4j): `"fraud:velocity:" + signal.getSignalName() + ":" + identifier` (stored as raw bytes — not accessible via `StringRedisTemplate.opsForValue()`)
**Warning signs:** Assertions always pass vacuously (key absent = assertion on absence passes; key present = never found).

### Pitfall 3: Velocity counter is a Bucket4j byte-key — not a string key
**What goes wrong:** `CacheVerifier` tries to read velocity counter value via `StringRedisTemplate.opsForValue().get(key)` and always gets null.
**Why it happens:** `VelocityCheckService` uses `LettuceBasedProxyManager` with `byte[]` keys. The Bucket4j internal format is opaque binary, not a plain string value.
**How to avoid:** `CacheVerifier.assertVelocityCounter` cannot read the counter value directly. Instead, verify the key exists via `StringRedisTemplate.hasKey()` using the string representation of the key, or inject `LettuceConnectionFactory` to get a raw connection. Alternatively, the verifier can assert absence/presence of the key rather than the count value.
**Warning signs:** CacheVerifier always returns 0 for velocity counter.

### Pitfall 4: WebhookDeliveryVerifier needs to wait for async delivery
**What goes wrong:** Assertion runs before the `@TransactionalEventListener(AFTER_COMMIT)` fires and delivers the webhook.
**Why it happens:** `WebhookDeliveryService.enqueue` is called from within a `REQUIRES_NEW` transaction that commits asynchronously after the inbound webhook callback returns.
**How to avoid:** Use Awaitility: `await().atMost(5, SECONDS).untilAsserted(() -> verifyDelivered(transactionId))`.
**Warning signs:** Intermittent test failures; passes when a `Thread.sleep` is inserted before assertion.

### Pitfall 5: OrangeWebhookPayloadBuilder `createtime` format
**What goes wrong:** `createtime` field is parsed incorrectly, causing `OrangeTimeUtil.parseOrangeTimestamp` to throw.
**Why it happens:** Orange timestamps are WAT (UTC+1, Africa/Douala) with no timezone offset in the string. The format is `yyyy-MM-dd HH:mm:ss` where the time is local WAT, not UTC.
**How to avoid:** Use `OrangeTimeUtil.parseOrangeTimestamp` to build the field value from an `Instant`, or hardcode a known-good format: `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(watDateTime)`.
**Warning signs:** `DateTimeParseException` or `NullPointerException` in `OrangeCallbackController`.

### Pitfall 6: TenantBuilder webhook setup requires both URL and secret for HMAC
**What goes wrong:** `WebhookDeliveryVerifier.assertHmacHeaderPresent` fails because signature is absent — webhook was delivered unsigned.
**Why it happens:** `WebhookDeliveryService.attemptDeliveryInternal` skips HMAC signing when `tenant.getWebhookSecret()` is null or blank ("sandbox mode").
**How to avoid:** `TenantBuilder.withWebhookSecret(secret)` must be called whenever a test needs to verify the `X-Payam-Signature` header. Tests that only verify delivery (not signature) can omit the secret.
**Warning signs:** Assertion on `X-Payam-Signature` header presence fails; header is absent in WireMock received requests.

### Pitfall 7: Spring Modulith outbox is not in use
**What goes wrong:** `EventVerifier` attempts to query a Spring Modulith outbox table that doesn't exist.
**Why it happens:** `ReconciliationModule.java` explicitly states `spring-modulith-starter-jpa is not in pom.xml`. The project uses `@TransactionalEventListener` directly, not the Modulith event publication outbox.
**How to avoid:** `EventVerifier` (VERIF-04) must query the `main.payment_event_log` table (which is the application's own event log), not a Modulith outbox. The "Spring Modulith outbox event publication count" in VERIF-04 refers to verifying that `payment_event_log` rows were written — not a Modulith-managed table.
**Warning signs:** `Table "spring_modulith_events" does not exist` error.

### Pitfall 8: DatabaseVerifier — no unique constraint on (tenantId, idempotencyKey) in `main.transaction`
**What goes wrong:** VERIF-01 says "no duplicate rows per (tenantId, idempotencyKey)" — but this constraint lives in `main.idempotency_key`, not `main.transaction`.
**Why it happens:** `Transaction` has no `idempotency_key` column. Idempotency is enforced via a separate `main.idempotency_key` table. `transaction` has a `UNIQUE` constraint only on `transaction_id`.
**How to avoid:** "No duplicate rows per (tenantId, idempotencyKey)" assertion must query `main.idempotency_key` table, not `main.transaction`. Orphan detection (checking for rows in `payment_event_log` without a matching `transaction`) is the correct scope of `DatabaseVerifier` for that requirement.

### Pitfall 9: LedgerVerifier account_code values are fixed constants
**What goes wrong:** LedgerVerifier tries to parameterize account codes but they are hardcoded in production.
**Why it happens:** `LedgerService.postEntry` always uses `"CUSTOMER_WALLET"` (DEBIT) and `"PROVIDER_CLEARING"` (CREDIT). These are not configurable.
**How to avoid:** `LedgerVerifier.assertLedgerBalanced` should assert these exact account_code values, not generic DEBIT/CREDIT existence.

---

## Code Examples

Verified patterns from production source:

### Hash chain canonical string (from `PaymentEventLog.create` and `EventLogService.verifyChain`)
```java
// Source: src/main/java/com/softropic/payam/transaction/repo/PaymentEventLog.java:87-93
String canonical = transactionId + "|"
        + eventType.name() + "|"
        + (statusFrom != null ? statusFrom.name() : "null") + "|"
        + statusTo.name() + "|"
        + actor + "|"
        + previousHash;
String eventHash = DigestUtils.sha256Hex(canonical);
```

### HMAC-SHA256 webhook signature (from `WebhookDeliveryService.attemptDeliveryInternal`)
```java
// Source: src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java:166-172
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(
    tenant.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
    "HmacSHA256"));
byte[] hmacBytes = mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
String signature = "sha256=" +
    org.apache.commons.codec.binary.Hex.encodeHexString(hmacBytes);
// Header: "X-Payam-Signature"
```

### Redis key patterns (verified from production services)
```java
// Idempotency — IdempotencyService.java:47
String redisKey = "idempotency:" + tenantId + ":" + idempotencyKey;

// MTN OAuth2 token — MtnTokenService.java:17
String TOKEN_KEY = "mtn:token:cm";
String LOCK_KEY  = "mtn:token:lock";

// Orange OAuth2 token — OrangeTokenService.java:17
String TOKEN_KEY = "orange:token:cm";
String LOCK_KEY  = "orange:token:lock";

// Velocity (Bucket4j — byte[] key, not String)
// fraud:velocity:{signal.getSignalName()}:{identifier}
// e.g. "fraud:velocity:IP_VELOCITY:192.168.1.1"
// Source: VelocityCheckService.java:78
byte[] key = ("fraud:velocity:" + signal.getSignalName() + ":" + identifier)
        .getBytes(StandardCharsets.UTF_8);
```

### Ledger invariant (two rows per SUCCESS, accounts hardcoded)
```java
// Source: LedgerService.java:35-55
// Always exactly 2 rows per postEntry call:
//   direction=DEBIT,  account_code="CUSTOMER_WALLET"
//   direction=CREDIT, account_code="PROVIDER_CLEARING"
// LedgerVerifier SQL:
jdbc.queryForList(
    "SELECT direction, account_code, amount FROM main.ledger_entry WHERE transaction_id = ?",
    transactionId);
```

### MTN callback payload fields (from `MtnCallbackPayload`)
```java
// Source: src/main/java/com/softropic/payam/mtn/contract/MtnCallbackPayload.java
// PUT callback fields:
//   financialTransactionId  — null on FAILED
//   externalId              — correlates to transactionId
//   status                  — "SUCCESSFUL" or "FAILED"
//   reason                  — present on FAILED only
```

### Orange callback payload fields (from `OrangeWebhookPayload`)
```java
// Source: src/main/java/com/softropic/payam/orange/contract/OrangeWebhookPayload.java
// POST notifUrl fields:
//   payToken, notifToken, status, txnid, msisdn, amount
//   createtime — WAT "yyyy-MM-dd HH:mm:ss", no offset — parse via OrangeTimeUtil
```

### Idempotency key structure
```java
// Source: main.idempotency_key DDL (V2 migration):
//   tenant_id, idempotency_key, response_body (TEXT), http_status, created_date, expires_at
// UNIQUE constraint: (tenant_id, idempotency_key)
```

### Transaction state machine (legal transitions for builders seeding in-flight state)
```
INITIATED -> AUTH_PENDING | FAILED
AUTH_PENDING -> AUTHORIZED | FAILED
AUTHORIZED -> PROCESSING | FAILED
PROCESSING -> SUCCESS | FAILED | REVERSED
SUCCESS, FAILED, REVERSED -> (terminal, no transitions)
```

### WireMock call count assertion pattern
```java
// Source: existing IT tests; WireMock API
mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/collection/v1_0/requesttopay")));
mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/collection/v1_0/requesttopay")));
```

### TenantService.createTenant return type
```java
// Source: TenantService.java:41
// Returns TenantCreationResult(Tenant tenant, TenantApiKey key, String rawKey)
// rawKey is the unhashed raw API key — store this for HTTP Authorization header
// key.getKeyHash() is SHA-256 of rawKey (stored in DB)
```

---

## Domain Model Summary

### Tables available for DatabaseVerifier (VERIF-01)

| Table | Key columns | Notes |
|-------|-------------|-------|
| `main.transaction` | `transaction_id` (UNIQUE), `tenant_id`, `tx_status`, `provider`, `amount`, `currency`, `provider_ref`, `mtn_financial_tx_id`, `pay_token`, `risk_score`, `device_fingerprint`, `fee_amount`, `fee_rule_id` | FK to `main.tenant(id)` |
| `main.payment_event_log` | `transaction_id`, `event_type`, `status_from`, `status_to`, `actor`, `metadata` (JSONB), `previous_hash`, `event_hash`, `created_date` | No FK to transaction; `transaction_id` is VARCHAR |
| `main.ledger_entry` | `transaction_id`, `entry_group_id`, `tenant_id`, `direction` (DEBIT/CREDIT), `account_code`, `amount`, `currency`, `created_date` | `CHECK (amount > 0)` |
| `main.idempotency_key` | `tenant_id`, `idempotency_key`, `http_status`, `response_body`, `expires_at` | UNIQUE(tenant_id, idempotency_key) |
| `main.webhook_delivery_log` | `transaction_id`, `tenant_id`, `webhook_url`, `event_type`, `http_status`, `attempt_count`, `next_retry_at`, `delivered`, `last_attempt_at` | Mutable delivery state |
| `main.tenant` | `id`, `tenant_ref`, `name`, `tenant_status`, `webhook_url`, `webhook_secret` | `AbstractAuditingEntity` + `status` column |
| `main.tenant_api_key` | `tenant_id`, `key_hash`, `key_prefix`, `key_status`, `environment`, `rotated_at` | FK to tenant |
| `main.reconciliation_report` | `report_date`, `provider`, `total_checked`, `total_matched`, `total_discrepancies`, `status` | UNIQUE(report_date, provider) |
| `main.reconciliation_discrepancy` | `report_id`, `payam_tx_id`, `provider_ref`, `payam_status`, `provider_status`, `discrepancy_type`, `severity` | FK to reconciliation_report |

### Enums available for builders/verifiers

| Enum | Values |
|------|--------|
| `TransactionStatus` | `INITIATED, AUTH_PENDING, AUTHORIZED, PROCESSING, SUCCESS, FAILED, REVERSED` |
| `TransactionEventType` | `PAYMENT_INITIATED, FRAUD_CHECK_PASSED, FRAUD_CHECK_BLOCKED, PROVIDER_AUTH_REQUESTED, PROVIDER_AUTHORIZED, PROVIDER_PROCESSING, PROVIDER_SUCCESS, PROVIDER_FAILED, TRANSACTION_REVERSED` |
| `LedgerDirection` | `DEBIT, CREDIT` |
| `FraudSignal` | `IP_VELOCITY, MSISDN_VELOCITY, APP_VELOCITY, MSISDN_HOUSEHOLD` |
| `MobilePaymentProvider` | `ORANGE, MTN, NEXTTEL` |
| `ApiKeyStatus` | `ACTIVE, ROTATED, REVOKED` |
| `TenantStatus` | `ACTIVE` (and others) |
| `DiscrepancyType` | `MISSING_IN_PROVIDER, AMOUNT_MISMATCH, STATUS_MISMATCH, UNCONFIRMED` |
| `DiscrepancySeverity` | `HIGH, LOW` (verify in source) |

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Inline assertions in `@AfterEach` | Dedicated verifier objects | Phase 19 | Composable, reusable, testable |
| Ad-hoc data seeding in `@BeforeEach` | Builder-pattern factories with `.create(jdbc)` | Phase 19 | Eliminates duplication; deterministic IDs |
| `DbCleaner` (partial, security tables only) | `TestDataCleaner.wipeAll()` (all payment tables) | Phase 18 | Complete cleanup in FK-safe order |

**Deprecated/outdated:**
- `DbCleaner` (in `utils/`): handles only `main.sec` and security tables. Phase 19 builders should coordinate with `TestDataCleaner`, not `DbCleaner`.
- Inline `tenantService.createTenant(...)` in test `@BeforeEach`: replaced by `TenantBuilder.create(jdbc)`.

---

## Open Questions

1. **`ProviderCallVerifier` SSRF callback URL check (VERIF-06)**
   - What we know: The requirement says "callback URL is Payam-owned (SSRF guard)". The codebase has `mtn.callback-ip-whitelist` and `orange.callback-ip-whitelist` properties, but no explicit SSRF guard class was found during research.
   - What's unclear: Where exactly the SSRF guard is enforced — at the HTTP client level or the callback controller level?
   - Recommendation: `ProviderCallVerifier` should assert that the callback URL in the WireMock stub (which is set via `baseUrlProperties`) points to the WireMock server, not an external host. This is implicitly guaranteed by the test setup. Check `MtnCallbackController` and `OrangeCallbackController` for any explicit SSRF validation if a positive assertion is needed.

2. **`QueryCountVerifier` datasource proxy setup (VERIF-10)**
   - What we know: `QueryRecorderListener` and `SqlStatementHolder` exist in `src/test/java/com/softropic/payam/utils/sql/`. The property `ledger.database.spy=true` is used in `LedgerServiceIT` and `PaymentEventLogIT` to enable the data-source proxy.
   - What's unclear: Exactly how the spy is activated (the bean configuration for `datasource-proxy` is not visible from the files read — it's likely in `TestConfig.java`).
   - Recommendation: Read `TestConfig.java` before implementing `QueryCountVerifier`. The N+1 detection infrastructure is already present; the verifier wraps `SqlStatementHolder`.

3. **`FraudSignalBuilder` velocity counter override (BUILD-06)**
   - What we know: Velocity counters are Bucket4j `LettuceBasedProxyManager` with byte[] keys. There is no public API to reset/seed a specific counter value.
   - What's unclear: How to "pre-seed velocity counter override" as BUILD-06 requires. Direct Bucket4j manipulation, or Redis `DEL` + manual counter insert?
   - Recommendation: `FraudSignalBuilder.withVelocityCounterOverride` should use `StringRedisTemplate.delete(key)` to reset the counter key (forcing Bucket4j to create a fresh bucket), then call `VelocityCheckService.checkVelocity` N times programmatically to exhaust the bucket to the desired level. Alternatively, inject `VelocityCheckService` and call `checkVelocity` directly from the builder.

4. **`ReconciliationReportBuilder` (BUILD-07) wire format**
   - What we know: `ReconciliationReport` entity has `totalChecked`, `totalMatched`, `totalDiscrepancies`, `status`. `ReconciliationDiscrepancy` has `discrepancyType`, `severity`, `payamTxId`, `providerRef`, amounts.
   - What's unclear: Whether `ReconciliationReportBuilder` should insert directly via `JdbcTemplate` or call `ReconciliationService`. The requirement says "builds provider reports with matched, missing, and mismatched transaction entries" — these are `ReconciliationDiscrepancy` rows.
   - Recommendation: Use `JdbcTemplate` direct inserts for `ReconciliationReport` and `ReconciliationDiscrepancy` rows. The service `ReconciliationService` is oriented toward running the full job, not seeding test data.

---

## Sources

### Primary (HIGH confidence)

- Source code read directly: `PaymentEventLog.java`, `EventLogService.java` — hash chain formula
- Source code read directly: `WebhookDeliveryService.java` — HMAC implementation, header name `X-Payam-Signature`, format `sha256=<hex>`
- Source code read directly: `IdempotencyService.java` — Redis key `idempotency:{tenantId}:{key}`
- Source code read directly: `MtnTokenService.java` — Redis key `mtn:token:cm`, lock `mtn:token:lock`
- Source code read directly: `OrangeTokenService.java` — Redis key `orange:token:cm`, lock `orange:token:lock`
- Source code read directly: `VelocityCheckService.java` — Redis key `fraud:velocity:{signal}:{identifier}` as byte[]
- Source code read directly: `AbstractPayamE2ETest.java` — `mtnServer`, `orangeServer` as `protected WireMockServer`
- Source code read directly: `TenantService.java`, `ApiKeyService.java` — tenant creation, key hashing via `DigestUtils.sha256Hex`
- Source code read directly: `LedgerService.java` — `CUSTOMER_WALLET`/`PROVIDER_CLEARING` account codes
- Source code read directly: `TestDataCleaner.java` — FK-safe delete order for all payment tables
- Source code read directly: `TransactionStatus.java` — complete state machine
- Source code read directly: `PaymentRequest.java` — `msisdn, amount, currency, externalReference, idempotencyKey, deviceFingerprint`
- Source code read directly: `MtnCallbackPayload.java` — `financialTransactionId, externalId, status, reason`
- Source code read directly: `OrangeWebhookPayload.java` — `payToken, notifToken, status, txnid, msisdn, amount, createtime`
- Source code read directly: `ReconciliationReport.java`, `ReconciliationDiscrepancy.java` — report/discrepancy structure
- DDL read directly: V1–V16 migration files — all table columns and constraints confirmed
- Source code read directly: `ReconciliationModule.java` — confirms spring-modulith is NOT in use

### Secondary (MEDIUM confidence)

- `QueryRecorderListener.java`, `SqlStatementHolder.java` — N+1 detection infrastructure confirmed present; exact activation mechanism requires reading `TestConfig.java` (not read)

---

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — all dependencies already declared in pom.xml; verified from import statements
- Domain model (entities, tables, columns): HIGH — read directly from Java source and Flyway DDL
- Redis key conventions: HIGH — read directly from production service constants
- Hash chain formula: HIGH — read directly from `PaymentEventLog.create()` and `EventLogService.verifyChain()`
- HMAC implementation: HIGH — read directly from `WebhookDeliveryService`
- Package structure recommendation: HIGH — follows Phase 18 established pattern
- QueryCountVerifier activation: MEDIUM — infrastructure exists; full activation config not read
- FraudSignalBuilder velocity seeding: MEDIUM — no direct pre-seed API exists; workaround strategy derived from Bucket4j + Redis understanding
- Spring Modulith outbox: HIGH (confirmed NOT present) — `ReconciliationModule.java` comment is explicit

**Research date:** 2026-03-27
**Valid until:** 2026-05-27 (stable domain; entities unlikely to change)
