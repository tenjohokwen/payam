# Architecture Patterns: Tenant & API Key Management (v5)

**Domain:** Multi-tenant mobile money payment gateway — Tenant lifecycle + API key management
**Researched:** 2026-04-02
**Confidence:** HIGH (based on direct codebase analysis of all affected files)

---

## Context: What Already Exists

The v1 foundation established the following in `com.softropic.payam.tenant`:

| File | Current State | Gap for v5 |
|------|--------------|------------|
| `Tenant` entity | `tenantRef`, `name`, `tenantStatus`, `webhookUrl`, `webhookSecret`, `@OneToMany apiKeys` | Missing: `keyPrefix` (immutable, derived from name at creation), `email` field |
| `TenantApiKey` entity | `keyHash`, `keyPrefix`, `keyStatus (ACTIVE/ROTATED/REVOKED)`, `environment` (VARCHAR "LIVE"), `rotatedAt` | `environment` column is VARCHAR "LIVE" — must become typed enum `PROD/DEV/SANDBOX` |
| `TenantStatus` enum | `ACTIVE`, `SUSPENDED` | Complete — no change needed |
| `ApiKeyStatus` enum | `ACTIVE`, `ROTATED`, `REVOKED` | Complete — no change needed |
| `ApiKeyService` | `generateAndStore()`, `authenticate()`, `rotate()`, `revoke()` | Missing: per-environment uniqueness enforcement, suspension bulk-revoke, prefix format (currently uses random 8 chars, not tenant-name-derived) |
| `TenantService` | `createTenant()` | Missing: `suspend()`, `reactivate()`, `updateTenantEmail()`, `updateWebhookUrl()`, `regenerateWebhookSecret()` |
| `TenantAdminResource` | POST `/v1/admin/tenants`, POST rotate, DELETE revoke | Missing: GET list/detail, PATCH status, PATCH email/webhookUrl, POST regenerate-webhook-secret |
| `TenantApiKeyRepository` | `findValidKeyByHash()`, `findAllByTenantId()` | Missing: `findActiveByTenantAndEnvironment()`, `findRotatedExpiredBefore()`, bulk revoke by tenantId |
| `ApiKeyAuthenticationFilter` | Validates key hash + ROTATED grace window | Must also reject keys whose tenant is SUSPENDED |

**Key schema facts from V1__tenant_schema.sql + V8__tenant_webhook_url.sql:**
- `tenant_api_key.environment` is `VARCHAR(10) NOT NULL DEFAULT 'LIVE'` — needs migration to `PROD/DEV/SANDBOX`
- `tenant.key_prefix` column does not exist — currently only on `TenantApiKey` — must be moved to `Tenant` per requirements (immutable per tenant, not per key)
- `tenant.email` column does not exist — new addition

---

## Modified Entities

### Tenant (modified)

Add two columns via Flyway migration `V18__tenant_v5_fields.sql`:

```sql
ALTER TABLE main.tenant
    ADD COLUMN IF NOT EXISTS email      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS key_prefix VARCHAR(4) NOT NULL DEFAULT 'UNK';

COMMENT ON COLUMN main.tenant.email      IS 'Tenant notification email — nullable; used for 6 event notifications';
COMMENT ON COLUMN main.tenant.key_prefix IS 'Immutable 3-char prefix (uppercase, 0-padded) derived from name at creation';
```

Java entity additions:
- `@Column(name = "email") private String email;` — nullable, editable
- `@Column(name = "key_prefix", nullable = false, updatable = false, length = 4) private String keyPrefix;` — set at creation, never changed

**Prefix derivation rule** (pure function, testable in isolation):
```
name = null or blank → "UNK"
name.length >= 3     → name.substring(0, 3).toUpperCase()
name.length == 2     → name.toUpperCase() + "0"
name.length == 1     → name.toUpperCase() + "00"
```

### TenantApiKey (modified)

Migrate `environment` column from freeform VARCHAR to constrained enum values via `V19__tenant_api_key_environment_enum.sql`:

```sql
-- Rename existing LIVE values to PROD (v1 only used LIVE)
UPDATE main.tenant_api_key SET environment = 'PROD' WHERE environment = 'LIVE';

-- Add check constraint
ALTER TABLE main.tenant_api_key
    ADD CONSTRAINT chk_api_key_environment CHECK (environment IN ('PROD', 'DEV', 'SANDBOX'));

-- Add unique constraint: one ACTIVE key per tenant + environment
CREATE UNIQUE INDEX IF NOT EXISTS uidx_tenant_api_key_active_env
    ON main.tenant_api_key (tenant_id, environment)
    WHERE key_status = 'ACTIVE';
```

Java entity change:
- `environment` field type: `String` → `ApiKeyEnvironment` enum (`PROD`, `DEV`, `SANDBOX`)
- `@Enumerated(EnumType.STRING)` annotation added to `environment`

Add new `ApiKeyEnvironment` enum in `tenant/contract/`:
```java
public enum ApiKeyEnvironment { PROD, DEV, SANDBOX }
```

**`key_prefix` on TenantApiKey** should be retained as a denormalized copy for fast auth-path lookup (key validation does not need to join to Tenant). The `Tenant.keyPrefix` is the source of truth; `TenantApiKey.keyPrefix` is populated from it at generation time.

---

## New Components

### `KeyRotationCleanupJob` (new Quartz job)

**Location:** `tenant/service/KeyRotationCleanupJob.java`  
**Config:** `tenant/config/KeyRotationSchedulerConfig.java`

Pattern mirrors `ReconciliationJob` exactly — extends `QuartzJobBean`, `@Transactional` on `executeInternal`, catch-all prevents trigger unscheduling.

```
Schedule: every hour ("0 0 * * * ?")
Query: SELECT k FROM TenantApiKey k WHERE k.keyStatus = 'ROTATED' AND k.rotatedAt < :cutoff
Action: bulk UPDATE key_status = 'REVOKED' for expired ROTATED keys
Cutoff: Instant.now().minus(Duration.ofHours(24))
```

Required new repository method:
```java
@Query("SELECT k FROM TenantApiKey k WHERE k.keyStatus = 'ROTATED' AND k.rotatedAt < :cutoff")
List<TenantApiKey> findExpiredRotatedKeys(@Param("cutoff") Instant cutoff);
```

Job runs hourly (not daily) because ROTATED keys can expire at any time relative to when rotation was triggered. Daily at 02:00 UTC would allow up to ~47 hours of grace instead of the specified 24.

**No Quartz data map parameters needed** — the job has no parameterised inputs; it queries the DB directly.

### `TenantEventEmailListener` (new)

**Location:** `email/infrastructure/listener/TenantEventEmailListener.java`

Follows the `PlatformConfigEmailListener` pattern exactly:
- `@Component`, `@EventListener` (not `@TransactionalEventListener` — MailManager handles AFTER_COMMIT)
- `@Transactional` on handler method
- Injects `ApplicationEventPublisher` and `${payam.platform.notification-email}`
- Publishes `Envelope` to MailManager for each of the 6 event types

**6 new domain events** in `tenant/contract/event/` (plain POJOs, no Spring base class needed):

| Event class | Fired by | Recipients |
|-------------|----------|------------|
| `ApiKeyGeneratedEvent` | `ApiKeyService.generateAndStore()` | platform email + tenant email (if present) |
| `ApiKeyRotatedEvent` | `ApiKeyService.rotate()` | platform email + tenant email |
| `ApiKeyRevokedEvent` | `ApiKeyService.revoke()` + bulk suspend | platform email + tenant email |
| `TenantStatusChangedEvent` | `TenantService.suspend()` / `reactivate()` | platform email + tenant email |
| `WebhookSecretRegeneratedEvent` | `TenantService.regenerateWebhookSecret()` | platform email + tenant email |
| `TenantContactChangedEvent` | `TenantService.updateEmail()` / `updateWebhookUrl()` | platform email + tenant email |

**6 new EmailTemplate enum values** in `email/contract/EmailTemplate.java`:
```
TENANT_API_KEY_GENERATED("email.tenant.api_key_generated.title")
TENANT_API_KEY_ROTATED("email.tenant.api_key_rotated.title")
TENANT_API_KEY_REVOKED("email.tenant.api_key_revoked.title")
TENANT_STATUS_CHANGED("email.tenant.status_changed.title")
TENANT_WEBHOOK_SECRET_REGENERATED("email.tenant.webhook_secret_regenerated.title")
TENANT_CONTACT_CHANGED("email.tenant.contact_changed.title")
```

### New REST endpoints on `TenantAdminResource`

Add to existing `TenantAdminResource` or split into a second controller if the file grows unwieldy:

| Method | Path | Action |
|--------|------|--------|
| GET | `/v1/admin/tenants` | List all tenants (paginated) |
| GET | `/v1/admin/tenants/{tenantId}` | Get single tenant with key list |
| PATCH | `/v1/admin/tenants/{tenantId}` | Update name, email, webhookUrl |
| POST | `/v1/admin/tenants/{tenantId}/suspend` | Suspend + bulk revoke all keys |
| POST | `/v1/admin/tenants/{tenantId}/reactivate` | Reactivate + auto-generate PROD key |
| POST | `/v1/admin/tenants/{tenantId}/webhook-secret` | Regenerate webhookSecret |
| GET | `/v1/admin/tenants/{tenantId}/webhook-secret` | Reveal webhookSecret (one-time display equivalent — returns current plaintext) |
| POST | `/v1/admin/tenants/{tenantId}/keys` | Generate new key for specified environment |
| POST | `/v1/admin/tenants/{tenantId}/keys/{keyId}/rotate` | Existing — no change |
| DELETE | `/v1/admin/tenants/{tenantId}/keys/{keyId}` | Existing — no change |

Note on `webhookSecret` reveal: unlike API keys (which are hashed and can never be retrieved), the webhookSecret is stored in plaintext in the `tenant.webhook_secret` column and is revealed on demand via the eye-icon UI. The GET reveal endpoint returns the current value directly.

### DTO additions

- `TenantDto` — add `email`, `keyPrefix`, `webhookUrl` fields
- `ApiKeyDto` — add `keyStatus`, `environment (ApiKeyEnvironment)`, `rotatedAt` fields; `rawKey` remains nullable (non-null only on generation/rotation)
- New `TenantDetailDto` record — wraps `TenantDto` + `List<ApiKeyDto>` for the GET single-tenant endpoint

---

## Data Flow

### Tenant Creation (enhanced)

```
POST /v1/admin/tenants
  → TenantAdminResource.createTenant(name, email, environment)
  → TenantService.createTenant(name, email, environment)
      → derive keyPrefix from name (pure function)
      → Tenant.builder().keyPrefix(keyPrefix).email(email)...save()
      → ApiKeyService.generateAndStore(tenant, PROD)
          → rawKey = prefix_UUID (prefix = tenant.keyPrefix)
          → hash = SHA-256(rawKey)
          → TenantApiKey.builder().keyStatus(ACTIVE).environment(PROD)...save()
          → publish ApiKeyGeneratedEvent
      → return TenantCreationResult(tenant, key, rawKey)
  → TenantAdminResource maps to TenantCreationResponse (rawKey shown once)
  → TenantEventEmailListener handles ApiKeyGeneratedEvent → sends email
```

**Key format change:** Currently `ApiKeyService.generateAndStore()` uses a random 32-byte Base64 string and takes the first 8 chars as prefix. This must change to `tenant.keyPrefix + "_" + UUID.randomUUID()`. The `key_prefix` stored on `TenantApiKey` becomes a denormalized copy from the parent `Tenant.keyPrefix`.

### Suspension Flow (synchronous cascade)

```
POST /v1/admin/tenants/{tenantId}/suspend
  → TenantService.suspend(tenantId, adminId)
      → load Tenant (verify status = ACTIVE)
      → tenant.setTenantStatus(SUSPENDED)
      → tenantRepository.save(tenant)
      → keyRepository.bulkRevokeAllForTenant(tenantId)  ← single UPDATE query
      → publish TenantStatusChangedEvent(OLD=ACTIVE, NEW=SUSPENDED)
      → publish ApiKeyRevokedEvent(count=N, reason=TENANT_SUSPENDED)
      → return updated TenantDto
```

**Synchronous vs event-driven for cascade revocation:** Revocation MUST be synchronous within the same transaction. The suspension and all key revocations must commit atomically. If revocation were event-driven (AFTER_COMMIT), there is a window between the tenant being SUSPENDED and the keys being revoked where a merchant could still authenticate. Given that the `ApiKeyAuthenticationFilter` checks key status but does NOT check tenant status (current codebase), synchronous within-transaction revocation is the only safe approach.

The `ApiKeyAuthenticationFilter` should also be extended to reject keys where `tenant.tenantStatus == SUSPENDED` as a defense-in-depth guard (handles any edge case where keys were not fully revoked, e.g. a partial failure).

Bulk revoke via a single JPQL UPDATE (not N individual saves):
```java
@Modifying
@Query("UPDATE TenantApiKey k SET k.keyStatus = 'REVOKED' WHERE k.tenant.id = :tenantId AND k.keyStatus <> 'REVOKED'")
int bulkRevokeAllForTenant(@Param("tenantId") Long tenantId);
```

### Reactivation Flow

```
POST /v1/admin/tenants/{tenantId}/reactivate
  → TenantService.reactivate(tenantId, adminId)
      → load Tenant (verify status = SUSPENDED)
      → tenant.setTenantStatus(ACTIVE)
      → tenantRepository.save(tenant)
      → ApiKeyService.generateAndStore(tenant, PROD)
          → rawKey = tenant.keyPrefix + "_" + UUID
          → publish ApiKeyGeneratedEvent
      → publish TenantStatusChangedEvent(OLD=SUSPENDED, NEW=ACTIVE)
      → return TenantReactivationResult(tenant, newKey, rawKey)
```

The admin UI must display the `rawKey` from `TenantReactivationResult` in a one-time reveal dialog — same pattern as tenant creation.

### Key Rotation (current flow — minor change)

Current `rotate()` generates a new key using `old.getTenant()` which loads the tenant proxy. With the new prefix format, the `keyPrefix` stored on the tenant drives the new key's prefix — no behavioral change needed, but `generateAndStore()` must read from `tenant.keyPrefix` rather than computing it from the raw key.

### Authentication Filter (extended)

`ApiKeyAuthenticationFilter.doFilterInternal()` currently only validates key status. Add tenant status check:

```
After successful key lookup:
  if (tenantApiKey.getTenant().getTenantStatus() == SUSPENDED) {
      → 401 Unauthorized
  }
```

This requires `JOIN FETCH k.tenant` to already be in `findValidKeyByHash()` — it is, so no query change needed.

---

## Event Flow Summary

```
TenantService / ApiKeyService
        │
        │  publish domain event (ApplicationEventPublisher)
        ▼
TenantEventEmailListener (@EventListener, @Transactional)
        │
        │  publish Envelope (ApplicationEventPublisher)
        ▼
MailManager (@TransactionalEventListener AFTER_COMMIT, @Async)
        │
        ├─ sends to: platform-notification-email (always)
        └─ sends to: tenant.email (when not null)
```

Events fire within the same thread/transaction as the service operation. `@EventListener` (not `@TransactionalEventListener`) on `TenantEventEmailListener` means the Envelope publication happens before commit. MailManager's `@TransactionalEventListener(AFTER_COMMIT)` then fires after the outer transaction commits — this is the established pattern from `PlatformConfigEmailListener`.

Hibernate Envers captures all entity mutations automatically via `@Audited` on `Tenant` and `TenantApiKey` — no additional code needed for the audit trail. The `created_by` / `last_modified_by` fields from `AbstractAuditingEntity` capture the admin JWT principal at each mutation.

---

## Admin UI Integration

### Route additions to `src/frontend/src/router/routes.js`

Under the existing `path: 'admin'` children array:

```javascript
{
  path: 'tenants',
  component: () => import('pages/admin/TenantListPage.vue'),
  meta: { requiresAuth: true },
},
{
  path: 'tenants/:tenantId',
  component: () => import('pages/admin/TenantDetailPage.vue'),
  meta: { requiresAuth: true },
},
```

### New API methods in `src/frontend/src/api/admin.api.js`

Add to the existing `adminApi` object:

```javascript
// Tenant management
listTenants(params = {})          → GET /v1/admin/tenants
getTenant(tenantId)               → GET /v1/admin/tenants/:id
createTenant(payload)             → POST /v1/admin/tenants
updateTenant(tenantId, payload)   → PATCH /v1/admin/tenants/:id
suspendTenant(tenantId)           → POST /v1/admin/tenants/:id/suspend
reactivateTenant(tenantId)        → POST /v1/admin/tenants/:id/reactivate
regenerateWebhookSecret(tenantId) → POST /v1/admin/tenants/:id/webhook-secret
revealWebhookSecret(tenantId)     → GET /v1/admin/tenants/:id/webhook-secret
generateKey(tenantId, env)        → POST /v1/admin/tenants/:id/keys
rotateKey(tenantId, keyId)        → POST /v1/admin/tenants/:id/keys/:keyId/rotate  (existing)
revokeKey(tenantId, keyId)        → DELETE /v1/admin/tenants/:id/keys/:keyId        (existing)
```

### New pages

| Page | Responsibilities |
|------|-----------------|
| `TenantListPage.vue` | Paginated table of tenants, status badge (ACTIVE/SUSPENDED), link to detail, Create Tenant button |
| `TenantDetailPage.vue` | Edit name/email/webhookUrl, status toggle (Suspend/Reactivate), key management per environment, webhook secret reveal/regenerate |

**One-time key reveal pattern** (used in TenantDetailPage for reactivation, and in TenantListPage for creation): Use a Quasar `QDialog` that shows the raw key and a copy-to-clipboard button. Once the dialog is dismissed the raw key is gone from state. This matches the pattern used in v1 for initial key display.

**Webhook secret reveal**: eye-icon button toggles visibility of the currently stored value (fetched once per reveal click, not persisted in frontend state between views).

### Navigation

Add a "Tenants" entry to the sidebar navigation in `MainLayout.vue`, guarded to `ROLE_ADMIN` — same pattern as existing admin nav items.

---

## Component Boundaries

| Component | Responsibility | Dependencies |
|-----------|---------------|-------------|
| `Tenant` entity | State holder — status, contact info, keyPrefix | `AbstractAuditingEntity`, Envers |
| `TenantApiKey` entity | Key lifecycle — hash, status, environment, rotation timestamp | `Tenant` (FK) |
| `ApiKeyService` | Key CRUD: generate, rotate, revoke, bulk-revoke, authenticate | `TenantApiKeyRepository` |
| `TenantService` | Tenant CRUD + lifecycle transitions | `TenantRepository`, `ApiKeyService`, `ApplicationEventPublisher` |
| `TenantAdminResource` | REST surface for admin operations | `TenantService`, `ApiKeyService` |
| `ApiKeyAuthenticationFilter` | Request auth — validates key hash + status + tenant status | `ApiKeyService` |
| `KeyRotationCleanupJob` | Scheduled ROTATED→REVOKED sweep | `TenantApiKeyRepository` |
| `TenantEventEmailListener` | Translates domain events → Envelope publications | `ApplicationEventPublisher`, `@Value notification-email` |
| `MailManager` | Async email delivery with circuit breaker + retry | (unchanged) |
| Frontend `TenantListPage` | List + create tenants | `adminApi` |
| Frontend `TenantDetailPage` | Edit + key management + webhook secret | `adminApi` |

---

## Build Order

Dependencies drive this order. Each item must be complete before the next begins.

### Phase 1 — Schema + Enum Migration
- `V18__tenant_v5_fields.sql` — add `tenant.email`, `tenant.key_prefix`
- `V19__tenant_api_key_environment_enum.sql` — migrate `environment` to `PROD/DEV/SANDBOX`, add partial unique index
- New `ApiKeyEnvironment` enum in `tenant/contract/`
- Update `TenantApiKey.environment` field type to `ApiKeyEnvironment`
- Update `Tenant` entity — add `email`, `keyPrefix` fields

*Rationale: All subsequent work depends on the schema being correct. Run tests here to confirm migrations apply cleanly against Testcontainers.*

### Phase 2 — Service Layer Changes
- `ApiKeyService.generateAndStore()` — adopt new prefix format (`tenant.keyPrefix + "_" + UUID`), enforce per-environment ACTIVE uniqueness, publish `ApiKeyGeneratedEvent`
- `ApiKeyService.rotate()` — publish `ApiKeyRotatedEvent`
- `ApiKeyService.revoke()` — publish `ApiKeyRevokedEvent`
- `TenantApiKeyRepository` — add `bulkRevokeAllForTenant()`, `findExpiredRotatedKeys()`, `findActiveByTenantAndEnvironment()`
- `TenantService.createTenant()` — accept `email`, derive and store `keyPrefix`
- `TenantService.suspend()`, `reactivate()`, `updateEmail()`, `updateWebhookUrl()`, `regenerateWebhookSecret()`
- `TenantService` publishes `TenantStatusChangedEvent`, `TenantContactChangedEvent`, `WebhookSecretRegeneratedEvent`

*Rationale: Repository must exist before service can call it; events must be defined before listeners can consume them.*

### Phase 3 — Quartz Rotation Cleanup Job
- `KeyRotationCleanupJob extends QuartzJobBean`
- `KeyRotationSchedulerConfig` — hourly cron trigger
- Unit test: expired ROTATED keys → REVOKED; non-expired → untouched

*Rationale: Independent of email and UI; can be built and tested in isolation.*

### Phase 4 — Email Notifications
- 6 new `EmailTemplate` values
- 6 domain event classes in `tenant/contract/event/`
- `TenantEventEmailListener`
- 6 Thymeleaf email templates
- Integration test: each event type → correct Envelope published → MailManager invoked

*Rationale: Depends on Phase 2 events being defined. Independent of UI.*

### Phase 5 — REST API Expansion
- New endpoints on `TenantAdminResource` (or split to `TenantAdminDetailResource`)
- Updated `TenantDto`, `ApiKeyDto`, new `TenantDetailDto`
- `CreateTenantRequest` — add `email` field, change `environment` validation to `PROD|DEV|SANDBOX`
- Authentication filter — add tenant SUSPENDED check

*Rationale: Depends on Phases 2-4 being complete so responses carry correct data and events fire.*

### Phase 6 — Admin UI
- `admin.api.js` additions
- `TenantListPage.vue`
- `TenantDetailPage.vue`
- Route additions
- Nav item in `MainLayout.vue`

*Rationale: Backend API must be complete before UI can be built and tested against it.*

### Phase 7 — E2E Tests
- Test: tenant SUSPENDED → all keys rejected by auth filter
- Test: reactivation → new PROD key shown, auth succeeds
- Test: ROTATED key within 24h → auth succeeds; after cleanup job → auth fails
- Test: two ACTIVE keys in same env → constraint violation
- Test: email events fired for all 6 event types

---

## Critical Integration Points

### `ApiKeyAuthenticationFilter` — tenant status check
The filter currently only checks `key_status` via the query (ACTIVE or ROTATED within grace window). It does NOT check `tenant_status`. A suspended tenant's keys will pass the hash check if the keys were not revoked (e.g., concurrent failure). Add explicit tenant status rejection after line 120 (`tenantApiKey = apiKeyService.authenticate(rawKey);`).

### `key_prefix` format change
`ApiKeyService.generateAndStore()` currently assigns `rawKey.substring(0, 8)` as `keyPrefix` — a random Base64 substring, not the tenant-name-derived prefix. This is wrong relative to the requirements. The `keyPrefix` on both `Tenant` and `TenantApiKey` must be derived from the tenant name. The raw key value must be `tenantKeyPrefix + "_" + UUID.randomUUID()` where the prefix is 3 chars.

### Per-environment uniqueness constraint
The partial unique index (`WHERE key_status = 'ACTIVE'`) must be added via Flyway migration, not only enforced in application code. Application-level checks can race under concurrent rotation requests.

### `CreateTenantRequest.environment` validation
Currently validates `LIVE|SANDBOX`. Must change to `PROD|DEV|SANDBOX`. Any existing integration tests using `LIVE` must be updated.

### Envers audit with admin ID
`AbstractAuditingEntity.created_by` / `last_modified_by` are populated by Spring Data's `AuditorAware` implementation from the SecurityContextHolder. All tenant/key mutations via `TenantAdminResource` happen in a JWT-authenticated admin request — the admin's username is already in the security context and will be captured automatically. No additional audit instrumentation is needed beyond what `@Audited` already provides.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Modified entity schema | HIGH | V1/V8 migrations read directly; gaps identified precisely |
| Service layer changes | HIGH | All service/repo files read; gaps catalogued |
| Quartz job pattern | HIGH | ReconciliationJob + ReconciliationSchedulerConfig read directly |
| Email event pattern | HIGH | PlatformConfigEmailListener + MailManager read directly |
| Authentication filter change | HIGH | ApiKeyAuthenticationFilter read line-by-line |
| Frontend integration | HIGH | All route/api/page files read; pattern is clear |
| Build order | HIGH | Dependency chain verified from code |

---

*Last updated: 2026-04-02*
