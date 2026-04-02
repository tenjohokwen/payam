# Pitfalls Research: Tenant & API Key Management (v5 Milestone)

**Domain:** Multi-tenant API key lifecycle — tenant status, per-environment scoping, rotation grace period, one-time key display, suspension cascade, prefix immutability
**Project:** Payam — Spring Boot 3.5, PostgreSQL, Hibernate Envers, Quartz, Vue 3 Quasar
**Researched:** 2026-04-02
**Confidence:** HIGH (all findings derived from direct codebase inspection + well-understood Spring/JPA/Quartz patterns)

---

## Reading Guide

This file is scoped exclusively to the v5 Tenant & API Key Management milestone — not to the payment domain pitfalls documented in the prior PITFALLS.md. Each pitfall maps to a concrete issue found in the existing code or a known failure mode for the features being added.

**Severity scale:**
- **CRITICAL** — Can cause security breach, data corruption, or undetectable inconsistency
- **HIGH** — Causes functional bugs, failed requirements, or significant rework if not addressed in the relevant phase
- **MEDIUM** — Causes operational pain or subtle edge-case bugs; fixable post-implementation but costly

---

## Critical Pitfalls

### P1 — Prefix Semantics Mismatch: Key-Prefix vs Tenant-Name-Prefix

**What goes wrong:**
The requirements define `prefix` as "first 3 characters of the Tenant Name (uppercase, 0-padded to 3), set at tenant creation, immutable even if name changes." The existing `ApiKeyService.generateAndStore` computes `prefix` as `rawKey.substring(0, 8)` — the first 8 characters of the random Base64 key. These are completely different concepts. The `Tenant` entity has no `keyPrefix` or `namePrefix` column at all.

If the v5 implementation adds per-environment key generation without fixing this, every generated key will carry a random 8-char prefix instead of the 3-char tenant-name prefix. The authentication filter logs `rawKey.substring(0, 8)` as "keyPrefix" for debugging, which will silently work but produce nonsense values — the log entry won't correlate to the tenant name.

**Why it happens:**
The v1 implementation used `key_prefix` as a convenience field for log correlation, not as the identity-carrying tenant prefix described in the requirements. The `key_prefix` column was sized `VARCHAR(8)` for the random-prefix design. The requirement says 3 chars plus underscore = 4 characters (e.g., `AL0_`), which fits in 8 but is structurally different.

**How to avoid:**
1. Add a `key_prefix` column to the `tenant` table (not `tenant_api_key`) — this is the immutable tenant-level prefix derived from the name at creation time.
2. Alter `tenant_api_key.key_prefix` to `VARCHAR(8)` remains fine size-wise, but its value must now be read from `tenant.key_prefix`, not from the raw key.
3. `ApiKeyService.generateAndStore` must accept or look up the tenant's `keyPrefix` field to prepend to the UUID postfix.
4. Rename the `Tenant` field clearly to avoid confusion with the per-key `keyPrefix` field.
5. Flyway migration must backfill `tenant.key_prefix` for existing tenants derived from their name, and re-derive `tenant_api_key.key_prefix` accordingly.

**Warning signs:**
- Generated key format `<8-random-chars>_<uuid>` instead of `<3-letter-tenant-prefix>_<uuid>`.
- `Tenant` entity has no `keyPrefix` or equivalent field after v5 implementation starts.
- Test creating a tenant named "Al" and checking that the key starts with `AL0_`.

**Phase to address:** First phase that creates the entity model and Flyway migration for v5. This must be resolved before any key generation logic is written.

---

### P2 — Environment Column Migration: Existing Keys Default to `LIVE`, Not a Valid New Enum Value

**What goes wrong:**
The `tenant_api_key.environment` column currently defaults to `'LIVE'`. The new per-environment scoping uses `PROD`, `DEV`, `SANDBOX`. If the migration renames `LIVE` → `PROD` for existing rows but forgets to update the column `DEFAULT` clause, any row inserted between migration execution and application deployment will still default to `'LIVE'` — a value the new Java enum does not recognize. This will cause a runtime `IllegalArgumentException` in Hibernate when loading those rows.

The inverse also bites: if the migration only adds a `CHECK` constraint for `('PROD','DEV','SANDBOX')` without an `UPDATE` for existing rows, Flyway migration itself will fail.

**Why it happens:**
Flyway migrations are often written to add the new constraint for future rows but forget to migrate existing data. The `DEFAULT 'LIVE'` in the DDL is a separate clause from the data `UPDATE`.

**How to avoid:**
The Flyway migration for environment scoping must do three things in order within one migration file:
1. `UPDATE main.tenant_api_key SET environment = 'PROD' WHERE environment = 'LIVE'`
2. `ALTER TABLE main.tenant_api_key ALTER COLUMN environment SET DEFAULT 'PROD'`
3. `ALTER TABLE main.tenant_api_key ADD CONSTRAINT chk_api_key_environment CHECK (environment IN ('PROD','DEV','SANDBOX'))`

The `CreateTenantRequest` validator currently accepts `LIVE|SANDBOX` — this regex must be updated in the same phase.

**Warning signs:**
- Flyway migration fails with a constraint violation if the `UPDATE` is missing.
- `IllegalArgumentException: No enum constant ApiKeyEnvironment.LIVE` at runtime.
- Any E2E test that creates a key before the migration and reads it after will expose this.

**Phase to address:** The Flyway migration phase for v5 entity/schema changes.

---

### P3 — One-Active-Key-Per-Environment Constraint: Not Enforced at DB or Service Layer

**What goes wrong:**
The requirements state "a tenant can have only one ACTIVE key per environment at any given time." Neither the schema nor the current service enforces this. The `rotate` method in `ApiKeyService` does not check whether the tenant already has an ACTIVE key in the target environment before generating a new one. A concurrent double-click on the rotate button, or two admin sessions acting simultaneously, could create two ACTIVE keys for the same tenant+environment. The authentication filter would then accept either key — but auditing and reconciliation logic would see two ACTIVE rows, breaking all reporting invariants.

**Why it happens:**
The constraint is a business rule that was not translated into a database-level enforcement. Service-layer checks with `SELECT ... WHERE status = 'ACTIVE'` followed by `INSERT` are vulnerable to TOCTOU races under concurrent requests.

**How to avoid:**
Add a partial unique index at the database level:
```sql
CREATE UNIQUE INDEX uix_tenant_active_key_per_env
    ON main.tenant_api_key (tenant_id, environment)
    WHERE key_status = 'ACTIVE';
```
This makes the database enforce the constraint atomically. The service-layer check (`findActiveKeyByTenantAndEnvironment`) can then return a useful error before attempting the insert, but the index is the safety net. The `rotate` method must set the old key to `ROTATED` before inserting the new ACTIVE key, within a single transaction.

**Warning signs:**
- No partial unique index on `(tenant_id, environment) WHERE key_status = 'ACTIVE'` in the migration.
- `rotate()` service method does not verify environment ownership before generating.
- Concurrency test omitted from E2E suite.

**Phase to address:** The Flyway migration phase and the `ApiKeyService.rotate` implementation phase.

---

### P4 — Suspension Cascade Partial Failure: Non-Atomic Revocation Across All Environments

**What goes wrong:**
When a tenant is suspended, all keys across all environments must be immediately moved to `REVOKED`. If this is implemented as a loop (`for each key: key.setStatus(REVOKED)`), a transient error mid-loop (e.g., optimistic locking conflict from a concurrent rotation) leaves some keys REVOKED and others still ACTIVE or ROTATED. The tenant is now SUSPENDED in the `tenant` table but has live keys. The authentication filter does not check tenant status on every request — it only validates the key. A SUSPENDED tenant with a surviving ACTIVE key can continue processing payments.

**Why it happens:**
Developers implement revocation as an application-level loop without wrapping it in a database-level bulk update. The `@Transactional` annotation on the service method does provide a transaction, but JPA's dirty-check mechanism will attempt individual `UPDATE` statements per entity — if one fails (due to concurrent modification), the transaction rolls back, but the intermediate state may be visible to other threads before rollback completes on some isolation levels.

**How to avoid:**
Use a bulk JPQL update for the suspension cascade:
```java
@Modifying
@Query("UPDATE TenantApiKey k SET k.keyStatus = 'REVOKED' WHERE k.tenant.id = :tenantId AND k.keyStatus IN ('ACTIVE', 'ROTATED')")
int revokeAllActiveAndRotatedKeysForTenant(@Param("tenantId") Long tenantId);
```
This executes as a single SQL `UPDATE` statement. Combined with the `@Transactional` boundary on the suspend method, the whole operation succeeds or fails atomically.

Additionally, the authentication filter should check tenant status on every request — not just key validity. Load it as part of `findValidKeyByHash` (the existing query already does `JOIN FETCH k.tenant`). Add `AND k.tenant.tenantStatus = 'ACTIVE'` to the repository query so a suspended tenant's key is rejected at the query level.

**Warning signs:**
- Suspension implemented with `tenant.getApiKeys().forEach(k -> k.setKeyStatus(REVOKED))` instead of a bulk `@Modifying` JPQL.
- `findValidKeyByHash` query does not filter on `tenant.tenantStatus`.
- No E2E test covering: create tenant, rotate key (now ACTIVE + ROTATED exist), then suspend — verify both keys are rejected after suspension.

**Phase to address:** Tenant status lifecycle implementation phase.

---

### P5 — Reactivation Auto-Key Generation: Raw Key Lost if Not Surfaced to Caller

**What goes wrong:**
Reactivation must generate a new PROD key and display it to the admin exactly once. The generated raw key is returned by `ApiKeyService.generateAndStore` as an in-memory `String`. If `TenantService.reactivateTenant` calls `generateAndStore` internally but returns only a `TenantDto` (not the raw key), the caller has no way to surface the raw key to the frontend. The admin sees "Tenant reactivated" but no key — the PROD key is now unrecoverable. The only remediation is to rotate again.

**Why it happens:**
`TenantService.reactivateTenant` is likely modeled after `suspendTenant` (which just changes status and returns void). Adding key generation to reactivation changes the return contract. If the API layer calls the service and discards the result, or if the result type doesn't include the raw key, the key is silently lost.

**How to avoid:**
Model `reactivateTenant` with a return type analogous to `TenantCreationResult` — a record containing both the `Tenant` and the `ApiKeyAndRawKey`. The REST endpoint must accept this and include the raw key in the response body. The frontend must display it in the same one-time-display modal used at creation.

**Warning signs:**
- `reactivateTenant` returns `void` or `TenantDto`.
- The admin API for reactivation returns `200 OK` with no body or a body without a `rawKey` field.
- No test that calls reactivate and asserts a non-null `rawKey` in the response.

**Phase to address:** Tenant status lifecycle implementation phase.

---

### P6 — Quartz Grace Period Job: TOCTOU Race Between Rotation and Expiry

**What goes wrong:**
The rotation grace period job scans for keys where `key_status = 'ROTATED' AND rotated_at < NOW() - 24h` and moves them to `REVOKED`. If the admin manually revokes or re-rotates a key in the window between the job's `SELECT` and its `UPDATE`, the job may attempt to update a key that is already in a different terminal state. With JPA dirty-check updates, this may silently succeed (overwriting the legitimate state change) or fail with an optimistic locking exception that Quartz swallows unless specifically re-thrown.

Additionally, if the Quartz job runs with `@Transactional` wrapping the full `executeInternal` method (as the existing `ReconciliationJob` does), and the job processes hundreds of expired keys in a single transaction, any single key failure rolls back all revocations in that batch — keys that should have been revoked remain ROTATED.

**Why it happens:**
The existing Quartz job pattern (`ReconciliationJob`, `WebhookDeliveryJob`) uses `@Transactional` on `executeInternal` — correct for those jobs because they process a single logical unit per execution. The grace period job processes N independent entities; wrapping them in one transaction couples their fates incorrectly.

**How to avoid:**
1. Add `@Version` (optimistic locking) to `TenantApiKey`. The job's `UPDATE` will fail with `OptimisticLockException` if the entity was modified concurrently, which is the correct outcome — do not swallow it, log it at WARN level and continue.
2. Process each expired key in its own `@Transactional` sub-transaction, called from a non-transactional `executeInternal`. Follow the `WebhookDeliveryJob` pattern: the outer method is non-transactional; `deliveryService.attemptDelivery` has its own transaction.
3. Use a `@Modifying` bulk update as an alternative: `UPDATE TenantApiKey k SET k.keyStatus = 'REVOKED' WHERE k.keyStatus = 'ROTATED' AND k.rotatedAt < :deadline`. This processes all expired keys atomically without loading entities individually.

**Warning signs:**
- The grace period job has `@Transactional` on `executeInternal` and processes keys in a loop.
- No `@Version` field on `TenantApiKey`.
- No test with concurrent rotation + job execution (the existing test suite includes "concurrency races" — this should join that suite).

**Phase to address:** Quartz grace period job implementation phase.

---

### P7 — One-Time Key Display: No Recovery Path When Admin Closes the Modal

**What goes wrong:**
The raw key is generated server-side, returned in the HTTP response, and never stored. If the admin closes the browser, navigates away, or the modal is dismissed without copying the key, the key is permanently unrecoverable. There is no "show key again" flow — that would require storing the raw key, which violates the one-time display guarantee.

This is the intended design, but the pitfall is failing to make the UX communicate this forcefully. A subtle modal with a "Close" button that is as prominent as a "Copy" button will cause accidental key loss. When it happens, the admin will assume this is a bug and file a support ticket or demand a "reveal key" feature — both are expensive responses to an avoidable UX failure.

**How to avoid:**
1. The modal must have a clear, persistent "Copy to clipboard" button with visual confirmation (e.g., "Copied!" state).
2. The "Close" / "Done" button must be hidden or disabled until the clipboard copy action has been performed at least once, or the admin explicitly acknowledges "I have saved this key" via a checkbox.
3. The frontend must NOT store the key in component state beyond the modal lifecycle — once the modal is confirmed-closed, it is gone from both server and client.
4. Document this behavior explicitly in the admin UI tooltip: "This key will never be shown again."

**Warning signs:**
- The Quasar modal for key display has a standard "Close" (X) button with no copy-first gate.
- No clipboard-copy action tracked in the frontend component.
- No "I have copied this key" acknowledgement step before the close action.

**Phase to address:** Admin UI phase for key generation/rotation display.

---

## High Pitfalls

### P8 — Prefix Immutability Gap: Enforced in Entity but Not at Service Boundary

**What goes wrong:**
`TenantApiKey.keyPrefix` is correctly marked `updatable = false` in the JPA column definition. However, `updatable = false` only prevents JPA from including the column in `UPDATE` statements for the `TenantApiKey` entity itself. It does not prevent:
- A new `TenantApiKey` being created with a different prefix for the same tenant (if the generation logic reads from tenant name instead of from the stored `Tenant.keyPrefix`).
- The admin API accepting a `keyPrefix` in the request body and passing it to the builder.

If `Tenant.keyPrefix` is the source of truth (as it should be), but `ApiKeyService.generateAndStore` recomputes it from `tenant.getName().substring(0,3)` instead of reading `tenant.getKeyPrefix()`, then any tenant name update after creation will cause future keys to use a different prefix — breaking the immutability guarantee silently.

**How to avoid:**
1. The `Tenant` entity stores `keyPrefix` as a database column (`updatable = false`) set at creation from the name.
2. `ApiKeyService.generateAndStore` reads `tenant.getKeyPrefix()` — never recomputes from `tenant.getName()`.
3. The `updateTenant` service method may update `name` but must never touch `keyPrefix`.
4. Add a domain invariant test: create tenant "Google", update name to "Alphabet", generate a new key — assert prefix is still `GOO_`.

**Warning signs:**
- `generateAndStore` contains any call to `tenant.getName()`.
- `Tenant` entity has no `keyPrefix` column.

**Phase to address:** Entity model and `ApiKeyService` refactor phase.

---

### P9 — Hash Collision Risk for `keyHash` Uniqueness: Missing Database-Level UNIQUE Constraint

**What goes wrong:**
SHA-256 produces a 256-bit hash. For practical purposes, collisions are negligible. However, the `key_hash` column in `V1__tenant_schema.sql` has only an index (`idx_tenant_api_key_hash`) — not a `UNIQUE` constraint. If two keys ever produce the same hash (astronomically unlikely with SHA-256, but possible with weaker hash functions if the algorithm is changed later), `findValidKeyByHash` would find and return an arbitrary matching row.

More concretely: without a UNIQUE constraint, there is no database-level guarantee against application bugs that produce duplicate hashes (e.g., inserting the same raw key twice for two different tenants by mistake). With UUID postfixes this is also practically impossible, but the defense-in-depth principle applies.

**How to avoid:**
Add a UNIQUE constraint to `key_hash` in the v5 Flyway migration:
```sql
ALTER TABLE main.tenant_api_key ADD CONSTRAINT uq_tenant_api_key_hash UNIQUE (key_hash);
```
If there are existing duplicate hashes (which should not exist but could in a dev environment), the migration should check and fail fast rather than silently ignoring duplicates.

**Warning signs:**
- `key_hash` column has only an index, not a UNIQUE constraint.

**Phase to address:** Flyway migration phase.

---

### P10 — `findValidKeyByHash` Does Not Check Tenant Status

**What goes wrong:**
The existing `findValidKeyByHash` query validates key status (`ACTIVE` or `ROTATED` within grace period) but does not check `tenant.tenantStatus`. A SUSPENDED tenant's key will successfully authenticate if the key was ACTIVE at the time of suspension and the bulk revocation (P4) fails for any reason.

This is a defense-in-depth gap: even if the cascade correctly revokes all keys, the authentication query should independently verify tenant is ACTIVE. Two independent enforcement points means a single failure in either one is not a security breach.

**How to avoid:**
Extend `findValidKeyByHash` to add `AND k.tenant.tenantStatus = com.softropic.payam.tenant.contract.TenantStatus.ACTIVE` (or via JPQL enum comparison). Since `JOIN FETCH k.tenant` is already in the query, no additional join is required.

**Warning signs:**
- `findValidKeyByHash` does not reference `tenantStatus` in the WHERE clause.
- No test: suspend tenant → attempt request with previously ACTIVE key → assert 401.

**Phase to address:** Tenant status lifecycle implementation phase.

---

### P11 — Envers Audit: Job-Triggered Status Changes Lack Admin Actor

**What goes wrong:**
Hibernate Envers captures `created_by` / `last_modified_by` from `AbstractAuditingEntity` via `AuditingEntityListener`. For admin-triggered actions, the Spring Security context provides the admin's username. For Quartz job-triggered changes (grace period expiry moving ROTATED → REVOKED), there is no authenticated user in the security context — the Quartz thread has no Spring Security principal.

The result: every key revocation triggered by the grace period job will audit with a null or default `last_modified_by`, making it impossible to distinguish admin-revoked from auto-expired keys in the audit trail.

**How to avoid:**
The Quartz job must set a synthetic principal in the security context before the bulk update, and clear it after:
```java
SecurityContextHolder.getContext().setAuthentication(
    new UsernamePasswordAuthenticationToken("SYSTEM:grace-period-job", null, List.of())
);
```
This ensures `AuditingEntityListener` records `last_modified_by = "SYSTEM:grace-period-job"` instead of null. Clear the context in a `finally` block.

**Warning signs:**
- The grace period job does not set a security principal before calling the revocation service.
- Envers audit table shows null `last_modified_by` for auto-expired revocations.

**Phase to address:** Quartz grace period job implementation phase.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Derive prefix from tenant name on every key generation instead of storing it in `Tenant` | No migration needed | Prefix silently changes if name changes | Never — prefix immutability is a hard requirement |
| Service-layer check for one-active-key-per-environment without DB constraint | Simpler migration | Race condition window; two ACTIVE keys possible under concurrent load | Never for production; acceptable in MVP only if concurrency is impossible |
| `@Transactional` on full job `executeInternal` loop | Familiar pattern | One bad key rolls back all revocations | Never — process each key independently |
| Skip `@Version` on `TenantApiKey` | Fewer migration steps | Concurrent edits silently overwrite each other | Never for an entity with multiple concurrent writers |
| One-time key display with no copy-confirmation gate | Faster UI build | Admin key loss, support burden, pressure to add "reveal key" | Never — costs more to recover from than to prevent |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Hibernate Envers + Quartz | Quartz threads have no Spring Security principal → null audit actor | Set synthetic `SYSTEM:job-name` principal before service calls in `executeInternal` |
| JPA `updatable = false` | Prevents UPDATE on existing row but not wrong value on INSERT | Enforce prefix at service layer — always read from `Tenant.keyPrefix`, never recompute from name |
| Flyway + CHECK constraint | Adding constraint without UPDATE for existing data → migration failure | Always run `UPDATE` before `ALTER TABLE ADD CONSTRAINT CHECK` |
| `@Modifying` JPQL + `@Transactional` | `@Modifying` outside a transaction does nothing; JPA first-level cache stale after bulk update | Ensure `@Transactional` wraps the call; add `clearAutomatically = true` to `@Modifying` |
| Spring Modulith `@TransactionalEventListener` + key generation | Reactivation generates a key in an event listener — raw key return path is severed | Return raw key directly from the service method; do not route through event bus |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Loading all tenant keys via `tenant.getApiKeys()` for suspension cascade | N+1 queries; full key list loaded into memory | Use bulk `@Modifying` JPQL update by `tenant_id` | At > ~50 keys per tenant (unlikely but possible in SANDBOX heavy usage) |
| Grace period job loading all ROTATED keys globally | Full table scan if `key_status` index doesn't include `rotated_at` | Add composite index `(key_status, rotated_at)` | At > ~10K rotated keys (possible long-term) |
| Quartz `@DisallowConcurrentExecution` missing from grace period job | Two job instances run concurrently, double-revoking or conflicting | Add `@DisallowConcurrentExecution` annotation | First time Quartz misfires and queues two instances |

---

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Returning raw key in any log statement | Key exposure in Loki/log aggregation | Follow existing `ApiKeyAuthenticationFilter` pattern — log only `keyPrefix`, never `rawKey` |
| Storing raw `webhookSecret` in `tenant.webhook_secret` without hashing | If DB is compromised, all webhook secrets are exposed | The requirement says "admin-revealable via eye icon" — this implies storage in plain or reversible form; if so, treat the column as sensitive and ensure column-level encryption or accept the risk explicitly |
| `webhookSecret` visible in API response for list/detail endpoints | Unnecessary exposure | `webhookSecret` should be null in all list/detail responses; only populated on explicit "reveal" API call |
| Prefix as authentication signal | Attacker who knows the prefix format can target brute force to a specific tenant | The UUID postfix provides the entropy; prefix leakage is low-risk but should never appear in error messages that reach the caller |
| `TenantContext` ThreadLocal not cleared in Quartz threads | If Quartz reuses threads (it does), a previous job's tenant context leaks into the next job | Always set and clear `TenantContext` in Quartz job `executeInternal` via try/finally |

---

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| No copy-first gate on one-time key modal | Admin loses key, cannot recover PROD key without another rotation | Disable "Done" button until clipboard copy confirmed or "I have saved this" checkbox checked |
| Reactivation response shows PROD key in the same location as the status toggle | Admin toggles status expecting a simple confirm, is surprised by a key display modal | Explicit two-step: (1) confirm reactivation, (2) dedicated key display modal with copy flow |
| Environment tabs in key management showing all environments equally | Admin accidentally rotates SANDBOX key thinking it's PROD | PROD key should be visually distinct (badge, color, or explicit label) |
| No visible indicator of which keys are in ROTATED (grace period) state | Admin doesn't know old keys are still active for 24h, may assume rotation is instant | Show ROTATED keys with expiry countdown in the UI |

---

## "Looks Done But Isn't" Checklist

- [ ] **Prefix immutability:** Verify `tenant.key_prefix` column exists (migration) and `generateAndStore` reads from it, NOT from `tenant.name`.
- [ ] **One-active constraint:** Verify partial unique index `(tenant_id, environment) WHERE key_status = 'ACTIVE'` exists in migration.
- [ ] **Suspension cascade:** Verify bulk `@Modifying` JPQL update is used (not entity loop), AND `findValidKeyByHash` filters on `tenantStatus = ACTIVE`.
- [ ] **Reactivation return type:** Verify REST response for reactivation contains a non-null `rawKey` field in the body.
- [ ] **Quartz audit actor:** Verify Envers `last_modified_by` is `SYSTEM:grace-period-job` (not null) for auto-expired revocations.
- [ ] **One-time display gate:** Verify the Quasar modal requires copy confirmation before allowing close.
- [ ] **`key_hash` UNIQUE constraint:** Verify `uq_tenant_api_key_hash` UNIQUE constraint exists (not just index) in migration.
- [ ] **Environment migration:** Verify `UPDATE ... SET environment = 'PROD' WHERE environment = 'LIVE'` runs before CHECK constraint is added.
- [ ] **`@DisallowConcurrentExecution`:** Verify grace period job class has this annotation.
- [ ] **`webhookSecret` reveal-only:** Verify `webhookSecret` is null in all list/detail API responses.

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Prefix mismatch discovered post-launch | HIGH | Data migration: update `tenant.key_prefix` from names; update all `tenant_api_key.key_prefix`; revoke and re-generate all ACTIVE keys (customers must update) |
| Environment column migration corrupts existing keys | HIGH | Restore from backup + replay Flyway from correct checkpoint; or: add corrective Flyway migration to re-set environment values |
| Two ACTIVE keys per environment (race condition) | MEDIUM | Identify via `SELECT ... GROUP BY tenant_id, environment HAVING COUNT(*) > 1`; manually revoke the older one; add the missing DB constraint |
| Admin loses one-time key | LOW | Admin rotates the key; old key enters 24h grace period; new key shown in fresh one-time display |
| Audit trail shows null actor for auto-revocations | LOW | Data is not wrong, just unlabeled; add corrective Flyway migration to update `last_modified_by` for known job-timestamp windows |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| P1 — Prefix semantics mismatch | Entity/schema migration phase | Test: create tenant "Al", assert key starts with `AL0_`; update name, generate key, assert still `AL0_` |
| P2 — Environment column migration | Flyway migration phase | Test: Flyway runs cleanly on test DB with seeded `LIVE` rows; all rows read as `PROD` after |
| P3 — One-active constraint missing | Flyway migration + rotate() phase | Concurrency test: 10 threads rotate same key concurrently → exactly 1 ACTIVE key after |
| P4 — Suspension cascade partial failure | Tenant status lifecycle phase | Test: tenant with ACTIVE + ROTATED key → suspend → authenticate with both → assert 401 both |
| P5 — Reactivation key lost | Tenant status lifecycle phase | Test: suspend → reactivate → assert response body contains non-null rawKey |
| P6 — Quartz TOCTOU race | Grace period job phase | Test: rotate key, immediately revoke manually, run job → assert no exception, key stays REVOKED |
| P7 — One-time modal no copy gate | Admin UI phase | Manual: close modal without copying → verify no "show again" is possible; copy → modal closes cleanly |
| P8 — Prefix recomputed from name | Entity model phase | Test: update tenant name after creation → generate key → assert prefix unchanged |
| P9 — Hash UNIQUE constraint missing | Flyway migration phase | Verify migration SQL contains `UNIQUE` constraint on `key_hash` |
| P10 — Auth filter skips tenant status | Auth filter update phase | Test: suspend tenant → send request with old ACTIVE key → assert 401 |
| P11 — Envers null actor for job | Grace period job phase | Test: trigger job → query Envers audit table → assert `last_modified_by = 'SYSTEM:grace-period-job'` |

---

## Sources

- Direct codebase inspection: `ApiKeyService.java`, `TenantApiKey.java`, `Tenant.java`, `TenantApiKeyRepository.java`, `ApiKeyAuthenticationFilter.java`, `V1__tenant_schema.sql`
- Pattern analysis: `ReconciliationJob.java`, `WebhookDeliveryJob.java` (Quartz job patterns in this codebase)
- Requirements: `requirements/tenant-management.md` (prefix spec, one-active constraint, rotation grace period, reactivation flow)
- Spring Data JPA: `@Modifying` + `clearAutomatically` behavior, `@Version` optimistic locking semantics (HIGH confidence — well-documented behavior)
- Hibernate Envers: audit actor population from Spring Security context in Quartz threads (HIGH confidence — standard Envers behavior)
- PostgreSQL: partial unique index behavior (HIGH confidence — standard PostgreSQL feature)

---
*Pitfalls research for: Payam v5 — Tenant & API Key Management milestone*
*Researched: 2026-04-02*
