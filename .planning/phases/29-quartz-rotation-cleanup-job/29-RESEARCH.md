# Phase 29: Quartz Rotation Cleanup Job - Research

**Researched:** 2026-04-06
**Domain:** Spring Quartz scheduled job, JPA entity lifecycle, Hibernate Envers audit capture
**Confidence:** HIGH

## Summary

This phase is a narrow, well-constrained addition to an existing Quartz infrastructure. The project already has two canonical Quartz jobs (`WebhookDeliveryJob` and `ReconciliationJob`) and their scheduler configs. The new `RotatedKeyCleanupJob` follows the `WebhookDeliveryJob` pattern exactly: `@Component` + `QuartzJobBean` + `@Transactional executeInternal` + service delegation + `kv()` structured logging.

All locked decisions are known from CONTEXT.md: no `revokedAt` column, entity-level save for Envers capture (not bulk `@Modifying`), and log only when `expiredCount > 0`. The domain model (`TenantApiKey`, `ApiKeyService`, `TenantApiKeyRepository`) is already complete and the `GRACE_PERIOD` constant is already defined in `ApiKeyService`. One new repository query method and one new service method are the only non-scaffolding additions.

The integration test follows `ReconciliationJobIT` as the canonical model: `@SpringBootTest`, `@ActiveProfiles("dev")`, `@Import(TestConfig.class)`, JDBC-seeded test data, direct service call (Quartz not auto-firing), `TransactionTemplate` for seeding. Teardown must delete Envers audit tables before main tables to avoid FK constraints (established in `TenantServiceIT`/`TenantAuditIT`).

**Primary recommendation:** Replicate `WebhookDeliveryJob` + `WebhookSchedulerConfig` verbatim, scoped to the tenant package. Add one repository query, one service method, and one integration test. Zero schema changes.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Do NOT add a `revokedAt` column or Flyway migration. Just flip `keyStatus` to `REVOKED`. Consistent with the existing `revoke()` and `revokeAllActiveAndRotatedByTenantId` paths — neither sets a dedicated timestamp. `AbstractAuditingEntity.lastModifiedDate` (`updated_at`) records when the row last changed, which is sufficient.
- **D-02:** Use entity-level load + `setKeyStatus(REVOKED)` + `save` (NOT a bulk `@Modifying` JPQL). Each auto-revocation must produce a Hibernate Envers revision row to fully honor AUDIT-02 ("all changes to API key states are captured by Hibernate Envers"). The batch is expected to be small (keys expire once per 24h per rotation event), so individual saves are acceptable.
- **D-03:** Log at the job scan level only — one structured log line per run with `expiredCount` (how many keys were found), and one line after processing with `revokedCount`. Use `kv()` structured args (project standard). Consistent with `WebhookDeliveryJob` pattern. Log nothing when `expiredCount = 0`.

### Claude's Discretion
- Package placement: `tenant/service/RotatedKeyCleanupJob.java` + `tenant/config/RotatedKeyCleanupSchedulerConfig.java` (consistent with `webhook/` and `reconciliation/` package structure)
- Repository query: new `findExpiredRotatedKeys(Instant cutoff)` JPQL in `TenantApiKeyRepository` (returns `List<TenantApiKey>` where `status = ROTATED AND rotatedAt < :cutoff`)
- Job schedule: `SimpleScheduleBuilder.repeatMinutelyForever(5)` (consistent with `WebhookSchedulerConfig` approach for frequent jobs)
- Integration test approach: seed a `TenantApiKey` with `rotatedAt` set to `Instant.now().minusSeconds(86500)` via JDBC (25h ago), call `ApiKeyService.revokeExpiredRotatedKeys()` directly (Quartz prevented from auto-firing via `spring.quartz.properties`), assert `keyStatus = REVOKED`; also assert a key with `rotatedAt = 1h ago` is untouched

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AKEY-05 | System automatically moves ROTATED keys to REVOKED status after 24 hours via an automated job | Fully covered: repository query pattern identified, service method design confirmed, job + scheduler config patterns from WebhookDeliveryJob/WebhookSchedulerConfig, integration test pattern from ReconciliationJobIT |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-starter-quartz` | Already in project | Quartz JDBC job scheduling | Already on classpath; QRTZ_* tables already provisioned; job-store-type: jdbc configured |
| `org.springframework.scheduling.quartz.QuartzJobBean` | Spring Boot 3.x | Base class for all Quartz jobs in this project | Canonical pattern — both `WebhookDeliveryJob` and `ReconciliationJob` extend it |
| `org.quartz.SimpleScheduleBuilder` | Quartz 2.x (via Spring Boot 3) | Interval-based trigger | Used by `WebhookSchedulerConfig` for minute-based scheduling |
| Hibernate Envers | Already in project | Audit table capture on `TenantApiKey` | `@Audited` already on `TenantApiKey` entity; Envers tables exist in main schema |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `net.logstash.logback.argument.StructuredArguments.kv()` | Already in project | Structured log fields | All job log calls use this — import from `net.logstash.logback.argument.StructuredArguments` |
| `TransactionTemplate` | Spring | JDBC seeding within transactions in tests | Needed in `@BeforeEach` / `@AfterEach` for Envers audit table cleanup |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `SimpleScheduleBuilder.repeatMinutelyForever(5)` | `CronScheduleBuilder.cronSchedule("0 0/5 * * * ?")` | Cron gives deterministic clock-aligned firing; `repeatMinutelyForever` fires relative to startup. `ReconciliationSchedulerConfig` uses cron for its daily job. For a 5-minute cleanup job the cron alignment benefit is negligible — `repeatMinutelyForever` is the project pattern for high-frequency jobs. |
| Entity-level load + save | `@Modifying` bulk JPQL | Bulk JPQL is faster but bypasses Envers — violates D-02 and AUDIT-02. Must use entity-level save. |

**Installation:** No new dependencies required. All libraries already on classpath.

## Architecture Patterns

### Recommended Project Structure
```
src/main/java/com/softropic/payam/tenant/
├── config/
│   └── RotatedKeyCleanupSchedulerConfig.java   # JobDetail + Trigger beans
├── service/
│   ├── RotatedKeyCleanupJob.java                # extends QuartzJobBean
│   └── ApiKeyService.java                       # add revokeExpiredRotatedKeys()
└── repo/
    └── TenantApiKeyRepository.java              # add findExpiredRotatedKeys(Instant)

src/test/java/com/softropic/payam/tenant/
└── RotatedKeyCleanupJobIT.java                  # @SpringBootTest integration test
```

### Pattern 1: Quartz Job Class (replicate WebhookDeliveryJob exactly)
**What:** `@Component` class extending `QuartzJobBean`, field-injected service via `@Autowired` (not constructor — Quartz instantiates job instances directly), `@Transactional` on `executeInternal`.
**When to use:** Every Quartz job in this project.
**Example:**
```java
// Source: src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java
@Component
public class RotatedKeyCleanupJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(RotatedKeyCleanupJob.class);

    @Autowired
    private ApiKeyService apiKeyService;

    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        List<TenantApiKey> expired = apiKeyService.findExpiredRotatedKeys();
        if (expired.isEmpty()) {
            return;  // D-03: log nothing when expiredCount = 0
        }
        log.info("Rotated key cleanup scan",
            kv("operation", "rotated_key_cleanup_scan"),
            kv("expiredCount", expired.size()));
        int revokedCount = apiKeyService.revokeExpiredRotatedKeys(expired);
        log.info("Rotated key cleanup complete",
            kv("operation", "rotated_key_cleanup_complete"),
            kv("revokedCount", revokedCount));
    }
}
```

### Pattern 2: Scheduler Config (replicate WebhookSchedulerConfig exactly)
**What:** `@Configuration` class with `@Bean JobDetail` (`.storeDurably()`) and `@Bean Trigger` using `SimpleScheduleBuilder.repeatMinutelyForever(5)`.
**Example:**
```java
// Source: src/main/java/com/softropic/payam/webhook/config/WebhookSchedulerConfig.java
@Configuration
public class RotatedKeyCleanupSchedulerConfig {

    @Bean
    public JobDetail rotatedKeyCleanupJobDetail() {
        return JobBuilder.newJob(RotatedKeyCleanupJob.class)
            .withIdentity("rotatedKeyCleanupJob")
            .storeDurably()
            .build();
    }

    @Bean
    public Trigger rotatedKeyCleanupTrigger(JobDetail rotatedKeyCleanupJobDetail) {
        return TriggerBuilder.newTrigger()
            .forJob(rotatedKeyCleanupJobDetail)
            .withIdentity("rotatedKeyCleanupTrigger")
            .withSchedule(SimpleScheduleBuilder.repeatMinutelyForever(5))
            .build();
    }
}
```

### Pattern 3: Repository Query — findExpiredRotatedKeys
**What:** JPQL query returning `List<TenantApiKey>` where `keyStatus = ROTATED AND rotatedAt < :cutoff`. No `JOIN FETCH` needed since the job only touches `keyStatus`; lazy `tenant` association is not accessed.
**Example:**
```java
// Source: modeled on TenantApiKeyRepository existing query patterns
@Query("""
    SELECT k FROM TenantApiKey k
    WHERE k.keyStatus = com.softropic.payam.tenant.contract.ApiKeyStatus.ROTATED
      AND k.rotatedAt < :cutoff
    """)
List<TenantApiKey> findExpiredRotatedKeys(@Param("cutoff") Instant cutoff);
```

### Pattern 4: Service Method — revokeExpiredRotatedKeys
**What:** Computes cutoff as `Instant.now().minus(GRACE_PERIOD)`, calls repository, iterates and calls `setKeyStatus(REVOKED)` + `keyRepository.save(key)` per entity. Envers captures each save automatically.
**Note:** `GRACE_PERIOD` is already declared as `private static final Duration GRACE_PERIOD = Duration.ofHours(24)` in `ApiKeyService`. It needs to be used here — either widen visibility or expose the cutoff computation as a package-private method. Since the job delegates to the service (not repository), the `GRACE_PERIOD` constant stays private inside `ApiKeyService`.

### Pattern 5: Integration Test (replicate ReconciliationJobIT + TenantServiceIT teardown)
**What:** `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("dev")`, `@Import(TestConfig.class)`. Quartz does NOT auto-fire in tests because QRTZ_ tables are empty in the Testcontainers-provisioned PostgreSQL — no trigger rows are seeded by `createSchema.sql`, so Quartz JDBC store finds nothing to execute. Call `apiKeyService.revokeExpiredRotatedKeys()` directly.

**Teardown order (critical):** Delete Envers audit tables BEFORE main tables:
```java
// Source: src/test/java/com/softropic/payam/tenant/TenantServiceIT.java (tearDown)
transactionTemplate.execute(status -> {
    jdbcTemplate.execute("DELETE FROM main.tenant_api_key_aud");
    jdbcTemplate.execute("DELETE FROM main.tenant_aud");
    jdbcTemplate.execute("DELETE FROM main.revinfo");
    jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
    jdbcTemplate.execute("DELETE FROM main.tenant");
    return null;
});
```

**JDBC seeding for rotatedAt backdating:**
```java
// Seed key with rotatedAt 25h ago (over grace period — should be revoked)
transactionTemplate.execute(status -> {
    jdbcTemplate.update(
        "UPDATE main.tenant_api_key SET key_status = 'ROTATED', rotated_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(90000L)), overdueKeyId);
    return null;
});
```

### Anti-Patterns to Avoid
- **Bulk JPQL `@Modifying` for revocation:** Bypasses Hibernate Envers — no audit row produced. Violates D-02 and AUDIT-02. Always use entity-level load + `save`.
- **Constructor injection in `RotatedKeyCleanupJob`:** Quartz instantiates job beans directly via reflection without using Spring's constructor injection. Use `@Autowired` field injection (established pattern in `WebhookDeliveryJob`).
- **`JOIN FETCH k.tenant` in `findExpiredRotatedKeys`:** Not needed — the job only reads/sets `keyStatus`, never accesses the `tenant` association. Avoids unnecessary JOIN.
- **Logging when `expiredCount = 0`:** D-03 says log nothing in this case. Prevents log noise in normal operation.
- **Adding `revokedAt` column:** D-01 explicitly forbids this. No Flyway migration needed.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Scheduled execution every 5 min | Custom `@Scheduled` + `SchedulingConfigurer` | Quartz JDBC store | Project already uses Quartz JDBC; `@Scheduled` state is lost on JVM restart; Quartz triggers are persistent |
| Audit capture on status flip | Custom `EntityListener` or audit log write | Hibernate Envers via `@Audited` | `TenantApiKey` is already `@Audited`; every `save()` auto-produces a revision row in `tenant_api_key_aud` |
| Grace period duration | Redefine `Duration.ofHours(24)` in job | `ApiKeyService.GRACE_PERIOD` already declared | Single source of truth; must reuse the same constant to stay consistent with `authenticate()` logic |

**Key insight:** The project's Quartz infrastructure (JDBC store, QRTZ_* tables, config pattern) is fully operational. This phase is pure addition — no infrastructure work.

## Common Pitfalls

### Pitfall 1: Envers revision not captured because entity is not managed
**What goes wrong:** If `findExpiredRotatedKeys()` returns detached entities (e.g., called outside a transaction), calling `setKeyStatus()` + `save()` on them will still INSERT a new row rather than UPDATE.
**Why it happens:** `executeInternal` is `@Transactional` on the job but the service method has its own `@Transactional` boundary. If query and save are in the same transaction, Hibernate tracks the entity as managed and the `save()` triggers an UPDATE + Envers revision. If the service method opens a new transaction without the entity, the entity is detached.
**How to avoid:** Keep both `findExpiredRotatedKeys()` and the save loop in the same `@Transactional` method in `ApiKeyService`. Do not call `findExpiredRotatedKeys()` as `readOnly = true` and then save in a separate transaction.
**Warning signs:** `SELECT` then `INSERT` in SQL logs instead of `SELECT` then `UPDATE`.

### Pitfall 2: Teardown FK violation when cleaning Envers audit tables
**What goes wrong:** `DELETE FROM main.tenant_api_key` fails with FK constraint if `tenant_api_key_aud` rows referencing `revinfo` still exist, or if audit rows have a FK back to the main table.
**Why it happens:** Envers audit tables have FK references to `revinfo`. The correct delete order is: `tenant_api_key_aud` → `tenant_aud` → `revinfo` → `tenant_api_key` → `tenant`.
**How to avoid:** Copy the teardown order from `TenantServiceIT` and `TenantAuditIT` exactly.
**Warning signs:** `PSQLException: ERROR: update or delete on table "revinfo" violates foreign key constraint` or similar during `@AfterEach`.

### Pitfall 3: Quartz auto-fires the job during integration test
**What goes wrong:** The job fires asynchronously during test setup/assertion, revoking keys the test did not expect to be revoked.
**Why it happens:** In a full Spring context, Quartz starts its scheduler. If QRTZ_* trigger tables have rows (from a previous test run that committed), Quartz can fire immediately.
**How to avoid:** The QRTZ_* tables in the Testcontainers PostgreSQL are empty (no trigger rows seeded in `createSchema.sql`), so Quartz finds nothing to fire. This is the same mechanism that protects `ReconciliationJobIT`. No additional property override is needed — but do not seed QRTZ_* rows anywhere in test setup.
**Warning signs:** Test asserting key is still `ROTATED` finds `REVOKED` unexpectedly.

### Pitfall 4: `rotatedAt` column is null for direct JDBC-seeded keys
**What goes wrong:** Test seeds a `TenantApiKey` row via `tenantService.createTenant()` (which creates ACTIVE keys, no `rotatedAt`). Developer forgets to also UPDATE `key_status = 'ROTATED'` and `rotated_at = <timestamp>`.
**Why it happens:** `TenantApiKey` builder defaults `keyStatus = ACTIVE` and `rotatedAt = null`. Rotation must be simulated via JDBC update.
**How to avoid:** After creating the tenant/key via service, issue a JDBC UPDATE within a `transactionTemplate.execute()` block to set both `key_status = 'ROTATED'` and `rotated_at` to the desired timestamp (25h ago for the overdue key, 1h ago for the under-grace key).

### Pitfall 5: `GRACE_PERIOD` visibility — private constant in ApiKeyService
**What goes wrong:** `RotatedKeyCleanupJob` needs to pass a cutoff `Instant` to the repository. If it duplicates `Duration.ofHours(24)` instead of reusing `ApiKeyService.GRACE_PERIOD`, the two can drift.
**Why it happens:** `GRACE_PERIOD` is currently `private static final` in `ApiKeyService`.
**How to avoid:** The job delegates entirely to `ApiKeyService.revokeExpiredRotatedKeys()` — the service method computes the cutoff internally using the existing `GRACE_PERIOD` constant. The job passes no cutoff; the service owns the expiry logic.

## Code Examples

Verified patterns from official sources (codebase):

### WebhookDeliveryJob — canonical Quartz job structure
```java
// Source: src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java
@Component
public class WebhookDeliveryJob extends QuartzJobBean {
    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryJob.class);
    @Autowired
    private WebhookDeliveryService deliveryService;

    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        List<WebhookDeliveryLog> pending = deliveryService.findPendingDeliveries();
        log.info("Webhook delivery job scan",
            kv("operation", "webhook_delivery_scan"),
            kv("pendingCount", pending.size()));
        for (WebhookDeliveryLog delivery : pending) {
            try {
                deliveryService.attemptDelivery(delivery);
            } catch (Exception e) {
                log.warn("Webhook delivery attempt failed", ...);
            }
        }
    }
}
```

### WebhookSchedulerConfig — canonical scheduler config structure
```java
// Source: src/main/java/com/softropic/payam/webhook/config/WebhookSchedulerConfig.java
@Configuration
public class WebhookSchedulerConfig {
    @Bean
    public JobDetail webhookDeliveryJobDetail() {
        return JobBuilder.newJob(WebhookDeliveryJob.class)
            .withIdentity("webhookDeliveryJob")
            .storeDurably()
            .build();
    }
    @Bean
    public Trigger webhookDeliveryTrigger(JobDetail webhookDeliveryJobDetail) {
        return TriggerBuilder.newTrigger()
            .forJob(webhookDeliveryJobDetail)
            .withIdentity("webhookDeliveryTrigger")
            .withSchedule(SimpleScheduleBuilder.repeatMinutelyForever(1))
            .build();
    }
}
```

### TenantAuditIT — audit assertion pattern
```java
// Source: src/test/java/com/softropic/payam/tenant/TenantAuditIT.java
Integer revokedCount = jdbcTemplate.queryForObject(
    "SELECT COUNT(*) FROM main.tenant_api_key_aud WHERE id = ? AND key_status = 'REVOKED'",
    Integer.class, keyId);
assertThat(revokedCount).isGreaterThanOrEqualTo(1);
```

### ApiKeyService — GRACE_PERIOD constant and save pattern
```java
// Source: src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java
private static final Duration GRACE_PERIOD = Duration.ofHours(24);

// revoke() uses entity-level save (the pattern to replicate):
public void revoke(Long keyId) {
    TenantApiKey key = keyRepository.findById(keyId)
        .orElseThrow(() -> new EntityNotFoundException("Key not found: " + keyId));
    key.setKeyStatus(ApiKeyStatus.REVOKED);
    keyRepository.save(key);
}
```

## Environment Availability

Step 2.6: SKIPPED — this phase is pure code/config changes. No external dependencies beyond the existing project stack (PostgreSQL via Testcontainers, Quartz JDBC store already operational).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + AssertJ |
| Config file | `src/test/resources/application.properties` (minimal overrides) |
| Quick run command | `./mvnw test -pl . -Dtest=RotatedKeyCleanupJobIT -Dsurefire.failIfNoSpecifiedTests=false` |
| Full suite command | `./mvnw verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| AKEY-05 | ROTATED key with `rotatedAt` > 24h ago is moved to REVOKED | integration | `./mvnw test -Dtest=RotatedKeyCleanupJobIT#revokeExpiredRotatedKeys_revokesOverdueKey` | ❌ Wave 0 |
| AKEY-05 | ROTATED key with `rotatedAt` < 24h ago is left untouched | integration | `./mvnw test -Dtest=RotatedKeyCleanupJobIT#revokeExpiredRotatedKeys_leavesUnderGraceKeyUntouched` | ❌ Wave 0 |
| AKEY-05 / AUDIT-02 | Envers revision row created for each auto-revoked key | integration | `./mvnw test -Dtest=RotatedKeyCleanupJobIT#revokeExpiredRotatedKeys_createsEnversAuditRow` | ❌ Wave 0 |
| AKEY-05 | No-op when no expired rotated keys exist | integration | `./mvnw test -Dtest=RotatedKeyCleanupJobIT#revokeExpiredRotatedKeys_isIdempotent_noOp` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./mvnw test -Dtest=RotatedKeyCleanupJobIT`
- **Per wave merge:** `./mvnw test -Dtest=RotatedKeyCleanupJobIT`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/tenant/RotatedKeyCleanupJobIT.java` — covers all AKEY-05 test cases above

*(Existing test infrastructure covers all supporting concerns — `TestConfig`, `TransactionTemplate`, `JdbcTemplate`, Testcontainers PostgreSQL are all already available)*

## Sources

### Primary (HIGH confidence)
- `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java` — canonical Quartz job structure: `@Component`, `QuartzJobBean`, `@Transactional executeInternal`, `@Autowired` service, `kv()` logging
- `src/main/java/com/softropic/payam/webhook/config/WebhookSchedulerConfig.java` — canonical scheduler config: `JobDetail` + `Trigger` beans, `SimpleScheduleBuilder.repeatMinutelyForever(N)`
- `src/main/java/com/softropic/payam/reconciliation/config/ReconciliationSchedulerConfig.java` — alternate cron-based scheduler config (reference)
- `src/main/java/com/softropic/payam/tenant/repo/TenantApiKey.java` — entity fields: `rotatedAt (Instant)`, `keyStatus (ApiKeyStatus enum)`, `@Audited`
- `src/main/java/com/softropic/payam/tenant/repo/TenantApiKeyRepository.java` — existing JPQL query patterns to model `findExpiredRotatedKeys` after
- `src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java` — `GRACE_PERIOD` constant, `revoke()` entity-level save pattern
- `src/test/java/com/softropic/payam/reconciliation/ReconciliationJobIT.java` — canonical IT for Quartz jobs: `@SpringBootTest`, direct service call, JDBC seeding
- `src/test/java/com/softropic/payam/tenant/TenantServiceIT.java` — Envers teardown order, `transactionTemplate.execute()` JDBC seeding pattern
- `src/test/java/com/softropic/payam/tenant/TenantAuditIT.java` — Envers audit assertion pattern via JDBC count queries
- `src/main/resources/application.yaml` — Quartz config: `job-store-type: jdbc`, `org.hibernate.envers.default_schema: main`
- `.planning/phases/29-quartz-rotation-cleanup-job/29-CONTEXT.md` — all locked decisions

### Secondary (MEDIUM confidence)
- `.planning/REQUIREMENTS.md` — AKEY-05, AKEY-04, AKEY-08 requirement context
- `.planning/STATE.md` — accumulated phase decisions, Envers schema placement decision

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already on classpath; patterns verified from codebase source
- Architecture: HIGH — two canonical Quartz jobs exist in codebase; replicate directly
- Pitfalls: HIGH — identified from actual code patterns (Envers FK order from TenantServiceIT, null rotatedAt from entity defaults, Quartz auto-fire from QRTZ_ table state)

**Research date:** 2026-04-06
**Valid until:** 2026-05-06 (stable internal patterns)
