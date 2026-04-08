# Phase 33: Admin UI — Tenant Management - Research

**Researched:** 2026-04-08
**Domain:** Quasar Framework v2 Admin SPA — Vue 3 Composition API, q-table server-side pagination, q-dialog programmatic confirm, persistent modal, lazy-fetch secret reveal
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Use the `PlatformConfigPage` pattern — always-editable `q-input` fields, each with its own Save button. No "edit mode" toggle; no pencil-icon-per-field pattern. Per-field save means per-field Save button, not a confirmation dialog before each PATCH.
- **D-02:** Save button is `:loading` during the PATCH call and shows a `$q.notify` success/failure toast on completion — consistent with the rest of the admin pages.
- **D-03:** Display keys in a `q-table` on the detail page, columns: Env, Key Prefix, Status (chip), Created Date, Actions. Rows are grouped/sorted by env (PROD first).
- **D-04:** Action buttons in each row are context-sensitive by key state: No active key for env → "Generate" button; Active key → "Rotate" | "Revoke" buttons; Revoked key → "Reactivate" button; Rotated key (grace period) → no actions (read-only row).
- **D-05:** Generate, Rotate, and Reactivate all return a `rawKey` — all three trigger the UI-03 one-time key modal after the API call completes. The modal is a single reusable component.
- **D-06:** Suspend and Reactivate are triggered by a single status toggle button on the detail page header (label changes based on current status: "Suspend" when ACTIVE, "Reactivate" when SUSPENDED).
- **D-07:** Confirmation uses `$q.dialog()` programmatic confirm — consistent with existing admin patterns. Copy: "Suspend [tenant name]? All API keys will be revoked." / "Reactivate [tenant name]? A new PROD key will be generated."
- **D-08:** On reactivate confirmation: call API → on success, immediately open the UI-03 one-time key modal with the returned `rawKey`. Two distinct steps (confirm dialog → key modal), not merged into one.
- **D-09:** On suspend confirmation: call API → `$q.notify` success toast. No key modal (keys are revoked, not generated).
- **D-10:** Persistent `q-dialog` (cannot be dismissed by clicking outside or pressing Escape). Shows the raw key in a monospace `q-input readonly` with a "Copy" button.
- **D-11:** Dismiss is gated: a checkbox "I have copied the key" must be checked before the "Done" button enables. On "Done", clear `rawKey` from component state immediately (set to `null`).
- **D-12:** The modal is a single shared component (`OneTimeKeyModal.vue`) used from all three trigger points (generate, rotate, reactivate). It receives `rawKey` as a prop; parent manages visibility.
- **D-13:** Secret displayed in a `q-input` with `type="password"` and an eye-icon append slot. Initial state: masked, no secret in component state.
- **D-14:** On eye-icon click: lazy-fetch `GET /v1/admin/tenants/{tenantRef}/webhook-secret`, display plaintext, start a 30-second countdown timer, auto-re-mask (clear secret from state) on timeout.
- **D-15:** If eye icon is clicked again while revealed, immediately re-mask and cancel the timer (toggle behavior). A second click re-fetches and restarts the 30s timer.
- **D-16:** Add all tenant endpoints to the existing `adminApi` in `src/api/admin.api.js` — consistent with how all other admin API calls are organized. No new file.
- **D-17:** Two new routes under `/admin`: `/admin/tenants` → `TenantListPage.vue`; `/admin/tenants/:tenantRef` → `TenantDetailPage.vue`
- **D-18:** Add "Tenants" nav item to `MainLayout.vue` sidebar under the Admin section, between Transactions and Reconciliation. Icon: `group`.

### Claude's Discretion

- Component file placement: new pages in `src/pages/admin/`, new shared modal in `src/components/admin/`
- Error handling: `$q.notify({ type: 'negative', message: ... })` pattern — consistent with existing admin pages
- Status chip colors: ACTIVE → `positive`, SUSPENDED → `negative`, other → `grey`
- Key status chip colors: ACTIVE → `positive`, REVOKED → `negative`, ROTATED → `warning`
- Pagination: use server-side pagination on the tenant list q-table (`:pagination` + `@request` handler), consistent with the paginated API (`page`/`size` params, `content`/`totalElements` response shape from Phase 31)

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope.

</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| UI-01 | Admin can view a tenant list page with paginated q-table, status filter, and row-click navigation to tenant detail | GET /v1/admin/tenants returns Spring Page<TenantSummaryDto> with content/totalElements; q-table server-side pagination via @request handler |
| UI-02 | Admin can view and edit tenant detail (name, email, webhookUrl) with inline save; can toggle status (suspend/reactivate) with a confirmation step | Three PATCH endpoints (204 No Content); POST /suspend and POST /reactivate; $q.dialog() programmatic confirm pattern established |
| UI-03 | Admin sees a one-time API key display modal (persistent QDialog, copy-confirm gate, rawKey cleared from component state on dismissal) after key generation or rotation | rawKey returned from rotate/reactivate endpoints; ApiKeyDto shape confirmed; q-dialog :persistent="true" prevents escape/outside-click |
| UI-04 | Admin can reveal and re-mask a tenant's webhook secret via eye icon on tenant detail page (lazy-fetch; auto-re-masks after 30s) | GET /v1/admin/tenants/{tenantRef}/webhook-secret returns WebhookSecretDto { webhookSecret }; setTimeout pattern for auto-mask |

</phase_requirements>

---

## Summary

Phase 33 is a pure frontend phase — all backend REST endpoints are already implemented and tested in Phase 31. The work is three new Vue 3 SFC files (TenantListPage, TenantDetailPage, OneTimeKeyModal) plus targeted edits to three existing files (admin.api.js, routes.js, MainLayout.vue).

The project uses Quasar Framework v2 with Vue 3 `<script setup>` Composition API, no TypeScript, no Pinia stores. All patterns are established in existing admin pages. The tenant list page follows TransactionSearchPage exactly (q-table + filter card + row-click navigation). The detail page follows PlatformConfigPage (always-editable inputs + per-field Save). The one-time key modal is a new pattern not yet present in the codebase but is a straightforward persistent q-dialog. The webhook secret reveal is a new lazy-fetch/timer pattern also not yet present, but trivially implemented with a `ref`, `setTimeout`, and a `setInterval` for the countdown display.

The API surface is fully mapped: all endpoint paths, HTTP methods, request bodies, and response shapes have been read directly from the backend source. No ambiguity exists in the integration contract. The one important behavioral detail: `reactivateKey` returns 204 No Content (no rawKey), so the key table Generate/Rotate actions use separate endpoints that do return rawKey. The tenant-level reactivate (`POST /reactivate`) does return an `ApiKeyDto` with rawKey.

**Primary recommendation:** Implement in three plans — (1) adminApi extensions + routing + nav, (2) TenantListPage + TenantDetailPage without key management, (3) key table actions + OneTimeKeyModal + webhook secret reveal.

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Quasar Framework v2 | 2.x (project-locked) | UI component library | Project-wide; all admin pages use it |
| Vue 3 | 3.x (project-locked) | SPA framework | Project-wide |
| vue-router 4 | project-locked | Client-side routing | All routes in routes.js |
| axios | project-locked (via src/boot/axios) | HTTP client | All adminApi calls use `api` from this boot file |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| navigator.clipboard | Browser API | Copy raw key to clipboard | OneTimeKeyModal "Copy" button |
| setTimeout / clearTimeout | Browser API | 30s auto-mask timer | Webhook secret reveal (D-14, D-15) |
| setInterval / clearInterval | Browser API | Countdown display | Showing remaining seconds while secret is revealed |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| navigator.clipboard | Quasar Clipboard plugin | navigator.clipboard is simpler for a single use; Quasar plugin adds a dependency |
| setTimeout for countdown | Quasar's $q.notify with timeout | setTimeout gives precise control needed for cancel-on-second-click (D-15) |

**Installation:** No new packages needed. All dependencies already present.

---

## Architecture Patterns

### Recommended Project Structure

```
src/frontend/src/
├── pages/admin/
│   ├── TenantListPage.vue       # UI-01 — new file
│   └── TenantDetailPage.vue     # UI-02, UI-03 (modal), UI-04 — new file
├── components/admin/
│   └── OneTimeKeyModal.vue      # UI-03 shared modal — new file (new dir)
├── api/
│   └── admin.api.js             # extend existing — add 10 tenant methods
├── router/
│   └── routes.js                # add 2 routes under admin children
└── layouts/
    └── MainLayout.vue           # add Tenants q-item between Transactions and Reconciliation
```

### Pattern 1: Server-Side Paginated q-table (UI-01)

**What:** q-table with `:pagination` reactive object and `@request` handler that calls the API when the user changes page/size.
**When to use:** Whenever the backend returns Spring `Page<T>` with `content` and `totalElements`.

```javascript
// Source: Quasar docs + existing admin page conventions
const pagination = ref({ page: 1, rowsPerPage: 20, rowsNumber: 0 })

async function onRequest({ pagination: p }) {
  loading.value = true
  try {
    const resp = await adminApi.listTenants({
      status: statusFilter.value === 'ALL' ? undefined : statusFilter.value,
      page: p.page - 1,  // Spring is 0-indexed; Quasar q-table is 1-indexed
      size: p.rowsPerPage,
    })
    rows.value = resp.data.content
    pagination.value = { ...p, rowsNumber: resp.data.totalElements }
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load tenants' })
  } finally {
    loading.value = false
  }
}
```

**Critical detail:** Spring Page uses 0-based `page` param. Quasar q-table `@request` handler provides 1-based `pagination.page`. Subtract 1 when calling the API.

### Pattern 2: Per-Field Save with :loading (UI-02)

**What:** Always-editable `q-input outlined dense` with a dedicated Save button per field. The Save button uses a per-field `saving` reactive object to track loading state independently.
**When to use:** Inline editing with separate PATCH endpoints per field.

```javascript
// Source: PlatformConfigPage.vue pattern — savingProvider ref applied per-field
const saving = reactive({ name: false, email: false, webhookUrl: false })

async function saveName() {
  saving.name = true
  try {
    await adminApi.updateTenantName(tenantRef, { name: form.name })
    $q.notify({ type: 'positive', message: 'Name updated successfully' })
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to update name. Please try again.' })
  } finally {
    saving.name = false
  }
}
```

### Pattern 3: Programmatic Confirm Dialog (UI-02)

**What:** `$q.dialog()` with `cancel: true` to show a confirm dialog before destructive or significant actions.
**When to use:** Suspend/Reactivate tenant status toggle (D-07).

```javascript
// Source: Quasar docs — programmatic dialog pattern; consistent with D-07
function confirmSuspend() {
  $q.dialog({
    title: `Suspend ${tenant.value.name}?`,
    message: 'All API keys will be revoked. The tenant will not be able to process payments.',
    cancel: true,
    ok: { label: 'Suspend', color: 'negative' },
    persistent: true,
  }).onOk(async () => {
    await doSuspend()
  })
}
```

### Pattern 4: Persistent One-Time Key Modal (UI-03)

**What:** `q-dialog` with `:persistent="true"` that cannot be dismissed until a checkbox is checked.
**When to use:** Any time rawKey is returned from an API call (generate, rotate, tenant reactivate).

```vue
<!-- OneTimeKeyModal.vue — receives rawKey prop, parent controls v-model visibility -->
<template>
  <q-dialog :model-value="modelValue" persistent @update:model-value="$emit('update:modelValue', $event)">
    <q-card style="min-width: 500px">
      <q-card-section>
        <div class="text-h6">API Key Generated</div>
      </q-card-section>
      <q-card-section>
        <p>This key will not be shown again. Copy it now and store it in a secure location.</p>
        <q-input
          :model-value="rawKey"
          readonly
          outlined
          :input-style="{ fontFamily: 'monospace' }"
        >
          <template #append>
            <q-btn flat icon="content_copy" @click="copyKey" />
          </template>
        </q-input>
      </q-card-section>
      <q-card-section>
        <q-checkbox v-model="copied" label="I have copied the key and stored it safely" />
      </q-card-section>
      <q-card-actions align="right">
        <q-btn color="primary" label="Done" :disable="!copied" @click="dismiss" />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref } from 'vue'
const props = defineProps({ rawKey: { type: String, required: true }, modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'close'])
const copied = ref(false)

function copyKey() {
  navigator.clipboard.writeText(props.rawKey)
}
function dismiss() {
  emit('close')    // parent sets rawKey = null
  emit('update:modelValue', false)
}
</script>
```

**Security note:** Parent must set `rawKey = null` on close, not inside the modal — modal is stateless w.r.t. the key.

### Pattern 5: Lazy-Fetch Secret Reveal with Auto-Mask (UI-04)

**What:** Eye-icon button triggers GET fetch, displays secret, starts 30s auto-mask timer, shows countdown.
**When to use:** Webhook secret display (D-13, D-14, D-15).

```javascript
// Source: CONTEXT.md D-13–D-15; standard setTimeout/setInterval browser APIs
const secret = ref(null)
const unmasked = ref(false)
const countdown = ref(0)
let maskTimer = null
let countdownInterval = null

function clearTimers() {
  clearTimeout(maskTimer)
  clearInterval(countdownInterval)
}

function startAutoMask() {
  countdown.value = 30
  countdownInterval = setInterval(() => { countdown.value-- }, 1000)
  maskTimer = setTimeout(() => {
    reMask()
  }, 30000)
}

function reMask() {
  clearTimers()
  secret.value = null
  unmasked.value = false
  countdown.value = 0
}

async function toggleSecret() {
  if (unmasked.value) {
    reMask()
    return
  }
  try {
    const resp = await adminApi.getWebhookSecret(route.params.tenantRef)
    secret.value = resp.data.webhookSecret
    unmasked.value = true
    startAutoMask()
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load webhook secret. Please try again.' })
  }
}
```

**Timer cleanup:** Call `clearTimers()` in `onUnmounted()` to prevent memory leaks if the user navigates away before the 30s expires.

### Anti-Patterns to Avoid

- **Single `saving` boolean for all fields:** Use `reactive({ name: false, email: false, webhookUrl: false })` so each field Save button shows loading independently. A shared boolean would lock all Save buttons during any single PATCH.
- **Merging confirm dialog + key modal into one step:** D-08 explicitly requires two separate UX steps: confirm dialog first, then key modal on success. Merging them would violate the contract.
- **Storing rawKey in a Pinia store or persistent state:** The raw key must live only in component `ref` state and be set to `null` on modal dismissal. Never pass it through a router, URL, or store.
- **Using `q-table` client-side pagination for tenant list:** The API returns Spring Page. Must use server-side pagination with `@request` handler. Client-side pagination would only show the first page of results.
- **Forgetting page offset:** Spring Page is 0-indexed; q-table pagination is 1-indexed. Always pass `page - 1` to the API.
- **Not calling `onUnmounted` cleanup for timers:** The webhook secret 30s timer must be cleared on component unmount or it fires after navigation, attempting to mutate unmounted component state.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Confirm dialog | Custom modal component | `$q.dialog({ cancel: true })` | Established pattern, accessible, consistent with admin UX |
| Success/error toasts | Custom notification component | `$q.notify({ type: 'positive'/'negative' })` | Already used across all admin pages |
| Status badge/chip | Custom `<span>` with color CSS | `<q-chip :color="...">` | Consistent visual language; automatic dark-mode handling |
| Clipboard copy | Custom textarea hack | `navigator.clipboard.writeText()` | Browser-native, no library needed |
| Loading spinner per-field | CSS spinner | `:loading="saving.fieldName"` on `q-btn` | Quasar built-in button loading state |

**Key insight:** Every UI primitive needed for this phase already exists as a Quasar component or browser API. The only custom code is orchestration logic (API calls, state management, timer control).

---

## API Integration Contract

All endpoints on `TenantAdminResource` at `/v1/admin/tenants`. Read directly from source.

### Read Endpoints

| Method | Path | Response | Notes |
|--------|------|----------|-------|
| GET | `/v1/admin/tenants` | `Page<TenantSummaryDto>` | params: `status`, `page` (0-based), `size` |
| GET | `/v1/admin/tenants/{tenantRef}` | `TenantDetailDto` | includes `List<ApiKeySummaryDto> keys` |
| GET | `/v1/admin/tenants/{tenantRef}/webhook-secret` | `WebhookSecretDto` | admin-only; returns `{ webhookSecret: string }` |

### Mutation Endpoints

| Method | Path | Body | Response | Notes |
|--------|------|------|----------|-------|
| PATCH | `/v1/admin/tenants/{tenantRef}/name` | `{ name }` | 204 No Content | |
| PATCH | `/v1/admin/tenants/{tenantRef}/email` | `{ email }` | 204 No Content | |
| PATCH | `/v1/admin/tenants/{tenantRef}/webhook-url` | `{ webhookUrl }` | 204 No Content | |
| POST | `/v1/admin/tenants/{tenantRef}/suspend` | (none) | 204 No Content | revokes all keys |
| POST | `/v1/admin/tenants/{tenantRef}/reactivate` | (none) | `ApiKeyDto` | returns rawKey for new PROD key |
| POST | `/v1/admin/tenants/{tenantRef}/webhook-secret` | (none) | 204 No Content | triggers regen; fetch secret via GET |

### Key Management Endpoints (use tenantId, not tenantRef)

| Method | Path | Response | Notes |
|--------|------|----------|-------|
| POST | `/v1/admin/tenants/{tenantId}/keys/{keyId}/rotate` | `ApiKeyDto` | returns rawKey |
| DELETE | `/v1/admin/tenants/{tenantId}/keys/{keyId}` | 204 No Content | revoke |
| POST | `/v1/admin/tenants/{tenantId}/keys/{keyId}/reactivate` | 204 No Content | no rawKey returned |

**CRITICAL distinction:** Tenant-level reactivate (`POST /{tenantRef}/reactivate`) returns `ApiKeyDto` with rawKey. Key-level reactivate (`POST /{tenantId}/keys/{keyId}/reactivate`) returns 204 No Content with no rawKey. D-04 lists "Reactivate" as a key table action for revoked keys — this uses the key-level endpoint and does NOT trigger the UI-03 modal (no rawKey to show). Only Generate (tenant create), Rotate, and tenant-level Reactivate trigger the modal (D-05).

**Important: Key generation ("Generate" button):** The CONTEXT.md D-04 lists a "Generate" button for envs with no active key. Looking at the backend, there is no standalone "generate key for existing tenant" endpoint separate from tenant creation. The rotate endpoint (`POST /{tenantId}/keys/{keyId}/rotate`) creates a new key from an existing one. For an env that has never had a key, the only path is `POST /` (create tenant). This is a gap to resolve in planning — the "Generate" button may need to call rotate on a placeholder key, or this feature may not be implementable without a new backend endpoint. Flag this as an open question.

### DTO Field Reference

**TenantSummaryDto** (list page rows):
```
{ id, tenantRef, name, tenantStatus }
```
Note: no `email` or `createdDate` in TenantSummaryDto. The UI-SPEC lists "Created" as a list column — but `TenantSummaryDto` has no `createdDate` field. This is a gap to resolve.

**TenantDetailDto** (detail page):
```
{ id, tenantRef, name, email, webhookUrl, tenantStatus, keys: ApiKeySummaryDto[] }
```

**ApiKeySummaryDto** (key table rows):
```
{ id, keyPrefix, environment, keyStatus }
```
Note: no `createdDate` field. The UI-SPEC lists "Created Date" as a key table column — but `ApiKeySummaryDto` has no date. This is a gap to resolve.

**ApiKeyDto** (returned from create/rotate/reactivate):
```
{ id, keyPrefix, environment, rawKey }
```

**WebhookSecretDto** (webhook secret reveal):
```
{ webhookSecret }
```

**TenantStatus enum:** `ACTIVE`, `SUSPENDED`
**ApiKeyStatus enum:** `ACTIVE`, `ROTATED`, `REVOKED`
**ApiKeyEnvironment enum:** `PROD`, `DEV`, `SANDBOX`

---

## Common Pitfalls

### Pitfall 1: Spring Page 0-Indexing vs. Quasar 1-Indexing
**What goes wrong:** Quasar q-table `@request` handler passes `pagination.page` starting at 1. Passing this directly to the API results in the second page being returned for the first page.
**Why it happens:** Spring Data uses 0-based page numbers; Quasar uses 1-based.
**How to avoid:** Always pass `p.page - 1` to the API call in the `@request` handler.
**Warning signs:** First page loads correctly, second page shows the wrong data.

### Pitfall 2: reactivateKey Returns 204 — No Modal Trigger
**What goes wrong:** Calling `reactivateKey` (key-level endpoint) and expecting a rawKey to show in the one-time modal.
**Why it happens:** Key-level reactivate (`POST /{tenantId}/keys/{keyId}/reactivate`) returns 204 No Content, not an ApiKeyDto. Only tenant-level reactivate returns a rawKey.
**How to avoid:** Do not trigger OneTimeKeyModal after key-level reactivate. Per D-04, "Reactivate" key action reactivates a revoked key (returns it to ACTIVE) — this does not issue a new key and has no rawKey.
**Warning signs:** Attempting to access `resp.data.rawKey` when data is empty/null.

### Pitfall 3: Timer Leak on Navigation
**What goes wrong:** User navigates away from TenantDetailPage while webhook secret is revealed. The 30s timer fires, attempts to write `secret.value = null` on an unmounted component.
**Why it happens:** setTimeout/setInterval continue running after component unmount.
**How to avoid:** Call `clearTimers()` in `onUnmounted()`.
**Warning signs:** Vue warnings about setting reactive state on an unmounted instance.

### Pitfall 4: rawKey Persisting in Component State
**What goes wrong:** rawKey is set on the parent (TenantDetailPage) and the modal `close` event is not handled — rawKey remains in state after dismissal.
**Why it happens:** Missing handler for the modal `close` event.
**How to avoid:** Parent must handle `@close` on OneTimeKeyModal and set `rawKey = null` immediately. Per D-11.
**Warning signs:** Re-opening the modal (e.g., for a second rotate) shows the previous key.

### Pitfall 5: q-table @row-click Event Signature
**What goes wrong:** Using `(row) => ...` instead of `(evt, row) => ...` for the row-click handler.
**Why it happens:** Quasar q-table `@row-click` passes `(evt, row, index)` — the first argument is the native click event, not the row.
**How to avoid:** Use `function onRowClick(evt, row)` — match the pattern in TransactionSearchPage.vue (`function onRowClick(evt, row)`).
**Warning signs:** `row` is a MouseEvent object instead of a data row.

### Pitfall 6: Key Table "Generate" Button — Missing Backend Endpoint
**What goes wrong:** Building a "Generate" button that assumes a standalone key generation endpoint exists for existing tenants.
**Why it happens:** D-04 specifies "Generate" for envs with no active key, but there is no `POST /{tenantRef}/keys` endpoint in the current backend. The existing create-tenant endpoint creates a tenant + initial key atomically.
**How to avoid:** Clarify with planner whether "Generate" is deferred, implemented via another path, or requires a new backend endpoint. Do not silently omit it or wire it to the wrong endpoint.
**Warning signs:** 404 errors when the "Generate" button is clicked.

---

## Code Examples

### adminApi Tenant Methods to Add

```javascript
// Source: TenantAdminResource.java — all paths and response shapes verified

// TENT-05
listTenants(params = {}) {
  // params: { status?, page, size }
  return api.get('/v1/admin/tenants', { params })
},

// TENT-06
getTenantDetail(tenantRef) {
  return api.get(`/v1/admin/tenants/${tenantRef}`)
},

// WSEC-03
getWebhookSecret(tenantRef) {
  return api.get(`/v1/admin/tenants/${tenantRef}/webhook-secret`)
},

// TENT-10
updateTenantName(tenantRef, data) {
  return api.patch(`/v1/admin/tenants/${tenantRef}/name`, data)
},

// TENT-02
updateTenantEmail(tenantRef, data) {
  return api.patch(`/v1/admin/tenants/${tenantRef}/email`, data)
},

// TENT-03
updateTenantWebhookUrl(tenantRef, data) {
  return api.patch(`/v1/admin/tenants/${tenantRef}/webhook-url`, data)
},

// TENT-04
suspendTenant(tenantRef) {
  return api.post(`/v1/admin/tenants/${tenantRef}/suspend`)
},

// TENT-07 — returns ApiKeyDto with rawKey
reactivateTenant(tenantRef) {
  return api.post(`/v1/admin/tenants/${tenantRef}/reactivate`)
},

// Key management — uses tenantId (Long), not tenantRef (String)
rotateKey(tenantId, keyId) {
  return api.post(`/v1/admin/tenants/${tenantId}/keys/${keyId}/rotate`)
},

revokeKey(tenantId, keyId) {
  return api.delete(`/v1/admin/tenants/${tenantId}/keys/${keyId}`)
},

// NOTIF-04 — returns 204 No Content (no rawKey)
reactivateKey(tenantId, keyId) {
  return api.post(`/v1/admin/tenants/${tenantId}/keys/${keyId}/reactivate`)
},
```

### Routes to Add in routes.js

```javascript
// Source: routes.js — add inside the admin children array
{
  path: 'tenants',
  component: () => import('pages/admin/TenantListPage.vue'),
  meta: { requiresAuth: true },
},
{
  path: 'tenants/:tenantRef',
  component: () => import('pages/admin/TenantDetailPage.vue'),
  meta: { requiresAuth: true },
},
```

### Nav Item to Add in MainLayout.vue

```vue
<!-- Source: MainLayout.vue — insert after Transactions q-item, before Reconciliation q-item -->
<q-item clickable to="/admin/tenants">
  <q-item-section avatar>
    <q-icon name="group" />
  </q-item-section>
  <q-item-section>
    <q-item-label>Tenants</q-item-label>
  </q-item-section>
</q-item>
```

### Status Chip Pattern

```vue
<!-- q-chip for tenant status — used in both list and detail pages -->
<q-chip
  :color="tenant.tenantStatus === 'ACTIVE' ? 'positive' : tenant.tenantStatus === 'SUSPENDED' ? 'negative' : 'grey'"
  text-color="white"
  dense
>
  {{ tenant.tenantStatus }}
</q-chip>
```

### Key Status Chip Pattern

```vue
<!-- q-chip for API key status in key table -->
<q-chip
  :color="row.keyStatus === 'ACTIVE' ? 'positive' : row.keyStatus === 'REVOKED' ? 'negative' : row.keyStatus === 'ROTATED' ? 'warning' : 'grey'"
  text-color="white"
  dense
>
  {{ row.keyStatus }}
</q-chip>
```

---

## Runtime State Inventory

Not applicable — greenfield UI phase. No rename/refactor/migration involved.

---

## Environment Availability

Step 2.6: SKIPPED — this phase adds frontend Vue/Quasar files. No new external dependencies beyond the already-running Quasar dev server and Spring Boot backend (both established in prior phases).

---

## Validation Architecture

`workflow.nyquist_validation` key is absent from `.planning/config.json` — treat as enabled.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | No automated frontend test framework detected in project |
| Config file | none |
| Quick run command | Manual browser verification |
| Full suite command | Manual browser verification |

Scanning the project reveals no `jest.config.*`, `vitest.config.*`, `*.test.vue`, or `*.spec.js` files in the frontend. The existing test suite is entirely Java/JUnit (backend). Frontend verification is manual.

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| UI-01 | Tenant list loads with status filter and pagination | manual | — | N/A |
| UI-01 | Row click navigates to detail page | manual | — | N/A |
| UI-02 | Inline field save sends PATCH and shows toast | manual | — | N/A |
| UI-02 | Suspend shows confirm dialog, then calls API | manual | — | N/A |
| UI-02 | Reactivate shows confirm dialog, then opens key modal | manual | — | N/A |
| UI-03 | Key modal cannot be dismissed without checking checkbox | manual | — | N/A |
| UI-03 | rawKey is null in parent state after modal close | manual | — | N/A |
| UI-04 | Secret fetched lazily on first eye-click | manual | — | N/A |
| UI-04 | Secret auto-masks after 30s | manual | — | N/A |
| UI-04 | Second eye-click while revealed immediately re-masks | manual | — | N/A |

### Sampling Rate

- **Per task commit:** Build check (`quasar build` or dev server hot-reload)
- **Per wave merge:** Manual smoke test of all new pages in the running dev server
- **Phase gate:** All four success criteria verified manually before `/gsd:verify-work`

### Wave 0 Gaps

No test framework setup required — project has no frontend automated test infrastructure. Validation is manual.

---

## Open Questions

1. **"Generate" key button — no standalone backend endpoint**
   - What we know: D-04 specifies a "Generate" button for envs with no active key in the key table. The backend `TenantAdminResource` has no `POST /{tenantRef}/keys` or `POST /{tenantRef}/keys/generate` endpoint. The only endpoint that creates keys is `POST /` (create tenant, atomically).
   - What's unclear: Is "Generate" intended to call rotate on a non-existent key (impossible), or is this button deferred, or does it require a new backend endpoint outside Phase 33's scope?
   - Recommendation: Planner must decide: (a) omit "Generate" from the key table if no backend endpoint exists, or (b) add this to the Phase 33 scope with a minimal new backend endpoint. Given phase boundary says "No new backend work", option (a) is likely correct — the "Generate" button may never appear in practice because tenant creation always seeds a PROD key.

2. **TenantSummaryDto missing `createdDate` and `email`**
   - What we know: `TenantSummaryDto` has only `{ id, tenantRef, name, tenantStatus }`. The UI-SPEC lists "Email" and "Created" as columns on the list page, but these fields are not in the DTO.
   - What's unclear: Were these columns specified speculatively, or should the list query return a richer DTO?
   - Recommendation: Planner should either (a) remove Email and Created columns from the list page (use only the 4 fields in TenantSummaryDto), or (b) if email is important, note it as a gap needing a backend DTO change. Given phase boundary "No new backend work", option (a) is recommended — show Tenant Name, Ref, and Status only.

3. **ApiKeySummaryDto missing `createdDate`**
   - What we know: `ApiKeySummaryDto` has `{ id, keyPrefix, environment, keyStatus }`. UI-SPEC key table has "Created Date" column.
   - Recommendation: Same as above — either omit the Created Date column or accept it as a future enhancement. Planner should decide.

---

## Sources

### Primary (HIGH confidence)

- Source read directly: `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java` — all endpoint paths, HTTP methods, request/response shapes, and path variable types
- Source read directly: `src/main/java/com/softropic/payam/tenant/contract/*.java` — all DTO field names and types
- Source read directly: `src/frontend/src/pages/admin/TransactionSearchPage.vue` — q-table + filter card + row-click pattern
- Source read directly: `src/frontend/src/pages/admin/PlatformConfigPage.vue` — always-editable inputs + per-field saving pattern
- Source read directly: `src/frontend/src/api/admin.api.js` — existing API module structure to extend
- Source read directly: `src/frontend/src/router/routes.js` — route structure to extend
- Source read directly: `src/frontend/src/layouts/MainLayout.vue` — nav structure to extend
- Source read directly: `.planning/phases/33-admin-ui-tenant-management/33-CONTEXT.md` — all locked decisions
- Source read directly: `.planning/phases/33-admin-ui-tenant-management/33-UI-SPEC.md` — visual and interaction contract

### Secondary (MEDIUM confidence)

- Quasar v2 q-table server-side pagination: `@request` handler and `:pagination` object shape inferred from ReconciliationPage.vue usage pattern + Quasar docs knowledge
- `$q.dialog()` confirm pattern: described in CONTEXT.md as "consistent with existing admin patterns" but no existing usage found in codebase — pattern documented from Quasar docs knowledge

### Tertiary (LOW confidence)

None.

---

## Metadata

**Confidence breakdown:**
- API surface: HIGH — read directly from Java source files
- DTO shapes: HIGH — read directly from Java record definitions
- Frontend patterns: HIGH — read directly from existing admin page source files
- q-table server-side pagination specifics: MEDIUM — no existing server-side pagination example in codebase (existing pages use static pagination or client-side); inferred from Quasar docs
- $q.dialog programmatic confirm: MEDIUM — not yet used in codebase; documented from Quasar docs knowledge

**Research date:** 2026-04-08
**Valid until:** 2026-05-08 (stable stack — Quasar v2, backend API frozen)
