# Phase 2: Transaction Core + Event Sourcing - Research

**Researched:** 2026-03-23
**Domain:** Transaction state machine, append-only event log with SHA-256 hash chain, idempotency store (Redis + PostgreSQL), double-entry ledger
**Confidence:** HIGH overall — all critical patterns verified against official sources or existing codebase

---

## Summary

Phase 2 builds the transaction backbone. It has four distinct technical concerns that must compose correctly:
(1) a transaction entity with a strict lifecycle state machine, (2) an append-only event log with a SHA-256
hash chain linking each event to the previous, (3) an idempotency store using Redis as primary with PostgreSQL
table as the durable fallback, and (4) a double-entry ledger that records balanced debit/credit pairs for every
state transition that moves money.

The key architectural insight is that **none of these four concerns require a new framework**. The state machine
is a Java enum with a guarded `transitionTo()` method. The event log is a JPA entity annotated `@Immutable`
with inserts-allowed but updates silently ignored. The idempotency key check uses
`StringRedisTemplate.opsForValue().setIfAbsent()` — a single atomic NX+EX call — with the existing
`main.idempotency_key` table (V2 migration already applied in Phase 1) as the durable fallback. The ledger
is two insert-only rows (`DEBIT` / `CREDIT`) per money-moving event.

Three new Maven dependencies are needed: `spring-boot-starter-data-redis` (includes Lettuce automatically),
`commons-pool2` (required for Lettuce connection pooling), and `org.testcontainers:testcontainers` for
the Redis container in integration tests (already transitively present via `spring-boot-testcontainers`).

**Primary recommendation:** Keep all four concerns inside the `transaction` module with the layered package
convention already established (`api/`, `contract/`, `service/`, `repo/`). Do not introduce Spring State
Machine — the overhead is unjustified for a six-state lifecycle. Do not use Axon, EventStore, or any
event-sourcing framework — this is a single append-only table with a hash chain column.

---

## Standard Stack

The project already has most dependencies. Three additions are needed.

### Already in pom.xml (no additions needed for these)

| Library | Version | Purpose | Why Available |
|---------|---------|---------|--------------|
| `spring-boot-starter-data-jpa` | 3.5.11 (managed) | `Transaction`, `PaymentEventLog`, `LedgerEntry` JPA entities | Already present |
| `flyway-database-postgresql` | managed | V3, V4 schema migrations | Already present |
| `commons-codec` (DigestUtils) | 1.19.0 | `sha256Hex()` for hash chain computation | Already present; used by ApiKeyService |
| `spring-boot-starter-data-jpa` | 3.5.11 | State stored as `@Enumerated(EnumType.STRING)` | Already present |
| `hibernate-envers` | 6.6.14.Final | Audit trail on Transaction entity changes | Already present |
| `micrometer-tracing-bridge-otel` | managed | Propagating `trace_id` via MDC and OTel spans | Already present |
| `spring-boot-starter-test` + `spring-boot-testcontainers` | managed | Base test infrastructure | Already present |
| `org.testcontainers:postgresql` | managed | PostgreSQL Testcontainer | Already present |

### New Dependencies Required (3 additions)

| Library | Version | Purpose | Why Needed |
|---------|---------|---------|------------|
| `spring-boot-starter-data-redis` | 3.5.11 (managed) | `StringRedisTemplate` for idempotency NX+EX | Idempotency primary store; not in pom.xml yet |
| `org.apache.commons:commons-pool2` | managed by Spring Boot | Lettuce connection pooling | Required for `LettucePoolingClientConfiguration`; `setIfAbsent` is non-blocking by default but pool is needed for high throughput |
| `org.testcontainers:testcontainers` (generic) | managed | `GenericContainer<>("redis:7")` for Redis IT | Need a Redis container for `IdempotencyStoreIT` |

Note: `spring-boot-starter-data-redis` transitively includes `lettuce-core`; no separate Lettuce dependency.

**Installation:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
<!-- Test scope: Redis container for IT tests -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
```

application.yaml additions:
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2
```

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Java enum state machine | Spring State Machine (spring-statemachine) | Spring State Machine adds significant configuration overhead (StateMachineConfigurer, builder, persist adapter) for a 6-state lifecycle. The enum approach is type-safe, trivially testable, and requires no new dependency. Use Spring State Machine only if the state graph has complex concurrent regions or cross-node persistence. |
| `@Immutable` Hibernate entity for event log | JPA repository with `@PreUpdate` guard | `@Immutable` tells Hibernate not to track dirty state on existing rows — no update SQL is ever generated. The guard approach is application-only and can be bypassed by EntityManager flush. |
| Redis primary + PostgreSQL fallback | Redis only | Redis can restart; the `main.idempotency_key` table (already created in V2 migration) ensures durability. Both stores must be checked in order. |
| Two DEBIT/CREDIT insert rows per ledger event | A `debit_amount` + `credit_amount` on a single row | Two-row model is the accounting standard (double-entry). Single-row model breaks double-entry invariant and complicates balance queries. |

---

## Architecture Patterns

### Recommended Project Structure

```
src/main/java/com/softropic/payam/
└── transaction/                    # NEW module for Phase 2
    ├── api/
    │   └── (Phase 5 — payment orchestration adds controllers here)
    ├── contract/
    │   ├── TransactionStatus.java  # INITIATED → ... → SUCCESS|FAILED|REVERSED
    │   ├── TransactionEventType.java  # PAYMENT_INITIATED, FRAUD_CHECK_PASSED, etc.
    │   ├── LedgerDirection.java    # DEBIT, CREDIT
    │   └── TransactionDto.java     # outgoing DTO (Phase 5)
    ├── service/
    │   ├── TransactionService.java       # state transition orchestrator
    │   ├── IdempotencyService.java       # Redis + PostgreSQL check
    │   └── LedgerService.java            # double-entry writes
    ├── repo/
    │   ├── Transaction.java              # @Entity, holds current status
    │   ├── TransactionRepository.java
    │   ├── PaymentEventLog.java          # @Entity @Immutable, append-only
    │   ├── PaymentEventLogRepository.java
    │   ├── LedgerEntry.java              # @Entity @Immutable, two rows per event
    │   ├── LedgerEntryRepository.java
    │   ├── IdempotencyKey.java           # @Entity for PostgreSQL fallback
    │   └── IdempotencyKeyRepository.java
    └── config/
        └── RedisConfig.java              # StringRedisTemplate bean (if custom needed)
```

### Pattern 1: Enum State Machine with Guarded Transitions

**What:** `TransactionStatus` enum declares which transitions are allowed from each state.
Attempting an invalid transition throws `IllegalStateTransitionException`.

**When to use:** Any service method that moves a Transaction between states calls
`status.transitionTo(next)` before persisting. The service never sets status directly.

```java
// Source: Baeldung "Implementing Simple State Machines with Java Enums" +
//         existing EntityStatus pattern in this codebase
public enum TransactionStatus {

    INITIATED {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return Set.of(AUTH_PENDING, FAILED);
        }
    },
    AUTH_PENDING {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return Set.of(AUTHORIZED, FAILED);
        }
    },
    AUTHORIZED {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return Set.of(PROCESSING, FAILED);
        }
    },
    PROCESSING {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return Set.of(SUCCESS, FAILED, REVERSED);
        }
    },
    SUCCESS {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return Set.of();  // terminal state
        }
    },
    FAILED {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return Set.of();  // terminal state
        }
    },
    REVERSED {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return Set.of();  // terminal state
        }
    };

    public abstract Set<TransactionStatus> allowedTransitions();

    public TransactionStatus transitionTo(TransactionStatus next) {
        if (!allowedTransitions().contains(next)) {
            throw new IllegalStateTransitionException(
                "Cannot transition from " + this + " to " + next);
        }
        return next;
    }
}
```

### Pattern 2: Transaction Entity (mutable, tracks current state)

```java
// Source: follows existing Tenant entity pattern — extends AbstractAuditingEntity,
//         schema = main, @Tsid id, @Enumerated(EnumType.STRING)
@Audited
@Entity
@Table(name = "transaction", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction extends AbstractAuditingEntity {

    @Column(name = "transaction_id", unique = true, nullable = false, updatable = false)
    private String transactionId;          // UUID assigned at INITIATED

    @Column(name = "trace_id", nullable = false, updatable = false)
    private String traceId;               // from Micrometer MDC at creation

    @Column(name = "external_reference", length = 255)
    private String externalReference;     // client-provided reference

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;                // FK to main.tenant(id)

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_status", nullable = false)
    @Builder.Default
    private TransactionStatus txStatus = TransactionStatus.INITIATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private MobilePaymentProvider provider;

    @Column(name = "amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;              // ISO 4217 e.g. "XAF"

    @Column(name = "provider_ref")
    private String providerRef;           // provider's own transaction reference

    // txStatus setter must go through TransactionStatus.transitionTo()
    public void applyTransition(TransactionStatus next) {
        this.txStatus = this.txStatus.transitionTo(next);
    }
    // ... getters
}
```

### Pattern 3: Append-Only Event Log with SHA-256 Hash Chain

**What:** `PaymentEventLog` is marked `@Immutable` (Hibernate). Every state transition appends
a new row. The `event_hash` column stores `SHA-256(eventData + previousHash)`. The first event
uses the sentinel string `"GENESIS"` as `previousHash`.

**Key insight on hash input:** To prevent false-positive tamper alerts, the hash input must be
deterministic. Use a fixed concatenation of specific fields (not JSON serialization, which is
not order-stable) as shown below.

```java
// Source: sha256Hex verified against Apache Commons Codec 1.21.0 official docs;
//         @Immutable behavior verified against Hibernate docs (Vlad Mihalcea)
@Entity
@Immutable   // org.hibernate.annotations.Immutable — no UPDATE ever generated
@Table(name = "payment_event_log", schema = "main")
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEventLog {

    @Id @Tsid
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(name = "trace_id", nullable = false)
    private String traceId;

    @Column(name = "external_reference")
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private TransactionEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_from")
    private TransactionStatus statusFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_to", nullable = false)
    private TransactionStatus statusTo;

    @Column(name = "actor", nullable = false)        // "system" | "provider" | tenantRef
    private String actor;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;                          // JSON blob (use JsonUtil.toJson())

    @Column(name = "previous_hash", nullable = false)
    private String previousHash;                      // "GENESIS" or prior event's hash

    @Column(name = "event_hash", nullable = false)
    private String eventHash;                         // SHA-256 of canonical fields

    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    // Factory method — always compute hash at construction
    public static PaymentEventLog create(String transactionId,
                                         String traceId,
                                         String externalReference,
                                         TransactionEventType eventType,
                                         TransactionStatus statusFrom,
                                         TransactionStatus statusTo,
                                         String actor,
                                         String metadata,
                                         String previousHash) {
        String canonical = transactionId + "|" + eventType.name() + "|"
            + (statusFrom != null ? statusFrom.name() : "null") + "|"
            + statusTo.name() + "|" + actor + "|" + previousHash;
        String hash = DigestUtils.sha256Hex(canonical);

        PaymentEventLog log = new PaymentEventLog();
        log.transactionId    = transactionId;
        log.traceId          = traceId;
        log.externalReference = externalReference;
        log.eventType        = eventType;
        log.statusFrom       = statusFrom;
        log.statusTo         = statusTo;
        log.actor            = actor;
        log.metadata         = metadata;
        log.previousHash     = previousHash;
        log.eventHash        = hash;
        log.createdDate      = Instant.now();
        return log;
    }
}
```

**Hash chain lookup** (for appending the next event):

```java
// Repository: find most recent event hash for a transaction
@Query("SELECT e.eventHash FROM PaymentEventLog e WHERE e.transactionId = :txId " +
       "ORDER BY e.createdDate DESC LIMIT 1")
Optional<String> findLatestHashByTransactionId(@Param("txId") String txId);
```

When no prior event exists, use `"GENESIS"` as the `previousHash`.

### Pattern 4: Idempotency Store — Redis Primary, PostgreSQL Fallback

**What:** On every write operation, check Redis first with an atomic NX+EX `setIfAbsent`.
If the key already exists in Redis, return the cached response. If Redis is unavailable,
fall back to `main.idempotency_key` table (already created in V2 migration).

**Phase 2 scope:** IdempotencyService handles the check+store. Phase 3+ pass the
`Idempotency-Key` header through `TransactionService`.

```java
// Source: StringRedisTemplate.opsForValue().setIfAbsent() verified against
//         Spring Data Redis 4.0.3 official JavaDoc (ValueOperations API)
@Service
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redis;
    private final IdempotencyKeyRepository repo;

    public IdempotencyService(StringRedisTemplate redis, IdempotencyKeyRepository repo) {
        this.redis = redis;
        this.repo  = repo;
    }

    /**
     * Returns the previously cached response body if this key was seen before.
     * Returns empty if this is the first time the key is seen (key is now reserved).
     *
     * @param tenantId        tenant owning this key (for namespace isolation)
     * @param idempotencyKey  client-supplied Idempotency-Key header value
     */
    public Optional<CachedResponse> checkAndReserve(Long tenantId, String idempotencyKey) {
        String redisKey = KEY_PREFIX + tenantId + ":" + idempotencyKey;

        try {
            // Atomic NX+EX — returns true if key was absent (first call)
            Boolean wasAbsent = redis.opsForValue()
                .setIfAbsent(redisKey, "RESERVED", TTL);

            if (Boolean.FALSE.equals(wasAbsent)) {
                // Key existed in Redis → duplicate request
                String cached = redis.opsForValue().get(redisKey);
                return Optional.ofNullable(cached)
                    .filter(v -> !"RESERVED".equals(v))
                    .map(CachedResponse::fromJson);
            }
            // Key was absent → first time seen; proceed with processing
            return Optional.empty();

        } catch (Exception redisFailure) {
            // Redis unavailable → fall back to PostgreSQL
            return repo.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .filter(k -> k.getResponseBody() != null)
                .map(k -> new CachedResponse(k.getHttpStatus(), k.getResponseBody()));
        }
    }

    /**
     * Stores the final response in both Redis and PostgreSQL after successful processing.
     */
    public void store(Long tenantId, String idempotencyKey, int httpStatus, String responseBody) {
        String redisKey = KEY_PREFIX + tenantId + ":" + idempotencyKey;
        String jsonValue = CachedResponse.toJson(httpStatus, responseBody);

        // Update Redis with actual response (replaces "RESERVED" placeholder)
        redis.opsForValue().set(redisKey, jsonValue, TTL);

        // Upsert PostgreSQL fallback
        repo.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
            .ifPresentOrElse(
                existing -> {
                    existing.setResponseBody(responseBody);
                    existing.setHttpStatus(httpStatus);
                    repo.save(existing);
                },
                () -> repo.save(IdempotencyKey.builder()
                    .tenantId(tenantId)
                    .idempotencyKey(idempotencyKey)
                    .responseBody(responseBody)
                    .httpStatus(httpStatus)
                    .expiresAt(Instant.now().plus(TTL))
                    .build())
            );
    }
}
```

### Pattern 5: Double-Entry Ledger

**What:** Every state transition that moves money (AUTHORIZED → PROCESSING, PROCESSING → SUCCESS,
PROCESSING → REVERSED) inserts exactly two `LedgerEntry` rows per event: one DEBIT and one CREDIT.
The two rows must have matching `transaction_id` and `entry_group_id` so they can be verified as
a pair. Amount is always positive; direction (`DEBIT` / `CREDIT`) indicates the sign.

```java
// Source: canonical double-entry schema from gist.github.com/NYKevin/9433376 +
//         adapted to this project's @Tsid / AbstractAuditingEntity conventions
@Entity
@Immutable   // ledger entries are never updated — corrections are new entries
@Table(name = "ledger_entry", schema = "main")
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {

    @Id @Tsid
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "entry_group_id", nullable = false, updatable = false)
    private String entryGroupId;       // same UUID for DEBIT + CREDIT pair

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private LedgerDirection direction;  // DEBIT or CREDIT

    @Column(name = "account_code", nullable = false, length = 50)
    private String accountCode;        // e.g. "CUSTOMER_WALLET" or "PROVIDER_CLEARING"

    @Column(name = "amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;         // always > 0; direction encodes sign

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;
}
```

**LedgerService** always writes in pairs:

```java
// Pattern for posting a balanced pair
public void postEntry(String transactionId, Long tenantId,
                      BigDecimal amount, String currency) {
    String groupId = UUID.randomUUID().toString();
    Instant now = Instant.now();

    LedgerEntry debit = LedgerEntry.builder()
        .transactionId(transactionId)
        .entryGroupId(groupId)
        .tenantId(tenantId)
        .direction(LedgerDirection.DEBIT)
        .accountCode("CUSTOMER_WALLET")
        .amount(amount)
        .currency(currency)
        .createdDate(now)
        .build();

    LedgerEntry credit = LedgerEntry.builder()
        .transactionId(transactionId)
        .entryGroupId(groupId)
        .tenantId(tenantId)
        .direction(LedgerDirection.CREDIT)
        .accountCode("PROVIDER_CLEARING")
        .amount(amount)
        .currency(currency)
        .createdDate(now)
        .build();

    ledgerEntryRepository.saveAll(List.of(debit, credit));
}
```

### Recommended Flyway Migration Sequence

Next available version after Phase 1 is **V3** and **V4** (V1 = tenant schema, V2 = idempotency_key schema).

**V3__transaction_schema.sql** — transaction entity + event log:

```sql
CREATE TABLE main.transaction (
    id                  BIGINT PRIMARY KEY,
    transaction_id      VARCHAR(36) UNIQUE NOT NULL,
    trace_id            VARCHAR(255) NOT NULL,
    external_reference  VARCHAR(255),
    tenant_id           BIGINT NOT NULL REFERENCES main.tenant(id),
    tx_status           VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    status              VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',    -- AbstractAuditingEntity.status
    provider            VARCHAR(20) NOT NULL,
    amount              NUMERIC(20, 2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    provider_ref        VARCHAR(255),
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT
);

CREATE INDEX idx_transaction_tenant_id     ON main.transaction(tenant_id);
CREATE INDEX idx_transaction_external_ref  ON main.transaction(external_reference);
CREATE INDEX idx_transaction_provider_ref  ON main.transaction(provider_ref);

CREATE TABLE main.payment_event_log (
    id                  BIGINT PRIMARY KEY,
    transaction_id      VARCHAR(36) NOT NULL,
    trace_id            VARCHAR(255) NOT NULL,
    external_reference  VARCHAR(255),
    event_type          VARCHAR(50) NOT NULL,
    status_from         VARCHAR(20),
    status_to           VARCHAR(20) NOT NULL,
    actor               VARCHAR(100) NOT NULL,
    metadata            JSONB,
    previous_hash       VARCHAR(64) NOT NULL,
    event_hash          VARCHAR(64) NOT NULL,
    created_date        TIMESTAMP NOT NULL DEFAULT NOW()
    -- NO updated_by / modified columns — this is append-only
);

CREATE INDEX idx_event_log_transaction_id  ON main.payment_event_log(transaction_id);
CREATE INDEX idx_event_log_created_date    ON main.payment_event_log(created_date);
```

**V4__ledger_schema.sql** — double-entry ledger:

```sql
CREATE TABLE main.ledger_entry (
    id              BIGINT PRIMARY KEY,
    transaction_id  VARCHAR(36) NOT NULL,
    entry_group_id  VARCHAR(36) NOT NULL,
    tenant_id       BIGINT NOT NULL REFERENCES main.tenant(id),
    direction       VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    account_code    VARCHAR(50) NOT NULL,
    amount          NUMERIC(20, 2) NOT NULL CHECK (amount > 0),
    currency        CHAR(3) NOT NULL,
    created_date    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_transaction_id   ON main.ledger_entry(transaction_id);
CREATE INDEX idx_ledger_entry_group_id   ON main.ledger_entry(entry_group_id);
CREATE INDEX idx_ledger_tenant_id        ON main.ledger_entry(tenant_id);
```

### Pattern 6: Redis Testcontainer in TestConfig

The existing `TestConfig.java` must be extended to provide a Redis container for `IdempotencyService`
integration tests:

```java
// Source: Spring Boot official blog 2023/06/23 "Improved Testcontainers Support in Spring Boot 3.1"
// Pattern: GenericContainer with @ServiceConnection(name = "redis")
@Bean
@ServiceConnection(name = "redis")
GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);
}
```

**Important:** The `name = "redis"` attribute on `@ServiceConnection` is required for `GenericContainer`
because Spring Boot cannot infer the service type from a generic image name without it. `PostgreSQLContainer`
does not need this because it has its own typed container class.

### Anti-Patterns to Avoid

- **Computing hash inside the JPA entity lifecycle callbacks (`@PrePersist`)**: `@PrePersist` fires inside
  the Hibernate flush, and reading the previous event's hash inside that callback requires an additional
  query within the same flush. Compute the hash in the service layer before constructing the entity,
  then pass the computed hash to `PaymentEventLog.create()`. The entity constructor never calls the DB.

- **Using `tx_status` on the transaction entity directly as a field with a public setter**: All callers
  bypass the state machine guard. Always use `transaction.applyTransition(next)` which calls
  `TransactionStatus.transitionTo()`.

- **Reusing `AbstractAuditingEntity.status` column for transaction lifecycle status**: The superclass
  already declares a `status` column mapped to `EntityStatus` (ACTIVE/INACTIVE/DELETED). The transaction
  lifecycle status is a separate concern. Use `tx_status` column name (same pattern as `tenant_status`
  and `key_status` in Phase 1).

- **Storing the `Idempotency-Key` without tenant scoping**: A malicious or buggy tenant could match
  another tenant's key. The composite `UNIQUE (tenant_id, idempotency_key)` on `main.idempotency_key`
  (already created in V2) prevents this at the database layer. The Redis key must also be namespaced:
  `"idempotency:" + tenantId + ":" + idempotencyKey`.

- **Writing ledger entries without a `CHECK (amount > 0)` constraint**: The double-entry invariant
  depends on amounts being positive. Direction (`DEBIT`/`CREDIT`) encodes the sign. A negative amount
  silently corrupts balance queries.

- **Not persisting the idempotency_key row before calling the provider**: The correct order is:
  (1) persist INITIATED record, (2) persist idempotency key row, (3) call provider. If step 3 fails
  and the client retries, step 2 ensures the retry is detected before reaching the provider again.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SHA-256 hashing | Custom `MessageDigest` boilerplate | `DigestUtils.sha256Hex(String)` (commons-codec 1.19.0, already in pom.xml) | Already present; one-liner; accepts String directly — no byte[] conversion needed |
| State machine framework | Spring State Machine dependency | Java enum with `transitionTo()` method | 6 states, no concurrency regions, no cross-JVM persistence; SSM is justified only for 20+ states with complex guards |
| Append-only enforcement | `@EntityListeners` with `@PreUpdate` that throws | `@Immutable` (Hibernate annotation) | `@Immutable` prevents Hibernate from generating UPDATE SQL at the persistence layer — it is enforced below the application |
| Dual-store idempotency | Custom lock table | `StringRedisTemplate.setIfAbsent()` (NX+EX atomic) + existing `main.idempotency_key` table | Redis NX+EX is a single atomic call; the table already exists (V2 migration); nothing to hand-build |
| Account balance calculation | Running balance column | `SUM(amount) WHERE direction='CREDIT' - SUM(amount) WHERE direction='DEBIT'` aggregation query | Running balance is a denormalization that can go out of sync; aggregate query on indexed `transaction_id` is correct |
| Trace ID propagation | Custom thread-local trace store | MDC (already used by `TransactionIdProvider`) + Micrometer tracing (already in pom.xml) | Both are already wired; add `trace_id`, `transaction_id`, `external_reference` as MDC keys in the service |

---

## Common Pitfalls

### Pitfall 1: Hash Chain Broken by Non-Deterministic Input

**What goes wrong:** The `canonical` string fed to `sha256Hex()` includes fields that can vary
(timestamps, generated IDs, JVM object ordering). Two runs of the same event produce different
hashes. Verification fails even for unmodified data.

**Why it happens:** Using `toString()`, JSON serialization without key ordering, or including
`Instant.now()` in the hash input.

**How to avoid:** Hash only stable, business-meaningful fields. Use the fixed pipe-delimited
concatenation pattern: `transactionId + "|" + eventType + "|" + statusFrom + "|" + statusTo
+ "|" + actor + "|" + previousHash`. Never include `createdDate` or the entity's database `id`
in the hash input (those are assigned after construction).

**Warning signs:** `PaymentEventLogVerificationService` reports tampered events on rows that
were never modified.

---

### Pitfall 2: Race Condition in Idempotency Check-Then-Set

**What goes wrong:** Service checks Redis for the idempotency key (`exists(key)` returns false),
then performs the business logic, then stores the result. Two concurrent requests both see the
key absent and both proceed to call the provider — resulting in a double charge.

**Why it happens:** Check and set are two separate operations with a gap between them.

**How to avoid:** Use `setIfAbsent(key, "RESERVED", TTL)` as a single atomic NX+EX Redis command.
The Boolean return value of `setIfAbsent` is the gate: `false` = duplicate, `true` = first call.
There is no separate check step.

**Warning signs:** Duplicate provider calls in integration tests that fire two concurrent requests
with the same idempotency key.

---

### Pitfall 3: V4 Migration with `status` Column Missing on `ledger_entry`

**What goes wrong:** `LedgerEntry` extends `AbstractAuditingEntity` which declares a `status`
column mapped to `EntityStatus`. If `ledger_entry` DDL omits the `status` column, Hibernate
throws `Schema-validation: missing column` on startup.

**Why it happens:** `LedgerEntry` is intended to be minimal/immutable, so developers forget
that the superclass adds `status`, `created_by`, `created_date`, `last_modified_by`,
`last_modified_date`, `request_id`, `session_id`.

**How to avoid:** Two options: (A) Do NOT extend `AbstractAuditingEntity` for `LedgerEntry` and
`PaymentEventLog` — extend `BaseEntity` only and add `created_date` manually. (B) Extend
`AbstractAuditingEntity` and include all its columns in the DDL. Option A is preferred because
it avoids hibernate-envers audit table creation for these immutable tables.

**Warning signs:** Startup fails with `Schema-validation: missing column [status] in table [main.ledger_entry]`.

---

### Pitfall 4: Transaction Entity `tx_status` vs. `AbstractAuditingEntity.status`

**What goes wrong:** Developer maps the transaction lifecycle to `AbstractAuditingEntity.status`
(EntityStatus). The `status` column can only hold `ACTIVE`, `INACTIVE`, `DELETED` — it cannot
hold `INITIATED`, `AUTH_PENDING`, etc.

**Why it happens:** Pattern from Phase 1 where `tenant_status` was added as a separate column
alongside `status` is not remembered when writing Transaction.

**How to avoid:** This is the established Phase 1 decision: domain-specific lifecycle columns
use a distinct column name (`tx_status` for transactions) and a distinct enum
(`TransactionStatus`). The `status` column from `AbstractAuditingEntity` retains its
`EntityStatus` value (typically `ACTIVE`).

**Warning signs:** `TransactionStatus` enum values cause `DataIntegrityViolationException`
when persisted because the `status` check constraint or enum mapping rejects them.

---

### Pitfall 5: Lettuce Connection Pooling Not Active

**What goes wrong:** Under load, Lettuce connections are not pooled. Requests queue on a single
connection, causing timeout failures on `setIfAbsent` calls.

**Why it happens:** `commons-pool2` not added to pom.xml, or `spring.data.redis.lettuce.pool`
properties not configured.

**How to avoid:** Add `commons-pool2` (unversioned — Spring Boot manages the version) to pom.xml.
Add pool properties to `application.yaml`. Verify with an integration test using concurrent requests.

**Warning signs:** Redis operations intermittently time out under load; Lettuce logs
"Connection pool exhausted".

---

### Pitfall 6: `@Immutable` Does Not Throw on Update Attempts

**What goes wrong:** A developer calls `repo.save(existingEventLogRow)` and expects an exception.
Hibernate silently ignores the update. The row is not modified, but no error is raised. This can
mask bugs where code incorrectly attempts to update event log rows.

**Why it happens:** Hibernate `@Immutable` behavior is silent suppression of updates.

**How to avoid:** For `PaymentEventLog` and `LedgerEntry`, make all fields `final` (or Lombok
`@Getter` only, no `@Setter`). The repository should not expose a `save(existing)` path — only
`save(new)`. Consider adding a `@PreUpdate` EntityListener that throws `UnsupportedOperationException`
as a belt-and-suspenders guard at the application layer.

---

## Code Examples

### Hash Chain — Computing the Next Hash

```java
// Source: DigestUtils.sha256Hex(String) — verified from Apache Commons Codec 1.21.0 API docs
//         (String overload exists: sha256Hex accepts String directly, no byte[] conversion)
import org.apache.commons.codec.digest.DigestUtils;

public String computeEventHash(String transactionId,
                                TransactionEventType eventType,
                                TransactionStatus statusFrom,
                                TransactionStatus statusTo,
                                String actor,
                                String previousHash) {
    String canonical = transactionId + "|"
        + eventType.name() + "|"
        + (statusFrom != null ? statusFrom.name() : "null") + "|"
        + statusTo.name() + "|"
        + actor + "|"
        + previousHash;
    return DigestUtils.sha256Hex(canonical);
}

// Genesis case (first event for a transaction):
String firstHash = computeEventHash(txId, eventType, null, INITIATED, "system", "GENESIS");
```

### Idempotency — Atomic NX+EX Check

```java
// Source: Spring Data Redis 4.0.3 official JavaDoc — ValueOperations.setIfAbsent(K, V, Duration)
//         equivalent to Redis SET key value NX EX <seconds>
import org.springframework.data.redis.core.StringRedisTemplate;

// Returns true if key was absent (first call — proceed with processing)
// Returns false if key already existed (duplicate — return cached response)
Boolean wasAbsent = stringRedisTemplate.opsForValue()
    .setIfAbsent("idempotency:" + tenantId + ":" + key, "RESERVED", Duration.ofHours(24));
```

### Redis Testcontainer in TestConfig

```java
// Source: Spring Boot 3.1 official blog + Baeldung Redis Testcontainers article
// name = "redis" is REQUIRED for GenericContainer — Spring Boot cannot infer from image name
@Bean
@ServiceConnection(name = "redis")
GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);
}
```

### MDC Propagation for Trace IDs

```java
// Source: existing TransactionIdProvider.java in this project
// Pattern: put trace_id, transaction_id, external_reference into MDC for every operation
import org.slf4j.MDC;

// In TransactionService.initiate():
MDC.put("trace_id", tracer.currentSpan().context().traceId());  // from Micrometer
MDC.put("transaction_id", transaction.getTransactionId());
MDC.put("external_reference", transaction.getExternalReference());
// ... proceed with business logic; clear in finally or via MdcDecorator
```

### Balance Query for Ledger Verification

```java
// Source: adapted from gist.github.com/NYKevin/9433376 double-entry schema pattern
@Query("SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) " +
       "FROM LedgerEntry e WHERE e.transactionId = :txId")
BigDecimal computeBalance(@Param("txId") String txId);

// At the end of a balanced pair: computeBalance should return 0
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Spring State Machine for payment workflows | Java enum with guarded `transitionTo()` | 2023+ (community consensus) | No extra dependency; 5x less configuration; trivially unit-testable |
| Mutable status field directly updated | Immutable event log + current status derived or separately tracked | Standard for fintech since 2018 | Audit trail is the source of truth; current status row is a projection |
| Raw Redis `set` + separate `exists` check | `setIfAbsent(key, value, Duration)` atomic NX+EX | Spring Data Redis 2.1+ | Eliminates TOCTOU race condition |
| `BIGSERIAL` auto-increment IDs | `@Tsid` (hypersistence-utils) generating sortable Long IDs | Used throughout Phase 1 | Already established in this project; do not use SERIAL |
| Custom JSON serialization for hash input | Deterministic pipe-delimited canonical string | N/A | Avoids key-ordering bugs in JSON; simpler and faster |

**Deprecated/outdated approaches that must not be used:**
- `BIGSERIAL` / `SERIAL` in DDL: Project uses `@Tsid` and `BIGINT` without sequences. See V1 migration.
- Extending `AbstractAuditingEntity` for immutable tables: Use `BaseEntity` only to avoid Hibernate Envers
  audit table creation and to avoid the `status` column clash.

---

## Open Questions

1. **`PaymentEventLog` and `LedgerEntry` — extend `BaseEntity` or `AbstractAuditingEntity`?**
   - What we know: `AbstractAuditingEntity` adds `status` (EntityStatus), auditing columns, Hibernate
     Envers `@Audited`, and two EntityListeners. For immutable tables this is overhead.
   - What's unclear: Whether the Hibernate Envers audit tables for `PaymentEventLog` and `LedgerEntry`
     would be correctly named and not interfere with the append-only design.
   - Recommendation: Extend `BaseEntity` only for `PaymentEventLog` and `LedgerEntry`. Add `created_date`
     as a manually-mapped `@Column`. This is cleaner, avoids Envers, and avoids the `status` column clash.
     The `Transaction` entity (which is mutable and tracks current state) should extend `AbstractAuditingEntity`.

2. **`JSONB` metadata column on `payment_event_log` — driver compatibility**
   - What we know: `hypersistence-utils-hibernate-63` is already in pom.xml at version 3.9.10, which
     provides `@Type(io.hypersistence.utils.hibernate.type.json.JsonBinaryType)` for PostgreSQL JSONB.
   - What's unclear: Whether Phase 2 should use `@Type(JsonBinaryType.class)` on the metadata field,
     or simply use `TEXT` for the metadata column and serialize/deserialize in the service layer.
   - Recommendation: Use `TEXT` for `metadata` in Phase 2. Avoid adding `@Type(JsonBinaryType.class)`
     until PostgreSQL JSONB queries (filtering by metadata field) are needed (likely Phase 7 fraud engine).
     Using `TEXT` keeps the entity simpler and the Flyway DDL consistent with the rest of the schema.

3. **`IdempotencyKey` entity — does it need its own `@Tsid` or `created_date` handling?**
   - What we know: V2 migration defines `id BIGINT PRIMARY KEY` (TSID-compatible) and
     `created_date TIMESTAMP NOT NULL DEFAULT NOW()`, plus `expires_at`. The table does NOT include
     `AbstractAuditingEntity` columns.
   - Recommendation: The `IdempotencyKey` JPA entity should extend `BaseEntity` only (not
     `AbstractAuditingEntity`) and map `created_date` and `expires_at` manually. This matches the V2
     DDL which was written without audit columns.

---

## Sources

### Primary (HIGH confidence)

- Existing codebase: `AbstractAuditingEntity.java`, `BaseEntity.java`, `EntityStatus.java`,
  `Tenant.java`, `TenantApiKey.java`, `ApiKeyService.java`, `TransactionIdProvider.java`,
  `TenantProvisioningIT.java`, `TestConfig.java`, `V1__tenant_schema.sql`, `V2__idempotency_key_schema.sql`,
  `application.yaml`, `pom.xml` — all read directly from the repository
- Apache Commons Codec 1.21.0 official JavaDoc (WebFetch) — confirmed `sha256Hex(String)` signature
- Spring Data Redis 4.0.3 official JavaDoc (WebFetch) — confirmed `setIfAbsent(K, V, Duration)` signature
  for atomic NX+EX
- Spring Data Redis drivers documentation (WebFetch) — confirmed `commons-pool2` required for Lettuce pooling
- Hibernate `@Immutable` documentation (verified via WebSearch + Vlad Mihalcea article) — confirmed
  inserts allowed, updates silently ignored

### Secondary (MEDIUM confidence)

- Spring Boot official blog (2023/06/23) "Improved Testcontainers Support in Spring Boot 3.1" — confirmed
  `@ServiceConnection(name = "redis")` pattern for `GenericContainer`
- WebSearch "spring-boot-starter-data-redis 3.5 includes lettuce-core automatically" — confirmed Lettuce
  is bundled; no separate lettuce-core dependency needed
- gist.github.com/NYKevin/9433376 — PostgreSQL double-entry schema (WebFetch) — confirmed two-row pattern
  (one entry per debit, one per credit), `amount > 0` check constraint, and balance via `SUM` aggregation

### Tertiary (LOW confidence — noted for awareness)

- dev.to/veritaschain hash chain article (JavaScript, not Java) — architecture valid but code requires
  Java translation; specific pipe-delimiter canonical pattern in this research is derived, not directly
  copied from the article
- bytegoblin.io Spring Boot 3 Redis idempotency article — weak implementation (no atomic NX+EX);
  discarded in favor of the official ValueOperations JavaDoc which confirms the correct API

---

## Metadata

**Confidence breakdown:**

| Area | Level | Reason |
|------|-------|--------|
| Standard stack additions (Redis, commons-pool2) | HIGH | Verified from official docs and mvnrepository |
| Flyway migration version numbers (V3, V4) | HIGH | V1 and V2 confirmed by reading actual migration files |
| Enum state machine pattern | HIGH | Verified against Baeldung + existing EntityStatus pattern in codebase |
| `@Immutable` entity behavior | HIGH | Verified via Vlad Mihalcea article + Hibernate documentation (inserts allowed, updates suppressed) |
| SHA-256 hash chain canonical string | MEDIUM | Algorithm verified; specific pipe-delimiter format is a design choice, not mandated by a spec |
| `setIfAbsent(K, V, Duration)` atomicity | HIGH | Verified in Spring Data Redis 4.0.3 JavaDoc — maps to Redis SET NX EX |
| Double-entry ledger two-row pattern | HIGH | Verified from NYKevin PostgreSQL gist + double-entry accounting literature |
| `@ServiceConnection(name = "redis")` for GenericContainer | MEDIUM | Confirmed by Spring Boot blog; the `name = "redis"` requirement confirmed by search results citing the official docs |
| `BaseEntity` vs `AbstractAuditingEntity` for immutable tables | MEDIUM | Derived from existing code analysis; no official Spring/Hibernate doc prescribes this; recommendation is defensive |

**Research date:** 2026-03-23
**Valid until:** 2026-09-01 (Spring Data Redis APIs, Hibernate @Immutable, and DigestUtils are stable)
