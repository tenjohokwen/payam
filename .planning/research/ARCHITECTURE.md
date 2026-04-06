# Architecture Research — v6

**Researched:** 2026-04-07
**Confidence:** HIGH — all findings drawn directly from codebase inspection

---

## REST Controller Integration

### Where New Endpoints Live

The controller shell already exists: `TenantAdminResource` at `com.softropic.payam.tenant.api` with base path `/v1/admin/tenants`. It currently has three endpoints — `POST /` (create), `POST /{tenantId}/keys/{keyId}/rotate`, and `DELETE /{tenantId}/keys/{keyId}`. All six `TenantService` operations not yet exposed as HTTP simply extend this same class and module location.

**Module:** `tenant/api/TenantAdminResource.java` — the only file that needs HTTP additions.

The existing controller is already:
- Annotated `@RestController @RequestMapping("/v1/admin/tenants")`
- `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` per method (ROLE_ADMIN or ROLE_LTD_ADMIN)
- Inside the `/v1/admin/**` exclusion in `TenantSecurityConfig`, so it rides the JWT chain — not the API-key chain
- `AdminTransactionResource` and `AdminMetricsResource` in `admin/api/` follow the identical pattern, confirming this is the right location for admin-facing endpoints

**What to add to `TenantAdminResource`:**

| Method | Path | Service call | Notes |
|--------|------|--------------|-------|
| PATCH | `/{tenantRef}/name` | `TenantService.updateName()` | `204 No Content` |
| PATCH | `/{tenantRef}/email` | `TenantService.updateEmail()` | `204 No Content` |
| PATCH | `/{tenantRef}/webhook-url` | `TenantService.updateWebhookUrl()` | `204 No Content` |
| POST | `/{tenantRef}/suspend` | `TenantService.suspend()` | `204 No Content` |
| POST | `/{tenantRef}/reactivate` | `TenantService.reactivate()` | Returns `ApiKeyDto` with rawKey (one-time) |
| POST | `/{tenantRef}/webhook-secret/regenerate` | `TenantService.regenerateWebhookSecret()` | Returns raw secret in response body |
| GET | `/` | new `TenantQueryService.findAll()` | TENT-05: paginated list |
| GET | `/{tenantRef}` | new `TenantQueryService.findByRef()` | TENT-06: detail view |

For TENT-05/06 list and detail: `TenantService` has no read operations yet. Two options: (a) add `findAll()` / `findByTenantRef()` directly to `TenantService`, or (b) add a thin `TenantQueryService` in `tenant/service/`. Given the codebase pattern (`AdminTransactionQueryService` exists separately for read operations), option (b) is preferable — separates reads from write transactions.

**New contract DTOs needed** (in `tenant/contract/`):
- `TenantDetailDto` — extends `TenantDto` fields plus email, webhookUrl, keyPrefix, list of `ApiKeyDto` (without rawKey)
- `UpdateNameRequest`, `UpdateEmailRequest`, `UpdateWebhookUrlRequest` — simple `@NotBlank`-annotated records
- `WebhookSecretDto` — wraps the returned raw secret for the regenerate endpoint

**No new module, no new package for controllers.** Everything lands in the existing `tenant` module.

---

## TENT-09 Auth Enforcement

### Current Filter Behavior

`ApiKeyAuthenticationFilter.doFilterInternal()` calls `ApiKeyService.authenticate(rawKey)` which runs:

```java
keyRepository.findValidKeyByHash(hash, graceDeadline)
```

The JPQL query checks `k.keyStatus = 'ACTIVE' OR (k.keyStatus = 'ROTATED' AND k.rotatedAt > graceDeadline)` but does NOT check `k.tenant.tenantStatus`. The tenant entity is loaded via `JOIN FETCH k.tenant` — meaning `tenantApiKey.getTenant()` is already populated before the `@Transactional` authenticate() transaction closes.

### Exact Change Required

**File:** `ApiKeyAuthenticationFilter.doFilterInternal()` — the check goes after the `authenticate()` call succeeds, before `TenantContext.set()`.

```java
// After: tenantApiKey = apiKeyService.authenticate(rawKey);
// Add:
if (tenantApiKey.getTenant().getTenantStatus() != TenantStatus.ACTIVE) {
    log.warn("API key rejected — tenant suspended",
        kv("operation", "api_key_auth"),
        kv("keyPrefix", rawKey.contains("_") ? rawKey.substring(0, rawKey.indexOf("_")) : "[unknown]"),
        kv("status", "TENANT_SUSPENDED"));
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Tenant account suspended");
    return;
}
```

This approach:
- Does not change the `ApiKeyService.authenticate()` transaction or the JPQL query — the tenant is already JOIN FETCHed so no extra DB round-trip
- Does not break the existing `authenticate()` contract (it still returns a valid key or throws)
- Keeps the filter as the single enforcement point for both key status and tenant status
- Logs consistently with the existing `kv("operation", "api_key_auth")` pattern

**Alternative rejected:** Adding the tenant status check inside the JPQL query. That would make `authenticate()` ambiguous — the caller cannot distinguish "bad key" from "suspended tenant" without inspecting the result. Keeping the check in the filter preserves the service method's single responsibility.

**Import needed:** `TenantStatus` from `com.softropic.payam.tenant.contract.TenantStatus`.

---

## Email Event Architecture

### Existing Pattern (confirmed from codebase)

There are two established patterns in the codebase:

**Pattern A — `PlatformConfigService` (service publishes, listener transforms):**
1. `@Transactional` service method calls `eventPublisher.publishEvent(new PlatformConfigChangedEvent(...))`
2. `PlatformConfigEmailListener` listens with `@EventListener @Transactional` (plain, not `@TransactionalEventListener`)
3. The listener builds an `Envelope` and calls `publisher.publishEvent(envelope)`
4. `MailManager.sendEmailFromTemplate()` is annotated `@TransactionalEventListener(AFTER_COMMIT)` — it fires after the outer DB transaction commits

**Pattern B — `AccountChangeEmailListener`:** Same two-stage pattern. Domain event fires in transaction → Listener transforms → Envelope event → MailManager fires AFTER_COMMIT.

**Key decision from PROJECT.md:** Use `@EventListener` on the domain listener (not `@TransactionalEventListener`) because `MailManager` handles AFTER_COMMIT on the Envelope event. Double-wrapping would break delivery.

### How to Wire NOTIF-01..06

Six new domain events go in `tenant/contract/event/` (matching the platform pattern: `PlatformConfigChangedEvent` lives in `platform/contract/event/`):

| Event record | Published by | NOTIF |
|---|---|---|
| `ApiKeyGeneratedEvent(tenantRef, tenantName, tenantEmail, environment, keyPrefix)` | `TenantService.createTenant()` and `TenantService.reactivate()` | NOTIF-01 |
| `ApiKeyRotatedEvent(tenantRef, tenantName, tenantEmail, environment, keyPrefix)` | `ApiKeyService.rotate()` | NOTIF-02 |
| `ApiKeyRevokedEvent(tenantRef, tenantName, tenantEmail, environment, keyPrefix)` | `ApiKeyService.revoke()` | NOTIF-03 |
| `TenantStatusChangedEvent(tenantRef, tenantName, tenantEmail, newStatus)` | `TenantService.suspend()` and `TenantService.reactivate()` | NOTIF-04 |
| `WebhookUrlChangedEvent(tenantRef, tenantName, tenantEmail, newWebhookUrl)` | `TenantService.updateWebhookUrl()` | NOTIF-05 |
| `TenantEmailChangedEvent(tenantRef, tenantName, oldEmail, newEmail)` | `TenantService.updateEmail()` | NOTIF-06 |

`ApiKeyService.revoke()` (NOTIF-03) currently has no `ApplicationEventPublisher` dependency — it will need one injected. Both `TenantService` and `ApiKeyService` are `@Transactional` so events published inside their methods will be deferred correctly by `MailManager`'s AFTER_COMMIT listener.

One new listener class handles all six: `TenantLifecycleEmailListener` in `email/infrastructure/listener/`. It takes `@EventListener` methods for each event type, builds an `Envelope` for the appropriate template, and calls `publisher.publishEvent(envelope)`.

**New `EmailTemplate` entries needed** (in `email/contract/EmailTemplate.java`):
```
TENANT_API_KEY_GENERATED("email.tenant.key_generated.title"),
TENANT_API_KEY_ROTATED("email.tenant.key_rotated.title"),
TENANT_API_KEY_REVOKED("email.tenant.key_revoked.title"),
TENANT_STATUS_CHANGED("email.tenant.status_changed.title"),
TENANT_WEBHOOK_URL_CHANGED("email.tenant.webhook_url_changed.title"),
TENANT_EMAIL_CHANGED("email.tenant.email_changed.title"),
```

**Thymeleaf templates** go in `src/main/resources/templates/` following the existing naming convention.

**Recipient construction:** The tenant `email` field (added in V18 migration, `Tenant.getEmail()`) is the notification address. If `email` is null, the listener must skip sending rather than throw. Guard: `if (event.tenantEmail() == null || event.tenantEmail().isBlank()) return;`

**Note on `TenantService.reactivate()`:** This method both changes tenant status (NOTIF-04) and generates a new key (NOTIF-01). Both events should be published from `reactivate()` so the tenant receives both notifications.

### Summary of Modified vs New Files for Email
- `TenantService.java` — MODIFIED: inject `ApplicationEventPublisher`, add publish calls in `createTenant()`, `updateEmail()`, `updateWebhookUrl()`, `suspend()`, `reactivate()`
- `ApiKeyService.java` — MODIFIED: inject `ApplicationEventPublisher`, add publish in `rotate()` and `revoke()`
- `EmailTemplate.java` — MODIFIED: add 6 new enum constants
- NEW: `tenant/contract/event/` package with 6 event records
- NEW: `email/infrastructure/listener/TenantLifecycleEmailListener.java`
- NEW: 6 Thymeleaf `.html` templates

---

## One-Time Key Display (AKEY-07) — Data Flow

### Where the Raw Key Lives

The raw key is **never stored**. In `ApiKeyService.generateAndStore()`:

```java
String rawKey = generateSecureKey(prefix);  // PREFIX_UUID, in-memory only
String hash   = DigestUtils.sha256Hex(rawKey);
// Only hash persisted in TenantApiKey.keyHash
keyRepository.save(entity);
return new ApiKeyAndRawKey(saved, rawKey);  // rawKey returned to caller in-memory only
```

`TenantAdminResource` already returns it:
- `createTenant()` returns `TenantCreationResponse(tenantDto, ApiKeyDto(id, prefix, env, rawKey))`
- `rotateKey()` returns `ApiKeyDto(id, prefix, env, rawKey)`

`ApiKeyDto.rawKey` is documented `// NON-NULL only on creation/rotation — never stored, shown once`

### Backend: Already Correct

The backend one-time display is **already implemented correctly**. No backend changes needed for AKEY-07. The raw key is returned in the HTTP response exactly once and is only held in memory within the request/response cycle. The hash in DB cannot be reversed.

### Frontend: AKEY-07 is a UI Concern Only

1. Admin calls `POST /v1/admin/tenants` or `POST /v1/admin/tenants/{id}/keys/{keyId}/rotate`
2. Response contains `rawKey` in `ApiKeyDto`
3. Vue component opens a `QDialog` with the raw key displayed
4. Dialog shows a "I have copied this key" checkbox or confirmation button — dismissal blocked until confirmed
5. On dismissal, `rawKey` is discarded from component state — never cached in Pinia store

**Component location:** `src/frontend/src/components/tenant/ApiKeyRevealDialog.vue` — reusable by both creation and rotation flows.

**Store rule:** The Pinia tenant store must NOT persist `rawKey`. Store the returned `ApiKeyDto` with `rawKey` set to `null` after the dialog closes.

---

## WebhookSecret Reveal (WSEC-02) — Data Flow

### Current Storage: Plaintext

Confirmed from V8 migration and `Tenant.java`:
- Column: `main.tenant.webhook_secret VARCHAR(255)` — no hash suffix, no salt column
- `TenantService.regenerateWebhookSecret()` stores `UUID.randomUUID().toString()` directly via `tenant.setWebhookSecret(newSecret)`

**The `webhookSecret` is stored plaintext in the DB.** This is intentional: the outbound webhook delivery system needs the raw secret to compute `HMAC-SHA256(payload, secret)` for the `X-Signature` header. Hashing would prevent signing.

### WSEC-02 Reveal Flow

Because the secret is plaintext in DB, reveal requires no decryption:

**Backend — add reveal endpoint to `TenantAdminResource`:**

```
GET /v1/admin/tenants/{tenantRef}/webhook-secret
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
Returns: WebhookSecretDto { String webhookSecret }
```

The service (or query service) reads `tenant.getWebhookSecret()` and returns it. The `TenantDetailDto` returned by `GET /{tenantRef}` must NOT include `webhookSecret` — only the dedicated reveal endpoint exposes it, following least-disclosure.

**Frontend:**
1. `TenantDetailPage.vue` shows a masked field `•••••••••` with an eye icon
2. On eye icon click, call `GET /v1/admin/tenants/{tenantRef}/webhook-secret`
3. Display the returned secret in the field temporarily
4. Do NOT cache the secret in the Pinia store — read on demand each time

**Security note:** Existing `@PreAuthorize(HAS_ADMIN_ROLE)` guard is sufficient for v6. No additional rate-limiting or token-gating needed beyond what the JWT chain already provides.

**Future consideration (out of v6 scope):** If webhook secret moves to a hashed+separate signing key model, both the reveal endpoint and `regenerateWebhookSecret()` would need updates. Current plaintext design is appropriate for single-server deployment.

---

## Suggested Build Order

Dependencies drive the order. Auth enforcement (TENT-09) must land before testing any new endpoints. Email event infrastructure must exist (publish calls + listener) before the email flow can be tested end-to-end. UI depends on all REST endpoints being stable.

### Phase 1 — TENT-09 Auth Enforcement
**Modified:** `ApiKeyAuthenticationFilter.java` only  
**Why first:** Zero new classes. Immediate security correctness. Every subsequent test of tenant suspension relies on this being in place.

### Phase 2 — REST Controller Expansion (all 8 new endpoints)
**Modified:** `TenantAdminResource.java`  
**New:** `TenantQueryService.java`, `TenantDetailDto.java`, `WebhookSecretDto.java`, 3 update request records  
**Includes:** TENT-05 list, TENT-06 detail, all 6 write operations, WSEC-02 reveal endpoint  
**Why second:** Provides the HTTP surface that both email integration tests and the UI depend on.

### Phase 3 — Email Event Infrastructure
**Modified:** `TenantService.java`, `ApiKeyService.java`, `EmailTemplate.java`  
**New:** `tenant/contract/event/` (6 records), `TenantLifecycleEmailListener.java`, 6 Thymeleaf templates  
**Why third:** Service publish calls can only be added once the event types exist. The listener can be added independently of UI. Tests for NOTIF-01..06 can run without UI.

### Phase 4 — Admin UI
**New:** `TenantListPage.vue`, `TenantDetailPage.vue`, `ApiKeyRevealDialog.vue`, `tenant.api.js`, `tenant.store.js`  
**Modified:** `router/routes.js` (add `admin/tenants` and `admin/tenants/:tenantRef`)  
**Why fourth:** Depends on all REST endpoints being stable. AKEY-07 modal is part of TenantDetailPage. WSEC-02 eye-icon is in TenantDetailPage.

### Dependency Graph

```
Phase 1: TENT-09 (filter change — 1 file)
    |
Phase 2: REST endpoints (TenantAdminResource + TenantQueryService + DTOs)
    |                    |
Phase 3: Email events    Phase 4: Admin UI
(can proceed in         (can proceed in
 parallel after Phase 2)  parallel after Phase 2)
```

### Flyway V22

Based on current schema inspection, no new columns are required for v6. The existing schema already has: `tenant_status`, `webhook_secret`, `email`, `key_prefix`, `webhook_url`. V22 is not needed unless a gap is discovered during implementation.

---

## New vs Modified — Complete Map

| File | Status | Phase |
|------|--------|-------|
| `tenant/config/ApiKeyAuthenticationFilter.java` | MODIFIED — add tenant status check after authenticate() | 1 |
| `tenant/api/TenantAdminResource.java` | MODIFIED — add 8 new endpoint methods | 2 |
| `tenant/service/TenantQueryService.java` | NEW — findAll(), findByRef() read operations | 2 |
| `tenant/contract/TenantDetailDto.java` | NEW | 2 |
| `tenant/contract/WebhookSecretDto.java` | NEW | 2 |
| `tenant/contract/UpdateNameRequest.java` | NEW | 2 |
| `tenant/contract/UpdateEmailRequest.java` | NEW | 2 |
| `tenant/contract/UpdateWebhookUrlRequest.java` | NEW | 2 |
| `tenant/contract/event/ApiKeyGeneratedEvent.java` | NEW | 3 |
| `tenant/contract/event/ApiKeyRotatedEvent.java` | NEW | 3 |
| `tenant/contract/event/ApiKeyRevokedEvent.java` | NEW | 3 |
| `tenant/contract/event/TenantStatusChangedEvent.java` | NEW | 3 |
| `tenant/contract/event/WebhookUrlChangedEvent.java` | NEW | 3 |
| `tenant/contract/event/TenantEmailChangedEvent.java` | NEW | 3 |
| `tenant/service/TenantService.java` | MODIFIED — inject publisher, add 5 publish calls | 3 |
| `tenant/service/ApiKeyService.java` | MODIFIED — inject publisher, add 2 publish calls | 3 |
| `email/contract/EmailTemplate.java` | MODIFIED — add 6 enum constants | 3 |
| `email/infrastructure/listener/TenantLifecycleEmailListener.java` | NEW | 3 |
| `resources/templates/tenant-key-generated.html` (and 5 siblings) | NEW (6 files) | 3 |
| `frontend/src/api/tenant.api.js` | NEW | 4 |
| `frontend/src/pages/admin/TenantListPage.vue` | NEW | 4 |
| `frontend/src/pages/admin/TenantDetailPage.vue` | NEW | 4 |
| `frontend/src/components/tenant/ApiKeyRevealDialog.vue` | NEW | 4 |
| `frontend/src/stores/tenant.store.js` | NEW | 4 |
| `frontend/src/router/routes.js` | MODIFIED — add 2 admin tenant routes | 4 |
