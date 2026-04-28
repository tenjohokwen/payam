# Phase 50: Schema & Balance Infrastructure - Research

**Researched:** 2026-04-24
**Domain:** Flyway schema migration, JPA pessimistic locking, disbursement state machine
**Confidence:** HIGH

---

## Summary

Phase 50 creates the database foundation for the v10 disbursement system. It has three distinct deliverables: (1) a Flyway migration that creates `main.disbursement`, `main.disbursement_aud`, and `main.merchant_wallet_balance` tables; (2) a `WalletBalanceService` with a `checkAndReserve()` method that uses `SELECT FOR UPDATE` to prevent concurrent overdraft; and (3) a `DisbursementStatus` enum with the required values including the terminal `EXPIRED` state.

**Critical discovery:** Flyway V26 and V27 are already taken by the security module and audit schema gap-closure migrations shipped in prior milestones. The disbursement tables migration must be **V28**. The STATE.md note saying "next is V26" is outdated and reflects the state before those security migrations were applied.

The wallet balance locking pattern is established in the codebase: `TransactionRepository.findByTransactionIdForUpdate()` uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` and `FOR UPDATE SKIP LOCKED`. STATE.md explicitly calls out that `@Lock(PESSIMISTIC_WRITE)` is required (not optimistic retry). The concurrency test pattern using `CyclicBarrier` + `ExecutorService` already exists in `ApiKeyConcurrentRotationIT` and is the model for `WalletBalanceConcurrencyIT`.

**Primary recommendation:** Create V28 migration, a `disbursement` package modeled on existing domain structure, a `MerchantWalletBalance` JPA entity with `@Lock(PESSIMISTIC_WRITE)` on its repository query, and a `WalletBalanceService` that wraps reserve/release in `TransactionTemplate` (not `@Transactional`) consistent with the codebase pattern.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| BAL-01 | System checks MERCHANT_WALLET balance covers `principal + fee` before any provider call, using pessimistic write lock (`SELECT FOR UPDATE`); returns `422 INSUFFICIENT_BALANCE` if insufficient | `@Lock(PESSIMISTIC_WRITE)` pattern established in `TransactionRepository`; `TransactionTemplate` pattern established in `OrangeMoneyPort` and `MtnMoMoPort`; HTTP 422 response pattern established in `PaymentResource` |
| BAL-02 | System releases reserved balance back to MERCHANT_WALLET when disbursement reaches FAILED terminal state | `release()` method on `WalletBalanceService`; must be callable from orchestrator on FAILED transition; balance arithmetic: `balance += reservedAmount` |
| BAL-03 | System sets disbursement status to EXPIRED (not FAILED) when provider accepted but internal error prevents clean state update; reserved balance held pending manual ops resolution; ops alert triggered | `DisbursementStatus.EXPIRED` as distinct terminal state; must be in illegal-transition guard; `AlertService` exists in `com.softropic.payam.alert` package |
</phase_requirements>

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Flyway | Managed by Spring Boot 3.5.11 BOM | Schema migration | Already in project; `flyway-core` + `flyway-database-postgresql` in pom.xml |
| Spring Data JPA | Managed by Spring Boot 3.5.11 BOM | JPA repositories with `@Lock` | Already in project; `@Lock(PESSIMISTIC_WRITE)` used in `TransactionRepository` |
| Hibernate Envers | 6.6.14.Final | `_aud` audit tables | Already in project; all `AbstractAuditingEntity` subclasses are `@Audited` |
| Hypersistence TSID | 3.9.10 | `@Tsid` ID generation | Project standard ID strategy for all entities; `BaseEntity` uses `@Tsid` |
| Lombok | Managed by Spring Boot BOM | `@SuperBuilder`, `@Getter`, `@NoArgsConstructor` | Project standard; all entities use it |
| Testcontainers | Managed by Spring Boot BOM | Real PostgreSQL for IT | Already in project; `postgres:14.18` image in `TestConfig` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| TransactionTemplate | Spring Framework (managed) | Programmatic transaction boundary | Mandatory when a service method must be non-`@Transactional` but still needs a transaction — existing pattern in all port classes |
| AssertJ | Managed by Spring Boot BOM | Test assertions | Project standard assertion library |
| JUnit 5 | Managed by Spring Boot BOM | Test framework | Project standard; all ITs use `@SpringBootTest` |

### No new dependencies required
All libraries needed for this phase are already declared in `pom.xml`.

---

## Architecture Patterns

### Recommended Project Structure

New disbursement package following the existing domain module layout:

```
src/main/java/com/softropic/payam/
└── disbursement/
    ├── contract/
    │   └── DisbursementStatus.java        # enum with state machine guard
    ├── repo/
    │   ├── Disbursement.java              # JPA entity, extends AbstractAuditingEntity
    │   ├── DisbursementRepository.java    # JpaRepository
    │   ├── MerchantWalletBalance.java     # JPA entity, extends AbstractAuditingEntity
    │   └── MerchantWalletBalanceRepository.java  # JpaRepository with @Lock query
    └── service/
        └── WalletBalanceService.java      # checkAndReserve() and release()
```

SQL migration:
```
src/main/resources/db/migration/
└── V28__disbursement_schema.sql
```

Tests:
```
src/test/java/com/softropic/payam/disbursement/
└── WalletBalanceConcurrencyIT.java
```

### Pattern 1: Flyway Migration (V28)

**What:** Creates three tables — `main.disbursement`, `main.disbursement_aud`, `main.merchant_wallet_balance` — after V27.
**When to use:** Next migration after V27 (the last existing migration as of 2026-04-24).

**Critical:** V26 and V27 are already taken. The next available version is **V28**. Using V26 would cause Flyway checksum conflict and fail startup.

The disbursement table tracks individual payout requests. The merchant_wallet_balance table tracks the pre-funded wallet used for the balance gate.

```sql
-- V28__disbursement_schema.sql
-- Creates: main.disbursement, main.disbursement_aud, main.merchant_wallet_balance

-- ============================================================
-- Table: main.disbursement
-- ============================================================
CREATE TABLE IF NOT EXISTS main.disbursement (
    id                   BIGINT          NOT NULL,
    disbursement_id      VARCHAR(36)     NOT NULL,
    tenant_id            BIGINT          NOT NULL,
    recipient_msisdn     VARCHAR(50)     NOT NULL,
    amount               NUMERIC(20, 2)  NOT NULL,
    currency             CHAR(3)         NOT NULL DEFAULT 'XAF',
    reference            VARCHAR(255)    NOT NULL,
    description          VARCHAR(500),
    status               VARCHAR(30)     NOT NULL,
    provider             VARCHAR(20),
    provider_ref         VARCHAR(255),
    idempotency_key      VARCHAR(255),
    reserved_amount      NUMERIC(20, 2),
    metadata             TEXT,
    created_by           VARCHAR(50),
    created_date         TIMESTAMP,
    last_modified_by     VARCHAR(50),
    last_modified_date   TIMESTAMP,
    request_id           VARCHAR(255),
    session_id           TEXT,
    CONSTRAINT pk_disbursement            PRIMARY KEY (id),
    CONSTRAINT uq_disbursement_id         UNIQUE (disbursement_id),
    CONSTRAINT chk_disbursement_amount_positive CHECK (amount > 0)
);

-- Index for tenant-scoped queries (GET /v1/disbursements)
CREATE INDEX IF NOT EXISTS idx_disbursement_tenant_id
    ON main.disbursement (tenant_id);

-- ============================================================
-- Table: main.disbursement_aud (Envers)
-- Mirrors @Audited fields on Disbursement entity.
-- ============================================================
CREATE TABLE IF NOT EXISTS main.disbursement_aud (
    id                   BIGINT          NOT NULL,
    rev                  INTEGER         NOT NULL REFERENCES main.revinfo(rev),
    revtype              SMALLINT,
    disbursement_id      VARCHAR(36),
    tenant_id            BIGINT,
    recipient_msisdn     VARCHAR(50),
    amount               NUMERIC(20, 2),
    currency             CHAR(3),
    reference            VARCHAR(255),
    description          VARCHAR(500),
    status               VARCHAR(30),
    provider             VARCHAR(20),
    provider_ref         VARCHAR(255),
    idempotency_key      VARCHAR(255),
    reserved_amount      NUMERIC(20, 2),
    metadata             TEXT,
    created_by           VARCHAR(50),
    created_date         TIMESTAMP,
    last_modified_by     VARCHAR(50),
    last_modified_date   TIMESTAMP,
    request_id           VARCHAR(255),
    session_id           TEXT,
    PRIMARY KEY (id, rev)
);

-- ============================================================
-- Table: main.merchant_wallet_balance
-- One row per tenant. balance is the AVAILABLE (unreserved) amount.
-- Version column for optimistic lock fallback (not primary strategy;
-- pessimistic write lock is the BAL-01 requirement).
-- ============================================================
CREATE TABLE IF NOT EXISTS main.merchant_wallet_balance (
    id                   BIGINT          NOT NULL,
    tenant_id            BIGINT          NOT NULL,
    balance              NUMERIC(20, 2)  NOT NULL DEFAULT 0,
    currency             CHAR(3)         NOT NULL DEFAULT 'XAF',
    version              BIGINT          NOT NULL DEFAULT 0,
    created_by           VARCHAR(50),
    created_date         TIMESTAMP,
    last_modified_by     VARCHAR(50),
    last_modified_date   TIMESTAMP,
    request_id           VARCHAR(255),
    session_id           TEXT,
    CONSTRAINT pk_merchant_wallet_balance PRIMARY KEY (id),
    CONSTRAINT uq_wallet_tenant_id        UNIQUE (tenant_id),
    CONSTRAINT chk_wallet_balance_non_negative CHECK (balance >= 0)
);
```

### Pattern 2: MerchantWalletBalance Entity with PESSIMISTIC_WRITE Lock

**What:** JPA entity with a `findByTenantIdForUpdate` repository method using `@Lock(PESSIMISTIC_WRITE)`.
**When to use:** This is the BAL-01 requirement. Must be used inside a `TransactionTemplate.execute()` block to ensure the lock is held for the duration of the check-and-decrement.

```java
// MerchantWalletBalanceRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM MerchantWalletBalance w WHERE w.tenantId = :tenantId")
Optional<MerchantWalletBalance> findByTenantIdForUpdate(@Param("tenantId") Long tenantId);
```

This follows exactly the same pattern as `TransactionRepository.findByTransactionIdForUpdate()`.

### Pattern 3: WalletBalanceService checkAndReserve() — TransactionTemplate, Not @Transactional

**What:** The service method that checks and decrements balance must use `TransactionTemplate` programmatically. The existing codebase rule is: methods that will be called from non-transactional contexts (like the orchestrator) and that need real transaction semantics must use `TransactionTemplate` because `@Transactional` on public methods called via the same Spring bean works, but the STATE.md says to use `TransactionTemplate` for orchestrator methods.

**Critical rule from STATE.md:** "No `@Transactional` on orchestrator methods that make HTTP calls — use `TransactionTemplate` (established pattern)". The `WalletBalanceService.checkAndReserve()` is called by the orchestrator BEFORE the HTTP call; it does not make HTTP calls itself. However, it needs to guarantee atomicity. Using `@Transactional` on `checkAndReserve()` is safe here because this is a pure DB service method — unlike port methods, it has no HTTP call within it.

**Recommended pattern:** `checkAndReserve()` is `@Transactional` (plain Spring service method), which is correct. The orchestrator wraps the entire pre-call sequence (checkAndReserve + disbursement row creation) in a `TransactionTemplate` if atomic grouping is needed. The `release()` method should also be `@Transactional`.

```java
// WalletBalanceService.java
@Service
public class WalletBalanceService {

    private final MerchantWalletBalanceRepository walletBalanceRepository;

    @Transactional
    public void checkAndReserve(Long tenantId, BigDecimal amount) {
        MerchantWalletBalance wallet = walletBalanceRepository
            .findByTenantIdForUpdate(tenantId)
            .orElseThrow(() -> new InsufficientBalanceException(
                "No wallet found for tenant: " + tenantId));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(
                "Insufficient balance: available=" + wallet.getBalance()
                    + " required=" + amount);
        }
        wallet.setBalance(wallet.getBalance().subtract(amount));
        wallet.setReservedAmount(wallet.getReservedAmount().add(amount));
    }

    @Transactional
    public void release(Long tenantId, BigDecimal amount) {
        MerchantWalletBalance wallet = walletBalanceRepository
            .findByTenantIdForUpdate(tenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No wallet found for tenant: " + tenantId));
        wallet.setBalance(wallet.getBalance().add(amount));
        wallet.setReservedAmount(wallet.getReservedAmount().subtract(amount));
    }
}
```

**Alternative consideration:** The `balance` column could mean "total balance" and "reserved" tracked separately. The success criterion says "wallet balance is identical before reservation and after release" — this means the stored `balance` should decrease on reserve and increase on release by the exact reserved amount. The `merchant_wallet_balance.balance` should represent the *available* (spendable) balance. The `reserved_amount` column tracks what is currently earmarked.

### Pattern 4: DisbursementStatus Enum with Terminal-State Guard

**What:** A standalone enum in `disbursement/contract/` following the `TransactionStatus` model.
**Required values:** `INITIATED`, `PENDING_CONFIRMATION`, `PROCESSING`, `SUCCESS`, `FAILED`, `EXPIRED`.
**Key rule:** `EXPIRED` must be in the illegal-transition guard. Both `SUCCESS`, `FAILED`, and `EXPIRED` are terminal — no transitions out.

```java
// DisbursementStatus.java
public enum DisbursementStatus {
    INITIATED {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.of(PENDING_CONFIRMATION, PROCESSING, FAILED);
        }
    },
    PENDING_CONFIRMATION {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.of(PROCESSING, EXPIRED, FAILED);
        }
    },
    PROCESSING {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.of(SUCCESS, FAILED, EXPIRED);
        }
    },
    SUCCESS {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.noneOf(DisbursementStatus.class); // terminal
        }
    },
    FAILED {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.noneOf(DisbursementStatus.class); // terminal
        }
    },
    EXPIRED {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.noneOf(DisbursementStatus.class); // terminal
        }
    };

    public abstract Set<DisbursementStatus> allowedTransitions();

    public DisbursementStatus transitionTo(DisbursementStatus next) {
        if (!allowedTransitions().contains(next)) {
            throw new IllegalStateTransitionException(
                "Invalid disbursement state transition: " + this + " -> " + next
                + ". Allowed: " + allowedTransitions());
        }
        return next;
    }
}
```

**Note on EXPIRED (BAL-03):** The distinction between `FAILED` (provider never accepted) and `EXPIRED` (provider accepted but internal error prevents clean state update, OR timeout on unconfirmed `PENDING_CONFIRMATION`) means reserved balance is NOT released on `EXPIRED`. Only `FAILED` triggers balance release.

### Pattern 5: Disbursement Entity

The `Disbursement` entity extends `AbstractAuditingEntity` (which extends `BaseEntity` with `@Tsid` ID). It is `@Audited` so Envers writes to `disbursement_aud`. It stores disbursement status as an enum.

```java
@Audited
@Entity
@Table(name = "disbursement", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Disbursement extends AbstractAuditingEntity {

    @Column(name = "disbursement_id", unique = true, nullable = false, updatable = false)
    private String disbursementId;  // UUID string, business key

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "recipient_msisdn", nullable = false)
    private String recipientMsisdn;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String reference;

    @Column
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisbursementStatus disbursementStatus = DisbursementStatus.INITIATED;

    @Enumerated(EnumType.STRING)
    @Column
    private MobilePaymentProvider provider;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "reserved_amount", precision = 20, scale = 2)
    private BigDecimal reservedAmount;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    public void applyTransition(DisbursementStatus next) {
        this.disbursementStatus = this.disbursementStatus.transitionTo(next);
    }
}
```

**Note on `status` column:** `AbstractAuditingEntity` already has a `status` column (`EntityStatus`). The disbursement-specific lifecycle status must use a different column name: `disbursement_status` in the SQL / `disbursementStatus` in Java. This avoids collision with the inherited `status` field.

### Pattern 6: WalletBalanceConcurrencyIT

Model after `ApiKeyConcurrentRotationIT`. Use a `CyclicBarrier` to release 20 threads simultaneously. Each thread calls `walletBalanceService.checkAndReserve(tenantId, amount)`. With balance = 1 × amount, exactly 1 thread must succeed and 19 must throw `InsufficientBalanceException`. Catch the exception in the test and count outcomes.

```java
// WalletBalanceConcurrencyIT.java pattern
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class WalletBalanceConcurrencyIT {

    @Autowired WalletBalanceService walletBalanceService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void concurrentReserve_exactlyOneSucceeds() throws Exception {
        // 1. Seed wallet with balance = 1000 XAF (enough for exactly 1 disbursement of 1000)
        // 2. CyclicBarrier with 20 threads, each calling checkAndReserve(tenantId, 1000)
        // 3. Assert exactly 1 success, 19 InsufficientBalanceException
        // 4. Assert wallet balance = 0 (not negative — no overdraft)
    }
}
```

### Anti-Patterns to Avoid

- **Using optimistic locking (`@Version` + retry loop) for the balance gate:** STATE.md explicitly states "optimistic retry allows second drain after first succeeds." Use `@Lock(PESSIMISTIC_WRITE)` only.
- **Annotating the orchestrator method with `@Transactional` when it makes HTTP calls:** The orchestrator pattern is `TransactionTemplate` for DB-only sections, with HTTP calls outside any transaction boundary. `WalletBalanceService` itself can use `@Transactional` since it has no HTTP calls.
- **Putting balance check logic outside a transaction:** The check and decrement must be atomic. Any gap between reading the balance and writing the new value allows a race.
- **Using the column name `status` for DisbursementStatus:** `AbstractAuditingEntity` owns `status`; use `disbursement_status`.
- **Assuming V26 is available:** V26 and V27 are taken. The migration must be V28.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| ID generation | Custom sequence or UUID | `@Tsid` (already in project) | All entities use this; consistent with BaseEntity |
| Audit tables | Manual trigger | Hibernate Envers (`@Audited`) | Already configured; Envers generates audit rows automatically |
| Transaction management | Manual `conn.begin()`/`conn.commit()` | `@Transactional` or `TransactionTemplate` | Spring manages connection pooling and rollback |
| Pessimistic locking SQL | Native `SELECT ... FOR UPDATE` manually | Spring Data `@Lock(PESSIMISTIC_WRITE)` | Same outcome; Spring Data handles provider differences |

---

## Common Pitfalls

### Pitfall 1: Wrong Flyway Migration Version Number
**What goes wrong:** Creating `V26__disbursement_schema.sql` causes Flyway startup failure with "checksum mismatch" because V26 already contains the security module schema.
**Why it happens:** STATE.md says "last migration is V25" but V26 and V27 were added in the security module (phases 41-49).
**How to avoid:** Create `V28__disbursement_schema.sql`. Verify by listing `src/main/resources/db/migration/` — highest existing version is V27.
**Warning signs:** Flyway throws `FlywayException: Validate failed: Detected applied migration not resolved locally` on startup.

### Pitfall 2: `status` Column Collision in Disbursement Entity
**What goes wrong:** Hibernate throws a schema validation error because `AbstractAuditingEntity` defines a `status` column and the Disbursement entity tries to map a second `status` column for `DisbursementStatus`.
**Why it happens:** `AbstractAuditingEntity` has `@Column(name = "status")` for `EntityStatus`. Subclass adds another field mapped to `status`.
**How to avoid:** Name the disbursement lifecycle column `disbursement_status` in both SQL and JPA (`@Column(name = "disbursement_status")`).
**Warning signs:** `SchemaValidationException: column [disbursement_status] in table [disbursement] is of wrong type` or duplicate column errors.

### Pitfall 3: Optimistic Lock Retry Allowing Double-Drain
**What goes wrong:** Using `@Version` + retry loop for the balance gate. Thread A reads balance=1000, thread B reads balance=1000, A writes 0 (success), B retries and reads balance=0 — but the retry loop may still proceed if the exception is mishandled.
**Why it happens:** Optimistic locking only fails on concurrent write conflicts, not on semantic check failures. With a retry, the loser can succeed on the second attempt after the balance has already been drained.
**How to avoid:** Use `@Lock(PESSIMISTIC_WRITE)`. The SELECT FOR UPDATE blocks thread B from even reading the row until thread A commits, ensuring B sees balance=0 and throws immediately without needing a retry.

### Pitfall 4: Balance Released for EXPIRED Status
**What goes wrong:** A disbursement that transitions to `EXPIRED` (BAL-03: provider accepted but internal error) triggers the balance release path. This means the funds are returned to the available balance while the provider may still execute the payout, causing overdraft.
**Why it happens:** Conflating `FAILED` (no provider call) with `EXPIRED` (provider accepted).
**How to avoid:** `WalletBalanceService.release()` must only be called for `FAILED` transitions, not `EXPIRED`. Document this explicitly in the service Javadoc.

### Pitfall 5: Missing Envers `_aud` Table Causing Startup Failure
**What goes wrong:** `@Audited` on `Disbursement` causes Hibernate Envers to expect `main.disbursement_aud` at startup. If the table is absent, Hibernate throws a schema validation exception.
**Why it happens:** Flyway migration creates the base table but not the `_aud` table.
**How to avoid:** Include `main.disbursement_aud` in V28. Follow V27's pattern (audit tables for all audited entities).

### Pitfall 6: TestDataCleaner Missing disbursement/wallet Tables
**What goes wrong:** Concurrency IT leaves wallet seed data between test methods, causing balance state contamination.
**Why it happens:** `TestDataCleaner.wipeAll()` does not include `main.disbursement` or `main.merchant_wallet_balance`.
**How to avoid:** Add `DELETE FROM main.disbursement` and `DELETE FROM main.merchant_wallet_balance` to `TestDataCleaner.wipeAll()` in FK order (disbursement child rows first, then wallet).

---

## Code Examples

### Verified pattern: PESSIMISTIC_WRITE lock in repository
```java
// Source: TransactionRepository.java (existing)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM Transaction t WHERE t.transactionId = :transactionId")
Optional<Transaction> findByTransactionIdForUpdate(@Param("transactionId") String transactionId);
```

Apply identically for wallet:
```java
// MerchantWalletBalanceRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM MerchantWalletBalance w WHERE w.tenantId = :tenantId")
Optional<MerchantWalletBalance> findByTenantIdForUpdate(@Param("tenantId") Long tenantId);
```

### Verified pattern: CyclicBarrier concurrency test
```java
// Source: ApiKeyConcurrentRotationIT.java (existing) — adapted for wallet
int THREADS = 20;
CyclicBarrier barrier = new CyclicBarrier(THREADS);
ExecutorService pool = Executors.newFixedThreadPool(THREADS);

List<Future<Void>> futures = new ArrayList<>();
for (int i = 0; i < THREADS; i++) {
    futures.add(pool.submit(() -> {
        try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
        walletBalanceService.checkAndReserve(tenantId, disbursementAmount);
        return null;
    }));
}
```

### Verified pattern: TransactionTemplate for atomic DB operations
```java
// Source: OrangeMoneyPort.java, MtnMoMoPort.java (existing)
transactionTemplate.execute(status -> {
    walletBalanceService.checkAndReserve(tenantId, amount);
    return null;
});
```

### Verified pattern: @Audited entity extending AbstractAuditingEntity
```java
// Source: Transaction.java (existing)
@Audited
@Entity
@Table(name = "disbursement", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Disbursement extends AbstractAuditingEntity { ... }
```

### Verified pattern: AbstractAuditingEntity audit table SQL
```sql
-- Source: V27__audit_schema_gap_closure.sql (existing)
CREATE TABLE IF NOT EXISTS main.disbursement_aud (
    id     BIGINT  NOT NULL,
    rev    INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype SMALLINT,
    -- ... all audited fields ...
    PRIMARY KEY (id, rev)
);
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| V23 unique constraint on ledger groups | V25 deferrable balance trigger | Phase 46 | Balance check fires at commit, not per-row insert |
| `@Version` optimistic lock for concurrency | `@Lock(PESSIMISTIC_WRITE)` for safety-critical paths | Phase 39 | Eliminates retry-induced double-drain; used for wallet gate |
| `@Transactional` on orchestrator methods making HTTP calls | `TransactionTemplate` for DB-only sections | Phase 38 | Prevents holding DB connections during provider HTTP calls |

---

## Environment Availability

Step 2.6: SKIPPED — this phase is code/config-only changes (new Java classes + SQL migration). All external dependencies (PostgreSQL via Testcontainers, Redis) are already confirmed available from prior phases.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test (managed by Spring Boot 3.5.11 BOM) |
| Config file | No separate config file — `@SpringBootTest` annotations on each test |
| Quick run command | `mvn test -pl . -Dtest=WalletBalanceConcurrencyIT` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| BAL-01 | 20 concurrent requests, only 1 succeeds, 19 get INSUFFICIENT_BALANCE | Integration (concurrency) | `mvn test -Dtest=WalletBalanceConcurrencyIT` | ❌ Wave 0 |
| BAL-01 | V28 creates all 3 tables on a V27 database | Integration (migration) | `mvn verify` (Flyway runs at startup) | ❌ Wave 0 (via migration test) |
| BAL-02 | release() restores exact reserved amount, no overdraft | Integration | `mvn test -Dtest=WalletBalanceConcurrencyIT` | ❌ Wave 0 |
| BAL-03 | DisbursementStatus has all 6 values, EXPIRED transitions correctly | Unit | `mvn test -Dtest=DisbursementStatusTest` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** Compile check (`mvn compile -q`)
- **Per wave merge:** `mvn verify`
- **Phase gate:** `mvn verify` green including `WalletBalanceConcurrencyIT`

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/disbursement/WalletBalanceConcurrencyIT.java` — covers BAL-01 and BAL-02
- [ ] `src/test/java/com/softropic/payam/domain/DisbursementStatusTest.java` — covers BAL-03 (state machine unit test)
- [ ] `TestDataCleaner.wipeAll()` update — add DELETE for `main.disbursement` and `main.merchant_wallet_balance`

---

## Open Questions

1. **Does `merchant_wallet_balance` track a single balance or multiple currencies?**
   - What we know: Requirements say "XAF only" — consistent with existing collections constraint.
   - What's unclear: Whether the schema needs a per-currency row or a single row per tenant.
   - Recommendation: One row per tenant with a `currency` column defaulting to `XAF`. The `UNIQUE (tenant_id)` constraint enforces one balance per tenant. Multi-currency can be added later by dropping the unique constraint and making the key `(tenant_id, currency)`.

2. **Does `reserved_amount` need to be tracked in `merchant_wallet_balance`?**
   - What we know: BAL-02 says "releases the full reserved amount." BAL-03 says "reserved balance is held pending manual ops resolution."
   - What's unclear: Whether the stored `reserved_amount` is used for reporting or just for the release calculation (which could use the `Disbursement.reservedAmount` field instead).
   - Recommendation: Store `reserved_amount` on `MerchantWalletBalance` for operational visibility (ops can query total reserved); also store it on `Disbursement` for per-disbursement release precision. Both are needed.

3. **Should `Disbursement` be linked to `Transaction` via FK or be a standalone domain entity?**
   - What we know: Phase 51 creates `DisbursementOrchestrator` — disbursements are a separate flow from collections.
   - What's unclear: Whether a disbursement row points to a `transaction` row.
   - Recommendation: Standalone. Disbursements are their own root aggregate, not an extension of `Transaction`. The `LedgerService` takes a `transactionId` (string) as FK — use `disbursementId` there too. No FK from `disbursement` to `main.transaction`.

---

## Sources

### Primary (HIGH confidence)
- Direct code inspection: `TransactionRepository.java` — `@Lock(PESSIMISTIC_WRITE)` pattern verified in production code
- Direct code inspection: `ApiKeyConcurrentRotationIT.java` — `CyclicBarrier` + `ExecutorService` concurrency test pattern
- Direct code inspection: `LedgerEntry.java` — `@Tsid` ID pattern for entities not extending `AbstractAuditingEntity`
- Direct code inspection: `AbstractAuditingEntity.java` + `BaseEntity.java` — inheritance chain and `@Tsid` ID
- Direct code inspection: `OrangeMoneyPort.java` + `MtnMoMoPort.java` — `TransactionTemplate` pattern for non-`@Transactional` methods
- Direct code inspection: `TransactionStatus.java` — state machine guard pattern for `DisbursementStatus`
- Direct code inspection: `V25__ledger_disbursement_schema.sql`, `V26__security_schema.sql`, `V27__audit_schema_gap_closure.sql` — migration history; V28 is confirmed next
- Direct code inspection: `pom.xml` — Spring Boot 3.5.11, Hibernate Envers 6.6.14.Final, all deps present
- Direct code inspection: `TestConfig.java`, `PostgresContainerConfig.java` — `postgres:14.18` Testcontainers setup
- Direct code inspection: `TestDataCleaner.java` — FK ordering requirement for wipeAll()
- Direct code inspection: `PaymentResource.java` + `OrchestratorError.java` — HTTP 422 pattern for domain errors

### Secondary (MEDIUM confidence)
- STATE.md — `@Lock(PESSIMISTIC_WRITE)` explicitly called out as required; "optimistic retry allows second drain" warning documented

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in pom.xml, verified by inspection
- Architecture: HIGH — all patterns have direct precedents in production code
- Pitfalls: HIGH — V26/V27 version collision directly observed; other pitfalls derived from concrete code evidence
- Migration version: HIGH — V26 and V27 files physically exist; V28 is unambiguously next

**Research date:** 2026-04-24
**Valid until:** 2026-05-24 (stable domain — no fast-moving dependencies)
