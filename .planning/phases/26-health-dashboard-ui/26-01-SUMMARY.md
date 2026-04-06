---
phase: 26-health-dashboard-ui
plan: 01
subsystem: frontend
tags: [vue3, quasar, admin-ui, actuator, health]

# Dependency graph
requires:
  - phase: 25-provider-health-indicators
    provides: /manage/health endpoint with mtnPlatform + orangePlatform components and CB state
provides:
  - HealthDashboardPage.vue — loops all health components, renders status badges + detail key/value pairs
  - adminApi.getHealth() — GET http://hostname:8367/manage/health with credentials
  - /admin/health-dashboard route (meta: requiresAuth)
  - Access-denied banner for non-admin (v-if="!health.components")
  - 503 DOWN-state handling: extracts body from error.response.data when Spring returns HTTP 503

# Plan 26-01: HealthDashboardPage.vue, getHealth() API function, and route

**Admin UI health dashboard surfaces all health check results; access is restricted to admin users via Spring Actuator show-details: when-authorized + ROLE_ADMIN.**

## What was built

- `HealthDashboardPage.vue` — Vue 3 Composition API page that calls `adminApi.getHealth()` on mount, renders overall status chip, and loops `health.components` as q-cards with status badge and detail rows
- `adminApi.getHealth()` — constructs actuator URL at port 8367 directly, sends request with cookies via `withCredentials: true` axios instance
- Route registered as child of `/admin` in routes.js with `meta: { requiresAuth: true }`
- Nav link added to MainLayout sidebar (icon: monitor_heart)
- 503 error handling: Spring returns HTTP 503 when overall status is DOWN but body contains full JSON — extracted from `error.response.data` so dashboard renders DOWN state correctly

## Verification (2026-04-02)

Live response confirmed all HLTH-06/07 criteria:
- JWT auth works on management port 8367 — `components` present for ROLE_ADMIN user
- `mtnPlatform` component: status DOWN, msisdn, error (sandbox 401), circuitBreaker CLOSED
- `orangePlatform` component: status DOWN, msisdn, error (sandbox 404), circuitBreaker CLOSED
- `db`, `redis`, `mail`, `ping`, `ssl`, `refreshScope` all UP
- Provider DOWN status is sandbox credential expiry, not a code issue

## Key decisions

- `getHealth()` uses hardcoded port 8367 (not dev proxy) — works because CORS allows localhost:9000; in production port 8367 must be browser-accessible
- 503 error body extraction — Spring's default behavior when overall status is DOWN; component must extract data from error response rather than success response
