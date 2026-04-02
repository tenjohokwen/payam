# Technology Stack: Payam — v5 Tenant & API Key Management

**Project:** Payam — unified multi-tenant mobile money API for Cameroon
**Researched:** 2026-04-02
**Scope:** Additive stack analysis for v5 Tenant & API Key Management milestone only. Existing stack is NOT re-researched.
**Overall confidence:** HIGH — all findings verified against codebase (read 2026-04-02) and official Spring/Quasar documentation.

---

## Verdict: Zero New Dependencies Required

Every capability needed for v5 is already provided by libraries in the existing codebase. This section documents how to use what is already present correctly, and what NOT to add.

---

## Existing Stack (Do Not Change)

The following is already in place and is the complete foundation for v5:

| Component | Artifact | Relevant to v5 |
|-----------|----------|----------------|
| Framework | spring-boot-starter-parent 3.5.x | All features |
| Security | spring-boot-starter-security + BCryptPasswordEncoder | User passwords only — NOT API keys |
| Persistence | spring-boot-starter-data-jpa + PostgreSQL + Flyway | Tenant/key entities, Quartz schema |
| Audit | hibernate-envers | Tenant + key audit trail (already @Audited on both entities) |
| HMAC/Digest | commons-codec 1.19.0 (DigestUtils) | API key hashing — already in use in ApiKeyService |
| Scheduler | spring-boot-starter-quartz (JDBC store) | Grace-period rotation cleanup job |
| Frontend | Vue 3.5.22 + Quasar 2.16.0 | Admin tenant management screens |
| Events | Spring Modulith (Event Publication Registry) | Email notifications via @ApplicationModuleListener |

---

## Area 1: API Key Hashing

### Decision: SHA-256 via DigestUtils — already implemented, no change needed

**Confidence: HIGH** — Verified by reading `ApiKeyService.java` (2026-04-02).

The existing `ApiKeyService` already uses the correct approach:

```java
// Already in ApiKeyService — commons-codec DigestUtils
String hash = DigestUtils.sha256Hex(rawKey);
```

The stored hash is 64 hex characters (SHA-256 output). Authentication compares `DigestUtils.sha256Hex(incomingKey)` against the stored hash using a direct string equality check inside a JPQL query (`findValidKeyByHash`). This is correct because:

- API keys are 32 cryptographically random bytes (256 bits of entropy) encoded as URL-safe Base64 — effectively a high-entropy random token, not a password.
- SHA-256 is the correct hashing algorithm for high-entropy tokens. BCrypt is designed for low-entropy human passwords (it is intentionally slow to resist brute-force on a small password space). Applying BCrypt to a 256-bit random token gains no security benefit and degrades authentication throughput significantly.
- The industry standard for API key storage (Stripe, GitHub, Twilio) is SHA-256, not BCrypt. Source: https://fly.io/blog/api-tokens-a-tedious-survey/ (pattern confirmed across multiple providers).

**What NOT to do:**

Do NOT use `PasswordEncoder.encode()` / `PasswordEncoder.matches()` (BCrypt) for API key storage or comparison. The `PasswordEncoder` bean in `SecurityConfiguration` is wired for user password authentication. Introducing BCrypt for API keys would:
1. Yield no security improvement (keys are already 256-bit random — the search space is astronomically large regardless of hash algorithm).
2. Add ~100–300ms per authentication check (BCrypt work factor 10).
3. Require changing the existing `findValidKeyByHash` JPQL query to a service-level loop, since BCrypt hashes are not comparable in SQL.

**What the new features need from hashing (v5 additions):**

The v5 spec adds:
- Prefix derived from tenant name (first 3 chars, uppercase, 0-padded) — this is string manipulation, not hashing.
- WebhookSecret: a plain UUID stored as-is — no hashing needed. WebhookSecret is revealed to the admin on demand; it is not a one-way stored credential.

Neither requires a new library.

---

## Area 2: Quartz Job for 24-Hour ROTATED → REVOKED Grace Period

### Decision: New QuartzJobBean + scheduler config — no new library, follows established pattern

**Confidence: HIGH** — Verified by reading `WebhookSchedulerConfig.java`, `ReconciliationSchedulerConfig.java`, `ReconciliationJob.java`, and `application.yaml` (2026-04-02). Quartz JDBC store is already active.

The Quartz infrastructure is fully configured:

```yaml
# Already in application.yaml
quartz:
  job-store-type: jdbc
  jdbc:
    initialize-schema: never
  properties:
    org.quartz.jobStore.tablePrefix: QRTZ_
    org.quartz.jobStore.isClustered: false
    org.quartz.threadPool.threadCount: 3
    org.quartz.jobStore.driverDelegateClass: org.quartz.impl.jdbcjobstore.PostgreSQLDelegate
```

The rotation cleanup job must follow the exact same pattern used by `WebhookSchedulerConfig` (fires every N minutes, `SimpleScheduleBuilder`) rather than `ReconciliationSchedulerConfig` (daily cron). The grace period check is not a one-time daily event — it needs to run frequently enough to catch keys whose 24-hour window has expired since the last check.

**Recommended schedule:** Every 10 minutes. Justification: a 10-minute polling interval means a ROTATED key transitions to REVOKED within 10 minutes of its 24-hour expiry. This is precise enough for the security requirement (keys are blocked when `rotatedAt > 24h ago` in `findValidKeyByHash` regardless of job timing — the job is cleanup, not enforcement).

**Job structure (pattern to implement):**

```java
// RotatedKeyExpiryJob.java — extends QuartzJobBean, same as ReconciliationJob
// Queries: SELECT k FROM TenantApiKey k WHERE k.keyStatus = 'ROTATED'
//            AND k.rotatedAt < :cutoff
// Batch-updates status to REVOKED

// RotatedKeyExpirySchedulerConfig.java — @Configuration bean
// Uses SimpleScheduleBuilder.repeatMinutelyForever(10)
// identity: "rotatedKeyExpiryJob" / "rotatedKeyExpiryTrigger"
```

**Repository query needed (addition to `TenantApiKeyRepository`):**

```java
@Query("SELECT k FROM TenantApiKey k WHERE k.keyStatus = 'ROTATED' AND k.rotatedAt < :cutoff")
List<TenantApiKey> findExpiredRotatedKeys(@Param("cutoff") Instant cutoff);
```

**Thread pool note:** `threadCount: 3` is already configured. Adding a fourth Quartz job does not require increasing the thread count — three concurrent job executions is sufficient for the current job inventory (reconciliation, webhook delivery, MTN poller, Orange poller, rotation cleanup = 5 jobs, but they do not all fire simultaneously). If concurrent overlap becomes an issue, increase `threadCount` to 5 in application.yaml — no code change required.

**Flyway note:** No Flyway migration needed for this job. Quartz QRTZ_* tables already exist (managed by a prior migration, `initialize-schema: never` confirms this). The new job row is inserted by Quartz at startup via `storeDurably()` on the `JobDetail`.

---

## Area 3: Spring Security / PasswordEncoder Applicability to API Keys

### Decision: PasswordEncoder does NOT apply to API keys — use only for user passwords

**Confidence: HIGH** — Verified by reading `SecurityConfiguration.java` (2026-04-02).

The `PasswordEncoder` bean (`BCryptPasswordEncoder`) is wired into `DaoAuthProvider` for user username/password authentication only. It has no role in API key flows.

The `ApiKeyAuthenticationFilter` (which reads the `Authorization: Bearer <rawKey>` header or `X-Api-Key` header) calls `ApiKeyService.authenticate(rawKey)`, which recomputes `DigestUtils.sha256Hex(rawKey)` and queries the database. This is the correct, complete chain. Spring Security's `PasswordEncoder` abstraction is intentionally not in this path.

**One integration point to verify during implementation:** The `TenantSecurityConfig.java` must ensure that the `ApiKeyAuthenticationFilter` is registered with `FilterRegistrationBean(setEnabled=false)` to prevent double-registration as a servlet filter (established pattern noted in PROJECT.md Key Decisions). This is an existing concern, not a v5 addition, but it is the place where API key auth wires into Spring Security.

---

## Area 4: Vue/Quasar Components for One-Time Display and Reveal Toggle

### Decision: All needed components are already in Quasar 2.16.0 — no new frontend libraries

**Confidence: HIGH** — Verified by reading `UpdatePasswordDialog.vue` and `package.json` (2026-04-02). The reveal-toggle pattern is already implemented in the codebase.

#### 4a. One-Time Key Display Dialog

**Pattern:** `q-dialog` (persistent) + `q-card` + `q-banner` (warning) + clipboard copy button.

The project already uses this exact structure in `UpdatePasswordDialog.vue` and `SessionWarningDialog.vue`. The one-time display requirement adds:

- `persistent` prop on `q-dialog` — prevents accidental close via ESC or backdrop click.
- A `q-banner` with `bg-warning` coloring to communicate "this key will not be shown again."
- A `q-btn` with `@click="copyToClipboard"` using the Quasar `useQuasar().$q.copyToClipboard(text)` utility (built into Quasar — no separate clipboard library needed).

```vue
<!-- Pattern: already available in Quasar 2.16.0 -->
<q-dialog v-model="visible" persistent>
  <q-card style="min-width: 480px">
    <q-banner class="bg-warning text-dark q-mb-sm" rounded>
      Copy this key now. It will not be shown again.
    </q-banner>
    <q-card-section>
      <q-input v-model="rawKey" readonly outlined>
        <template #append>
          <q-btn flat icon="content_copy" @click="$q.copyToClipboard(rawKey)" />
        </template>
      </q-input>
    </q-card-section>
    <q-card-actions align="right">
      <q-btn color="primary" label="I have copied it" @click="close" />
    </q-card-actions>
  </q-card>
</q-dialog>
```

`$q.copyToClipboard()` is documented in Quasar 2.x and confirmed available in the installed version. Source: https://quasar.dev/quasar-plugins/copyToClipboard (Quasar 2.x docs).

#### 4b. WebhookSecret Reveal Toggle (Eye Icon)

**Pattern:** Already in `UpdatePasswordDialog.vue`. The exact same `q-input` + `q-icon` toggle pattern applies:

```vue
<!-- Already-established pattern — replicate for webhookSecret field -->
<q-input
  v-model="webhookSecret"
  :type="isRevealed ? 'text' : 'password'"
  label="Webhook Secret"
  outlined
  readonly
>
  <template #append>
    <q-icon
      :name="isRevealed ? 'visibility_off' : 'visibility'"
      class="cursor-pointer"
      @click="isRevealed = !isRevealed"
    />
  </template>
</q-input>
```

The `visibility` and `visibility_off` Material Icons are already included via `@quasar/extras` (confirmed in `package.json`). No new icon pack needed.

#### 4c. Tenant Management Table

**Pattern:** `q-table` with row actions. Already used in `TransactionSearchPage.vue` and `ReconciliationPage.vue`. The tenant list page follows the same pattern with:

- `q-chip` or `q-badge` for status display (ACTIVE = green `positive`, SUSPENDED = red `negative`).
- `q-btn-dropdown` or `q-btn-group` for per-row actions (Suspend, Reactivate, Manage Keys).
- `q-dialog` + `q-card` for confirmation dialogs on destructive actions.

All of these are Quasar built-ins. No new components or libraries required.

#### 4d. Per-Environment Key Management Panel

**Pattern:** `q-tabs` + `q-tab-panels` for PROD / DEV / SANDBOX switching. Available in Quasar 2.x. Each panel shows the current key's prefix, status badge, and action buttons (Rotate, Revoke). No new library needed.

---

## Complete Delta — New Dependencies

**None.**

All v5 capabilities are provided by existing dependencies. The following table confirms each capability maps to an existing library:

| Capability | Library | Already in pom/package.json |
|------------|---------|------------------------------|
| API key SHA-256 hashing | commons-codec 1.19.0 (DigestUtils.sha256Hex) | YES |
| API key comparison | commons-codec (string equality on hex digests) | YES |
| ROTATED→REVOKED grace period job | spring-boot-starter-quartz (JDBC store) | YES |
| Audit trail for tenant/key changes | hibernate-envers (@Audited already on entities) | YES |
| Email notifications for 6 events | Spring Modulith events + existing MailManager | YES |
| One-time key display dialog | Quasar q-dialog + q-input + $q.copyToClipboard | YES |
| Webhook secret reveal toggle | Quasar q-input + q-icon (pattern in UpdatePasswordDialog) | YES |
| Tenant management table | Quasar q-table + q-chip + q-dialog | YES |
| Per-environment key panels | Quasar q-tabs + q-tab-panels | YES |

---

## What NOT to Add

| What | Why Not |
|------|---------|
| BCryptPasswordEncoder for API key hashing | Keys are 256-bit random tokens — BCrypt provides zero security benefit and costs ~200ms per auth. SHA-256 is correct for high-entropy tokens. |
| Argon2 / PBKDF2 for API key hashing | Same reason as BCrypt — intentionally slow KDFs are for low-entropy passwords, not random tokens. |
| Clipboard.js or copy-to-clipboard npm package | Quasar's built-in `$q.copyToClipboard()` covers the requirement. Adding a separate library for a single use case is unnecessary. |
| vue-clipboard2 or similar | Same reason — Quasar 2.x already provides this. |
| ShedLock for the rotation job | Quartz JDBC store already handles distributed exclusive execution. ShedLock solves the same problem; adding both creates conflicting locking mechanisms. |
| A new Quartz JDBC schema migration | The QRTZ_* tables already exist (application.yaml has `initialize-schema: never`, meaning the schema was applied in a prior Flyway migration). The new job is registered at startup automatically via `storeDurably()`. |
| A separate "token prefix" library | The prefix derivation (first 3 chars of tenant name, uppercase, 0-padded) is 2 lines of plain Java — `name.substring(0, Math.min(3, name.length())).toUpperCase()` plus `String.format("%3s", prefix).replace(' ', '0')`. No library warranted. |

---

## Integration Points to Verify During Implementation

These are not new dependencies but implementation-time decisions that need verification:

1. **Tenant name prefix immutability**: The `Tenant.name` field is mutable (no `updatable = false` on the column). The spec requires the API key prefix to be derived from the name at key generation time and never change. The prefix must be stored as a separate immutable column on `TenantApiKey` (already present: `key_prefix` with `updatable = false`). Verify that `ApiKeyService.generateAndStore()` computes the prefix from `tenant.getName()` at generation time and stores it — not a live computation from the current name on each auth check.

2. **One ACTIVE key per environment per tenant**: The spec requires a constraint of one ACTIVE key per environment per tenant. This is a database-level unique partial index:
   ```sql
   CREATE UNIQUE INDEX uix_tenant_env_active_key
   ON main.tenant_api_key (tenant_id, environment)
   WHERE key_status = 'ACTIVE';
   ```
   This goes in a Flyway migration. There is no JPA annotation that expresses a partial unique index — `@UniqueConstraint` applies globally. The service layer must also enforce this before insert (check and revoke any existing ACTIVE key for the same tenant/environment before generating a new one).

3. **Suspension cascades to key revocation**: When `TenantService` sets a tenant status to SUSPENDED, it must bulk-update all ACTIVE and ROTATED keys for that tenant to REVOKED in the same transaction. The `TenantApiKeyRepository` needs a bulk update query for this:
   ```java
   @Modifying
   @Query("UPDATE TenantApiKey k SET k.keyStatus = 'REVOKED' WHERE k.tenant.id = :tenantId AND k.keyStatus IN ('ACTIVE', 'ROTATED')")
   int revokeAllActiveKeysForTenant(@Param("tenantId") Long tenantId);
   ```

4. **WebhookSecret storage**: The spec requires WebhookSecret to be revealable to admins. This means it is stored in plaintext (or symmetrically encrypted if at-rest encryption is needed — but the current codebase has no symmetric encryption layer). The `Tenant.webhookSecret` column already exists as a plain `String` column. Confirm with the team whether plaintext storage is acceptable or if at-rest column encryption is needed. If plaintext is acceptable, no new library is required.

5. **Quartz thread pool**: Current `threadCount: 3`. With the new rotation cleanup job, the job inventory is: reconciliation (daily), webhook delivery (every 1 min), MTN poller, Orange poller, and rotation cleanup (every 10 min). At any given minute, up to 3 jobs could overlap. This is at the thread pool limit. Increase to `threadCount: 5` in application.yaml before shipping v5 to avoid job starvation.

---

## Sources

- Codebase read 2026-04-02:
  - `src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java`
  - `src/main/java/com/softropic/payam/tenant/repo/TenantApiKey.java`
  - `src/main/java/com/softropic/payam/tenant/repo/TenantApiKeyRepository.java`
  - `src/main/java/com/softropic/payam/tenant/repo/Tenant.java`
  - `src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java`
  - `src/main/java/com/softropic/payam/reconciliation/config/ReconciliationSchedulerConfig.java`
  - `src/main/java/com/softropic/payam/reconciliation/service/ReconciliationJob.java`
  - `src/main/java/com/softropic/payam/webhook/config/WebhookSchedulerConfig.java`
  - `src/main/resources/application.yaml`
  - `src/frontend/package.json`
  - `src/frontend/src/components/profile/UpdatePasswordDialog.vue`
  - `.planning/PROJECT.md`
- API token hashing survey (industry pattern confirmation): https://fly.io/blog/api-tokens-a-tedious-survey/
- Quasar copyToClipboard utility: https://quasar.dev/quasar-plugins/copyToClipboard
- Spring Security BCryptPasswordEncoder — intentional slowness for low-entropy passwords: https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html
