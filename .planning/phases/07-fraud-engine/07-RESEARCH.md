# Phase 7: Fraud Engine - Research

**Researched:** 2026-03-24
**Domain:** Payment fraud detection — velocity checks, risk scoring, device fingerprinting, DB-configurable rules
**Confidence:** HIGH

---

## Summary

Phase 7 builds a fraud evaluation pipeline that intercepts every payment initiation before provider dispatch. All core infrastructure is already in place: Bucket4j 8.10.1 is a direct dependency, `hypersistence-utils-hibernate-63` 3.9.10 is present for JSONB column mapping, and the `uap-java` 1.6.1 library is in pom.xml for user-agent parsing. Redis (via `spring-boot-starter-data-redis`) is active and already used by idempotency and dedup in prior phases.

The codebase also contains a working `RateLimitingService` that uses Bucket4j in-memory (no Redis). For fraud velocity checks — which span multiple JVM instances and must survive restarts — the buckets must be moved to Redis-backed state (Bucket4j-Redis integration via `bucket4j-redis`). The existing in-memory service is a model but its bucket storage is unsuitable for payment velocity.

The single integration point is `PaymentOrchestrator.initiate()`, which already has a clear pre-dispatch phase (Steps 1–4 before the provider HTTP call). The fraud check inserts cleanly between Step 4 (build PaymentCommand) and Step 5 (dispatch to port), following the established non-transactional pattern in that method.

**Primary recommendation:** Implement `FraudScoringService` that (1) runs velocity checks via Bucket4j-Redis, (2) computes a weighted risk score from DB-loaded signal weights, (3) stores device fingerprint data on the transaction, then wire it into `PaymentOrchestrator` as a pre-dispatch hook. Load signal weights from `fraud_rule` DB table with a `@Scheduled` cache refresh (no restart needed).

---

## What Already Exists in the Codebase

### Directly Reusable

| Component | Location | Reuse in Phase 7 |
|-----------|----------|-----------------|
| `RateLimitingService` | `security/service/RateLimitingService.java` | Pattern only — bucket storage is in-memory `ConcurrentHashMap`; Phase 7 needs Redis-backed buckets |
| `RateLimitingAspect` + `@RateLimited` | `security/infrastructure/RateLimitingAspect.java` | Pattern reference; Phase 7 calls the fraud service directly, not via AOP (need conditional blocking, not just 429) |
| `RequestMetadataProvider` | `security/common/util/RequestMetadataProvider.java` | **Direct reuse** — provides `getClientInfo()` thread-local with IP, userAgent, fingerprintCookie, apiKey already populated per-request by `SecurityAdviceFilter` |
| `RequestMetadata` | `security/common/util/RequestMetadata.java` | **Direct reuse** — contains ipAddress, userAgent, fingerprintCookie, apiKey; all fraud signals already extracted |
| `Bucket4j` (bucket4j-core 8.10.1) | pom.xml | **Direct reuse** — already present; add `bucket4j-redis` for distributed buckets |
| `hypersistence-utils-hibernate-63` 3.9.10 | pom.xml | **Direct reuse** — `@Type(JsonType.class)` / `@JdbcTypeCode(SqlTypes.JSON)` for JSONB score storage; already used in Phase 2 event log |
| `uap-java` 1.6.1 | pom.xml | **Direct reuse** — `Parser.get().parse(userAgent)` returns `Client` with OS/device/browser breakdown |
| `StringRedisTemplate` | Active since Phase 2 | **Direct reuse** — already used for dedup/idempotency; use for velocity bucket state |
| `Transaction` entity | `transaction/repo/Transaction.java` | **Extend** — add `risk_score`, `device_fingerprint` columns via new Flyway migration V10 |
| `PaymentCommand` record | `common/payment/PaymentCommand.java` | **Extend** — add `clientIp`, `userAgent`, `deviceFingerprint` fields to carry fraud signals |
| `PaymentOrchestrator.initiate()` | `payment/service/PaymentOrchestrator.java` | **Integration point** — insert `fraudScoringService.evaluate(cmd)` between Step 4 and Step 5 |
| `AbstractAuditingEntity` / `BaseEntity` | `common/persistence/` | **Reuse** — `FraudRule` extends `AbstractAuditingEntity` |

### Key Architectural Patterns to Follow

From `RateLimitingService`:
- `buckets.computeIfAbsent(bucketKey, k -> createBucket(...))` — same pattern, but replace `ConcurrentHashMap<String, Bucket>` with a Redis-backed proxy
- Key structure: `"fraud:velocity:{dimension}:{identifier}"` (dimension = ip, msisdn, tenantApp)

From `PaymentOrchestrator` (no-transaction boundary decision — 05-01):
- The fraud check is called BEFORE provider dispatch, OUTSIDE any `@Transactional` boundary
- Fraud blocking returns a `PaymentResponse.failed(...)` early — same as UNKNOWN_MSISDN_PREFIX path
- Score/fingerprint persistence must use `TransactionTemplate` to avoid holding connection

From `WebhookDeliveryLog` (Phase 6):
- JSON column pattern: `@JdbcTypeCode(SqlTypes.JSON)` on `Map<String, Object>` or on a typed record

---

## Standard Stack

### Core (all already in pom.xml)

| Library | Version | Purpose | Status |
|---------|---------|---------|--------|
| `bucket4j-core` | 8.10.1 | Token-bucket rate limiter | Present |
| `spring-boot-starter-data-redis` | (SB 3.5.11 managed) | Redis client (Lettuce) | Present |
| `hypersistence-utils-hibernate-63` | 3.9.10 | JSONB column mapping | Present |
| `uap-java` | 1.6.1 | User-agent → OS/device/browser | Present |
| `spring-boot-starter-quartz` | (SB managed) | Cache-refresh scheduler (optional) | Present |

### New Dependency Needed

| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| `bucket4j-redis` | 8.10.1 | Redis-backed bucket state | In-memory buckets do not survive restarts or multi-node; velocity checks must be durable |

**Installation:**
```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-redis</artifactId>
    <version>8.10.1</version>
</dependency>
```

**Confidence:** HIGH — bucket4j-redis is part of the same bucket4j 8.x release family as bucket4j-core already in pom.xml.

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| bucket4j-redis | Pure Redis INCR+EXPIRE scripts | Bucket4j gives token-bucket semantics (burst tolerance) vs fixed-window; token-bucket is better for payment velocity |
| DB-table for signal weights | application.yaml | DB allows hot-reload without restart (required by SC-4); YAML requires restart |
| `@Scheduled` weight cache | `@CacheEvict` with TTL | `@Scheduled` with `@RefreshScope` is simpler; both work |
| Transaction.risk_score on entity | Separate `fraud_assessment` table | Single column on Transaction is simpler and sufficient for Phase 7; separate table adds complexity |

---

## Architecture Patterns

### Recommended Project Structure

```
src/main/java/com/softropic/payam/fraud/
├── contract/
│   ├── FraudDecision.java         # record: blocked(boolean), riskScore(int), reason(String)
│   └── FraudSignal.java           # enum: IP_VELOCITY, MSISDN_VELOCITY, APP_VELOCITY, DEVICE_FP
├── repo/
│   ├── FraudRule.java             # @Entity: signal_name, weight, threshold, enabled
│   └── FraudRuleRepository.java   # JpaRepository<FraudRule, Long>
└── service/
    ├── FraudScoringService.java   # orchestrates velocity + scoring + fingerprint
    ├── VelocityCheckService.java  # Bucket4j-Redis per-dimension buckets
    └── FraudRuleCache.java        # @Scheduled reload of DB rules into memory
```

Integration target (existing file, small addition):
```
src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
  -- add: private final FraudScoringService fraudScoringService
  -- add: pre-dispatch call after Step 4
```

Migration:
```
src/main/resources/db/migration/V10__fraud_schema.sql
  -- main.fraud_rule table
  -- ADD COLUMN risk_score INTEGER on main.transaction
  -- ADD COLUMN device_fingerprint TEXT on main.transaction
```

PaymentCommand extension:
```
src/main/java/com/softropic/payam/common/payment/PaymentCommand.java
  -- add: String clientIp
  -- add: String userAgent
  -- add: String deviceFingerprint
```

### Pattern 1: Bucket4j-Redis Velocity Check

**What:** One Redis-backed bucket per (dimension, identifier). Consumes a token per payment attempt.
**When to use:** IP velocity, MSISDN velocity, tenant-app velocity — any check that must survive restart and work across JVM instances.

```java
// Source: Bucket4j 8.x Redis integration docs (bucket4j-redis module)
// ProxyManager provided by RedissonBasedProxyManager or LettuceBasedProxyManager
BucketConfiguration config = BucketConfiguration.builder()
    .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, windowDuration)))
    .build();

String key = "fraud:velocity:ip:" + clientIp;
Bucket bucket = proxyManager.builder().build(key, () -> config);
boolean allowed = bucket.tryConsume(1);
```

Note: `bucket4j-redis` integrates with either Redisson or Lettuce. This project uses Lettuce (via `spring-boot-starter-data-redis`). Use `LettuceBasedProxyManager`.

### Pattern 2: DB-Configurable Signal Weights

**What:** `fraud_rule` table rows, loaded into memory on startup and refreshed on a schedule.
**When to use:** All weight lookups during risk score computation.

```java
// FraudRule entity
@Entity @Table(name = "fraud_rule", schema = "main")
public class FraudRule extends AbstractAuditingEntity {
    @Column(name = "signal_name", unique = true)  private String signalName;
    @Column                                        private int weight;       // 0-100
    @Column                                        private int threshold;    // velocity limit
    @Column                                        private boolean enabled;
    @Column                                        private String description;
}
```

```java
// FraudRuleCache — @Scheduled hot-reload
@Scheduled(fixedDelayString = "${fraud.rule-cache.refresh-interval-ms:60000}")
public void refreshRules() {
    List<FraudRule> rules = fraudRuleRepository.findByEnabledTrue();
    rulesCache.set(rules);  // AtomicReference<List<FraudRule>>
}
```

Hot-reload means no restart needed when a DB row changes weight/threshold — success criterion SC-4.

### Pattern 3: Risk Score Computation

**What:** Weighted sum of active fraud signals, clamped to 0–100.
**When to use:** After all velocity signals are evaluated, before blocking decision.

```java
// Example scoring (weights from DB):
int rawScore = 0;
if (ipVelocityTriggered)     rawScore += ipRule.getWeight();      // e.g. 40
if (msisdnVelocityTriggered) rawScore += msisdnRule.getWeight();  // e.g. 35
if (deviceFpSuspicious)      rawScore += deviceRule.getWeight();  // e.g. 25
int riskScore = Math.min(rawScore, 100);

// Block if score exceeds configured block threshold (separate fraud_rule row or config property)
boolean blocked = riskScore >= blockThreshold;
```

SIM-sharing household down-weight: the `msisdn_household` signal has a lower default weight in the DB seed data. Operators can lower it further without restart — satisfies SC-5.

### Pattern 4: Device Fingerprint Capture

**What:** Extract device fingerprint from the `X-Device-Fingerprint` request header (client-provided) and parse user-agent via `uap-java`.
**When to use:** Every payment initiation — stored for future pattern analysis (SC-3).

```java
// ua-parser usage (uap-java 1.6.1)
// Source: https://github.com/ua-parser/uap-java
import ua_parser.Client;
import ua_parser.Parser;

Parser uaParser = new Parser();
Client c = uaParser.parse(userAgent);
String device = c.device.family;   // e.g. "iPhone"
String os     = c.os.family;       // e.g. "iOS"
String browser = c.userAgent.family; // e.g. "Mobile Safari"
```

Device fingerprint stored as a JSON string in `transaction.device_fingerprint` TEXT column (not JSONB — the fingerprint is opaque and not queried by field in this phase).

### Pattern 5: PaymentOrchestrator Pre-Dispatch Hook

**What:** `FraudScoringService.evaluate(cmd)` called between Step 4 and Step 5 in `PaymentOrchestrator.initiate()`.
**When to use:** Every payment initiation, before any provider call.

```java
// In PaymentOrchestrator.initiate() — insert between Step 4 and Step 5:
FraudDecision fraud = fraudScoringService.evaluate(cmd);
if (fraud.blocked()) {
    log.warn("Payment blocked by fraud engine: transactionId={}, reason={}", tx.getTransactionId(), fraud.reason());
    applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.FRAUD_BLOCKED, fraud.reason());
    return PaymentResponse.failed(tx.getTransactionId(),
            OrchestratorError.FRAUD_BLOCKED.getErrorCode(),
            "Payment blocked: " + fraud.reason());
}
// Store risk score and fingerprint (inside TransactionTemplate, like other state mutations)
transactionTemplate.execute(status -> {
    Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId()).orElseThrow();
    locked.setRiskScore(fraud.riskScore());
    locked.setDeviceFingerprint(cmd.deviceFingerprint());
    return null;
});
```

A new `OrchestratorError.FRAUD_BLOCKED` enum entry returns HTTP 422 (same as SUBSCRIBER_INACTIVE). This fits the existing `resolveHttpStatus()` default case.

### Anti-Patterns to Avoid

- **In-memory-only Bucket4j:** The existing `RateLimitingService` uses `ConcurrentHashMap<String, Bucket>`. Do NOT reuse this for payment velocity — it loses state on restart and does not work across multiple JVM instances.
- **@Transactional on the fraud check:** `PaymentOrchestrator.initiate()` is deliberately not `@Transactional` (decision 05-01). The fraud service must not hold a DB connection during velocity check (Redis call). Use `TransactionTemplate` only for the score persistence step.
- **Blocking before transaction creation:** Velocity check happens AFTER the `Transaction` row is created (Step 3). This ensures every blocked attempt is still recorded in the DB for audit. SC-1 says "blocked before reaching the provider" — not before the transaction row.
- **Hard-coding signal weights in Java:** All weights live in `fraud_rule` DB rows. No `static final int WEIGHT_IP = 40` constants anywhere.
- **Querying DB on every payment:** `FraudRuleCache` loads rules into memory. DB is queried only on cache refresh cycle (default 60s), not per-request.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Redis-backed token bucket | Custom Redis INCR/EXPIRE scripts | `bucket4j-redis` with `LettuceBasedProxyManager` | Token-bucket handles burst tolerance, atomic decrement, TTL management; hand-rolled scripts miss edge cases |
| User-agent parsing | String splitting on User-Agent header | `uap-java` `Parser.parse()` | Regex rules for 1000+ UA variants; uap-java already in pom.xml |
| Device fingerprint hashing | Custom hash of IP+UA | Accept `X-Device-Fingerprint` header from client + uap-java for server-side enrichment | Client-side fp libraries (FingerprintJS) produce stable IDs that survive IP change; server-side UA parse is supplemental |
| IP range checks | Custom CIDR math | `commons-validator` `InetAddressValidator` or plain String match | Already used in MTN IP whitelist; for velocity, exact IP is the key (not range check) |

---

## Common Pitfalls

### Pitfall 1: bucket4j-redis vs bucket4j-core API

**What goes wrong:** `bucket4j-core` `Bucket.builder().build()` creates a local bucket. `bucket4j-redis` requires `proxyManager.builder().build(key, configSupplier)`. These are different APIs.
**Why it happens:** Both are in the `io.github.bucket4j` namespace; it is easy to use the wrong factory.
**How to avoid:** `LettuceBasedProxyManager` requires a `StatefulRedisConnection<byte[], byte[]>` (raw byte connection, not StringRedisTemplate). Get it from the `RedisClient` or extract from the Lettuce `ConnectionFactory`.
**Warning signs:** `ClassCastException` on Redis connection type; local bucket created silently if wrong factory used.

### Pitfall 2: @Transactional self-invocation (already known, applies here)

**What goes wrong:** `FraudScoringService` calling its own `@Transactional` methods would bypass Spring AOP proxy.
**Why it happens:** Same issue as WebhookTransitionService in Phase 6 (decision 06-02).
**How to avoid:** Any `@Transactional` score-persistence call must be on a separate bean or use `TransactionTemplate` directly (consistent with PaymentOrchestrator pattern).

### Pitfall 3: Hot-reload cache visibility without volatile/AtomicReference

**What goes wrong:** `@Scheduled` method updates a `List<FraudRule>` field without synchronization; reader threads see stale data.
**Why it happens:** JVM memory model — writes to plain fields may not be visible across threads.
**How to avoid:** Store rules in `AtomicReference<List<FraudRule>>`. `@Scheduled` calls `ref.set(newList)`. Readers call `ref.get()`. No lock needed.

### Pitfall 4: PaymentCommand is a record — cannot add fields without breaking callers

**What goes wrong:** Adding `clientIp`, `userAgent`, `deviceFingerprint` to `PaymentCommand` adds constructor parameters; all existing construction sites must be updated.
**Why it happens:** Java records have a canonical constructor with all fields.
**How to avoid:** Update `PaymentOrchestrator.initiate()` (the only `PaymentCommand` construction site) when extending the record. Check test for `PaymentOrchestratorIT` — it constructs `PaymentRequest` not `PaymentCommand` directly; orchestrator builds `PaymentCommand` internally so the impact is contained.

### Pitfall 5: X-Device-Fingerprint header not in RequestMetadata

**What goes wrong:** `RequestMetadataProvider.initRequestMetadata()` does not read `X-Device-Fingerprint` header from the request. The fingerprint is unavailable in the thread-local.
**Why it happens:** `RequestMetadata` was designed for login flows, not payment flows.
**How to avoid:** Two options: (a) read the header directly in `PaymentResource` and pass it on `PaymentRequest`, or (b) add a new field to `RequestMetadata` and read it in `initRequestMetadata()`. Option (a) is less invasive and keeps the change inside the fraud phase scope. Add `deviceFingerprint` field to `PaymentRequest`.

### Pitfall 6: SIM-sharing false positives on MSISDN velocity

**What goes wrong:** A household shares one SIM card for multiple payments (common in Cameroon). Each payment from the same MSISDN hits the velocity bucket and eventually blocks.
**Why it happens:** Simple per-MSISDN velocity cannot distinguish household vs. fraudulent reuse.
**How to avoid:** The `msisdn_household` signal row in `fraud_rule` table has a lower default weight (e.g., weight=15 instead of 35). Operators tune it via DB update, no restart needed. This satisfies SC-5. The velocity threshold is still enforced but contributes less to the final score.

### Pitfall 7: Flyway V10 conflicts with any existing V10

**What goes wrong:** If another team member adds V10 before fraud engine lands, migration checksums conflict.
**Why it happens:** Flyway migration numbering is sequential; coordination needed.
**How to avoid:** V9 is `webhook_delivery_log.sql`. V10 is the next available slot. Verify no V10 exists before creating it.

---

## Code Examples

### Bucket4j-Redis with Lettuce

```java
// Source: bucket4j-redis module README (https://github.com/bucket4j/bucket4j)
// LettuceBasedProxyManager requires io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]>

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;

// Obtain StatefulRedisConnection<byte[], byte[]> from existing Lettuce RedisClient or ConnectionFactory
StatefulRedisConnection<byte[], byte[]> redisConnection = redisClient.connect(ByteArrayCodec.INSTANCE);
ProxyManager<byte[]> proxyManager = LettuceBasedProxyManager.builderFor(redisConnection).build();

// Bucket per key
BucketConfiguration config = BucketConfiguration.builder()
    .addLimit(Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1))))
    .build();
Bucket bucket = proxyManager.builder().build(key.getBytes(), () -> config);
boolean allowed = bucket.tryConsume(1);
```

**Confidence:** MEDIUM — API shape verified against bucket4j GitHub README; exact constructor args may differ by minor version. Verify against Context7 or bucket4j 8.x docs before coding.

### FraudDecision return type

```java
// Source: design decision for this phase
public record FraudDecision(boolean blocked, int riskScore, String reason) {
    public static FraudDecision allow(int riskScore) {
        return new FraudDecision(false, riskScore, null);
    }
    public static FraudDecision block(int riskScore, String reason) {
        return new FraudDecision(true, riskScore, reason);
    }
}
```

### uap-java usage

```java
// Source: https://github.com/ua-parser/uap-java (confirmed in pom.xml as uap-java 1.6.1)
import ua_parser.Client;
import ua_parser.Parser;

Parser uaParser = new Parser();   // creates singleton; cache as @Bean
Client c = uaParser.parse(userAgent);
// c.device.family, c.os.family, c.userAgent.family
```

### FraudRule migration (V10)

```sql
-- V10__fraud_schema.sql
CREATE TABLE main.fraud_rule (
    id                  BIGINT PRIMARY KEY,
    version             BIGINT NOT NULL DEFAULT 0,
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    signal_name         VARCHAR(100) UNIQUE NOT NULL,
    weight              INTEGER NOT NULL DEFAULT 0,    -- contribution to 0-100 score
    threshold           INTEGER NOT NULL DEFAULT 10,  -- velocity window capacity
    window_seconds      INTEGER NOT NULL DEFAULT 60,  -- velocity window duration
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    description         VARCHAR(500)
);

-- Seed default rules
INSERT INTO main.fraud_rule (id, signal_name, weight, threshold, window_seconds, enabled, description)
VALUES
    (1, 'IP_VELOCITY',        40, 10, 60,   true, 'Too many payments from same IP in 60s'),
    (2, 'MSISDN_VELOCITY',    35, 5,  60,   true, 'Too many payments to same MSISDN in 60s'),
    (3, 'APP_VELOCITY',       25, 20, 60,   true, 'Too many payments from same tenant+app in 60s'),
    (4, 'MSISDN_HOUSEHOLD',   15, 8,  3600, true, 'SIM-sharing household pattern — down-weighted');

ALTER TABLE main.transaction
    ADD COLUMN IF NOT EXISTS risk_score        INTEGER,
    ADD COLUMN IF NOT EXISTS device_fingerprint TEXT;
```

---

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| In-memory Guava cache for login velocity (`LoginAttemptsService`) | Redis-backed Bucket4j for payment velocity | Multi-node safe; survives restarts |
| Static `@Value` config for rate limits | DB `fraud_rule` table with `@Scheduled` refresh | Hot-reloadable without restart |
| No fraud signal on `Transaction` | `risk_score` + `device_fingerprint` columns on `Transaction` | Queryable by Phase 8 admin dashboard |

---

## Open Questions

1. **LettuceBasedProxyManager connection extraction**
   - What we know: `spring-boot-starter-data-redis` auto-configures a Lettuce `LettuceConnectionFactory`; `LettuceBasedProxyManager` needs a raw `StatefulRedisConnection<byte[], byte[]>`
   - What's unclear: Whether `LettuceConnectionFactory.getNativeClient()` directly provides a `RedisClient` for `connect(ByteArrayCodec.INSTANCE)` without additional boilerplate
   - Recommendation: Plan 07-01 should include a `@Bean VelocityBucketProxyManager` that casts `LettuceConnectionFactory` to extract the Lettuce client, tested with a quick integration test

2. **PaymentRequest fingerprint field**
   - What we know: `X-Device-Fingerprint` is not in `RequestMetadata`; the simplest path is adding `deviceFingerprint` to `PaymentRequest`
   - What's unclear: Whether the client-facing API should expose this as an optional field
   - Recommendation: Add as `@Nullable String deviceFingerprint` to `PaymentRequest`; clients that send it get fingerprint storage; those that don't get null stored (acceptable for Phase 7)

3. **block_threshold value**
   - What we know: Risk score range is 0–100; score is a weighted sum of triggered signals
   - What's unclear: Whether block threshold should be another `fraud_rule` row or a dedicated config property
   - Recommendation: Add a `BLOCK_THRESHOLD` row in `fraud_rule` table (signal_name='BLOCK_THRESHOLD', threshold=70) to keep all config DB-driven and hot-reloadable

---

## Logical Plan Breakdown

The roadmap already specifies 2 plans. This research confirms the split is correct:

### Plan 07-01: FraudScoringService + Velocity + Fingerprint
**Scope:** All the new fraud code plus Flyway V10 schema
- `FraudRule` entity + `FraudRuleRepository`
- `FraudRuleCache` with `@Scheduled` hot-reload
- `VelocityCheckService` with Bucket4j-Redis (`LettuceBasedProxyManager`)
- `FraudScoringService` — orchestrates velocity checks + score computation + fingerprint capture
- `FraudDecision` record
- Add `bucket4j-redis` dependency to pom.xml
- `V10__fraud_schema.sql` — `fraud_rule` table + `risk_score`/`device_fingerprint` columns on `transaction`
- Extend `PaymentCommand` with `clientIp`, `userAgent`, `deviceFingerprint`
- Extend `PaymentRequest` with optional `deviceFingerprint`
- **IT test: `FraudScoringServiceIT`** — uses real Redis Testcontainer; verify velocity block fires, score computed correctly, fingerprint stored

### Plan 07-02: PaymentOrchestrator integration + end-to-end IT
**Scope:** Wire fraud engine into payment flow; update OrchestratorError; full flow IT
- Add `OrchestratorError.FRAUD_BLOCKED` to `OrchestratorError` enum
- Inject `FraudScoringService` into `PaymentOrchestrator`
- Add pre-dispatch fraud hook (between Step 4 and Step 5)
- Update `PaymentOrchestrator.initiate()` to read IP/UA from `RequestMetadataProvider`
- `application.yaml` — add `fraud:` config block (block-threshold, rule-cache-refresh-interval-ms)
- **IT test: `FraudEngineIT`** — end-to-end via `POST /v1/payments`; assert velocity block returns 422 with FRAUD_BLOCKED; assert normal transaction carries risk_score in DB

---

## Sources

### Primary (HIGH confidence)
- Direct code inspection of `RateLimitingService.java`, `RequestMetadataProvider.java`, `RequestMetadata.java`, `PaymentOrchestrator.java`, `PaymentCommand.java`, `Transaction.java`
- `pom.xml` — verified library presence: `bucket4j-core 8.10.1`, `hypersistence-utils-hibernate-63 3.9.10`, `uap-java 1.6.1`, `spring-boot-starter-data-redis`
- All Flyway migrations V1–V9 — confirmed V10 is next available slot
- `STATE.md` decisions — confirmed `@Transactional` avoidance pattern in PaymentOrchestrator (decision 05-01), `TransactionTemplate` pattern

### Secondary (MEDIUM confidence)
- Bucket4j GitHub README / bucket4j-redis module documentation — API shape for `LettuceBasedProxyManager`
- uap-java GitHub (https://github.com/ua-parser/uap-java) — `Parser.parse(userAgent)` API

### Tertiary (LOW confidence)
- Exact constructor for `LettuceBasedProxyManager.builderFor()` in bucket4j 8.10.1 — confirm before coding plan 07-01

---

## Metadata

**Confidence breakdown:**
- What exists in codebase: HIGH — directly inspected
- Standard stack: HIGH — all libraries confirmed in pom.xml
- Architecture patterns: HIGH — follows established decisions from STATE.md
- Bucket4j-Redis API details: MEDIUM — confirmed module exists; exact API needs Context7 or docs verification
- Pitfalls: HIGH — derived from existing decisions + Java fundamentals

**Research date:** 2026-03-24
**Valid until:** 2026-04-24 (bucket4j-redis is stable; no fast-moving APIs involved)
