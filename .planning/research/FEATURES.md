# Features Research — v6

**Domain:** Admin UI patterns for API key management, tenant CRUD, and lifecycle email notifications
**Researched:** 2026-04-07
**Overall confidence:** HIGH — patterns verified against GitLab/Stripe/GitHub implementations; Quasar docs confirmed; all codebase patterns read directly from source

> **Note:** This file supersedes the v5 FEATURES.md for the v6 milestone. v5 research (2026-04-02) covered
> service-layer design. This document focuses on the UI/UX patterns, email security, and implementation
> approach for the HTTP surface and Admin UI features now being added.

---

## One-Time Key Display (AKEY-07)

### What the Pattern Is

When a key is generated or rotated, the raw value is returned exactly once from the API and is never stored
in plaintext. The backend already enforces this: `TenantAdminResource.createTenant()` and `rotateKey()`
both return `result.rawKey()` — a value that exists only in memory during the HTTP response, not in the DB.

The frontend must catch this single response and surface it in a modal that prevents accidental dismissal
before the admin has confirmed they copied it.

This is the canonical pattern used by GitHub, GitLab, Stripe, and AWS IAM. GitLab's docs state explicitly:
"After creating a token, GitLab will display it once. Copy the token and store it in a secure place, as
you will not be able to view it again."

### Table Stakes (must have)

| Feature | Why Required | Complexity | Notes |
|---------|--------------|------------|-------|
| Full key displayed in monospace read-only field | Admin must see the value to copy it | Low | `q-input readonly outlined` with font-family monospace |
| Copy-to-clipboard button | One-click copy; reduces transcription error | Low | Quasar `copyToClipboard(key).then(...)` imported from `quasar` |
| Copy-confirmed visual state | Feedback that copy succeeded (icon changes to checkmark) | Low | Toggle `hasCopied` boolean after `.then()` resolves; swap icon `content_copy` → `check` |
| Dismissal guard — confirm button disabled until copy confirmed | Prevents modal close before admin copies key | Low | `q-btn :disable="!hasCopied"` on the "I've saved it" action button |
| Warning banner inside modal | "This key will not be shown again" | Low | `q-banner` with `bg-warning` tone |
| Modal title identifies context | "New API Key — PROD" so admin knows which key | Low | Pass environment + key prefix as props |
| `persistent` modal — no ESC or backdrop close | Key is lost silently if closed accidentally | Low | `q-dialog persistent` prop; dismissal only via the confirm button |

### Differentiators (valuable, not expected)

| Feature | Value | Complexity | Notes |
|---------|-------|------------|-------|
| Auto-select text on input focus | Speeds up manual copy as fallback | Low | `@focus="$event.target.select()"` on the q-input element |
| 3-second countdown before confirm button enables | Slows accidental click-through | Low | `setTimeout(() => { canDismiss = true }, 3000)` on modal open |
| Key prefix shown separately | Confirms prefix matches tenant name | Low | Derived from key itself (`key.split('_')[0]`) |

### Anti-Features

| Anti-Feature | Why Avoid | Instead |
|--------------|-----------|---------|
| Allowing modal close via ESC or backdrop before copy | Key lost silently | `q-dialog persistent` — only explicit confirm dismisses |
| Storing raw key in component state after modal closes | Re-exposure risk | Set `rawKey = null` in `@hide` handler immediately |
| Storing raw key in Pinia store or localStorage | Persistence creates exposure surface | Component-local `ref` only; cleared on close |
| Silently auto-copying key to clipboard without user action | Clipboard API requires user gesture in modern browsers | Always trigger copy on explicit button click |

### Quasar Implementation Approach

```
q-dialog persistent v-model="showKeyModal"
  q-card (min-width: 480px)
    q-card-section
      div.text-h6 → "New API Key — {{ environment }}"
    q-card-section
      q-banner inline-actions class="bg-warning text-white"
        → "This key is shown only once. Copy it now — it cannot be retrieved again."
    q-card-section
      q-input :model-value="rawKey" readonly outlined
               style="font-family: monospace"
               @focus="$event.target.select()"
        template #append
          q-btn flat round
                :icon="hasCopied ? 'check' : 'content_copy'"
                :color="hasCopied ? 'positive' : 'grey'"
                @click="copyKey"
    q-card-actions align="right"
      q-btn label="I have saved this key" color="primary"
             :disable="!hasCopied" @click="closeModal"
```

`copyKey()` calls `copyToClipboard(rawKey).then(() => { hasCopied.value = true })`.

**Known Quasar limitation (issue #15076):** `copyToClipboard` uses `navigator.clipboard` (async Clipboard
API) and falls back to `document.execCommand('copy')`. The fallback path can fail when a `q-dialog` backdrop
is in the DOM in some browsers because focus is trapped. The safe workaround: on click, also call
`$event.target.closest('input, textarea')?.select()` then `document.execCommand('copy')` as an explicit
secondary path if the promise rejects.

**Triggering the modal:** The modal is opened from two places:
1. After `POST /v1/admin/tenants` (tenant creation) — response body contains `apiKey.rawKey`
2. After `POST /v1/admin/tenants/{ref}/keys/{keyId}/rotate` — response body contains `rawKey`
3. After `POST /v1/admin/tenants/{ref}/reactivate` — response body contains `rawKey`

In all three cases: open modal immediately in the `.then()` of the API call, before the tenant detail
refreshes. Do not refresh the page until the modal confirm is clicked.

---

## Secret Reveal UI (WSEC-02)

### What the Pattern Is

The webhook secret is stored persistently (unlike API keys, it is stored in recoverable form — encrypted
or plaintext per current `TenantService.regenerateWebhookSecret()` which stores the raw UUID). The field
displays as masked by default (`••••••••`) and reveals on explicit toggle. This is the password-visibility-toggle
pattern adapted for a read-only credential field.

Two established icon conventions exist:
- **Icon shows the action**: slash-eye when hidden → click reveals. Users must interpret what the icon means.
- **Icon shows the state**: open-eye when visible → this is what is currently true.

Research consensus: for admin/security contexts, **icon-shows-state** with a companion text label ("Show" / "Hide")
is clearest and reduces misclick risk.

### Table Stakes

| Feature | Why Required | Complexity | Notes |
|---------|--------------|------------|-------|
| Masked display by default | Shoulder-surfing protection | Low | Display as `••••••••` or `q-input type="password"` |
| Eye icon toggle | Expected pattern; universally understood | Low | `q-btn flat round` in `q-input` append slot |
| State-indicating icon + label | `visibility` + "Show" when hidden; `visibility_off` + "Hide" when revealed | Low | `revealed` boolean ref; both icon and text label change |
| Copy-to-clipboard when revealed | Expected on any credential field | Low | Same `copyToClipboard` pattern; only enabled when `revealed = true` |

### Differentiators

| Feature | Value | Complexity | Notes |
|---------|-------|------------|-------|
| Lazy fetch on reveal (secret not sent in initial page load) | Secret not in DOM until explicitly requested; reduces exposure surface | Medium | GET `/v1/admin/tenants/{ref}/webhook-secret` called only when admin clicks reveal |
| Auto-re-mask after 30 seconds | Masks if admin leaves the page open and walks away | Low | `setTimeout` reset on reveal; cancelled if masked manually |
| Re-mask on browser tab hide | Masks automatically if tab becomes hidden | Low | `document.addEventListener('visibilitychange', () => { if (document.hidden) revealed.value = false })` |

### Anti-Features

| Anti-Feature | Why Avoid | Instead |
|--------------|-----------|---------|
| Secret value in DOM before reveal (hidden via CSS only) | Still visible in page source and DevTools | Lazy-fetch pattern — value not in DOM until fetch completes |
| Persisting revealed state across navigation | Secret visible when admin returns to page | `revealed` is component-local ref; reset to false on `onUnmounted` |

### Quasar Implementation Approach

**Recommended: Lazy-fetch strategy (Strategy B)**

The current `TenantService` stores the webhook secret as a raw UUID in the `webhook_secret` column. This
means a GET detail endpoint that returns the full tenant would expose the secret in every detail page load.
The lazy-fetch approach prevents this.

```
// Component state
const revealed = ref(false)
const secretValue = ref('')   // empty until fetched

async function revealSecret() {
  if (!revealed.value) {
    const res = await adminApi.getWebhookSecret(tenantRef)
    secretValue.value = res.data.secret
    revealed.value = true
    // auto-re-mask after 30s
    setTimeout(() => { revealed.value = false; secretValue.value = '' }, 30_000)
  } else {
    revealed.value = false
    secretValue.value = ''
  }
}
```

```
q-input :model-value="revealed ? secretValue : '••••••••'"
         readonly outlined
  template #append
    q-btn flat round
          :icon="revealed ? 'visibility_off' : 'visibility'"
          :label="revealed ? 'Hide' : 'Show'"
          @click="revealSecret"
    q-btn flat round icon="content_copy"
          :disable="!revealed"
          @click="copyToClipboard(secretValue)"
```

**New endpoint required:** `GET /v1/admin/tenants/{ref}/webhook-secret` — returns `{ "secret": "raw-uuid" }`.
This endpoint must be `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`.

The current `TenantAdminResource` does not expose this yet. It is a net-new endpoint in the v6 HTTP surface.

---

## Tenant List/Detail (TENT-05/06)

### What the Pattern Is

Standard SaaS admin list + detail navigation: a paginated, filterable table listing all tenants with inline
status badges, and a detail page with full tenant info plus key management per environment.

The existing `TransactionSearchPage.vue` establishes the project pattern: `q-table` + filter inputs above +
`@row-click` navigation to detail + `$q.notify` for errors. Tenant pages follow the same structure.

### Table Stakes — Tenant List (TENT-05)

| Feature | Why Required | Complexity | Notes |
|---------|--------------|------------|-------|
| Paginated QTable of all tenants | Core feature; no one scrolls 500 rows | Low | Server-side pagination: `:pagination` with `rowsNumber`, `@request` event, `loading` prop |
| Status badge per row | Immediate visual scan — ACTIVE/SUSPENDED | Low | `q-badge :color="row.status === 'ACTIVE' ? 'positive' : 'negative'"` in `body-cell-status` slot |
| Search by name | Standard for lists > 20 items | Low | Debounced `q-input`; passes `search` param to GET `/v1/admin/tenants?search=...` |
| Filter by status | "Show all suspended" is a common ops query | Low | `q-select :options="['ALL','ACTIVE','SUSPENDED']"` driving `status` query param |
| Row click → detail | Established pattern in the project | Low | `@row-click="router.push('/admin/tenants/' + row.tenantRef)"` |
| Create tenant button | Primary action | Low | `q-btn` in page header opens create dialog |

### Table Stakes — Tenant Detail (TENT-06)

| Feature | Why Required | Complexity | Notes |
|---------|--------------|------------|-------|
| Tenant identity section (name, email, webhookUrl, status) | Core display | Low | Read-only display with edit buttons |
| Edit name / email / webhookUrl | Service layer complete in v5 | Low | `q-dialog` following `UpdateInfoDialog.vue` pattern |
| Status toggle — Suspend / Reactivate | Core management | Low | `q-btn` with confirmation before action |
| API key list per environment | Core display | Low | Grouped by env; show prefix, status, created-at |
| Rotate key | Core key operation | Medium | Confirm → rotate endpoint → AKEY-07 modal |
| Revoke key | Core key operation | Low | Confirm → DELETE endpoint; no key modal needed |
| Generate key for an environment | For envs with no active key | Low | Confirm env → POST → AKEY-07 modal |
| WebhookSecret section | WSEC-02 | Medium | Masked field + reveal toggle + regenerate button |

### Differentiators

| Feature | Value | Complexity | Notes |
|---------|-------|------------|-------|
| Inline key status chip with grace period countdown | "ROTATED — 18h 30m remaining" for ROTATED keys | Low | `rotatedAt` in API response; `dayjs(rotatedAt).add(24,'h').diff(now, 'minute')` |
| Tenant ref copy button | Quick copy of tenantRef for API integration handoff | Low | `copyToClipboard(tenantRef)` with same icon-swap feedback |
| Key count per environment in list view | "3 envs configured" quick scan | Low | Add to list endpoint or compute client-side from detail |

### Anti-Features

| Anti-Feature | Why Avoid | Instead |
|--------------|-----------|---------|
| Editable table cells (inline editing) | Creates conflicting state; project uses dialog pattern | `q-dialog` for all edits, consistent with existing profile dialogs |
| Showing raw key hash in UI | No operational value; exposes internal identifier | Display prefix, environment, status, created-at only |
| Tenant delete from UI | No soft-delete in schema; irreversible without recovery path | Suspend is the correct lifecycle action; hard delete deferred |
| Auto-refreshing detail page while admin has dialog open | Overwrites unsaved state | Disable polling/refresh when any edit dialog is open |

### Route Additions Needed

```javascript
// Add to routes.js under /admin children:
{ path: 'tenants', component: () => import('pages/admin/TenantListPage.vue') }
{ path: 'tenants/:tenantRef', component: () => import('pages/admin/TenantDetailPage.vue') }
```

### Status Toggle UX — Important Side Effects

**Suspension** calls `TenantService.suspend()` which atomically revokes all active + rotated keys for all
environments. The confirmation dialog must make this consequence explicit:
> "Suspending this tenant will immediately revoke all API keys across all environments (PROD, DEV, SANDBOX).
> The tenant cannot process payments until reactivated."

**Reactivation** calls `TenantService.reactivate()` which:
1. Sets tenant status to ACTIVE
2. Calls `apiKeyService.generateAndStore(tenant, ApiKeyEnvironment.PROD)` — returns a new raw PROD key

The API response from `POST /v1/admin/tenants/{ref}/reactivate` must therefore include the raw key so the
frontend can immediately open the AKEY-07 modal. Without this flow, the tenant is reactivated but has no
credentials.

---

## Email Notifications (NOTIF-01..06)

### Security Rules for Key Material in Emails

**Critical constraint (HIGH confidence — Stripe docs, multiple authoritative sources):**
Never include raw key material in any email.

Stripe docs: "Don't share keys over email, chat, or other unencrypted channels."

Email is unacceptable as a key delivery channel because:
- Stored in clear text on mail servers
- Replicated to backup systems with indefinite retention
- Forwarded, replied-to, or auto-archived by mail clients
- Accessible to IT / helpdesk personnel with email admin rights
- Subject to email-account-compromise scenarios that are unrelated to Payam

**Rule: emails notify that an event occurred — they never contain the credential itself.**

The one-time display modal (AKEY-07) is the delivery channel for key material. The email is the audit record.

### What to Include in Key Lifecycle Emails

| Include | Reason |
|---------|--------|
| Event type (key generated, rotated, revoked, reactivated) | Tenant must understand what happened |
| Tenant name | Confirms which tenant account |
| Environment (PROD / DEV / SANDBOX) | Narrows the scope of impact |
| Key prefix (e.g. `ACM_`) | Identifies which key without exposing it |
| Timestamp (ISO 8601) | Audit trail |
| Admin identifier who performed the action | Accountability |
| "If you did not authorize this action..." warning | Security baseline |
| Help code (existing project pattern) | Support reference |
| Link to admin dashboard (not to the key) | Where to go for further action |

### What Never to Include

| Exclude | Reason |
|---------|--------|
| Raw key value | Critical security violation |
| Key hash | Exposes internal identifier; no value to recipient |
| Webhook secret value | Same as raw key |
| Any string that could be used to authenticate a request | Defeats the purpose |

### NOTIF Event Matrix

| ID | Trigger | Recipient | Key Template Data | Template Name |
|----|---------|-----------|-------------------|---------------|
| NOTIF-01 | Key generated (new tenant or add env) | `tenant.email` | tenantName, environment, keyPrefix, timestamp, performedBy | `TENANT_KEY_GENERATED` |
| NOTIF-02 | Key rotated | `tenant.email` | tenantName, environment, keyPrefix, oldKeyPrefix, timestamp, performedBy | `TENANT_KEY_ROTATED` |
| NOTIF-03 | Key revoked (manual) | `tenant.email` | tenantName, environment, keyPrefix, timestamp, performedBy | `TENANT_KEY_REVOKED` |
| NOTIF-04 | Tenant reactivated (auto-generates new key) | `tenant.email` | tenantName, newKeyPrefix, timestamp, performedBy | Reuse `TENANT_KEY_GENERATED` with action=REACTIVATED or separate |
| NOTIF-05 | Webhook secret regenerated | `tenant.email` | tenantName, timestamp, "new secret available in admin dashboard" | `TENANT_WEBHOOK_SECRET_CHANGED` |
| NOTIF-06a | Tenant suspended | `tenant.email` | tenantName, timestamp, "all keys revoked", performedBy | `TENANT_STATUS_CHANGED` |
| NOTIF-06b | Tenant reactivated | `tenant.email` | tenantName, timestamp, performedBy | `TENANT_STATUS_CHANGED` (action switch) |
| NOTIF-06c | Tenant email changed | old email + new email | tenantName, oldEmail, newEmail, timestamp | `TENANT_CONFIG_CHANGED` |
| NOTIF-06d | Webhook URL changed | `tenant.email` | tenantName, oldUrl (partially masked), newUrl, timestamp | `TENANT_CONFIG_CHANGED` (action switch) |

### Table Stakes

| Feature | Why Required | Complexity | Notes |
|---------|--------------|------------|-------|
| Event-specific subject line | Admin inbox must be scannable | Low | Separate i18n subject key per template |
| Tenant name + environment + key prefix in body | Tenant must identify which key | Low | `${map.tenantName}`, `${map.environment}`, `${map.keyPrefix}` |
| Timestamp in body | Audit trail | Low | `${map.occurredAt}` formatted |
| "If you did not authorize..." warning | Security baseline | Low | Already in `platformConfigChanged.html` |
| Help code | Existing project pattern | Low | `ShortCode.shortenInt(UUID.randomUUID().hashCode())` |

### Differentiators

| Feature | Value | Complexity | Notes |
|---------|-------|------------|-------|
| Admin identity in email ("Action performed by: admin@payam.io") | Accountability; useful for dispute resolution | Low | Pass `performedBy` in event data |
| Single notification for suspend (not N revocation emails) | Avoid email flood: suspend revokes N keys atomically | Low | Emit one `TenantSuspendedEvent` mentioning "all keys revoked" |

### Anti-Features

| Anti-Feature | Why Avoid | Instead |
|--------------|-----------|---------|
| Raw key in email body | Critical security failure | Never |
| N individual key-revocation emails when tenant is suspended | Spam; suspend is one action | Single `TenantSuspendedEvent` email |
| Blocking HTTP thread on email send | Latency spike | Continue `ApplicationEventPublisher` → `Envelope` async pattern |
| Sending key-event emails to the admin who performed the action | Wrong recipient | Send to `tenant.email`; admin does not need a copy |

### Backend Integration Pattern

The existing email infrastructure: service calls `publisher.publishEvent(envelope)`, `Envelope` is consumed
by the mail service. Pattern for NOTIF events:

**Step 1:** Add new `EmailTemplate` enum values:
```java
TENANT_KEY_GENERATED("email.tenant_key_generated.title"),
TENANT_KEY_ROTATED("email.tenant_key_rotated.title"),
TENANT_KEY_REVOKED("email.tenant_key_revoked.title"),
TENANT_WEBHOOK_SECRET_CHANGED("email.tenant_webhook_secret_changed.title"),
TENANT_STATUS_CHANGED("email.tenant_status_changed.title"),
TENANT_CONFIG_CHANGED("email.tenant_config_changed.title")
```

**Step 2:** Create Thymeleaf templates in `src/main/resources/mails/` following the `platformConfigChanged.html`
pattern (plain HTML with Thymeleaf `th:text`, a `${map.action}` switch for variants, and the standard
help-code footer).

**Step 3:** Create a `TenantLifecycleEmailListener` following `AccountChangeEmailListener`. It listens
on domain events published from `TenantService` and `ApiKeyService` operations.

**Step 4:** Service layer publishes Spring application events. Events carry tenant metadata but never raw
key material.

**Event record structure for key events:**
```java
record TenantKeyEvent(
    String tenantRef,
    String tenantName,
    String tenantEmail,        // may be null — skip send if null
    String keyPrefix,
    ApiKeyEnvironment environment,
    TenantKeyEventType eventType,   // GENERATED | ROTATED | REVOKED
    String performedByAdminEmail,
    Instant occurredAt
) {}
// NOTE: rawKey is deliberately absent from this record.
```

**Conditional send:** If `tenant.getEmail()` is null or blank, skip the notification silently. The tenant
email field is optional in the current schema. Do not throw; log at DEBUG.

---

## Admin Tenant Management CRUD

### Overall Structure

Four interaction modes:

1. **List view** — browse, filter, navigate (TENT-05)
2. **Detail view** — inspect, edit fields, manage keys (TENT-06)
3. **Create tenant** — new tenant + initial key generation in one action
4. **Status toggle** — suspend / reactivate with confirmation + key modal on reactivate

### Table Stakes

| Feature | Why Required | Complexity | Notes |
|---------|--------------|------------|-------|
| Create tenant form | Core operation | Low | Dialog: name + environment select (`PROD/DEV/SANDBOX`); `POST /v1/admin/tenants` |
| Created → AKEY-07 modal | Key must be shown at creation | Low | `response.data.apiKey.rawKey` → open key modal immediately |
| Edit name dialog | Service layer done | Low | `q-dialog` matching `UpdateInfoDialog.vue` |
| Edit email dialog | Service layer done | Low | Same pattern; triggers NOTIF-06c email |
| Edit webhook URL dialog | Service layer done | Low | Same pattern; triggers NOTIF-06d email |
| Suspend with confirmation | Destructive: revokes all active keys | Low | `$q.dialog` confirm with explicit consequence text |
| Reactivate with key modal | Generates new PROD key as side effect | Medium | `POST /v1/admin/tenants/{ref}/reactivate` → response has rawKey → AKEY-07 modal |
| Rotate key (per key row) | Core key operation | Medium | Confirm → `POST .../keys/{id}/rotate` → AKEY-07 modal |
| Revoke key (per key row) | Core key operation | Low | Confirm → `DELETE .../keys/{id}`; no modal needed |
| Generate key for environment | When an env has no active key | Low | Confirm env → POST → AKEY-07 modal |
| Regenerate webhook secret | Core credential rotation | Low | Confirm → POST → show new secret in AKEY-07-style one-time modal OR WSEC-02 revealed state |
| TENT-09: suspended tenant blocks API auth | Authentication must check tenant status | Low | `ApiKeyAuthenticationFilter`: add `&& key.getTenant().getTenantStatus() == ACTIVE` check |

### Differentiators

| Feature | Value | Complexity | Notes |
|---------|-------|------------|-------|
| ROTATED key grace period remaining shown in UI | "18h 30m until auto-revoke" | Low | Requires `rotatedAt` in key API response; `dayjs` diff from `rotatedAt + 24h` |
| Color-coded key status chips | ACTIVE=green, ROTATED=amber, REVOKED=grey | Low | `q-chip :color="keyStatusColor(key.status)"` |

### Anti-Features

| Anti-Feature | Why Avoid | Instead |
|--------------|-----------|---------|
| `$q.dialog` confirm with a single click (no typed confirmation) | Destructive ops need friction | Use `$q.dialog` with `message` explaining consequences; keep it standard but clear |
| Showing key after initial modal | Re-exposure | Key section shows prefix + env + status + date only; raw value never shown again |
| Auto-refresh while edit dialog open | Overwrites form state | Gate refresh behind `!editDialogOpen` flag |
| Creating tenant without picking environment | Service requires env; will throw 400 | Enforce env selection in the create dialog form |
| Sending raw key in the reactivation confirmation email | Security violation | Email confirms reactivation; key was shown in the modal |

### REST Endpoints Summary

These surface the existing v5 service layer (or are net-new reads):

| Method | Path | Service Op | Status |
|--------|------|------------|--------|
| GET | `/v1/admin/tenants` | find all (paginated) | Net-new |
| GET | `/v1/admin/tenants/{ref}` | find by ref | Net-new |
| POST | `/v1/admin/tenants` | `createTenant` | **Exists** |
| PATCH | `/v1/admin/tenants/{ref}/name` | `updateName` | Net-new HTTP surface |
| PATCH | `/v1/admin/tenants/{ref}/email` | `updateEmail` | Net-new HTTP surface |
| PATCH | `/v1/admin/tenants/{ref}/webhookUrl` | `updateWebhookUrl` | Net-new HTTP surface |
| POST | `/v1/admin/tenants/{ref}/suspend` | `suspend` | Net-new HTTP surface |
| POST | `/v1/admin/tenants/{ref}/reactivate` | `reactivate` | Net-new HTTP surface |
| POST | `/v1/admin/tenants/{ref}/keys` | `generateAndStore` | Net-new HTTP surface |
| POST | `/v1/admin/tenants/{ref}/keys/{keyId}/rotate` | `rotate` | **Exists** |
| DELETE | `/v1/admin/tenants/{ref}/keys/{keyId}` | `revoke` | **Exists** |
| GET | `/v1/admin/tenants/{ref}/webhook-secret` | get plaintext secret | Net-new (WSEC-02 lazy reveal) |
| POST | `/v1/admin/tenants/{ref}/webhook-secret/regenerate` | `regenerateWebhookSecret` | Net-new HTTP surface |

**TENT-09 location:** `ApiKeyAuthenticationFilter.java` — the `authenticate()` call path. After the key is
found by hash, check `key.getTenant().getTenantStatus() != TenantStatus.ACTIVE` and throw
`BadCredentialsException` (or a new `TenantSuspendedException`) if suspended.

---

## Implementation Complexity Summary

| Feature | Complexity | Primary Risk |
|---------|------------|--------------|
| AKEY-07 one-time key modal | Low | Quasar `copyToClipboard` in-dialog fallback (#15076) — test explicitly |
| WSEC-02 secret reveal | Low–Medium | New GET endpoint + lazy-fetch pattern |
| TENT-05 tenant list | Low | QTable server-side pagination reactive wiring (known Quasar discussion thread) |
| TENT-06 tenant detail | Medium | Reactivate: dual concern (status change + key generation + immediate modal trigger) |
| NOTIF-01..06 emails | Low–Medium | 6 templates + listener + domain events from service layer; strict no-key-material rule |
| Admin CRUD | Medium | Suspend/reactivate flows have side effects that must chain to UI modal actions |
| TENT-09 auth enforcement | Low | Single condition in `ApiKeyAuthenticationFilter`; well-isolated change |

---

## Sources

- Quasar `copyToClipboard` utility: https://quasar.dev/quasar-utils/other-utils/
- Quasar QDialog `persistent` prop: https://quasar.dev/vue-components/dialog/
- Quasar copyToClipboard in-dialog failure (issue #15076): https://github.com/quasarframework/quasar/issues/15076
- QTable server-side pagination: https://quasar.dev/vue-components/table/ and https://dev.to/quasar/quasar-s-qtable-the-ultimate-component-3-6-loading-state-pagination-and-sorting-2mg0
- GitLab "shown once" token UX: https://docs.gitlab.com/user/profile/personal_access_tokens/
- Stripe key management ("one time display", "don't share over email"): https://docs.stripe.com/keys and https://docs.stripe.com/keys-best-practices
- Eye icon state vs action convention: https://joyalks.medium.com/toggle-icon-behavior-explained-857fb45b7925
- NN/g confirmation dialog guidance: https://www.nngroup.com/articles/confirmation-dialog/
- API key security — never in email: https://dev.to/hamd_writer_8c77d9c88c188/api-keys-the-complete-2025-guide-to-security-management-and-best-practices-3980
- API key lifecycle email content (what to include): https://oneuptime.com/blog/post/2026-02-20-api-key-management-best-practices/view
- Codebase sources read directly:
  - `TenantAdminResource.java` — existing HTTP endpoints
  - `TenantService.java` — full service layer
  - `ApiKeyService.java` — key operations including rotate/revoke
  - `AccountChangeEmailListener.java` — established email pattern
  - `EmailTemplate.java` — existing template enum
  - `platformConfigChanged.html`, `profileChange.html` — template style
  - `TransactionSearchPage.vue` — QTable + filter pattern to follow
  - `PlatformConfigPage.vue` — q-dialog + q-card pattern to follow
  - `AdminDashboardPage.vue` — q-badge + q-card metric card pattern
  - `routes.js` — existing route structure to extend

---

*Feature research for: Payam v6 — REST API Surface, Notifications & Admin UI*
*Researched: 2026-04-07*
