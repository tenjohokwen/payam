# Research Summary: Payam v5 — Tenant & API Key Management

**Project:** Payam — unified multi-tenant mobile money API for Cameroon
**Domain:** Multi-tenant API gateway — tenant lifecycle + per-environment API key management
**Researched:** 2026-04-02
**Confidence:** HIGH

---

## Executive Summary

Payam v5 adds full tenant lifecycle management and per-environment API key management on top of an already-functioning Spring Boot 3.5 / PostgreSQL / Quartz / Vue 3 + Quasar foundation. The existing codebase has partial v1 scaffolding for tenants and API keys, but v5 tightens the model significantly: tenant-name-derived prefixes (immutable even if the name changes), one ACTIVE key per environment per tenant enforced at the database level, a 24-hour automated grace period for ROTATED keys via a Quartz job, and atomic suspension that revokes all keys in a single transaction. These constraints are intentionally tighter than Stripe's model and are appropriate for a regulated B2B payment gateway where the operator manages merchants who may not be fully trusted.

The recommended approach is schema-first: fix the entity model and Flyway migrations before writing any service logic. Three concrete defects in the v1 codebase must be corrected during the migration phase — the `environment` column defaults to `'LIVE'` (must become `PROD/DEV/SANDBOX`), the `key_prefix` derivation is currently random Base64 characters rather than the tenant-name-derived 3-letter prefix, and the `Tenant` entity lacks the `keyPrefix` and `email` columns entirely. All subsequent phases depend on these being correct first. Zero new dependencies are needed; every required capability is already present in the existing stack.

The primary implementation risks are transactional integrity failures: the suspension cascade must use a bulk JPQL `@Modifying` update (not a loop over entities), the Quartz rotation cleanup job must not wrap its full loop in a single transaction, and the reactivation endpoint must return the raw PROD key directly (not routed through an event bus). The one-time key display modal also requires a copy-confirmation gate — without it, admins will accidentally lose keys and demand a "reveal key" feature that cannot exist given hash-only storage.

---

## Key Findings

### Stack Additions

**Zero new dependencies required.** Every v5 capability maps to a library already in `pom.xml` or `package.json`.

| Capability | Existing Library |
|------------|-----------------|
| API key SHA-256 hashing | commons-codec 1.19.0 (`DigestUtils.sha256Hex`) |
| ROTATED→REVOKED grace period job | spring-boot-starter-quartz (JDBC store, already active) |
| Audit trail | hibernate-envers (`@Audited` already on both entities) |
| Email notifications (6 events) | Spring Modulith events + existing `MailManager` |
| One-time key display modal | Quasar `q-dialog` + `$q.copyToClipboard` (built-in) |
| Webhook secret reveal toggle | Quasar `q-input` + `q-icon` (pattern already in `UpdatePasswordDialog.vue`) |
| Tenant management table | Quasar `q-table` + `q-chip` + `q-dialog` |

Do NOT add BCrypt for API key hashing — SHA-256 is correct for high-entropy tokens (BCrypt adds ~200ms latency with zero security benefit). Do not add ShedLock — Quartz JDBC store already handles distributed locking.

One configuration change is needed: increase Quartz `threadCount` from 3 to 5 in `application.yaml` before shipping v5 to prevent job starvation.

See `.planning/research/STACK.md` for full rationale and "what NOT to add" table.

### Feature Table Stakes

All items below must ship in v5. Nothing here is optional.

**Tenant lifecycle:**
- Tenant create with auto-generated TenantRef (UUID) and immutable 3-char key prefix (derived from name at creation; frozen even if name changes)
- ACTIVE / SUSPENDED status toggle — suspension atomically revokes all keys across all environments in one transaction
- Reactivation auto-generates a new PROD key and surfaces it in the same UX action (not a separate step)
- Name, email, webhookUrl editable by admin (name change does NOT alter key prefix)
- Paginated tenant list with status filter and name/email/ref search

**API key lifecycle:**
- Per-environment key generation: PROD, DEV, SANDBOX
- One-time raw key display on generation/rotation (hash-only storage; raw key never retrievable again)
- One ACTIVE key per environment per tenant (enforced by partial unique DB index — not just service logic)
- Key rotation with 24h ROTATED grace period — both old and new key accepted during window
- Automated ROTATED→REVOKED cleanup (Quartz job, hourly)
- Manual revocation (immediate, no grace period)

**Supporting features:**
- WebhookSecret: generate on tenant create, reveal via eye icon, regenerate on demand
- Email notifications for all 6 lifecycle events (dual recipient: platform email + tenant email when present)
- Hibernate Envers audit on Tenant and TenantApiKey entities (data captured; viewer UI deferred)
- Admin ID + timestamp captured on all key generation events

**Explicitly deferred to a future milestone:**
- Audit log viewer UI (Envers data will be captured; UI is a separate phase)
- Key permission scopes (read-only, write-only keys)
- Self-service tenant portal (admin-managed only in v5)
- DEV/SANDBOX auto-generation on tenant create (only PROD auto-generated; other envs are on-demand)
- Bulk tenant operations

See `.planning/research/FEATURES.md` for UX flow notes, admin flow walkthroughs, and complexity assessments per feature area.

### Architecture Highlights

The build has a clear dependency chain: schema changes ripple forward into service, job, event, REST, and UI layers in sequence.

**Entity changes required:**
- `Tenant` — add `email` (nullable, editable) and `keyPrefix` (`updatable = false`, derived from name at creation time)
- `TenantApiKey` — migrate `environment` from `VARCHAR 'LIVE'` to `ApiKeyEnvironment` enum (`PROD/DEV/SANDBOX`)

**Service layer changes:**
- `ApiKeyService.generateAndStore()` — read prefix from `tenant.getKeyPrefix()`, never recompute from `tenant.getName()`; enforce per-environment ACTIVE uniqueness before insert
- `TenantService` — add `suspend()`, `reactivate()`, `updateEmail()`, `updateWebhookUrl()`, `regenerateWebhookSecret()` methods
- `ApiKeyAuthenticationFilter` — extend to reject keys where `tenant.tenantStatus == SUSPENDED` as defense-in-depth

**New components:**
- `KeyRotationCleanupJob` — Quartz `QuartzJobBean`, hourly cron, mirrors `ReconciliationJob` pattern exactly
- `TenantEventEmailListener` — 6 domain events, mirrors `PlatformConfigEmailListener` pattern exactly
- 8 new REST endpoints on `TenantAdminResource` — GET list/detail, PATCH edit, POST suspend/reactivate/webhook-secret, GET reveal-webhook-secret, POST generate-key
- Frontend `TenantListPage.vue` and `TenantDetailPage.vue` under existing admin routes

**Critical constraint:** Suspension must be synchronous, not event-driven. The tenant status change and all key revocations must commit in the same transaction. Event-driven revocation creates a window where a suspended tenant can still authenticate. Use a single bulk JPQL `@Modifying` update for revocation — not a loop over loaded entities.

See `.planning/research/ARCHITECTURE.md` for the full 7-phase build order, data flow diagrams, and component boundary table.

### Critical Pitfalls

1. **Prefix semantics mismatch (P1, CRITICAL)** — The existing `ApiKeyService` assigns `rawKey.substring(0, 8)` as `keyPrefix` — a random Base64 substring, not the 3-char tenant-name prefix. The `Tenant` entity has no `keyPrefix` column. Fix both in the schema migration phase before any service work begins. `generateAndStore()` must read `tenant.getKeyPrefix()` — never recompute from `tenant.getName()`. Warning sign: generated key format is `<8-random-chars>_<uuid>` instead of `<3-letter-tenant-prefix>_<uuid>`.

2. **Environment column migration order (P2, CRITICAL)** — The migration must `UPDATE ... SET environment = 'PROD' WHERE environment = 'LIVE'` BEFORE adding the `CHECK` constraint, and must also update the column `DEFAULT` to `'PROD'`. Missing either step causes a Flyway failure or a runtime `IllegalArgumentException` from Hibernate. Update `CreateTenantRequest` validation regex in the same phase.

3. **One-active constraint not enforced at DB level (P3, CRITICAL)** — A service-layer check alone is vulnerable to TOCTOU races. The partial unique index `(tenant_id, environment) WHERE key_status = 'ACTIVE'` must be in the Flyway migration. The `rotate()` method must set the old key to ROTATED before inserting the new ACTIVE key, in a single transaction.

4. **Suspension cascade partial failure (P4, CRITICAL)** — Implement bulk revocation as a single JPQL `@Modifying` update (`WHERE k.keyStatus IN ('ACTIVE', 'ROTATED')`), not a loop. Also extend `findValidKeyByHash` to add `AND k.tenant.tenantStatus = ACTIVE` as a second enforcement layer.

5. **Reactivation raw key lost if not in response (P5, CRITICAL)** — `TenantService.reactivate()` must return a result record containing both the `Tenant` and the raw key. The REST endpoint must include `rawKey` in the response body. If the service returns `void` or the REST layer discards the result, the PROD key is permanently unrecoverable without another rotation.

6. **Quartz grace period job uses single transaction over loop (P6, CRITICAL)** — Do not annotate `executeInternal` with `@Transactional` when processing multiple keys. One key failure rolls back all revocations. Use a bulk `@Modifying` JPQL update instead. Add `@DisallowConcurrentExecution` to the job class.

7. **One-time modal with no copy-confirmation gate (P7, HIGH)** — The close button on the key display modal must be disabled or hidden until the admin confirms they have copied the key (clipboard copy action tracked, or explicit "I have saved this" checkbox). Without this gate, admins lose keys and demand a "reveal key" feature that cannot exist.

See `.planning/research/PITFALLS.md` for the full pitfall catalogue (11 pitfalls), warning signs, recovery strategies, and a "looks done but isn't" checklist.

---

## Implications for Roadmap

The architecture research defines a natural 7-phase build order driven by hard dependencies. This maps directly to the suggested roadmap structure.

### Phase 1 — Schema and Enum Migration
**Rationale:** Everything else depends on the entity model being correct. Three v1 defects must be fixed before any new code runs against them.
**Delivers:** `V18__tenant_v5_fields.sql` (add `tenant.email`, `tenant.key_prefix`), `V19__tenant_api_key_environment_enum.sql` (migrate `environment` to `PROD/DEV/SANDBOX`, add partial unique index and key_hash UNIQUE constraint), `ApiKeyEnvironment` enum, updated `Tenant` and `TenantApiKey` entities.
**Avoids:** P1 (prefix mismatch — `Tenant.keyPrefix` column must exist before service uses it), P2 (migration order: UPDATE before CHECK constraint), P3 (partial unique index on ACTIVE key per env), P9 (key_hash UNIQUE constraint).
**Research flag:** Standard Flyway + JPA patterns — no additional research needed.

### Phase 2 — Service Layer
**Rationale:** Repository queries must exist before services can call them; domain events must be defined before listeners can consume them.
**Delivers:** Updated `ApiKeyService` (correct prefix format, per-env uniqueness enforcement, event publications), new `TenantService` methods (`suspend`, `reactivate`, `updateEmail`, `updateWebhookUrl`, `regenerateWebhookSecret`), all new repository methods.
**Avoids:** P4 (bulk JPQL for suspension cascade), P5 (reactivation returns result record containing raw key), P8 (prefix always read from `tenant.getKeyPrefix()`, never recomputed from name).
**Research flag:** Standard Spring Data JPA + event patterns — no additional research needed.

### Phase 3 — Quartz Rotation Cleanup Job
**Rationale:** Independent of email and UI; can be built and tested in isolation after the service layer provides the repository queries it needs.
**Delivers:** `KeyRotationCleanupJob`, `KeyRotationSchedulerConfig`, `threadCount` increased to 5 in `application.yaml`.
**Avoids:** P6 (bulk `@Modifying` update, not transactional loop; `@DisallowConcurrentExecution`), P11 (set synthetic `SYSTEM:grace-period-job` principal in security context before service call, clear in `finally` block, so Envers records a non-null audit actor).
**Research flag:** Standard Quartz pattern — mirrors `ReconciliationJob` exactly — no additional research needed.

### Phase 4 — Email Notifications
**Rationale:** Depends on Phase 2 event classes being defined. Independent of UI.
**Delivers:** 6 `EmailTemplate` enum values, 6 domain event classes in `tenant/contract/event/`, `TenantEventEmailListener`, 6 Thymeleaf email templates.
**Note:** Events fire within the service transaction via `@EventListener` (not `@TransactionalEventListener`); `MailManager` uses `@TransactionalEventListener(AFTER_COMMIT)` — this is the established pattern from `PlatformConfigEmailListener`.
**Research flag:** Established pattern from `PlatformConfigEmailListener` — no additional research needed.

### Phase 5 — REST API Expansion
**Rationale:** Depends on Phases 2-4 so responses carry correct data and events fire. Authentication filter change belongs here.
**Delivers:** 8 new endpoints on `TenantAdminResource`, updated DTOs (`TenantDto`, `ApiKeyDto`, new `TenantDetailDto`), `ApiKeyAuthenticationFilter` tenant-status check.
**Avoids:** P10 (auth filter rejects SUSPENDED tenant keys as defense-in-depth), P5 (reactivation endpoint includes non-null `rawKey` in response body).
**Research flag:** Standard Spring MVC REST patterns — no additional research needed.

### Phase 6 — Admin UI
**Rationale:** Backend API must be complete before UI can be built and tested against it.
**Delivers:** `admin.api.js` additions, `TenantListPage.vue`, `TenantDetailPage.vue`, router additions, sidebar nav entry in `MainLayout.vue`.
**Avoids:** P7 (one-time modal requires copy-confirmation gate before close; PROD tab must be visually distinct from DEV/SANDBOX; ROTATED keys must show expiry countdown).
**Research flag:** All required Quasar components are established in the codebase — `q-dialog`, `q-table`, `q-tabs`, `q-input` reveal pattern from `UpdatePasswordDialog.vue`. No additional research needed.

### Phase 7 — E2E Tests
**Rationale:** Validates cross-layer correctness of all critical flows.
**Delivers:** E2E tests covering: suspension cascade (ACTIVE + ROTATED keys both rejected after suspend), reactivation returns `rawKey` in response, rotation grace period (ROTATED key accepted within 24h, rejected after job runs), concurrent rotation produces exactly 1 ACTIVE key, all 6 email events fire.
**Research flag:** Standard testing patterns — no additional research needed.

### Phase Ordering Rationale

- Phases 1 and 2 are strictly sequential: schema must be correct before service logic runs against it.
- Phases 3 and 4 can run in parallel after Phase 2 — both depend on the service layer event/repo contracts, but neither depends on the other.
- Phase 5 must follow Phases 2-4 to have correct return types, events wired, and job in place.
- Phase 6 must follow Phase 5 for a stable API surface to build the UI against.
- Phase 7 is final validation across all layers.

### Research Flags

All 7 phases follow well-documented patterns already established in the Payam codebase. No phase requires `gsd:research-phase` during planning.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All libraries verified against codebase read 2026-04-02; zero new dependencies confirmed |
| Features | HIGH | Patterns verified against Stripe/Twilio/Zuplo documentation; spec validated against `requirements/tenant-management.md` |
| Architecture | HIGH | All affected source files read directly; build order derived from actual dependency chain, not inference |
| Pitfalls | HIGH | All pitfalls derived from direct codebase inspection of v1 defects + well-understood Spring/JPA/Quartz failure modes |

**Overall confidence:** HIGH

### Gaps to Address

- **WebhookSecret storage decision:** The `tenant.webhook_secret` column is currently plaintext. The "admin-revealable via eye icon" requirement implies plaintext or reversibly-encrypted storage — one-way hashing is not viable here. The team must explicitly accept plaintext storage or add column-level encryption before implementation starts. If plaintext is accepted, `webhookSecret` must be null in all list/detail API responses and only populated on the explicit reveal endpoint.

- **Re-rotation during grace period:** If an admin rotates a key while a ROTATED key is still in its 24h grace window, the still-ROTATED key must be moved to REVOKED immediately (no two overlapping grace periods per environment). This edge case must be explicitly implemented in `ApiKeyService.rotate()` and covered by a test — it is documented in FEATURES.md but easy to miss during implementation.

---

## Sources

### Primary (HIGH confidence)
- Codebase direct inspection (2026-04-02): `ApiKeyService.java`, `TenantApiKey.java`, `Tenant.java`, `TenantApiKeyRepository.java`, `ApiKeyAuthenticationFilter.java`, `ReconciliationJob.java`, `WebhookSchedulerConfig.java`, `PlatformConfigEmailListener.java`, `SecurityConfiguration.java`, `V1__tenant_schema.sql`, `application.yaml`, `UpdatePasswordDialog.vue`, `package.json`
- `requirements/tenant-management.md` — authoritative spec for prefix format, one-active constraint, rotation grace period, reactivation flow
- `.planning/PROJECT.md` — existing features and key decisions
- Stripe API key documentation: https://docs.stripe.com/keys
- Quasar `$q.copyToClipboard` utility: https://quasar.dev/quasar-plugins/copyToClipboard

### Secondary (MEDIUM confidence)
- Zuplo API key lifecycle guide: https://zuplo.com/learning-center/api-key-rotation-lifecycle-management
- API token hashing survey (industry pattern): https://fly.io/blog/api-tokens-a-tedious-survey/
- Mastodon webhook UI bug (copy-before-close modal pattern rationale): https://github.com/mastodon/mastodon/issues/30498

---

*Research completed: 2026-04-02*
*Ready for roadmap: yes*
