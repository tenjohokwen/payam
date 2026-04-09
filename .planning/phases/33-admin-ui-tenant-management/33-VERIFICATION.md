---
phase: 33-admin-ui-tenant-management
verified: 2026-04-09T00:00:00Z
status: human_needed
score: 11/11 must-haves verified
human_verification:
  - test: "Navigate to /admin/tenants — verify Tenants sidebar item appears between Transactions and Reconciliation, page loads with paginated table and status filter, status chips are color-coded"
    expected: "Sidebar shows group icon, table renders Name/Ref/Email/Status/Created columns, status filter works, empty state shows 'No tenants found'"
    why_human: "Visual layout, chip colors, and filter behavior require browser interaction"
  - test: "Click a tenant row in the list page"
    expected: "Browser navigates to /admin/tenants/:tenantRef and detail page loads with inline edit fields, API key table, and webhook secret section"
    why_human: "Row-click navigation and detail page rendering require browser interaction"
  - test: "Edit Name/Email/Webhook URL fields individually and click each Update button"
    expected: "Loading spinner appears on the clicked button, success toast shown, field retains updated value"
    why_human: "Per-field save UX (loading state, toast timing) requires browser interaction"
  - test: "Click Suspend on an ACTIVE tenant, confirm, then Reactivate"
    expected: "Suspend: confirmation dialog with correct message, success toast, keys show REVOKED. Reactivate: OneTimeKeyModal opens immediately with raw key"
    why_human: "Dialog flow, status changes, and modal trigger require browser interaction"
  - test: "In OneTimeKeyModal: try to dismiss without checking checkbox; then check checkbox and click Done"
    expected: "Done button is disabled until checkbox checked; clicking outside or Escape does nothing (persistent); Done closes modal"
    why_human: "Modal persistence and checkbox gate require browser interaction"
  - test: "On detail page with an ACTIVE key: click Rotate"
    expected: "OneTimeKeyModal opens with new raw key; key table reloads showing old key as ROTATED and new key as ACTIVE"
    why_human: "Key rotation flow and modal trigger require browser interaction"
  - test: "Click the eye icon in Webhook Secret section"
    expected: "Secret appears in monospace field, countdown text shows 'Auto-hides in Xs'; second click immediately re-masks; waiting 30s auto-masks"
    why_human: "Timer behavior and visual secret display require browser interaction"
---

# Phase 33: Admin UI Tenant Management Verification Report

**Phase Goal:** Build the admin UI tenant management pages (list and detail), including API key lifecycle management, using the Quasar/Vue.js frontend stack.
**Verified:** 2026-04-09
**Status:** human_needed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

All must-haves from Plans 01–04 are assessed below.

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Admin can generate an API key for an env with no active key and receives the raw key exactly once | VERIFIED | `TenantAdminResource.java:133` POST `/{tenantRef}/keys/generate` calls `apiKeyService.generateAndStore`, returns `ApiKeyDto` with `rawKey` |
| 2 | Tenant list API returns each tenant's email address and creation timestamp | VERIFIED | `TenantSummaryDto` record has `String email, Instant createdAt`; `TenantQueryService.findAll` passes `t.getEmail(), t.getCreatedDate()` |
| 3 | Tenant detail API returns each key's creation timestamp | VERIFIED | `ApiKeySummaryDto` record has `Instant createdAt`; `TenantQueryService.findByTenantRef` passes `k.getCreatedDate()` |
| 4 | Admin can navigate to /admin/tenants and /admin/tenants/:tenantRef via the browser | VERIFIED | `routes.js:94-102` — both paths registered with `requiresAuth: true`, lazy-importing correct page components |
| 5 | Tenants nav item appears in the sidebar between Transactions and Reconciliation | VERIFIED | `MainLayout.vue:124-131` — `to="/admin/tenants"`, icon `group`, positioned after Transactions (line 115) and before Reconciliation (line 133) |
| 6 | OneTimeKeyModal cannot be dismissed without checking the confirmation checkbox | VERIFIED | `OneTimeKeyModal.vue:2` — `persistent` attribute on `q-dialog`; Done button has `:disable="!copied"` |
| 7 | Admin can view a paginated list of tenants with Name, Ref, Email, Status, and Created columns | VERIFIED | `TenantListPage.vue:142-148` — 5-column definition; `@request="onRequest"` wired; `adminApi.listTenants` called in `onRequest` |
| 8 | Admin can filter the tenant list by status (ALL, ACTIVE, SUSPENDED) | VERIFIED | `TenantListPage.vue:130-136` — statusOptions array; `onRequest` passes `status: statusFilter.value === 'ALL' ? undefined : statusFilter.value` |
| 9 | Admin can click a tenant row to navigate to that tenant's detail page | VERIFIED | `TenantListPage.vue:171-173` — `onRowClick(evt, row)` calls `router.push('/admin/tenants/' + row.tenantRef)` |
| 10 | Admin can edit a tenant's name/email/webhook URL with per-field save and success toast | VERIFIED | `TenantDetailPage.vue:255-291` — three save functions each call corresponding `adminApi` method and `$q.notify` on success; `saving` reactive object gates button loading state |
| 11 | Admin can reveal the webhook secret via eye icon; it auto-masks after 30 seconds | VERIFIED | `TenantDetailPage.vue:407-420` — `toggleSecret` calls `adminApi.getWebhookSecret`, sets `unmasked = true`, starts 30s `maskTimer`; `onUnmounted` calls `clearTimers` |

**Score:** 11/11 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/softropic/payam/tenant/contract/TenantSummaryDto.java` | 6-field record with email and createdAt | VERIFIED | `record TenantSummaryDto(Long id, String tenantRef, String name, TenantStatus tenantStatus, String email, Instant createdAt)` |
| `src/main/java/com/softropic/payam/tenant/contract/ApiKeySummaryDto.java` | 5-field record with createdAt | VERIFIED | `record ApiKeySummaryDto(Long id, String keyPrefix, ApiKeyEnvironment environment, ApiKeyStatus keyStatus, Instant createdAt)` |
| `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java` | POST generate endpoint | VERIFIED | `@PostMapping("/{tenantRef}/keys/generate")` at line 133; `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`; `TenantRepository` injected |
| `src/frontend/src/api/admin.api.js` | All tenant API client methods (13+) | VERIFIED | 13 tenant methods present: `listTenants`, `getTenantDetail`, `getWebhookSecret`, `updateTenantName`, `updateTenantEmail`, `updateTenantWebhookUrl`, `suspendTenant`, `reactivateTenant`, `regenerateWebhookSecret`, `generateKey`, `rotateKey`, `revokeKey`, `reactivateKey`; plus `createTenant` (bonus) |
| `src/frontend/src/router/routes.js` | Tenant routes registered | VERIFIED | `path: 'tenants'` → `TenantListPage.vue`; `path: 'tenants/:tenantRef'` → `TenantDetailPage.vue`; both with `requiresAuth: true` |
| `src/frontend/src/layouts/MainLayout.vue` | Tenants nav item with group icon | VERIFIED | `q-item clickable to="/admin/tenants"` with `q-icon name="group"` and label "Tenants"; positioned Transactions → Tenants → Reconciliation |
| `src/frontend/src/components/admin/OneTimeKeyModal.vue` | Persistent modal with copy-confirm gate | VERIFIED | 52 lines; `persistent` on `q-dialog`; `:disable="!copied"`; `emit('close')`; `watch` resets `copied` on reopen; `navigator.clipboard.writeText` |
| `src/frontend/src/pages/admin/TenantListPage.vue` | Paginated q-table with status filter | VERIFIED | 241 lines (above 80 min); `@request="onRequest"`; `adminApi.listTenants` called; `p.page - 1` offset correction; `onRowClick` navigates |
| `src/frontend/src/pages/admin/TenantDetailPage.vue` | Full detail page with inline edit, key table, webhook secret | VERIFIED | 436 lines (above 200 min); `adminApi.getTenantDetail` called; all key action functions present; `OneTimeKeyModal` wired; timer cleanup on unmount |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `TenantAdminResource.java` | `ApiKeyService.generateAndStore` | method call in generateKey | VERIFIED | Line 139: `apiKeyService.generateAndStore(tenant, env)` |
| `TenantQueryService.java` | `TenantSummaryDto` | mapper in findAll | VERIFIED | Line 40: `new TenantSummaryDto(t.getId(), t.getTenantRef(), t.getName(), t.getTenantStatus(), t.getEmail(), t.getCreatedDate())` |
| `admin.api.js` | `/v1/admin/tenants` | axios GET/POST calls | VERIFIED | `listTenants` uses `api.get('/v1/admin/tenants')`, other methods use matching paths |
| `routes.js` | `TenantListPage.vue` | lazy import | VERIFIED | `() => import('pages/admin/TenantListPage.vue')` at path `tenants` |
| `TenantListPage.vue` | `adminApi.listTenants` | onRequest handler | VERIFIED | `adminApi.listTenants({ status, page: p.page - 1, size: p.rowsPerPage })` in `onRequest` |
| `TenantListPage.vue` | `/admin/tenants/:tenantRef` | router.push on row click | VERIFIED | `router.push('/admin/tenants/' + row.tenantRef)` in `onRowClick` |
| `TenantDetailPage.vue` | `adminApi.updateTenantName` | saveName function | VERIFIED | `adminApi.updateTenantName(tenantRef, { name: form.name })` at line 258 |
| `TenantDetailPage.vue` | `adminApi.suspendTenant` | confirmSuspend → doSuspend | VERIFIED | `adminApi.suspendTenant(tenantRef)` at line 305 |
| `TenantDetailPage.vue` | `adminApi.reactivateTenant` | confirmReactivate → doReactivate | VERIFIED | `adminApi.reactivateTenant(tenantRef)` at line 326 |
| `TenantDetailPage.vue` | `OneTimeKeyModal.vue` | v-model showKeyModal + rawKey prop | VERIFIED | `<OneTimeKeyModal v-model="showKeyModal" :raw-key="rawKey \|\| ''" @close="onKeyModalClose" />` at line 173 |
| `TenantDetailPage.vue` | `adminApi.getWebhookSecret` | toggleSecret function | VERIFIED | `adminApi.getWebhookSecret(tenantRef)` at line 413 |
| `OneTimeKeyModal.vue` | parent component | emit close event | VERIFIED | `emit('close')` in `dismiss()` at line 49 |

---

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| `TenantListPage.vue` | `rows` | `adminApi.listTenants` → `resp.content` | Yes — Spring `Page<TenantSummaryDto>` serializes with `content` array; DB-backed via `tenantRepository.findAll` / `findByTenantStatus` | FLOWING |
| `TenantListPage.vue` | `pagination.rowsNumber` | `resp.totalElements` | Yes — Spring Page includes `totalElements` | FLOWING |
| `TenantDetailPage.vue` | `tenant`, `form`, `keyRows` | `adminApi.getTenantDetail` → `resp` (axios interceptor unwraps `response.data`) | Yes — `TenantQueryService.findByTenantRef` queries DB for tenant and keys | FLOWING |
| `TenantDetailPage.vue` | `rawKey` | `adminApi.reactivateTenant / generateKey / rotateKey` → `resp.rawKey` | Yes — interceptor unwraps `response.data`; `ApiKeyDto.rawKey` populated from `generateAndStore` / `rotate` | FLOWING |
| `TenantDetailPage.vue` | `secret` | `adminApi.getWebhookSecret` → `resp.webhookSecret` | Yes — interceptor unwraps `response.data`; `WebhookSecretDto.webhookSecret` from DB entity | FLOWING |

**Note on axios response unwrapping:** The axios interceptor in `src/frontend/src/boot/axios.js` (line 114) returns `response.data` directly instead of the full axios response. Both `TenantListPage` (using `resp.content`, `resp.totalElements`) and `TenantDetailPage` (using `resp.name`, `resp.keys`, `resp.rawKey`, `resp.webhookSecret`) correctly address the unwrapped data — this is consistent with the existing codebase pattern. The PLAN's example code used `resp.data.*` notation as pseudocode, not the actual accessor pattern.

---

### Behavioral Spot-Checks

Step 7b: SKIPPED — verifying frontend Vue pages requires a running dev server and browser. Backend API correctness is verified through code inspection and confirmed by the integration test suite referenced in SUMMARY files.

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| UI-01 | Plans 01, 03 | Admin can view a tenant list page with paginated q-table, status filter, and row-click navigation to tenant detail | SATISFIED | `TenantListPage.vue` implements all; API backend provides `TenantSummaryDto` with email/createdAt |
| UI-02 | Plans 01, 04 | Admin can view and edit tenant detail (name, email, webhookUrl) with inline save; can toggle status with confirmation | SATISFIED | `TenantDetailPage.vue`: `saveName`, `saveEmail`, `saveWebhookUrl`; `confirmSuspend`, `confirmReactivate` with `$q.dialog` |
| UI-03 | Plans 01, 02, 04 | Admin sees a one-time API key display modal (persistent QDialog, copy-confirm gate, rawKey cleared on dismissal) after key generation or rotation | SATISFIED | `OneTimeKeyModal.vue`: persistent, `:disable="!copied"`, emits `close`; detail page clears `rawKey.value = null` in `onKeyModalClose` |
| UI-04 | Plan 04 | Admin can reveal and re-mask a tenant's webhook secret via eye icon (lazy-fetch; auto-re-masks after 30s) | SATISFIED | `TenantDetailPage.vue`: `toggleSecret` lazy-fetches only on first click; 30s `maskTimer` + `countdownInterval`; second click calls `reMask` immediately |

No orphaned requirements found — all four requirement IDs declared in plan frontmatter are covered by verified implementations.

---

### Anti-Patterns Found

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| `TenantListPage.vue` | Empty state text deviates from UI-SPEC wording: "create a new tenant" vs specified "add a tenant via the API" | Info | Non-functional; the Create Tenant dialog is present so the alternative wording is contextually appropriate |
| `TenantDetailPage.vue` | `useRouter` not imported — `router` not used in this component (navigates via `onKeyModalClose` redirecting after create, which is in TenantListPage) | Info | Not a bug; detail page stays on same route after actions |

No blocker or warning anti-patterns found. No TODO/FIXME/placeholder comments. No empty implementations. No hardcoded stub returns.

---

### Human Verification Required

The automated checks for all 11 truths pass. The following items require browser testing to confirm the complete user experience:

#### 1. Tenant List — Visual Rendering and Filtering

**Test:** Start `cd src/frontend && npx quasar dev`, log in as admin, click "Tenants" in sidebar.
**Expected:** Tenants item appears with group icon between Transactions and Reconciliation; page shows paginated table with Name/Ref/Email/Status/Created columns; status chips color-coded (green for ACTIVE, red for SUSPENDED); status filter dropdown works; clicking Search reloads.
**Why human:** Visual layout, chip colors, and actual API data display require browser.

#### 2. Row Click Navigation

**Test:** Click any tenant row.
**Expected:** Browser navigates to `/admin/tenants/:tenantRef`; detail page loads with the tenant's data populated in all three edit fields and the key table.
**Why human:** Client-side routing and actual data rendering require browser.

#### 3. Per-Field Save with Loading State

**Test:** On detail page, modify Name field, click "Update Name".
**Expected:** Button shows loading spinner during request; success toast appears; no page reload.
**Why human:** Loading state visibility and toast timing require browser.

#### 4. Status Toggle Flow

**Test:** On an ACTIVE tenant, click "Suspend"; confirm in dialog; then click "Reactivate".
**Expected:** Suspend dialog shows correct message about key revocation; after confirm, status changes, keys show REVOKED. Reactivate dialog appears; after confirm, OneTimeKeyModal opens with a raw key visible.
**Why human:** Dialog interaction, status badge update, and modal trigger require browser.

#### 5. OneTimeKeyModal Gate

**Test:** When modal is open, try clicking outside, pressing Escape, and clicking Done without checking checkbox.
**Expected:** None of these dismisses the modal; Done button is disabled until checkbox checked; after checking, Done closes modal.
**Why human:** Modal persistence enforcement and button state require browser.

#### 6. Key Table Actions — Rotate/Revoke/Reactivate/Generate

**Test:** On a tenant with an ACTIVE key, click Rotate; on a REVOKED key, click Reactivate; on an env with no active key, click the Generate button.
**Expected:** Rotate and Generate open OneTimeKeyModal; Revoke and key-level Reactivate show success toast only (no modal); key table updates after each action.
**Why human:** Key lifecycle actions and conditional modal display require browser.

#### 7. Webhook Secret Reveal and Auto-Mask

**Test:** Click eye icon; click again while revealed; click again and wait 30 seconds.
**Expected:** First click reveals secret in monospace field with countdown; second click immediately re-masks; 30s timer auto-masks.
**Why human:** Timer behavior and visual masking require browser.

---

### Gaps Summary

No gaps found. All required artifacts exist, are substantive, and are correctly wired with real data flowing through all paths. The phase goal is achieved at the code level.

The human verification items above are the standard browser-testing requirements for any frontend phase — they are not gaps in the implementation, but confirmation tests that cannot be automated programmatically.

---

_Verified: 2026-04-09_
_Verifier: Claude (gsd-verifier)_
