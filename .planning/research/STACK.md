# Stack Research — v6

**Project:** Payam — unified multi-tenant mobile money API for Cameroon
**Researched:** 2026-04-07
**Scope:** Additive stack analysis for v6 REST API Surface, Notifications & Admin UI only.
**Overall confidence:** HIGH — findings verified against codebase read 2026-04-07.

---

## Verdict: Zero New Dependencies Required

Every capability needed for v6 is already provided by libraries already in pom.xml and package.json.
This file documents integration points, usage patterns, and what NOT to add.

---

## New Dependencies Required

**None.**

The full v6 feature set — 6 REST endpoints, `@TransactionalEventListener` notifications, TENT-09 filter
enforcement, and the Admin UI screens — maps entirely to existing stack components:

| v6 Capability | Library Already Present | Confidence |
|---------------|------------------------|------------|
| REST controllers (6 endpoints) | spring-boot-starter-web (Spring MVC already in use) | HIGH |
| Request validation | spring-boot-starter-validation (jakarta.validation in TenantAdminResource already) | HIGH |
| RBAC on new endpoints | spring-boot-starter-security + @PreAuthorize (established pattern) | HIGH |
| @TransactionalEventListener notifications | Spring TX event bus (MailManager.sendEmailFromTemplate already uses AFTER_COMMIT) | HIGH |
| Email delivery for 6 new event types | MailManager + Thymeleaf (existing — just add new templates + EmailTemplate entries) | HIGH |
| TENT-09 SUSPENDED check in filter | ApiKeyAuthenticationFilter + ApiKeyService.authenticate() (already exists, add tenant status check) | HIGH |
| Tenant list + detail endpoints | Spring MVC + Spring Data JPA (TenantRepository.findAll() / findByTenantRef()) | HIGH |
| Admin UI pages (tenant CRUD, status toggle) | Vue 3.5.22 + Quasar 2.16.0 q-table/q-dialog/q-form (same pattern as existing admin pages) | HIGH |
| One-time key modal (AKEY-07) | Quasar q-dialog (persistent) + $q.copyToClipboard() — documented in v5 STACK.md, applies here | HIGH |
| WebhookSecret reveal UI (WSEC-02) | Quasar q-input type toggle — pattern exists in UpdatePasswordDialog.vue | HIGH |

---

## Stack Integration Points

### 1. REST Controllers — Spring MVC + @PreAuthorize

**Existing anchor:** `TenantAdminResource` at `/v1/admin/tenants` already exists with `createTenant`,
`rotateKey`, `revokeKey`. All three follow the same pattern:

- Class: `@RestController` + `@RequestMapping("/v1/admin/tenants")`
- Method auth: `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` (resolves to ROLE_ADMIN or ROLE_LTD_ADMIN)
- Validation: `@Valid` on `@RequestBody`, jakarta.validation constraints on request records

v6 adds 6 more operations to the same controller or as extensions of it:

| Operation | HTTP Method | Path | Service Call |
|-----------|-------------|------|--------------|
| Update name | PATCH or PUT | `/{tenantRef}/name` | TenantService.updateName() |
| Update email | PATCH or PUT | `/{tenantRef}/email` | TenantService.updateEmail() |
| Update webhookUrl | PATCH or PUT | `/{tenantRef}/webhook-url` | TenantService.updateWebhookUrl() |
| Suspend | POST | `/{tenantRef}/suspend` | TenantService.suspend() |
| Reactivate | POST | `/{tenantRef}/reactivate` | TenantService.reactivate() |
| Regenerate webhookSecret | POST | `/{tenantRef}/webhook-secret` | TenantService.regenerateWebhookSecret() |

For TENT-05/06 (list + detail): add `@GetMapping` for `GET /v1/admin/tenants` and
`GET /v1/admin/tenants/{tenantRef}`. These require `TenantRepository.findAll(Pageable)` and
`findByTenantRef()` — both already available.

**Security chain note:** All `/v1/admin/**` paths are already excluded from `TenantSecurityConfig`
(the API-key chain) and fall through to the JWT filter chain. No security configuration changes
are needed for the new admin REST endpoints.

**Response for reactivate:** `TenantService.reactivate()` returns `ApiKeyAndRawKey` containing the
new raw key. The controller wraps this in an `ApiKeyDto` (same type as rotateKey response) — the
raw key is included once and only in this response, never logged or stored.

### 2. @TransactionalEventListener Notifications — MailManager Pattern

**Existing anchor:** `MailManager.sendEmailFromTemplate()` is already annotated
`@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async("sendMailPool")`. Any Spring event
of type `Envelope` published via `ApplicationEventPublisher.publishEvent(envelope)` within a
transaction is automatically picked up after commit.

**The existing listener chain is:**

```
Service method (@Transactional)
  → applicationEventPublisher.publishEvent(new SomeDomainEvent(...))
  → DomainEventListener (@EventListener or @TransactionalEventListener)
       → publisher.publishEvent(new Envelope(...))
  → MailManager.sendEmailFromTemplate(@TransactionalEventListener AFTER_COMMIT)
       → circuit breaker → retry → Thymeleaf → SMTP
```

**v6 notification events (NOTIF-01..06) follow the same two-step pattern:**

- Step A: Service method publishes a new domain event record (e.g. `TenantKeyGeneratedEvent`,
  `TenantSuspendedEvent`, etc.) using `ApplicationEventPublisher` injected into `TenantService`
  or `ApiKeyService`.
- Step B: A new `TenantNotificationEmailListener` in `email/infrastructure/listener/` listens with
  `@TransactionalEventListener(phase = AFTER_COMMIT)` or plain `@EventListener` (per the pattern
  established in `PlatformConfigEmailListener` — use `@EventListener` when `MailManager` handles
  the final AFTER_COMMIT listener, avoiding double-wrapping as noted in Key Decisions).

**EmailTemplate additions needed:** Add 6 new entries to `EmailTemplate` enum. Matching Thymeleaf
template files needed in `src/main/resources/templates/`. The enum and template naming follow the
established pattern (e.g. `TENANT_KEY_GENERATED`, `TENANT_SUSPENDED`, etc.).

**Who receives notifications:** The `Tenant.email` field (already on the entity) is the recipient
for all 6 tenant/key lifecycle events. The listener builds the `Recipient` from `tenant.getEmail()`.
If `tenant.getEmail()` is null (tenant has no email configured), the listener must guard with a
null check and log a warning rather than throwing — partial absence of an email address must not
fail the primary transaction.

### 3. TENT-09: SUSPENDED Status Check in ApiKeyAuthenticationFilter

**Existing anchor:** `ApiKeyAuthenticationFilter.doFilterInternal()` calls
`apiKeyService.authenticate(rawKey)`, which queries `TenantApiKeyRepository.findValidKeyByHash()`.
The query already JOIN FETCHes the tenant.

**Integration point:** The `authenticate()` method or the filter itself must check
`tenantApiKey.getTenant().getTenantStatus()` after the key is resolved. If the status is
`TenantStatus.SUSPENDED`, send HTTP 403 (not 401 — the key is valid but the tenant is suspended).

Two implementation options; the simpler one is preferred:

Option A (preferred — service layer): In `ApiKeyService.authenticate()`, after the key is found,
add:
```java
if (key.getTenant().getTenantStatus() == TenantStatus.SUSPENDED) {
    throw new TenantSuspendedException("Tenant is suspended: " + key.getTenant().getTenantRef());
}
```
The filter catches this specific exception and returns 403. This keeps auth logic in the service.

Option B (filter layer): Check directly in `ApiKeyAuthenticationFilter.doFilterInternal()` after
`authenticate()` returns. Functionally equivalent but mixes business logic into the filter.

**`TenantSuspendedException`** should be a new unchecked exception (extends `RuntimeException`).
`ApiKeyService.authenticate()` is `@Transactional(readOnly = true)` — adding this check requires
no transaction scope change.

**No new Spring Security mechanism needed.** The filter already controls the response directly
(`response.sendError()`). A 403 path is added alongside the existing 401 paths.

### 4. Admin UI — Vue 3 + Quasar (Existing Frontend Stack)

**Existing anchors:**
- `src/frontend/src/pages/admin/` — 5 existing admin pages (AdminDashboardPage, HealthDashboardPage,
  PlatformConfigPage, ReconciliationPage, TransactionSearchPage/DetailPage)
- `src/frontend/src/api/admin.api.js` — existing admin API client using axios
- `src/frontend/src/router/routes.js` — existing admin route subtree at `/admin`

**New pages needed (same pattern as existing admin pages):**

| Page | Path | Quasar Components |
|------|------|-------------------|
| Tenant list | `/admin/tenants` | q-table, q-chip (status badge), q-btn (row actions) |
| Tenant detail | `/admin/tenants/:tenantRef` | q-card, q-tabs + q-tab-panels (per-env keys) |
| Key generation modal | inline dialog | q-dialog (persistent), q-input (readonly), q-btn (copy + confirm) |
| WebhookSecret reveal | inline in detail page | q-input (type toggle), q-icon visibility/visibility_off |

**State management:** Existing pages use either local `ref()` state or the `admin-metrics.store.js`
Pinia store. Tenant pages should use a new `tenant.store.js` Pinia store (or local state for simpler
pages). The existing Pinia 3.0.1 is already installed; no new store library needed.

**API additions:** Add tenant CRUD methods to `admin.api.js`:
```js
listTenants(params)             // GET /v1/admin/tenants
getTenant(tenantRef)            // GET /v1/admin/tenants/:tenantRef
updateTenantField(tenantRef, body) // PATCH /v1/admin/tenants/:tenantRef/...
suspendTenant(tenantRef)        // POST /v1/admin/tenants/:tenantRef/suspend
reactivateTenant(tenantRef)     // POST /v1/admin/tenants/:tenantRef/reactivate
regenerateWebhookSecret(tenantRef) // POST /v1/admin/tenants/:tenantRef/webhook-secret
rotateKey(tenantId, keyId)      // POST already exists
revokeKey(tenantId, keyId)      // DELETE already exists
```

**Routing:** Add tenant route subtree under the existing `/admin` parent in `routes.js`. The
`meta: { requiresAuth: true }` guard is already enforced at the parent level.

**One-time key display (AKEY-07):** When `createTenant` or `reactivateTenant` returns a raw key in
the API response, the frontend displays the `ApiKeyDto.rawKey` field in a persistent `q-dialog` with
a "I have copied it" confirmation button. After dismissal, the raw key is cleared from component
state — it is never stored in the Pinia store. See v5 STACK.md Section 4a for the component pattern.

**WebhookSecret reveal (WSEC-02):** The tenant detail page fetches the tenant including the
`webhookSecret` field (already on `TenantDto`). The secret field is rendered with `type="password"`
by default and toggled with a `q-icon` eye button. See v5 STACK.md Section 4b for the component
pattern.

**Navigation:** Add "Tenants" link to `MainLayout.vue` drawer under the admin section. The existing
drawer already has admin nav links; tenant management follows the same entry pattern.

---

## What NOT to Add

| What | Why Not |
|------|---------|
| Spring Modulith `@ApplicationModuleListener` for notifications | The existing pattern uses plain `@EventListener` (on intermediate listeners) + `@TransactionalEventListener(AFTER_COMMIT)` on `MailManager`. Introducing `@ApplicationModuleListener` would be a different annotation with different semantics — mixing both patterns in one codebase creates confusion. Stay on the established pattern. |
| Separate `/v1/tenant/**` controller path for the 6 operations | The existing `TenantAdminResource` is at `/v1/admin/tenants`. Admin operations belong on the admin path (JWT-authenticated, ROLE_ADMIN). A `/v1/tenant/**` path would land in the API-key security chain, which is not what v6 specifies. Keep all tenant lifecycle admin endpoints under `/v1/admin/tenants`. |
| MapStruct for DTO mapping | The project uses MapStruct (it is in the annotation processor paths in pom.xml) but the existing tenant/admin API layer uses inline record construction. The service operations are simple enough that MapStruct mappers add a build-time artifact without simplifying the code. Follow the existing inline pattern. |
| A separate frontend state management library (Zustand, Vuex) | Pinia 3.0.1 is already installed and in use. Adding a second state management library for tenant pages would fragment state management across the app. |
| Vue 3 Composition API composables for HTTP calls (e.g. VueUse's useFetch) | The project uses axios-based api modules (admin.api.js, account.api.js, etc.). Adding `useFetch` or similar would create two HTTP patterns in the same codebase. Stay on the axios module pattern. |
| TenantSuspendedException as a checked exception | Spring's `@Transactional` rolls back on `RuntimeException` by default. Making it checked would require adding it to `throws` clauses throughout the call stack and adding explicit rollback configuration. Use `RuntimeException`. |
| A new `@Order(0)` security filter chain for tenant admin endpoints | The existing `TenantSecurityConfig` already excludes `/v1/admin/**` from the API-key chain. No new security chain needed; the default JWT chain handles all admin endpoints correctly as-is. |
| Pagination libraries (e.g. Spring HATEOAS, Pageable response wrappers) | The existing admin endpoints return plain lists (no HATEOAS links). Tenant list can use Spring Data's `Pageable` for the JPA query but wrap the result as a simple page DTO consistent with existing response shapes. Do not add HATEOAS. |

---

## Sources

Codebase read 2026-04-07:
- `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java`
- `src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java`
- `src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java`
- `src/main/java/com/softropic/payam/tenant/service/TenantService.java`
- `src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java`
- `src/main/java/com/softropic/payam/tenant/repo/Tenant.java`
- `src/main/java/com/softropic/payam/tenant/repo/TenantRepository.java`
- `src/main/java/com/softropic/payam/email/contract/EmailTemplate.java`
- `src/main/java/com/softropic/payam/email/service/MailManager.java`
- `src/main/java/com/softropic/payam/email/infrastructure/listener/AccountChangeEmailListener.java`
- `src/main/java/com/softropic/payam/email/infrastructure/listener/PlatformConfigEmailListener.java`
- `src/main/java/com/softropic/payam/platform/api/PlatformConfigAdminResource.java`
- `src/main/java/com/softropic/payam/platform/contract/event/PlatformConfigChangedEvent.java`
- `src/main/java/com/softropic/payam/security/common/util/SecurityConstants.java`
- `src/frontend/package.json`
- `src/frontend/src/api/admin.api.js`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/pages/admin/AdminDashboardPage.vue`
- `src/frontend/src/components/common/SessionWarningDialog.vue`
- `.planning/PROJECT.md`
