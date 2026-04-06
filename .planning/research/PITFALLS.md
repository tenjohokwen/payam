# Pitfalls Research — v6

**Domain:** REST API surface, email notifications, Admin UI added to existing Spring Boot 3.5 + Vue 3/Quasar system
**Project:** Payam — Spring Boot 3.5, Spring Security, Hibernate Envers, Vue 3 + Quasar
**Researched:** 2026-04-07
**Overall confidence:** HIGH — all findings grounded in direct codebase inspection (filter, services, audit, email infrastructure all read) and verified against Spring documentation

---

## Auth Filter TENT-09 Pitfalls

TENT-09 requires `ApiKeyAuthenticationFilter` to also block API calls when `tenant.status == SUSPENDED`. The existing filter (`ApiKeyAuthenticationFilter.java`) already short-circuits on missing/invalid keys via `apiKeyService.authenticate()`. The JOIN FETCH in the authenticate query already loads the full `Tenant` entity — the tenant status is already in memory after authentication succeeds.

### Pitfall 1: Status Check After SecurityContext Population

**What goes wrong:** Developer places the `tenantStatus == SUSPENDED` check after `TenantContext.set()` and `SecurityContextHolder.getContext().setAuthentication()` have already run. A SUSPENDED tenant's request gets a fully-populated security context installed before the suspension check fires. Any downstream filter that reads the security context before the request returns sees an authenticated principal — the SUSPENDED status enforcement is effectively bypassed.

**Why it happens:** Copy-paste of the existing 401 path, placed after the context-setting block because "authentication succeeded, now check business rules."

**Prevention:** Check `tenantStatus` immediately after `authenticate()` returns, before any side effect. The correct structure:

```java
TenantApiKey tenantApiKey = apiKeyService.authenticate(rawKey); // throws on bad key
if (tenantApiKey.getTenant().getTenantStatus() == TenantStatus.SUSPENDED) {
    log.warn("API key auth blocked — tenant suspended",
        kv("operation", "api_key_auth"),
        kv("tenantRef", tenantApiKey.getTenant().getTenantRef()),
        kv("tenantStatus", "SUSPENDED"));
    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant is suspended");
    return;  // short-circuit BEFORE TenantContext.set() or SecurityContextHolder
}
// Only now: TenantContext.set(), MDC.put(), SecurityContextHolder.setAuthentication()
```

**Detection:** Integration test: suspend a tenant, send a request with a previously-valid key, assert `SecurityContextHolder.getContext().getAuthentication()` is null on the server side after the response is returned.

---

### Pitfall 2: Returning 401 Instead of 403 for SUSPENDED

**What goes wrong:** Reusing the existing `sendError(SC_UNAUTHORIZED, "Invalid or expired API key")` error path for the SUSPENDED case. Clients receive 401 for both "bad key" and "suspended tenant" and cannot distinguish the two. Clients with expired keys will retry with key rotation logic; clients with suspended tenants will exhaust retries pointlessly.

**Why it happens:** SUSPENDED is discovered after the authentication check passes — developers conflate "auth failed" with "tenant blocked."

**Prevention:** SUSPENDED must return `403 Forbidden`. The existing filter has two distinct error paths already: the `rawKey == null` path (401) and the `authenticate()` exception path (401). Add a third path: SUSPENDED → 403. Use the existing structured logging pattern with `kv()`.

---

### Pitfall 3: Lazy Loading Tenant Status After Hibernate Session Closes

**What goes wrong:** `ApiKeyService.authenticate()` is annotated `@Transactional(readOnly = true)`. The existing `findValidKeyByHash` query uses `JOIN FETCH k.tenant` — this is load-bearing (documented in the filter Javadoc). If anyone refactors that query to remove the JOIN FETCH (to reduce query size), accessing `tenantApiKey.getTenant().getTenantStatus()` in the filter will throw `LazyInitializationException` because the Hibernate session is closed by the time the filter runs.

**Prevention:** The `JOIN FETCH k.tenant` in `TenantApiKeyRepository.findValidKeyByHash` is explicitly load-bearing. Add a comment on the query method explaining that the tenant must be eagerly fetched because the caller runs outside of any transaction. If TENT-09 requires additional tenant fields later, verify they are part of the same fetch.

---

### Pitfall 4: New Admin REST Endpoints Under Wrong URL Path Intercepted by API-Key Filter

**What goes wrong:** `TenantSecurityConfig` excludes `/v1/admin/**` from the API-key filter chain (the `BYPASS_PATTERNS` list and the `NegatedRequestMatcher` both exclude it). If new admin tenant-management endpoints (TENT-05/06) are placed under `/v1/tenant/**` instead of `/v1/admin/tenant/**`, they will be intercepted by the API-key filter. Admin users have JWT cookies, not API keys. Every admin request to those endpoints returns 401.

**Prevention:** All admin tenant-management endpoints must be mapped under `/v1/admin/`. The path convention is enforced by both `BYPASS_PATTERNS` in `ApiKeyAuthenticationFilter` and the `NegatedRequestMatcher` in `TenantSecurityConfig` — they are consistent. Enforce this in the controller `@RequestMapping` from the start.

---

## Email Notification Transaction Pitfalls

The existing email pipeline is a two-stage event pattern: `AccountChangeEmailListener` listens to domain events with `@EventListener` and publishes an `Envelope` event; `MailManager.sendEmailFromTemplate` listens to `Envelope` with `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async("sendMailPool")`. The `Envelope.sendId` deduplication guard is in `MailManager.sendEmailSync()` via `envelopeEntityRepository.findBySendId()`.

The v6 NOTIF-01..06 listeners must follow this exact pattern.

### Pitfall 1: New Listener Calls sendEmailSync() Directly from Inside a Transaction

**What goes wrong:** A developer writes a `TenantLifecycleEmailListener` that catches a `TenantSuspendedEvent` with `@EventListener` and calls `MailManager.sendEmailSync()` directly. This fires the email immediately, before `TenantService.suspend()` commits. If the outer transaction rolls back (e.g., a constraint violation on the bulk key revocation), the suspension is rolled back but the email has already been sent. The tenant receives a suspension notification for an operation that did not persist.

**Why it happens:** `AccountChangeEmailListener` uses `@EventListener` and it appears to work — the developer misses that it only publishes a second `Envelope` event, and it is `MailManager` that bears the `@TransactionalEventListener(AFTER_COMMIT)`.

**Prevention:** Follow the two-stage pattern exactly:
1. Service method publishes a domain event (e.g., `TenantSuspendedEvent`)
2. Listener catches the domain event with `@EventListener` and publishes an `Envelope` event
3. `MailManager` catches `Envelope` with `@TransactionalEventListener(AFTER_COMMIT) + @Async`

Never call `MailManager.sendEmailSync()` directly from inside an active transaction. If there is any doubt about whether the outer transaction has committed, use `@TransactionalEventListener(AFTER_COMMIT)` on the listener instead of `@EventListener`.

---

### Pitfall 2: Double-Send on Retry Due to Random sendId

**What goes wrong:** If a controller catches a transient exception from `TenantService.suspend()` and retries, the service method is called again. A second domain event fires, a second `Envelope` event fires, `MailManager.sendEmailFromTemplate` fires again. `MailManager.sendEmailSync()` has a deduplication guard: `envelopeEntityRepository.findBySendId(envelopeEntity.getSendId())`. But this guard only works if the `sendId` is the same across retries. If the `Envelope` is constructed with `UUID.randomUUID().toString()` as `sendId` (the `helpCode`/`sendId` pattern in `AccountChangeEmailListener`), each retry produces a different `sendId` and the guard does not fire. Two suspension emails are sent.

**Prevention:** Domain events for tenant lifecycle operations must carry a deterministic, idempotent `sendId` — for example, `DigestUtils.sha256Hex(tenantRef + eventType + epochDay)`. This reuses the existing `MailManager` deduplication mechanism without changes. If the `sendId` must be random (to support "send again" flows), the deduplication window must be managed separately.

---

### Pitfall 3: Publishing Domain Events from @Async Context Loses Transaction Binding

**What goes wrong:** An `@Async` method publishes a tenant lifecycle event. The event is published on a thread pool thread where there is no active Spring transaction. `@TransactionalEventListener` requires an active transaction on the publishing thread — if none exists, the listener is not invoked at all (Spring's default `fallbackExecution = false`). The email silently never fires.

**Prevention:** Domain events must be published from synchronous, `@Transactional` service methods. `TenantService` is already `@Transactional` — publish events from within those methods directly. The `@Async` annotation belongs on `MailManager.sendEmailFromTemplate`, not on the publishing side.

---

### Pitfall 4: NOTIF-06 Email Sent to New Address Instead of Old

**What goes wrong:** For `NOTIF-06` (tenant email change notification), `TenantService.updateEmail()` sets the new email and then publishes an event. If the event carries the tenant's current (new) email as the recipient, the notification goes to the new address only. The intended behavior for a security notification is to alert the old address that it was changed.

**Why it happens:** `tenant.setEmail(newEmail)` is called before the event is published. The event reads `tenant.getEmail()` which now returns `newEmail`.

**Prevention:** In `TenantService.updateEmail()`, capture `String oldEmail = tenant.getEmail()` before `tenant.setEmail(newEmail)`. Include both `oldEmail` and `newEmail` in the event payload. Decide explicitly which address receives the notification (old address for security alert is the standard pattern).

---

### Pitfall 5: @Async Email Listener Drops Envelope Events When No Transaction Is Active

**What goes wrong:** If the `Envelope` event is published outside a Spring-managed transaction (e.g., from a test that does not use `@Transactional`, or from a Quartz job thread), the `@TransactionalEventListener(AFTER_COMMIT)` on `MailManager.sendEmailFromTemplate` will not fire. The email is silently dropped.

**Prevention:** The `MailManager` listener has `fallbackExecution = false` by default. When calling email dispatch from contexts without a transaction (e.g. integration tests or Quartz jobs), either: (a) wrap the call in a `TransactionTemplate` so there is a transaction to commit, or (b) call `MailManager.sendEmailSync()` directly in those contexts. Integration tests for NOTIF-01..06 must use `@Transactional` or verify via the `EnvelopeEntityRepository` that the record was persisted.

---

## One-Time Key Display Pitfalls

The existing flow: `ApiKeyService.generateAndStore()` returns `ApiKeyAndRawKey(entity, rawKey)`. `TenantService.createTenant()` returns a `TenantCreationResult(tenant, key, rawKey)` record. `TenantService.reactivate()` returns `ApiKeyAndRawKey`. The raw key is generated in memory, hashed for storage, and the hash alone is persisted. The raw key is never stored.

### Pitfall 1: Raw Key Logged via toString() on Result Records

**What goes wrong:** Java records auto-generate `toString()` including all components. If a controller logs `log.info("Tenant created: {}", result)` where `result` is a `TenantCreationResult`, the raw key appears in the log output and is forwarded to Loki. The existing `ApiKeyAuthenticationFilter` has an explicit comment: "The raw key value is NEVER logged." The same discipline must apply to the response construction layer.

**Codebase risk:** `TenantCreationResult` and `ApiKeyAndRawKey` are both Java records. Neither has a custom `toString()`. Any debug log touching these objects will leak the raw key.

**Prevention:**
- Add a custom `toString()` to both records that replaces `rawKey` with `"[REDACTED]"`.
- Add `rawKey` to the `BodySanitizer` pattern list (the project has this for payment fields per v2 LOG-CODE-02 requirement).
- Never log response DTOs that contain `rawKey`.

---

### Pitfall 2: Controller Discards rawKey and Re-Fetches From Repository

**What goes wrong:** A developer building the REST controller decides to call `TenantService.createTenant()`, ignore the `rawKey` in the result, and then call `TenantService.getTenantDetail()` to build a "clean" response DTO. The detail view returns the `TenantApiKey` entity which has `keyHash` but not `rawKey`. The one-time display is lost permanently.

**Why it happens:** Misunderstanding the one-time nature — treating the service return value as a convenience rather than the only opportunity to see the raw key.

**Prevention:** Document on `TenantCreationResult` and `ApiKeyAndRawKey`: "rawKey is only available at creation time. It is never stored. Do not re-query to obtain it." The controller must include `rawKey` from the service return value in the HTTP response body directly. The response DTO for key creation/rotation must have a `rawKey` field.

---

### Pitfall 3: Frontend Re-Fetches Key from GET After Creation

**What goes wrong:** The frontend SPA receives a `201 Created` response with `rawKey` in the body. If the component navigates away or the response is handled asynchronously, the state update may be missed. A developer adds a `GET /v1/admin/tenants/{ref}/keys` call after creation to "refresh" the key list. The GET returns `keyHash` but not `rawKey`. The admin sees the new key in the list but cannot view its value.

**Prevention:** The frontend must read `rawKey` from the creation/rotation API response directly and immediately open the one-time display modal before any navigation or state reset. Do not initiate a GET to refresh key state before the modal has been shown and confirmed-dismissed.

---

### Pitfall 4: rawKey Stored in Pinia or Vue Reactive State Beyond Modal Lifetime

**What goes wrong:** The raw key is put in a Pinia store (e.g., `tenantStore.lastCreatedKey = rawKey`) so the parent component can pass it to the modal. If the user closes the modal and navigates away, then navigates back, the Pinia store still holds `rawKey`. The modal can be re-opened with the key still visible — the "one-time" guarantee is violated at the client level.

**Prevention:** `rawKey` must live only in a local component `ref` inside the modal. Pass it as a prop from the parent, where the parent holds it only transiently (reads from API response, passes to modal, clears its own ref immediately). On modal confirmation-dismiss: clear the local ref. Never put `rawKey` in a Pinia store, localStorage, sessionStorage, or any state that survives component unmount.

---

### Pitfall 5: Modal Dismissible via Escape / Backdrop Before Copy

**What goes wrong:** Quasar `QDialog` by default closes on Escape keypress and on backdrop click. If the AKEY-07 modal uses default dialog settings, the admin can dismiss it without copying the key. The raw key is gone.

**Prevention:** Set `persistent: true` on the `QDialog` component — this disables Escape and backdrop-click dismissal. Remove or suppress the default close icon (use `:no-close-icon` prop or omit it from the template). The only allowed dismissal path is the explicit "I have copied this key" confirmation button. On click of that button: clear the `rawKey` ref, then close programmatically via `dialogRef.hide()`.

---

## WebhookSecret Reveal Pitfalls

`Tenant.webhookSecret` is stored as a plaintext `UUID` string (see `TenantService.regenerateWebhookSecret()`). It is audited by Envers into `main.tenant_aud` (`webhook_secret VARCHAR(255)`) — every regenerated secret's previous value is preserved in the audit log. This is the architectural reality established in v5.

### Pitfall 1: Reveal Endpoint Missing @PreAuthorize

**What goes wrong:** A developer adds `GET /v1/admin/tenants/{ref}/webhook-secret` to expose the secret, but omits `@PreAuthorize("hasRole('ADMIN')")`. `SecurityConfiguration` has `@EnableMethodSecurity(securedEnabled = true, jsr250Enabled = true)` — method security IS active. If the URL-level rule for `/v1/admin/**` in the JWT chain requires `ROLE_ADMIN` at the path level, omitting the annotation on the method is currently safe. But if the URL pattern changes, or if a second security filter chain is added that matches `admin` paths differently, the method becomes unprotected silently.

**Prevention:** Every WebhookSecret reveal endpoint must carry `@PreAuthorize("hasRole('ADMIN')")` at the method level, regardless of URL-level rules. This is WSEC-02 — the requirement is admin-only. Defence in depth: method-level annotation cannot be erased by URL config changes.

---

### Pitfall 2: webhookSecret Returned in Standard Tenant Detail GET Response

**What goes wrong:** A developer includes `webhookSecret` in the `TenantDto` used by the standard `GET /v1/admin/tenants/{ref}` endpoint. Every page load of the tenant detail screen sends the webhook secret over the wire. Any admin-level XSS or compromised admin session exposes all webhook secrets passively.

**Prevention:** `webhookSecret` must be null (or absent) in all standard list/detail response DTOs. A separate `GET /v1/admin/tenants/{ref}/webhook-secret` endpoint is the only path to retrieve the secret, and it requires an explicit admin action (clicking the eye icon in the UI). The standard `TenantDto` must not include this field.

---

### Pitfall 3: webhookSecret Logged in Response Body Capture

**What goes wrong:** The existing structured logging captures `request_end` events. If the reveal endpoint's response body is captured by the logging infrastructure and `webhookSecret` is not in `BodySanitizer`'s redaction list, the secret appears in Loki logs.

**Prevention:** Add `webhookSecret` to `BodySanitizer`'s redaction patterns. The project's v2 `LOG-CODE-02` requirement covers this for payment fields — extend it to cover webhook secrets.

---

### Pitfall 4: Historical Webhook Secrets Exposed via Audit Table Queries

**What goes wrong:** `main.tenant_aud` stores `webhook_secret` in plaintext. Any admin query that returns full audit revision rows (e.g., a compliance export or a future audit viewer endpoint) exposes all historical webhook secrets. This is especially dangerous if the audit viewer endpoint is less tightly access-controlled than the reveal endpoint.

**Prevention:** Any future audit viewer endpoint for `tenant_aud` must explicitly mask or omit the `webhook_secret` column from its projection. This is a known limitation of the v5 architectural choice (plaintext storage). Document it in the phase plan so the risk is acknowledged rather than discovered accidentally.

---

## REST Controller Layer Pitfalls

Adding REST controllers over `TenantService` and `ApiKeyService` in v6.

### Pitfall 1: Redundant Validation Creating Drift Between Layers

**What goes wrong:** The controller adds `@Valid` with `@NotBlank` / `@Email` constraints on request DTOs. The service layer throws `IllegalArgumentException` for the same violations independently. Over time, they diverge: the controller rejects a request for one reason while the service would reject it for a different reason, or one layer is updated without the other. Tests for controller-layer validation pass while service-layer tests fail on different inputs.

**Prevention:** Define a single responsibility:
- Controller: Bean Validation (`@Valid`) for HTTP-layer format constraints (not blank, valid format)
- Service: domain-rule violations only (`IllegalStateException`, `EntityNotFoundException`, domain-specific exceptions)

Service methods must not duplicate format validation already enforced at the controller level. Use the `@ControllerAdvice` to translate service exceptions into HTTP error responses consistently.

---

### Pitfall 2: Missing @PreAuthorize on Admin Endpoint Methods

**What goes wrong:** A controller method is added without `@PreAuthorize("hasRole('ADMIN')")`. `SecurityConfiguration` has `@EnableMethodSecurity` active. Baeldung's guidance and the Spring Security docs confirm that without an explicit deny-all for unannotated methods, omitting the annotation means the endpoint falls through to URL-level rules only. The URL-level rules may currently require `ROLE_ADMIN`, but this is fragile.

**Prevention:** Every admin tenant-management endpoint must have `@PreAuthorize("hasRole('ADMIN')")` at the method level. Check the existing admin controllers (e.g., `AuditTrail`) to confirm the existing pattern. If they do not use method-level annotations, add them. This is defence-in-depth.

---

### Pitfall 3: Envers Captures "SYSTEM_ACCOUNT" for Admin HTTP Operations Due to Wrong Thread

**What goes wrong:** `SpringSecurityAuditorAware.getCurrentAuditor()` returns `"SYSTEM_ACCOUNT"` if no authentication is present. For admin REST calls, the JWT security context is populated on the request thread — Envers will correctly capture the admin's username as `last_modified_by`. The pitfall is accidentally triggering service methods from a context where the security context is not populated:
- From an `@Async` listener (new thread, no security context propagated)
- From a Quartz job thread
- From an integration test without `@WithMockUser`

**Codebase risk:** `MailManager.sendEmailFromTemplate` runs `@Async`. If any future change attempts to mutate tenant state from within that async listener, Envers will attribute it to `"SYSTEM_ACCOUNT"`.

**Prevention:** Never call `TenantService` mutation methods from async listeners, Quartz jobs, or filter callbacks. If a scheduled operation must mutate tenant state, explicitly set a `UsernamePasswordAuthenticationToken("SYSTEM:job-name", null, List.of())` on `SecurityContextHolder` before the call and clear it in a `finally` block. Integration tests for admin endpoints must use `@WithMockUser(roles = "ADMIN")` so Envers records are attributable.

---

### Pitfall 4: @Transactional on Controller Method

**What goes wrong:** A developer adds `@Transactional` to a controller method that calls `TenantService.suspend()` (which is itself `@Transactional`). With REQUIRED propagation (default), the service joins the controller's transaction. This means `@TransactionalEventListener(AFTER_COMMIT)` fires at the outer controller transaction's commit — which works for a single service call, but if two service calls are later added to the same controller method, all events from both calls fire simultaneously at the end, regardless of which operation caused them. Ordering surprises and difficult-to-debug notification grouping result.

**Prevention:** No `@Transactional` on controller methods. Controllers call service methods; service methods own their transaction boundaries. This is the standard Spring layered-monolith pattern already in use in this codebase.

---

### Pitfall 5: Error Responses From New Controllers Inconsistent With Existing @ControllerAdvice

**What goes wrong:** The existing codebase has a `@ControllerAdvice` (or equivalent) that maps `EntityNotFoundException` → 404, `IllegalStateException` → 409, etc. If new admin endpoints throw a custom exception type not handled by the advice (e.g., a new `TenantAlreadySuspendedException`), the exception falls through to Spring Boot's default error handler, which returns a different JSON shape (`timestamp`, `path`, `status`, `error` without the app's standard error body). API clients receive an inconsistent error response format.

**Prevention:** Before introducing new exception types, check what the existing `@ControllerAdvice` handles. Reuse existing exception types where semantics match. If a new exception is required (e.g., for SUSPENDED-specific 403 from the controller), add its mapping to the existing advice in the same phase — not a later cleanup.

---

### Pitfall 6: class-level @PreAuthorize Intercepting @ExceptionHandler Methods

**What goes wrong:** If `@PreAuthorize("hasRole('ADMIN')")` is placed at the controller class level rather than on each method, Spring Security applies method-level security to all methods in the class — including `@ExceptionHandler` methods. An exception thrown in a request context where the user lacks ROLE_ADMIN will cause the exception handler to also be denied, resulting in a 403 from the security layer instead of the application's custom error response.

**Prevention:** Place `@PreAuthorize` on individual public endpoint methods, not on the controller class. This is especially important for controllers that also declare `@ExceptionHandler` methods.

---

## Vue Admin UI Pitfalls

### Pitfall 1: One-Time Key Modal Dismissible Without Copy Confirmation

**What goes wrong:** Quasar `QDialog` closes on Escape, backdrop click, and any programmatic `hide()` call. An admin who accidentally presses Escape or clicks outside the modal loses the key permanently.

**Prevention:** Use `persistent: true` on the `QDialog`. Suppress the default close icon. The only dismiss path is a "I have copied this key" button that: (1) calls `navigator.clipboard.writeText(rawKey)`, (2) clears the local `rawKey` ref, (3) closes the dialog programmatically. Optionally, show the "Done" button as disabled until clipboard write succeeds.

---

### Pitfall 2: Raw Key in Pinia Store or Survives Component Unmount

**What goes wrong:** `rawKey` is stored in a Pinia store so the parent page can track creation state. The user navigates away and back. The Pinia store still holds `rawKey`. The modal re-renders with the key visible — the one-time display guarantee is broken.

**Prevention:** `rawKey` lives only in a local `ref` inside the modal component. The parent receives it from the API response, passes it as a prop to the modal, and immediately clears its own reference. The modal is the sole owner. On confirmed dismissal, the modal clears its `rawKey` ref.

---

### Pitfall 3: webhookSecret Fetched Eagerly in Tenant Detail Page Load

**What goes wrong:** The tenant detail page loads all tenant data including `webhookSecret` on mount (e.g., if `webhookSecret` was accidentally included in the standard GET response). Every page view exposes the secret in the network tab and in any JS heap snapshot. Reveal-on-demand is bypassed.

**Prevention:** The tenant detail GET endpoint must not return `webhookSecret`. The eye-icon button triggers a separate API call to the dedicated reveal endpoint. The revealed value is stored in a local `ref` and cleared when the icon is toggled off or when the component unmounts (`onUnmounted(() => { revealedSecret.value = null })`).

---

### Pitfall 4: Status Toggle Without Stale State Guard

**What goes wrong:** Admin A has a tenant detail page open. Admin B suspends the tenant from another session. Admin A's page still shows ACTIVE and they click "Reactivate" (which fails with AKEY-02 guard: active key already exists for PROD). The 409 error is unclear from the admin's perspective because their UI showed an incorrect state.

**Prevention:** After any status-mutating API call (suspend, reactivate), always refresh the tenant detail data from the server before updating local Vue state. The button must be disabled during the in-flight API call (prevent double-click). On `409 Conflict`, show an error that says "State has changed — refreshing" and reload the tenant detail.

---

### Pitfall 5: Admin Route Guard Not Applied to New Tenant Management Pages

**What goes wrong:** The Vue router has an `ROLE_ADMIN` guard on existing admin routes. New tenant management route definitions (e.g., `/admin/tenants`, `/admin/tenants/:ref`) are added without the `requiresAdmin: true` meta flag or equivalent guard. Any authenticated non-admin user who knows the route path can navigate directly to the tenant management screens via the browser URL bar.

**Prevention:** Apply the existing admin route guard to all new tenant management route definitions before merging. This is defence-in-depth alongside the backend `@PreAuthorize` annotations. Check the existing Quasar router config for the guard pattern (likely a `beforeEach` navigation guard checking user roles).

---

## Phase-Specific Warnings Summary

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|----------------|------------|
| TENT-09 filter SUSPENDED check | Status checked after SecurityContext set; wrong 401 vs 403 | Check before context population; 403 for SUSPENDED |
| TENT-09 filter SUSPENDED check | Lazy load of tenantStatus after Hibernate session closes | Verify JOIN FETCH still covers tenant_status; add comment on query |
| Admin REST endpoint paths | New endpoints under `/v1/tenant/**` intercepted by API-key filter | All admin endpoints must be `/v1/admin/**` |
| NOTIF-01..06 email listeners | @EventListener fires before commit; calls sendEmailSync in-transaction | Use AFTER_COMMIT; never call sendEmailSync inside active transaction |
| NOTIF-01..06 double-send on retry | Random UUID sendId bypasses MailManager dedup guard | Use deterministic sendId (e.g., sha256(tenantRef + eventType + epochDay)) |
| NOTIF-06 email change recipient | Email sent to new address, not old, for security notification | Capture oldEmail before mutation; event carries both |
| AKEY-07 one-time display | rawKey logged via auto-generated toString() on result records | Custom toString() redacting rawKey on both record types |
| AKEY-07 one-time display | Controller discards rawKey and re-fetches; frontend triggers GET | Controller returns rawKey from service result; frontend reads from POST response |
| AKEY-07 modal UX | QDialog dismissible by Escape/backdrop | persistent: true; confirmation-only dismissal path |
| AKEY-07 rawKey in Pinia | Key survives navigation via Pinia store | Local component ref only; cleared on modal confirmation |
| WSEC-02 reveal | Endpoint missing @PreAuthorize; secret in standard GET DTO | Method-level @PreAuthorize; dedicated reveal-only endpoint |
| WSEC-02 reveal | webhookSecret logged in response body | Add webhookSecret to BodySanitizer; not in standard TenantDto |
| REST controllers | Missing method-level @PreAuthorize | @PreAuthorize on every admin method, not just class-level |
| REST controllers | @Transactional on controller | Never annotate controller methods @Transactional |
| REST controllers | New exceptions not in existing @ControllerAdvice | Register new exception mappings in existing advice in same phase |
| REST controllers | class-level @PreAuthorize blocks @ExceptionHandler | Method-level annotations only; never class-level @PreAuthorize on controllers |
| Envers audit | Admin identity captured as SYSTEM_ACCOUNT from async/Quartz context | Only call TenantService mutation methods from synchronous request threads with active JWT context |
| Vue admin screens | New routes missing admin route guard | Apply requiresAdmin meta to all new tenant management routes |
| Vue admin screens | webhookSecret in standard tenant detail GET response | Dedicated reveal endpoint only; not in TenantDto |

---

## Sources

- `ApiKeyAuthenticationFilter.java` — existing filter structure, BYPASS_PATTERNS, short-circuit paths (codebase, HIGH confidence)
- `TenantSecurityConfig.java` — NegatedRequestMatcher scoping, FilterRegistrationBean pattern (codebase, HIGH confidence)
- `TenantService.java` — existing @Transactional service layer, rawKey return contracts (codebase, HIGH confidence)
- `ApiKeyService.java` — generateAndStore(), ApiKeyAndRawKey record, authenticate() (codebase, HIGH confidence)
- `MailManager.java` — @TransactionalEventListener(AFTER_COMMIT) + @Async pattern, sendId deduplication via findBySendId (codebase, HIGH confidence)
- `AccountChangeEmailListener.java` — two-stage event indirection pattern; @EventListener publishes Envelope (codebase, HIGH confidence)
- `SpringSecurityAuditorAware.java` — SYSTEM_ACCOUNT fallback when no principal present (codebase, HIGH confidence)
- `V20__envers_audit_tables.sql` — webhook_secret stored as plain VARCHAR in tenant_aud (codebase, HIGH confidence)
- `SecurityConfiguration.java` — @EnableMethodSecurity(securedEnabled=true, jsr250Enabled=true) active (codebase, HIGH confidence)
- Spring Framework Transaction-bound Events docs — AFTER_COMMIT phase, fallbackExecution=false default (MEDIUM confidence)
- Spring Security @EnableMethodSecurity and @PreAuthorize docs — method security requirements, class-level side effects (MEDIUM confidence)
- Baeldung "Deny Access on Missing @PreAuthorize" — class-level @PreAuthorize intercepting @ExceptionHandler (MEDIUM confidence)

---

*Pitfalls research for: Payam v6 — REST API Surface, Notifications & Admin UI milestone*
*Researched: 2026-04-07*
