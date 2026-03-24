---
phase: 08-admin-dashboard
plan: 03
subsystem: admin-spa
tags: [vue, quasar, pinia, sse, admin-ui, spa]

dependency-graph:
  requires:
    - "08-01: GET /v1/admin/transactions and GET /v1/admin/transactions/{id}/events"
    - "08-02: GET /v1/admin/metrics/stream SSE endpoint (parallel plan)"
    - "01-02: Router index.js cookie-based requiresAuth guard"
  provides:
    - "AdminDashboardPage.vue: live SSE metrics dashboard with per-provider latency cards"
    - "TransactionSearchPage.vue: paginated transaction search with QTable"
    - "TransactionDetailPage.vue: full event timeline with QTimeline"
    - "admin.api.js: searchTransactions() and getTransactionDetail() axios wrappers"
    - "admin-metrics.store.js: Pinia store with providerLatencyMs reactive state"
    - "Admin route group in routes.js: 3 child routes all with requiresAuth"
    - "MainLayout.vue drawer: Admin Dashboard and Transactions nav items"
    - "quasar.config.js: /v1/admin devServer proxy"
  affects:
    - "Phase 9/10: Future admin pages can follow the same pattern (pages/admin/, admin.api.js)"

tech-stack:
  added: []
  patterns:
    - "Pinia composition API store (defineStore with setup function)"
    - "SSE via browser-native EventSource with withCredentials:true, closed in onUnmounted"
    - "adminApi plain object (no default export) mirroring auth.api.js pattern"
    - "v-for over object to render dynamic provider latency cards (no hardcoded provider names)"
    - "Spring Data Page structure: response.data.content for paginated results"

key-files:
  created:
    - src/frontend/src/api/admin.api.js
    - src/frontend/src/stores/admin-metrics.store.js
    - src/frontend/src/pages/admin/AdminDashboardPage.vue
    - src/frontend/src/pages/admin/TransactionSearchPage.vue
    - src/frontend/src/pages/admin/TransactionDetailPage.vue
  modified:
    - src/frontend/src/router/routes.js
    - src/frontend/src/layouts/MainLayout.vue
    - src/frontend/quasar.config.js

decisions:
  - id: "08-03-A"
    decision: "No requiresAdmin guard added — backend returns 403/401 for non-ROLE_ADMIN"
    rationale: "Plan specified this explicitly: backend enforcement is sufficient for this phase; nav items visible to all authenticated users"
  - id: "08-03-B"
    decision: "v-for over store.providerLatencyMs object renders provider cards dynamically"
    rationale: "Backend may expose ORANGE, MTN, or future providers; hardcoding would require UI changes per provider addition"
  - id: "08-03-C"
    decision: "HTML entity &rarr; used in TransactionDetailPage instead of → arrow character"
    rationale: "Avoids Vue template encoding issues with raw arrow characters inside JSX-like attribute expressions"
  - id: "08-03-D"
    decision: "/v1/payments proxy rule added alongside /v1/admin in quasar.config.js"
    rationale: "Plan specifies both rules for completeness; /v1/payments was previously missing from devServer proxy"

metrics:
  duration: "~12 min"
  completed: "2026-03-24"
---

# Phase 8 Plan 03: Quasar SPA Admin Pages Summary

**One-liner:** Quasar SPA admin pages with SSE-driven live metrics dashboard (dynamic per-provider latency cards), transaction search QTable, and event timeline QTimeline — wired to existing 08-01 REST endpoints.

## What Was Built

Five new frontend files and three modified files giving the admin a full UI for system monitoring and transaction investigation:

**Admin API module** (`admin.api.js`): Named export `adminApi` with `searchTransactions(params)` and `getTransactionDetail(transactionId)` — mirrors auth.api.js import pattern (`api` from `src/boot/axios`).

**Pinia metrics store** (`admin-metrics.store.js`): Composition API store holding reactive counters (`paymentSuccessTotal`, `paymentFailedTotal`, `fraudBlockedTotal`, `tpsLast5s`, `processingCount`), a `providerLatencyMs` map (keyed by provider name), plus `connected` and `lastUpdated` status. Exposes `updateMetrics(snapshot)` and `setDisconnected()`.

**AdminDashboardPage.vue**: Connects to `/v1/admin/metrics/stream` via `EventSource` with `withCredentials: true` on mount; closes connection on unmount. Top row of 4 stat cards (success, failed, fraud-blocked, TPS). Second row with in-flight count and stream status badge. Provider Latency section uses `v-for` over `store.providerLatencyMs` so ORANGE, MTN, and any future provider render automatically without code changes.

**TransactionSearchPage.vue**: Three filter inputs (transactionId, traceId, phone/externalReference), Search + Clear buttons. QTable with 6 columns including formatted date. `@row-click` navigates to `/admin/transactions/{transactionId}/events`. Reads `response.data.content` for Spring Data Page compatibility.

**TransactionDetailPage.vue**: Back button, spinner during load, summary card (status badge with color mapping, provider, phone, risk score with color class), then QTimeline listing every event with type, timestamp, status transition arrow, actor, and optional metadata code block.

**Router** (`routes.js`): Admin route group under path `admin` with 3 children — `dashboard`, `transactions`, `transactions/:transactionId/events`. All carry `meta: { requiresAuth: true }`. The existing `Router.beforeEach` guard in `index.js` checks the `user=` cookie and redirects to `/login` if absent.

**MainLayout.vue**: Separator + "Admin" header label + two nav items in the drawer linking to `/admin/dashboard` (bar_chart icon) and `/admin/transactions` (search icon). No changes to `<script setup>`.

**quasar.config.js**: `/v1/admin` and `/v1/payments` proxy rules added before the existing `/v1/api` catch-all so more-specific paths take precedence.

## Decisions Made

| Decision | What | Why |
|----------|------|-----|
| 08-03-A | No frontend requiresAdmin guard | Backend 403/401 is sufficient; plan explicitly specified this |
| 08-03-B | v-for over providerLatencyMs object | Dynamic provider rendering; no hardcoded ORANGE/MTN names |
| 08-03-C | HTML entity &rarr; for status arrow in timeline | Avoids potential Vue template encoding issues |
| 08-03-D | /v1/payments proxy also added | Plan specified both rules for completeness; previously missing |

## Files Created/Modified

| File | Action | Purpose |
|------|--------|---------|
| src/frontend/src/api/admin.api.js | Created | searchTransactions() + getTransactionDetail() |
| src/frontend/src/stores/admin-metrics.store.js | Created | Pinia composition store with providerLatencyMs |
| src/frontend/src/pages/admin/AdminDashboardPage.vue | Created | SSE live metrics + dynamic provider latency cards |
| src/frontend/src/pages/admin/TransactionSearchPage.vue | Created | Search form + paginated QTable + row navigation |
| src/frontend/src/pages/admin/TransactionDetailPage.vue | Created | Summary card + QTimeline event list |
| src/frontend/src/router/routes.js | Modified | Admin route group with 3 requiresAuth children |
| src/frontend/src/layouts/MainLayout.vue | Modified | Admin nav items in drawer |
| src/frontend/quasar.config.js | Modified | /v1/admin and /v1/payments devServer proxy rules |

## Deviations from Plan

None — plan executed exactly as written.

## Verification Results

All 10 verification items from the plan confirmed:

1. routes.js has 3 admin child routes under `admin` path, all with `requiresAuth: true`
2. AdminDashboardPage.vue uses `new EventSource('/v1/admin/metrics/stream', { withCredentials: true })`
3. AdminDashboardPage.vue renders provider latency cards via `v-for` over `store.providerLatencyMs`
4. admin-metrics.store.js exposes `providerLatencyMs` ref initialized to `{}`, updated from `snapshot.providerLatencyMs`
5. TransactionSearchPage.vue reads `response.data.content` (Spring Page structure)
6. TransactionDetailPage.vue reads `route.params.transactionId` and calls `adminApi.getTransactionDetail`
7. admin.api.js imports from `src/boot/axios` and exports named `adminApi` (no default export)
8. quasar.config.js devServer proxy has `/v1/admin` rule pointing to localhost:9990
9. MainLayout.vue drawer contains nav items linking to `/admin/dashboard` and `/admin/transactions`
10. EventSource closed in `onUnmounted` (no leak)

## Next Phase Readiness

Phase 8 is complete when 08-02 finishes. All three plans provide:

- Backend admin REST endpoints (08-01)
- SSE metrics stream endpoint (08-02)
- SPA pages consuming both (08-03)

Phase 9 (Reconciliation / Reporting) can proceed independently of this UI layer.
