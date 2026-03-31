# Phase 26: Health Dashboard UI - Research

**Researched:** 2026-03-31
**Domain:** Vue 3 Quasar admin SPA, Spring Actuator health endpoint, admin route guard
**Confidence:** HIGH (all findings verified directly in the codebase)

---

## Summary

Phase 26 adds a read-only health dashboard page at `/admin/health-dashboard` in the Quasar SPA.
The page calls `GET /manage/health` with the admin JWT, parses the Spring Boot Actuator response,
and displays each health component's status and detail data. No new backend code is required —
the endpoint already exists and returns `orangePlatform` / `mtnPlatform` components (Phase 25).

Access control for HLTH-07 is inherent: Spring Boot's `show-details: when-authorized` hides the
`components` block from non-admin JWT holders. The frontend detects the absent `components` field
and shows an access-denied message.

---

## Standard Stack

### Core
| Component | Type | Purpose | Why Standard |
|-----------|------|---------|--------------|
| `GET /manage/health` | Spring Actuator | Health data source | Already returns `orangePlatform`, `mtnPlatform` with CB detail for admin JWT |
| `admin.api.js` | Existing module | `getHealth()` function | Same pattern as all other admin API calls |
| `HealthDashboardPage.vue` | Vue 3 `<script setup>` | New page | Matches all existing admin pages |
| `routes.js` admin children | Existing router | Route registration | `health-dashboard` child under admin parent |
| Quasar `q-badge` or `q-chip` | Quasar | Status visual indicator | Color-coded UP/DOWN |

### Response Shape (admin JWT)
```json
{
  "status": "DOWN",
  "components": {
    "db":             { "status": "UP",   "details": { ... } },
    "orangePlatform": { "status": "DOWN", "details": { "reason": "platform MSISDN not configured", "circuitBreaker": "CLOSED" } },
    "mtnPlatform":    { "status": "DOWN", "details": { "reason": "platform MSISDN not configured", "circuitBreaker": "CLOSED" } },
    "diskSpace":      { "status": "UP",   "details": { ... } },
    "ping":           { "status": "UP" }
  }
}
```

### Response Shape (non-admin / unauthenticated JWT)
```json
{ "status": "DOWN" }
```
No `components` field — `show-details: when-authorized` hides it.

---

## Architecture

### API Function
Add to `admin.api.js`:
```javascript
/**
 * Get the Spring Boot Actuator health response.
 * Admin JWT required to see component details.
 * Returns: { status: 'UP'|'DOWN', components: { [name]: { status, details? } } }
 */
getHealth() {
  return api.get('/manage/health')
},
```

### Access Control (HLTH-07)
The axios instance is configured with `withCredentials: true` — JWT is sent automatically.
Spring returns `{ "status": "..." }` (no `components`) for non-admin users. The component checks:
```javascript
if (!data.components) {
  // show access-denied message — user is not an admin
}
```
The route guard (`requiresAuth: true`) ensures the user is at minimum authenticated. Spring's
`when-authorized` + `roles: ROLE_ADMIN` ensures component detail is only visible to admins.

### Page Structure
```
HealthDashboardPage.vue
├── <q-page padding>
│   ├── <div class="text-h5 q-mb-md">System Health</div>
│   ├── <q-inner-loading :showing="isLoading" />
│   ├── <!-- access-denied banner (when !health.components) -->
│   │   <q-banner v-if="!isLoading && health && !health.components">
│   │       Access denied — admin role required to view health details
│   │   </q-banner>
│   ├── <!-- overall status chip -->
│   │   <q-chip v-if="health" :color="health.status === 'UP' ? 'positive' : 'negative'">
│   │       {{ health.status }}
│   │   </q-chip>
│   └── <!-- component cards -->
│       <q-card v-for="(component, name) in health.components" :key="name">
│           <q-card-section>
│               <div class="text-subtitle1">{{ name }}</div>
│               <q-badge :color="component.status === 'UP' ? 'positive' : 'negative'">
│                   {{ component.status }}
│               </q-badge>
│               <!-- detail key-value pairs -->
│               <div v-for="(val, key) in component.details" :key="key">
│                   {{ key }}: {{ val }}
│               </div>
│           </q-card-section>
│       </q-card>
```

### Reactive State
```javascript
const health = ref(null)    // full response object { status, components? }
const isLoading = ref(false)
```

### Route
```javascript
{
  path: 'health-dashboard',
  component: () => import('pages/admin/HealthDashboardPage.vue'),
  meta: { requiresAuth: true },
},
```

---

## Key Findings

### 1. Axios response is unwrapped
The boot/axios.js response interceptor returns `response.data` directly. `adminApi.getHealth()`
resolves to `{ status: '...', components: {...} }` — not an AxiosResponse wrapper. Use `data`
directly in the component (not `data.data`).

### 2. /manage/health uses same axios instance
`api` in admin.api.js is the shared axios instance with `baseURL: ''`. Relative path
`'/manage/health'` works identically to `/v1/admin/*` paths — no additional configuration needed.

### 3. components iteration with v-for on object
`v-for="(component, name) in health.components"` iterates over object entries. Vue 3 supports
this natively. The `name` is the health key (e.g. `"orangePlatform"`) and `component` is
`{ status, details? }`.

### 4. details field may be absent
`ping` and other simple indicators have no `details` field. Use `v-if="component.details"` or
`v-for="(val, key) in (component.details ?? {})"` to handle absent details safely.

### 5. No new backend code required
Phase 25 already provides all required backend data. Phase 26 is frontend-only.

---

## Anti-Patterns to Avoid

- **Calling `/v1/admin/providers/status` instead of `/manage/health`**: The health endpoint is the canonical source; it already aggregates all provider health indicators.
- **Throwing on absent `components`**: Non-admin users get a valid 200 response with no components — this is not an error state, it's an access-level signal.
- **Hardcoding component names**: Use `v-for` on `health.components` — the set of components can grow.

---

## Sources

### Primary (HIGH confidence)
- `src/frontend/src/pages/admin/PlatformConfigPage.vue` — definitive current page pattern
- `src/frontend/src/api/admin.api.js` — API module structure
- `src/frontend/src/router/routes.js` — admin children array structure
- `src/frontend/src/boot/axios.js` — axios instance config (`baseURL: ''`, response unwrap)
- `src/main/resources/application.yaml` lines 145-164 — `show-details: when-authorized`, `roles: ROLE_ADMIN`, `base-path: /manage`
- Phase 25 health indicators (orangePlatform, mtnPlatform) — confirmed response shape

**Research date:** 2026-03-31
