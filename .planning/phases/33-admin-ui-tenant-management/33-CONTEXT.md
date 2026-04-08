# Phase 33: Admin UI — Tenant Management - Context

**Gathered:** 2026-04-08
**Status:** Ready for planning

<domain>
## Phase Boundary

Build the Admin SPA tenant management screens on top of the Phase 31 REST API. Scope: tenant list page (UI-01), tenant detail/edit page (UI-02), one-time key display modal (UI-03), and webhook secret reveal (UI-04). No new backend work — all four requirements are frontend-only.

</domain>

<decisions>
## Implementation Decisions

### Per-field Edit UX (UI-02)

- **D-01:** Use the `PlatformConfigPage` pattern — always-editable `q-input` fields, each with its own Save button. No "edit mode" toggle; no pencil-icon-per-field pattern. Per-field save means per-field Save button, not a confirmation dialog before each PATCH.
- **D-02:** Save button is `:loading` during the PATCH call and shows a `$q.notify` success/failure toast on completion — consistent with the rest of the admin pages.

### Key Management Section (UI-02 / UI-03)

- **D-03:** Display keys in a `q-table` on the detail page, columns: Env, Key Prefix, Status (chip), Created Date, Actions. Rows are grouped/sorted by env (PROD first).
- **D-04:** Action buttons in each row are context-sensitive by key state:
  - No active key for env → "Generate" button
  - Active key → "Rotate" | "Revoke" buttons
  - Revoked key → "Reactivate" button
  - Rotated key (grace period) → no actions (read-only row)
- **D-05:** Generate, Rotate, and Reactivate all return a `rawKey` — all three trigger the UI-03 one-time key modal after the API call completes. The modal is a single reusable component.

### Status Toggle Flow (UI-02)

- **D-06:** Suspend and Reactivate are triggered by a single status toggle button on the detail page header (label changes based on current status: "Suspend" when ACTIVE, "Reactivate" when SUSPENDED).
- **D-07:** Confirmation uses `$q.dialog()` programmatic confirm — consistent with existing admin patterns. Copy: "Suspend [tenant name]? All API keys will be revoked." / "Reactivate [tenant name]? A new PROD key will be generated."
- **D-08:** On reactivate confirmation: call API → on success, immediately open the UI-03 one-time key modal with the returned `rawKey`. Two distinct steps (confirm dialog → key modal), not merged into one.
- **D-09:** On suspend confirmation: call API → `$q.notify` success toast. No key modal (keys are revoked, not generated).

### One-time Key Modal (UI-03)

- **D-10:** Persistent `q-dialog` (cannot be dismissed by clicking outside or pressing Escape). Shows the raw key in a monospace `q-input readonly` with a "Copy" button.
- **D-11:** Dismiss is gated: a checkbox "I have copied the key" must be checked before the "Done" button enables. On "Done", clear `rawKey` from component state immediately (set to `null`).
- **D-12:** The modal is a single shared component (`OneTimeKeyModal.vue`) used from all three trigger points (generate, rotate, reactivate). It receives `rawKey` as a prop; parent manages visibility.

### Webhook Secret Reveal (UI-04)

- **D-13:** Secret displayed in a `q-input` with `type="password"` and an eye-icon append slot. Initial state: masked, no secret in component state.
- **D-14:** On eye-icon click: lazy-fetch `GET /v1/admin/tenants/{tenantRef}/webhook-secret`, display plaintext, start a 30-second countdown timer, auto-re-mask (clear secret from state) on timeout.
- **D-15:** If eye icon is clicked again while revealed, immediately re-mask and cancel the timer (toggle behavior). A second click re-fetches and restarts the 30s timer.

### API Client Organization

- **D-16:** Add all tenant endpoints to the existing `adminApi` in `src/api/admin.api.js` — consistent with how all other admin API calls are organized. No new file.

### Routing

- **D-17:** Two new routes under `/admin`:
  - `/admin/tenants` → `TenantListPage.vue`
  - `/admin/tenants/:tenantRef` → `TenantDetailPage.vue`
- **D-18:** Add "Tenants" nav item to `MainLayout.vue` sidebar under the Admin section, between Transactions and Reconciliation. Icon: `group`.

### Claude's Discretion

- Component file placement: new pages in `src/pages/admin/`, new shared modal in `src/components/admin/`
- Error handling: `$q.notify({ type: 'negative', message: ... })` pattern — consistent with existing admin pages
- Status chip colors: ACTIVE → `positive`, SUSPENDED → `negative`, other → `grey`
- Key status chip colors: ACTIVE → `positive`, REVOKED → `negative`, ROTATED → `warning`
- Pagination: use server-side pagination on the tenant list q-table (`:pagination` + `@request` handler), consistent with the paginated API (`page`/`size` params, `content`/`totalElements` response shape from Phase 31)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### REST API surface (Phase 31 — what we're calling)
- `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java` — All endpoint paths, request/response shapes; mandatory read before writing `adminApi` calls
- `src/main/java/com/softropic/payam/tenant/contract/dto/TenantSummaryDto.java` — Fields for tenant list page
- `src/main/java/com/softropic/payam/tenant/contract/dto/TenantDetailDto.java` — Fields for tenant detail page
- `src/main/java/com/softropic/payam/tenant/contract/dto/ApiKeyDto.java` — Fields for key table rows

### Existing page patterns (follow exactly)
- `src/frontend/src/pages/admin/TransactionSearchPage.vue` — Reference for list page: q-table + filter card + row-click navigation pattern
- `src/frontend/src/pages/admin/PlatformConfigPage.vue` — Reference for inline edit: always-editable q-input + per-field Save button pattern
- `src/frontend/src/api/admin.api.js` — Extend this file; do not create a new API module
- `src/frontend/src/router/routes.js` — Add tenant routes here

### Existing layout/nav (must update)
- `src/frontend/src/layouts/MainLayout.vue` — Add Tenants nav item in Admin section

### Requirements
- `.planning/REQUIREMENTS.md` §UI — UI-01, UI-02, UI-03, UI-04 acceptance criteria

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `q-table` with `:loading`, `@row-click`, and `row-key` — used in `TransactionSearchPage`, reuse exactly for tenant list
- `$q.notify({ type: 'negative'|'positive', message })` — error/success toast pattern used throughout all admin pages
- `$q.dialog({ title, message, cancel: true })` — programmatic confirm dialog; use for suspend/reactivate confirmation
- `q-inner-loading :showing` — loading overlay used in PlatformConfigPage; reuse for detail page load state
- `useQuasar()`, `useRouter()`, `ref`/`reactive` — standard imports across all admin pages

### Established Patterns
- `<script setup>` Composition API with no TypeScript — all admin pages follow this
- Local page state only (`ref`, `reactive`) — no Pinia stores
- `adminApi.X()` calls inside `async function` with try/catch and `$q.notify` on error
- `:loading="saving"` on Save buttons with `loading` ref toggled around API calls
- All admin routes use `meta: { requiresAuth: true }` — must be added to tenant routes

### Integration Points
- `src/frontend/src/api/admin.api.js` — add tenant CRUD + key management + webhook-secret methods
- `src/frontend/src/router/routes.js` — add two new routes under `path: 'admin'` children
- `src/frontend/src/layouts/MainLayout.vue` — add Tenants q-item to drawer

</code_context>

<specifics>
## Specific Ideas

- No specific requirements beyond what the ROADMAP/REQUIREMENTS define — open to standard Quasar patterns for any visual details not covered above.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 33-admin-ui-tenant-management*
*Context gathered: 2026-04-08*
