# Research Summary — v6 REST API Surface, Notifications & Admin UI

**Project:** Payam — unified multi-tenant mobile money API for Cameroon
**Researched:** 2026-04-07
**Confidence:** HIGH

---

## Executive Summary

Payam v6 exposes the already-complete v5 service layer over HTTP, adds email notifications for six tenant/key lifecycle events, enforces SUSPENDED tenant blocking at the auth filter, and builds Admin UI screens for tenant management. Every capability maps to the existing stack — no new dependencies are required. The codebase has established, repeatable patterns for REST controllers (`TenantAdminResource`), email notifications (two-stage `@EventListener` → `Envelope` → `@TransactionalEventListener(AFTER_COMMIT)` via `MailManager`), and admin Vue pages (`TransactionSearchPage.vue`, `PlatformConfigPage.vue`). v6 is purely additive: extend, do not reinvent.

The recommended approach is a strict four-phase build ordered by dependencies: (1) TENT-09 auth enforcement, (2) REST controller expansion, (3) email event infrastructure, (4) Admin UI. Phases 3 and 4 are independent after Phase 2 and can be parallelized. No Flyway migration is needed — all required columns exist in the current schema (V21).

---

## Stack Additions

**None required.** Every v6 capability maps to a library already in `pom.xml` or `package.json`.

| v6 Capability | Existing Library |
|---|---|
| 8 new REST endpoints | Spring MVC (`TenantAdminResource` already `@RestController`) |
| RBAC on new endpoints | `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` — established pattern |
| Email notifications | `MailManager` + Thymeleaf — add 6 enum entries + templates |
| TENT-09 SUSPENDED check | `ApiKeyAuthenticationFilter` — add one condition |
| Admin UI pages | Vue 3.5.22 + Quasar 2.16.0 + Pinia 3.0.1 — same pattern as existing admin pages |
| One-time key modal | `q-dialog persistent` + `$q.copyToClipboard` |
| Webhook secret reveal | `q-input` type toggle — pattern in `UpdatePasswordDialog.vue` |

Do NOT add: `@ApplicationModuleListener`, MapStruct, Spring HATEOAS, VueUse `useFetch`, a second state management library, a new security filter chain for admin endpoints, or `TenantSuspendedException` as a checked exception.

---

## Feature Table Stakes

- **TENT-09:** Suspended tenant blocked at API key filter with HTTP 403 — check before SecurityContext population
- **TENT-05/06:** Paginated tenant list + detail page with full key management per env
- **AKEY-07:** One-time raw key display in a `persistent` QDialog with copy-confirmed gate and immediate `rawKey` ref clear on dismissal. Note: Quasar issue #15076 — `$q.copyToClipboard` fallback via `execCommand` can fail inside a dialog backdrop; needs explicit workaround.
- **WSEC-02:** Lazy-fetch webhook secret reveal via dedicated `GET /v1/admin/tenants/{ref}/webhook-secret`; never returned in standard GET
- **NOTIF-01..06:** Transactional emails for 6 lifecycle events — **no key material ever in email body**
- **Admin CRUD:** Create tenant (→ AKEY-07 modal), edit name/email/webhookUrl, suspend with confirmation, reactivate (→ AKEY-07 modal with new PROD key)

**Important:** The `reactivate()` response must include `rawKey` — it's the only opportunity to display the newly generated PROD key.

---

## Architecture Summary

Everything lands in the existing `tenant` module. `TenantAdminResource` gains 8 endpoint methods. A new `TenantQueryService` handles reads (following `AdminTransactionQueryService` precedent). Six domain event records go in `tenant/contract/event/`. One `TenantLifecycleEmailListener` handles all 6 NOTIF events. The frontend adds 2 pages, 1 shared dialog, 1 API module, and 1 Pinia store.

**Key codebase facts from direct inspection:**
- `TenantAdminResource` already exists at `/v1/admin/tenants` — 8 new endpoint methods are additions
- `ApiKeyAuthenticationFilter` already JOIN FETCHes tenant — no extra DB round-trip for TENT-09 check
- `webhook_secret` stored **plaintext** (V8 migration) — correct for HMAC signing
- `rawKey` is already returned in `ApiKeyDto` on create/rotate — AKEY-07 is purely a frontend concern
- Email pattern: `@EventListener` (not `@TransactionalEventListener`) on domain listener, then `Envelope` → `MailManager @TransactionalEventListener(AFTER_COMMIT)` — mirrors `PlatformConfigEmailListener`
- `@EnableMethodSecurity` is active — use method-level `@PreAuthorize` only (class-level intercepts `@ExceptionHandler`)

**Phase dependency graph:**
```
Phase N+0: TENT-09 auth enforcement (1 file change)
    |
Phase N+1: REST controller expansion (TenantAdminResource + TenantQueryService + DTOs)
    |                         |
Phase N+2: Email events    Phase N+3: Admin UI
(parallel after N+1)
```

---

## Watch Out For

1. **SUSPENDED check after SecurityContext population** — Must check `tenant.getTenantStatus()` immediately after `authenticate()` returns, before `TenantContext.set()` and `SecurityContextHolder.setAuthentication()`. Return HTTP 403 (not 401).

2. **Email fires before transaction commits** — Never call `MailManager.sendEmailSync()` from inside an active transaction. Two-stage: service → `@EventListener` → `Envelope` → `MailManager @TransactionalEventListener(AFTER_COMMIT)`.

3. **Raw key leaked via auto-generated `toString()`** — `TenantCreationResult` and `ApiKeyAndRawKey` are Java records with auto-generated `toString()` that includes `rawKey`. Add custom `toString()` redacting it. Add to `BodySanitizer`.

4. **WebhookSecret in standard tenant GET** — `TenantDetailDto` must omit `webhookSecret`. Dedicated reveal endpoint only, with method-level `@PreAuthorize`.

5. **NOTIF-06 sent to new email address** — Capture `String oldEmail = tenant.getEmail()` before `tenant.setEmail(newEmail)`. Security notification goes to the old address.

6. **`rawKey` stored in Pinia** — Lives only in a local `ref` inside `ApiKeyRevealDialog.vue`. Never in Pinia, `localStorage`, or `sessionStorage`. Parent clears reference on dismissal.

7. **NOTIF double-send on retry** — Use deterministic `sendId` (e.g., `sha256(tenantRef + eventType + epochDay)`) for MailManager deduplication guard.

---

## Open Questions for Planning

- **`sendId` formula** — Exact formula must be decided in the email phase plan before implementation; changing it after first deploy invalidates existing dedup records.
- **NOTIF-06 recipient policy** — Old address only, new address only, or both?
- **`reactivate()` dual-event emission** — Does tenant receive both `TenantStatusChangedEvent` + `ApiKeyGeneratedEvent` (two separate emails) or one combined event?
- **Webhook secret in `tenant_aud`** — `main.tenant_aud` stores `webhook_secret` plaintext via Envers. Any future audit viewer must mask this column. Not a v6 blocker; document in plan.

---

*Research completed: 2026-04-07*
*Ready for requirements: yes*
