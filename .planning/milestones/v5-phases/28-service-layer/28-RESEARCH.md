# Phase 28: Service Layer - Research

**Researched:** 2026-04-03
**Domain:** Spring/Hibernate service layer — tenant lifecycle, API key management, WebhookSecret, Hibernate Envers audit trail
**Confidence:** HIGH

---

## Summary

Phase 28 completes the tenant management service layer. The core entity model (Tenant, TenantApiKey), enums (TenantStatus, ApiKeyStatus, ApiKeyEnvironment), and the partial unique index are fully in place after Phase 27. The existing TenantService and ApiKeyService have a thin subset of operations already implemented. Phase 28 adds the missing lifecycle operations to those services, adds missing repository queries, and creates the Flyway V20 migration for Envers audit tables.

The most important discovery is that `hibernate.ddl-auto: none` is set in all profiles, which means Envers does NOT auto-create the REVINFO or `_AUD` shadow tables. A Flyway migration (V20) must create them explicitly. The entities themselves already carry `@Audited` — this is inherited from `AbstractAuditingEntity` — so zero entity changes are needed for AUDIT-01/AUDIT-02. The acting-admin identity for AUDIT-03 is already captured automatically by the `createdBy`/`lastModifiedBy` columns populated by `SpringSecurityAuditorAware` — no additional service plumbing is required beyond ensuring the security context is populated at call time.

**Primary recommendation:** Focus effort on (1) Flyway V20 for Envers DDL, (2) TenantService lifecycle methods (update, suspend, reactivate), (3) ApiKeyService AKEY-08 gap (revoke overlapping ROTATED key on rotation), (4) WebhookSecret generation in createTenant, and (5) integration tests for every new operation.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TENT-01 | Admin creates tenant — auto TenantRef (UUID), initial PROD key shown once, WebhookSecret | createTenant() exists; needs WebhookSecret auto-gen wired in; PROD key already works |
| TENT-02 | Admin updates tenant name | TenantService missing updateName(); repo findByTenantRef ready |
| TENT-03 | Admin updates tenant email | TenantService missing updateEmail(); email column exists on Tenant |
| TENT-04 | Admin updates tenant webhookUrl | TenantService missing updateWebhookUrl(); column exists on Tenant |
| TENT-07 | Admin suspends tenant — all keys immediately revoked | TenantService missing suspend(); needs @Modifying bulk JPQL on TenantApiKeyRepository |
| TENT-08 | Admin reactivates suspended tenant — new PROD key auto-generated, shown once | TenantService missing reactivate(); delegate to ApiKeyService.generateAndStore() |
| AKEY-02 | Admin generates key for specific environment — raw key shown once | ApiKeyService.generateAndStore() exists; needs guard: reject if ACTIVE key exists for env |
| AKEY-04 | Admin rotates key — old ROTATED (24h grace), new ACTIVE; raw key shown once | ApiKeyService.rotate() exists; AKEY-08 guard missing (see below) |
| AKEY-06 | Admin manually revokes a key — immediate, no grace | ApiKeyService.revoke() exists |
| AKEY-08 | If another ROTATED key exists for same env during rotation, immediately move it to REVOKED | ApiKeyService.rotate() missing this check; needs new repo query |
| WSEC-01 | WebhookSecret auto-generated on tenant create (UUID) | webhookSecret column exists on Tenant; createTenant() not setting it |
| WSEC-03 | Admin regenerates WebhookSecret — new replaces old | TenantService missing regenerateWebhookSecret() |
| AUDIT-01 | Envers captures all Tenant field mutations | @Audited already on Tenant via AbstractAuditingEntity — needs V20 Flyway for DDL |
| AUDIT-02 | Envers captures all TenantApiKey state mutations | @Audited already on TenantApiKey — same V20 dependency |
| AUDIT-03 | Key gen/rotation events log acting admin ID + timestamp | createdBy/lastModifiedBy auto-populated by SpringSecurityAuditorAware — no extra code needed IF admin JWT context is present at call time |
</phase_requirements>

---

## What Already Exists vs What Is Missing

### Fully Implemented (do not touch)
| Component | Location | Status |
|-----------|----------|--------|
| `Tenant` entity | `tenant/repo/Tenant.java` | Complete — all fields present (tenantRef, name, email, status, webhookUrl, webhookSecret, keyPrefix) |
| `TenantApiKey` entity | `tenant/repo/TenantApiKey.java` | Complete — all fields present including rotatedAt, environment |
| `TenantStatus` enum | `tenant/contract/TenantStatus.java` | `ACTIVE`, `SUSPENDED` — complete |
| `ApiKeyStatus` enum | `tenant/contract/ApiKeyStatus.java` | `ACTIVE`, `ROTATED`, `REVOKED` — complete |
| `ApiKeyEnvironment` enum | `tenant/contract/ApiKeyEnvironment.java` | `PROD`, `DEV`, `SANDBOX` — complete (LIVE removed in Phase 27) |
| `ApiKeyService.generateAndStore()` | `tenant/service/ApiKeyService.java` | Complete — SHA-256 hash, prefix from tenant, saves, returns raw key once |
| `ApiKeyService.authenticate()` | `tenant/service/ApiKeyService.java` | Complete — includes 24h ROTATED grace window |
| `ApiKeyService.rotate()` | `tenant/service/ApiKeyService.java` | Partially complete — sets ROTATED + rotatedAt, creates new ACTIVE; **missing AKEY-08 revoke-previous-ROTATED guard** |
| `ApiKeyService.revoke()` | `tenant/service/ApiKeyService.java` | Complete |
| `TenantService.createTenant()` | `tenant/service/TenantService.java` | Partially complete — generates TenantRef + PROD key; **missing WebhookSecret auto-gen** |
| `TenantService.deriveKeyPrefix()` | `tenant/service/TenantService.java` | Complete — 3-char uppercase, 0-padded |
| Partial unique index | V19 migration | `uidx_tenant_api_key_active_env` (tenant_id, environment) WHERE key_status = 'ACTIVE' — complete |
| UNIQUE on key_hash | V19 migration | `uq_tenant_api_key_hash` — complete |
| `@Audited` on Tenant | `Tenant.java` line 25 | Present — inherited audit scope covers all fields |
| `@Audited` on TenantApiKey | `TenantApiKey.java` line 25 | Present |
| `@Audited` on AbstractAuditingEntity | `AbstractAuditingEntity.java` line 34 | Present |
| `SpringSecurityAuditorAware` | `security/infrastructure/audit/` | Populates `createdBy`/`lastModifiedBy` from `SecurityContextHolder` |
| Envers config | `application.yaml` line 54 | `org.hibernate.envers.store_data_at_delete: true` |

### Missing — Must Build in Phase 28
| Component | Gap Description |
|-----------|-----------------|
| Flyway V20 migration | REVINFO table + tenant_AUD + tenant_api_key_AUD shadow tables — `ddl-auto: none` means Envers never creates them |
| `TenantService.updateName()` | TENT-02 |
| `TenantService.updateEmail()` | TENT-03 |
| `TenantService.updateWebhookUrl()` | TENT-04 |
| `TenantService.suspend()` | TENT-07 — must use bulk `@Modifying` JPQL to atomically revoke all keys |
| `TenantService.reactivate()` | TENT-08 — set ACTIVE, generate new PROD key, return raw key |
| WebhookSecret in `createTenant()` | TENT-01 / WSEC-01 — `UUID.randomUUID().toString()` stored in webhookSecret column |
| `TenantService.regenerateWebhookSecret()` | WSEC-03 |
| AKEY-02 guard in `generateAndStore()` | Reject call if an ACTIVE key already exists for this tenant+environment (partial index enforces at DB level, but service should throw a clean business exception rather than letting a `DataIntegrityViolationException` propagate) |
| AKEY-08 in `ApiKeyService.rotate()` | Before creating new ACTIVE key, find any ROTATED key for the same environment and immediately set it to REVOKED |
| New repo query: `findRotatedKeyByTenantAndEnvironment` | Used by AKEY-08 — find the prior ROTATED key for same (tenant_id, environment) to revoke it |
| TenantService update methods | Call `tenantRepository.findByTenantRef()` then mutate fields — all via `@Transactional` |
| Integration tests | `TenantServiceIT` / `TenantLifecycleIT` — cover all new operations |

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| hibernate-envers | 6.6.14.Final (in pom.xml) | Automatic `_AUD` shadow tables; REVINFO tracking | Already declared in pom.xml; `@Audited` already on entities |
| Spring Data JPA | (Spring Boot BOM) | `@Modifying` / `@Query` for bulk JPQL updates | Established project pattern (IdempotencyKeyRepository) |
| Apache Commons Codec (DigestUtils) | Already on classpath | SHA-256 key hashing | Used in existing ApiKeyService |
| SecureRandom + Base64.getUrlEncoder() | JDK | Raw key generation | Used in existing ApiKeyService |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` | (Spring Security) | Guard all admin service endpoints | Already used in TenantAdminResource |
| `TransactionTemplate` (tests) | Spring | Wrap JDBC tearDown and backdated-rotatedAt manipulation | Established pattern from TenantProvisioningIT |

### No new dependencies required.

---

## Architecture Patterns

### Existing Package Structure
```
src/main/java/com/softropic/payam/tenant/
├── api/
│   └── TenantAdminResource.java          # REST controller (extend with new endpoints)
├── config/
│   ├── ApiKeyAuthenticationFilter.java
│   └── TenantSecurityConfig.java
├── contract/
│   ├── ApiKeyDto.java
│   ├── ApiKeyEnvironment.java
│   ├── ApiKeyStatus.java
│   ├── TenantDto.java
│   ├── TenantPrincipal.java
│   └── TenantStatus.java
├── repo/
│   ├── Tenant.java                        # Entity — @Audited
│   ├── TenantApiKey.java                  # Entity — @Audited
│   ├── TenantApiKeyRepository.java        # Add bulk-revoke + AKEY-08 queries
│   └── TenantRepository.java             # Add findByTenantRef if needed (already exists)
└── service/
    ├── ApiKeyService.java                 # Add AKEY-08 guard, AKEY-02 guard
    └── TenantService.java                 # Add update*, suspend, reactivate, regenerateWebhookSecret
```

### Pattern 1: Bulk JPQL Update for Suspend (TENT-07)
**What:** Atomically revoke all API keys for a tenant in a single UPDATE statement
**When to use:** Suspension cascade — entity-loop approach is vulnerable to partial failure mid-loop
**Why:** Decision in REQUIREMENTS.md: "Suspension cascade uses bulk JPQL `@Modifying` update — atomicity guarantee; entity loop is vulnerable to partial failure"
```java
// In TenantApiKeyRepository:
@Transactional
@Modifying
@Query("UPDATE TenantApiKey k SET k.keyStatus = 'REVOKED' WHERE k.tenant.id = :tenantId AND k.keyStatus IN ('ACTIVE', 'ROTATED')")
int revokeAllActiveAndRotatedByTenantId(@Param("tenantId") Long tenantId);
```

### Pattern 2: saveAndFlush for Rotate (AKEY-08 / AKEY-04)
**What:** Flush status UPDATE before inserting new ACTIVE row to avoid partial unique index violation
**When to use:** Any rotation/status change followed immediately by a new ACTIVE key insert
**Decision logged:** [27-02] `ApiKeyService.rotate()` uses `saveAndFlush` — confirmed critical
```java
// Existing rotate() correctly uses saveAndFlush for old key update.
// AKEY-08 add-on: before saveAndFlush of the old key to ROTATED,
// find any existing ROTATED key for the same (tenant, environment) and set to REVOKED first.
```

### Pattern 3: WebhookSecret as UUID string (WSEC-01)
**What:** `UUID.randomUUID().toString()` stored as plaintext in `webhook_secret` column
**When to use:** On tenant creation and on regeneration
**Decision in REQUIREMENTS.md:** "WebhookSecret stored as plaintext — Required for admin reveal; null in all list/detail responses; only returned on explicit reveal endpoint"

### Pattern 4: Envers Audit — REVINFO + _AUD Tables via Flyway V20
**What:** Since `hibernate.ddl-auto: none`, Envers never auto-creates audit tables. Flyway V20 must create them.
**Schema (`main` schema — confirmed by `default_schema: main`):**
```sql
-- V20__envers_audit_tables.sql
CREATE SEQUENCE IF NOT EXISTS main.revinfo_seq START 1 INCREMENT 50;

CREATE TABLE IF NOT EXISTS main.revinfo (
    rev      INTEGER NOT NULL DEFAULT nextval('main.revinfo_seq'),
    revtstmp BIGINT,
    PRIMARY KEY (rev)
);

CREATE TABLE IF NOT EXISTS main.tenant_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    tenant_ref         VARCHAR(36),
    name               VARCHAR(255),
    tenant_status      VARCHAR(20),
    webhook_url        VARCHAR(2048),
    webhook_secret     VARCHAR(255),
    key_prefix         VARCHAR(4),
    email              VARCHAR(255),
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS main.tenant_api_key_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    tenant_id          BIGINT,
    key_hash           VARCHAR(64),
    key_prefix         VARCHAR(8),
    key_status         VARCHAR(20),
    environment        VARCHAR(10),
    rotated_at         TIMESTAMP,
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);
```
**Important:** Envers uses the entity class name lowercased + `_aud` by default with the `default_schema` applied. Table names will be `tenant_aud` and `tenant_api_key_aud` in the `main` schema.

### Pattern 5: Acting Admin ID for AUDIT-03
**What:** Spring Data's `@CreatedBy` / `@LastModifiedBy` via `SpringSecurityAuditorAware` populates `createdBy` and `lastModifiedBy` automatically from `SecurityContextHolder`
**When to use:** Applies automatically to all entity saves; no extra service code needed
**Key constraint:** Admin must be authenticated (JWT context in SecurityContextHolder) when service methods are called. For integration tests, use `AdminLogin.loginAsAdmin()` to establish context, or set authentication manually in the thread.
```java
// SpringSecurityAuditorAware.getCurrentAuditor() returns admin login (email) string.
// createdBy is set on INSERT; lastModifiedBy is updated on every UPDATE.
// For key generation: createdBy on the new TenantApiKey row = admin identity.
// For rotation: lastModifiedBy on the old (ROTATED) row = admin identity.
```

### Pattern 6: TenantService Lookup Pattern
```java
// Standard: find by tenantRef, throw 404 if not found
private Tenant findTenantOrThrow(String tenantRef) {
    return tenantRepository.findByTenantRef(tenantRef)
        .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantRef));
}
```

### Pattern 7: Integration Test Structure
All existing tenant ITs share the same structure — confirmed from TenantProvisioningIT and TenantAdminResourceIT:
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `@Import(TestConfig.class)`
- `@ActiveProfiles("dev")`
- `@AfterEach` cleans `main.tenant_api_key` then `main.tenant` via `TransactionTemplate`
- For HTTP tests: seed sec row + admin user in `@BeforeEach`, call `AdminLogin.loginAsAdmin()`
- For service-layer tests: directly `@Autowire` services, no HTTP layer needed

### Anti-Patterns to Avoid
- **Entity loop for bulk revoke:** Iterating `tenant.getApiKeys()` and saving one by one is vulnerable to partial failure on transaction rollback. Use the `@Modifying` JPQL bulk UPDATE.
- **Calling `getApiKeys()` after createTenant() for first key:** The OneToMany collection on a freshly-saved Tenant is the Hibernate-managed list, not the service-returned key entity. The comment in existing `TenantService.createTenant()` already warns: "do NOT use saved.getApiKeys().get(0)". Always use the `ApiKeyAndRawKey` result from `generateAndStore()`.
- **Exposing webhookSecret in TenantDto list responses:** REQUIREMENTS.md specifies null in list/detail; only returned on explicit reveal (Phase 32).
- **Mixing `saveAndFlush` and `save`:** When rotate() calls saveAndFlush on the old key then save on the new key, Hibernate ordering is deterministic. Don't add extra flushes elsewhere.
- **Forgetting schema prefix in Flyway SQL:** All tables live in `main` schema. V20 must use `main.revinfo`, `main.tenant_aud`, etc.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Audit trail of entity mutations | Custom audit log table + service | Hibernate Envers `@Audited` + V20 Flyway DDL | Already annotated; handles revtype (INSERT=0, UPDATE=1, DELETE=2), timestamps, all fields automatically |
| Current auditor (acting admin) | Pass adminId as method parameter | `SpringSecurityAuditorAware` + Spring Data `@CreatedBy`/`@LastModifiedBy` | Already wired; populates automatically from SecurityContextHolder |
| Raw key generation | custom random string | `SecureRandom` + `Base64.getUrlEncoder().withoutPadding()` + `DigestUtils.sha256Hex()` | Already implemented in `ApiKeyService.generateSecureKey()` and `generateAndStore()` — reuse, do not copy |
| Partial unique index enforcement | Service-level ACTIVE key check loop | DB-level partial unique index `uidx_tenant_api_key_active_env` | Already created in V19; service throws `DataIntegrityViolationException` on violation — wrap with a clean business exception |
| Bulk key revocation | Loop over tenant.getApiKeys() | `@Modifying` JPQL bulk UPDATE | Atomicity guarantee per REQUIREMENTS.md decision |

---

## Common Pitfalls

### Pitfall 1: Envers Tables Missing in Schema
**What goes wrong:** `@Audited` entities silently write nothing; Hibernate throws on first save or silently discards audit data.
**Why it happens:** `ddl-auto: none` — Envers does not auto-create REVINFO or `_AUD` tables. The entity annotations exist but the target tables do not.
**How to avoid:** Flyway V20 must create `main.revinfo`, `main.tenant_aud`, `main.tenant_api_key_aud` before any audited entity is saved.
**Warning signs:** Tests pass but SELECT from tenant_aud returns empty; or `relation "revinfo" does not exist` at startup.

### Pitfall 2: AKEY-08 Partial Unique Index Violation During Rotation
**What goes wrong:** If a previous ROTATED key for the same (tenant, environment) exists and is not revoked before rotation, the new ACTIVE key insert violates `uidx_tenant_api_key_active_env`.
**Why it happens:** The partial unique index covers `WHERE key_status = 'ACTIVE'` — it only allows one ACTIVE per env. But two ROTATED keys can co-exist. The issue is: when rotate() is called again in the same environment, the old ROTATED key stays ROTATED and now we have two. If the grace period of the original ROTATED key hasn't expired, the index is fine for ACTIVE, but REQUIREMENTS.md explicitly says to revoke the earlier ROTATED key. The real bug occurs if both are ACTIVE.
**How to avoid:** In `ApiKeyService.rotate()`, before calling `saveAndFlush(old)`, find and REVOKE any existing ROTATED key for the same (tenant, environment).
**Warning signs:** `ApiKeyRotationGracePeriodTest` passes (single rotation), but a double-rotation test (AKEY-08 scenario) throws `DataIntegrityViolationException`.

### Pitfall 3: WebhookSecret Not Set in createTenant
**What goes wrong:** `createTenant()` returns a null webhookSecret; TENT-01/WSEC-01 are violated.
**Why it happens:** The current implementation does not set webhookSecret on the Tenant.builder() call.
**How to avoid:** Add `.webhookSecret(UUID.randomUUID().toString())` to the builder chain inside createTenant().

### Pitfall 4: Acting Admin Context Not Present in Service Tests
**What goes wrong:** `createdBy` / `lastModifiedBy` fields are null or "SYSTEM_ACCOUNT" in integration tests.
**Why it happens:** Direct service injection without HTTP context means SecurityContextHolder is empty; SpringSecurityAuditorAware falls back to "SYSTEM_ACCOUNT".
**How to avoid:** For AUDIT-03 verification, either: (a) use the HTTP layer with `AdminLogin.loginAsAdmin()` and assert the `last_modified_by` column value in the `_aud` table, OR (b) programmatically set a `UsernamePasswordAuthenticationToken` in SecurityContextHolder before calling the service.
**Warning signs:** `createdBy = "SYSTEM_ACCOUNT"` in test assertions when expecting admin login email.

### Pitfall 5: Envers REVINFO in Wrong Schema
**What goes wrong:** Envers creates REVINFO in the default public schema while all app tables are in `main` schema; join between `_AUD` tables and REVINFO fails.
**Why it happens:** Envers default schema follows the Hibernate `default_schema` setting. Since `default_schema: main`, Envers should use `main` — but V20 must also create tables in `main` to be consistent.
**How to avoid:** All V20 DDL must use `main.` prefix. Verify with `\dt main.*` after migration.

---

## Code Examples

### New Repository Query: Find Existing ROTATED Key for Environment (AKEY-08)
```java
// In TenantApiKeyRepository — source: direct reading of existing codebase pattern
@Query("""
    SELECT k FROM TenantApiKey k
    WHERE k.tenant.id = :tenantId
      AND k.environment = :environment
      AND k.keyStatus = com.softropic.payam.tenant.contract.ApiKeyStatus.ROTATED
    """)
Optional<TenantApiKey> findRotatedKeyByTenantIdAndEnvironment(
    @Param("tenantId") Long tenantId,
    @Param("environment") ApiKeyEnvironment environment
);
```

### Bulk Revoke Query (TENT-07 Suspend)
```java
// In TenantApiKeyRepository
@Transactional
@Modifying
@Query("""
    UPDATE TenantApiKey k
    SET k.keyStatus = com.softropic.payam.tenant.contract.ApiKeyStatus.REVOKED
    WHERE k.tenant.id = :tenantId
      AND k.keyStatus IN (
          com.softropic.payam.tenant.contract.ApiKeyStatus.ACTIVE,
          com.softropic.payam.tenant.contract.ApiKeyStatus.ROTATED
      )
    """)
int revokeAllActiveAndRotatedByTenantId(@Param("tenantId") Long tenantId);
```

### TenantService.suspend() — Service Pattern
```java
@Transactional
public void suspend(String tenantRef) {
    Tenant tenant = tenantRepository.findByTenantRef(tenantRef)
        .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantRef));
    tenant.setTenantStatus(TenantStatus.SUSPENDED);
    tenantRepository.save(tenant);
    keyRepository.revokeAllActiveAndRotatedByTenantId(tenant.getId());
}
```

### TenantService.reactivate() — Return New Key Once
```java
@Transactional
public ApiKeyService.ApiKeyAndRawKey reactivate(String tenantRef) {
    Tenant tenant = tenantRepository.findByTenantRef(tenantRef)
        .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantRef));
    tenant.setTenantStatus(TenantStatus.ACTIVE);
    tenantRepository.save(tenant);
    return apiKeyService.generateAndStore(tenant, ApiKeyEnvironment.PROD);
}
```

### Integration Test Pattern — Envers Audit Verification
```java
// After calling tenantService.updateName(tenantRef, "New Name"):
// Verify Envers captured the change via raw JDBC query on the _aud table.
// Source: established JDBC verification pattern from TenantProvisioningIT
int auditRowCount = jdbcTemplate.queryForObject(
    "SELECT COUNT(*) FROM main.tenant_aud WHERE id = ? AND name = ?",
    Integer.class, tenantId, "New Name");
assertThat(auditRowCount).isGreaterThanOrEqualTo(1);
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `LIVE` environment value | `PROD` | Phase 27 | All tests and service code already migrated; no remaining LIVE references |
| Auto-increment Tenant IDs | TSID (`@Tsid`) via hypersistence-utils | Phase 1 | All entity IDs are Long TSIDs — never use sequence-generated longs in tests |
| No audit trail | Hibernate Envers `@Audited` on AbstractAuditingEntity (inherited) | Phase 1 | Audit tables will be created in V20; no entity changes needed |

---

## Environment Availability

Step 2.6: SKIPPED — Phase 28 is pure service-layer Java code + Flyway SQL migration. No external tools, CLIs, or services beyond the existing PostgreSQL (already running, used by all prior phases) and the standard Spring/Hibernate stack.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test (integration tests); existing pattern |
| Config file | None — Spring Boot auto-configuration |
| Quick run command | `mvn test -pl . -Dtest="TenantProvisioningIT,TenantAdminResourceIT" -q` |
| Full suite command | `mvn verify -q` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TENT-01 | createTenant sets webhookSecret | integration | `mvn test -Dtest="TenantProvisioningIT#createTenant_setsWebhookSecret"` | ❌ Wave 0 — new test method |
| TENT-02 | updateName persists new name | integration | `mvn test -Dtest="TenantServiceIT#updateName_persistsChange"` | ❌ Wave 0 — new test file |
| TENT-03 | updateEmail persists email | integration | `mvn test -Dtest="TenantServiceIT#updateEmail_persistsChange"` | ❌ Wave 0 |
| TENT-04 | updateWebhookUrl persists url | integration | `mvn test -Dtest="TenantServiceIT#updateWebhookUrl_persistsChange"` | ❌ Wave 0 |
| TENT-07 | suspend revokes all keys atomically | integration | `mvn test -Dtest="TenantServiceIT#suspend_revokesAllKeys"` | ❌ Wave 0 |
| TENT-08 | reactivate sets ACTIVE + returns new PROD key | integration | `mvn test -Dtest="TenantServiceIT#reactivate_generatesNewProdKey"` | ❌ Wave 0 |
| AKEY-02 | generateAndStore rejects if ACTIVE key exists for env | integration | `mvn test -Dtest="TenantServiceIT#generateKey_rejectsIfActiveExists"` | ❌ Wave 0 |
| AKEY-04 | rotate returns new key, old ROTATED | integration | `mvn test -Dtest="TenantProvisioningIT#rotate_oldKeyValidDuringGrace"` | ✅ Exists |
| AKEY-06 | revoke makes key unusable | integration | `mvn test -Dtest="TenantProvisioningIT#authenticate_revokedKey_throws"` | ✅ Exists |
| AKEY-08 | double-rotate revokes prior ROTATED key | integration | `mvn test -Dtest="TenantServiceIT#rotate_revokesExistingRotatedKeyForSameEnv"` | ❌ Wave 0 |
| WSEC-01 | webhookSecret present and non-null after createTenant | integration | `mvn test -Dtest="TenantProvisioningIT#createTenant_persistsEntities"` | needs assertion added |
| WSEC-03 | regenerateWebhookSecret replaces old value | integration | `mvn test -Dtest="TenantServiceIT#regenerateWebhookSecret_replacesOldValue"` | ❌ Wave 0 |
| AUDIT-01 | tenant_aud row created on name update | integration | `mvn test -Dtest="TenantAuditIT#updateName_createsAuditRow"` | ❌ Wave 0 — new test file |
| AUDIT-02 | tenant_api_key_aud row created on rotation | integration | `mvn test -Dtest="TenantAuditIT#rotate_createsAuditRow"` | ❌ Wave 0 |
| AUDIT-03 | createdBy on new key row = admin login | integration | `mvn test -Dtest="TenantAuditIT#generateKey_auditCapturesAdminIdentity"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest="TenantProvisioningIT,TenantAdminResourceIT" -q`
- **Per wave merge:** `mvn test -q` (all tenant package tests)
- **Phase gate:** `mvn verify -q` full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/tenant/TenantServiceIT.java` — covers TENT-02, TENT-03, TENT-04, TENT-07, TENT-08, AKEY-02, AKEY-08, WSEC-03
- [ ] `src/test/java/com/softropic/payam/tenant/TenantAuditIT.java` — covers AUDIT-01, AUDIT-02, AUDIT-03
- [ ] New test method in `TenantProvisioningIT` — `createTenant_persistsEntities` needs `webhookSecret` assertion added for WSEC-01

---

## Open Questions

1. **Envers schema resolution — does `default_schema: main` propagate to Envers?**
   - What we know: Hibernate `default_schema: main` is set. Envers uses the configured default schema for its tables.
   - What's unclear: Whether Envers auto-prefixes REVINFO with `main.` or uses the public schema in some edge cases.
   - Recommendation: Explicitly set `org.hibernate.envers.default_schema: main` in application.yaml AND use `main.` prefix in V20 DDL. Belt-and-suspenders.

2. **AKEY-02: Should `generateAndStore()` throw a business exception or let the DB unique index enforce?**
   - What we know: The partial unique index `uidx_tenant_api_key_active_env` will throw `DataIntegrityViolationException` on a second ACTIVE key.
   - What's unclear: Whether Phase 28 needs a service-level guard (check for existing ACTIVE key first) or accepts the DB exception.
   - Recommendation: Add a service-level guard in `generateAndStore()` that throws `IllegalStateException("Active key already exists for environment: " + env)` before the insert attempt. Cleaner error message for REST consumers than a raw SQL constraint error.

---

## Sources

### Primary (HIGH confidence)
- Direct source code reading of all files listed above — `Tenant.java`, `TenantApiKey.java`, `TenantService.java`, `ApiKeyService.java`, `TenantApiKeyRepository.java`, `TenantRepository.java`, `TenantProvisioningIT.java`, `TenantAdminResourceIT.java`, `AbstractAuditingEntity.java`, `SpringSecurityAuditorAware.java`, `SecurityConstants.java`, `application.yaml`, `application-dev.yaml`, V1–V19 migrations
- `pom.xml` — hibernate-envers 6.6.14.Final confirmed
- `.planning/REQUIREMENTS.md` — key decisions section (WebhookSecret plaintext, suspension cascade bulk JPQL, etc.)

### Secondary (MEDIUM confidence)
- Hibernate Envers 6.x behavior with `ddl-auto: none`: when auto-DDL is disabled, Envers never creates REVINFO or `_AUD` tables — requires manual DDL. This is a well-known requirement documented in the Hibernate Envers reference guide.

---

## Metadata

**Confidence breakdown:**
- What exists vs what's missing: HIGH — all source files directly read
- Envers DDL gap: HIGH — `ddl-auto: none` confirmed, no V20 migration file found
- AKEY-08 gap in rotate(): HIGH — code read; guard not present
- WebhookSecret gap in createTenant(): HIGH — code read; not set in builder
- Test infrastructure patterns: HIGH — three existing tenant IT files read
- Envers _AUD table naming (exact column list): MEDIUM — based on entity fields read + standard Envers conventions; verify with actual Hibernate DDL export if schema mismatch occurs

**Research date:** 2026-04-03
**Valid until:** 2026-05-03 (stable domain; low risk of staleness)
