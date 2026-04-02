# Milestone v4: Platform Config & Health

**Status:** ✅ SHIPPED 2026-04-02
**Phases:** 24–26
**Total Plans:** 5

## Overview

Admin-facing platform operations layer: admins can view and update the platform MSISDNs for both MTN and Orange providers, receive email notification on change, and monitor live provider health (MSISDN validation + circuit breaker state) through a dedicated admin UI dashboard. All health checks call real provider APIs on every poll — no caching.

## Phases

### Phase 24: Platform Configuration

**Goal**: Admin can view and update platform MSISDNs for both providers, with email notification on change
**Depends on**: Phase 23
**Requirements**: PCONF-01, PCONF-02, PCONF-03, PCONF-04
**Plans**: 3/3 — completed 2026-03-30

Plans:
- [x] 24-01: Flyway V17 migration, PlatformConfig entity/repo, PlatformConfigService, PlatformConfigAdminResource (GET + PUT)
- [x] 24-02: EmailTemplate enum entry, PlatformConfigEmailListener, platformConfigChanged.html Thymeleaf template
- [x] 24-03: PlatformConfigPage.vue (Vue 3 Composition API), admin.api.js API functions, routes.js child route

**Success Criteria met:**
1. ✅ Admin can view the current Orange and MTN platform MSISDNs in the admin UI
2. ✅ Admin can update the Orange platform MSISDN and see it persisted on reload
3. ✅ Admin can update the MTN platform MSISDN and see it persisted on reload
4. ✅ A notification email is sent to the configured address whenever either platform MSISDN is changed

**Key decisions:**
- `PayamPlatformProperties` registered via `@EnableConfigurationProperties` in companion `PlatformConfig` `@Configuration` class — mirrors OrangeMoneyConfig/OrangeConfig pattern
- `PlatformConfigChangedEvent` is a plain Java record — Spring 4.2+ supports non-ApplicationEvent events
- `publishEvent()` inside `@Transactional update()` for AFTER_COMMIT listener compatibility
- `PlatformConfigEmailListener` uses `@EventListener` (not `@TransactionalEventListener`) — matches AccountChangeEmailListener pattern; MailManager uses AFTER_COMMIT on the Envelope event
- `update()` normalises provider to upper-case before `findByProvider()` — prevents case mismatch bugs

---

### Phase 25: Provider Health Indicators

**Goal**: Spring Boot Actuator `/manage/health` reflects live Orange and MTN MSISDN validation and circuit breaker state
**Depends on**: Phase 24
**Requirements**: HLTH-01, HLTH-02, HLTH-03, HLTH-04, HLTH-05
**Plans**: 1/1 — completed 2026-03-31

Plans:
- [x] 25-01: OrangePlatformHealthIndicator + MtnPlatformHealthIndicator (HealthIndicator beans, validateSubscriber, CB state detail)

**Success Criteria met:**
1. ✅ `/manage/health` returns UP when both MSISDNs pass provider validation
2. ✅ `/manage/health` returns DOWN when either fails (confirmed via live response with expired sandbox creds)
3. ✅ Health response includes CB status for Orange adapter
4. ✅ Health response includes CB status for MTN adapter

---

### Phase 26: Health Dashboard UI

**Goal**: Admin UI health dashboard surfaces all health check results; access is restricted to admin users
**Depends on**: Phase 25
**Requirements**: HLTH-06, HLTH-07
**Plans**: 1/1 — completed 2026-04-02

Plans:
- [x] 26-01: HealthDashboardPage.vue, getHealth() in admin.api.js, health-dashboard route

**Success Criteria met:**
1. ✅ Admin users can view health dashboard with all component results
2. ✅ Non-admin users see access-denied banner (no component details shown)
3. ✅ Dashboard shows live mtnPlatform + orangePlatform MSISDN validation status and CB state

**Key decisions:**
- `getHealth()` constructs full URL to port 8367 directly (bypasses dev proxy) — CORS allows localhost:9000; production requires port 8367 to be browser-accessible
- 503 error body extraction: Spring returns HTTP 503 when overall status is DOWN but body contains full JSON — Vue component extracts from `error.response.data`
- `show-details: when-authorized` + `roles: ROLE_ADMIN` — JWT auth confirmed working on management port 8367; admin gets `components`, non-admin does not

---

## Milestone Summary

**Key Decisions:**
- Spring Boot Actuator management on separate port 8367 — JWT cookie-based auth confirmed working cross-port with CORS `allow-credentials: true`
- `@EventListener` (not `@TransactionalEventListener`) on PlatformConfigEmailListener — MailManager handles the AFTER_COMMIT boundary
- `PlatformConfigChangedEvent` as a plain POJO record — no ApplicationEvent overhead needed
- Health indicators call validateSubscriber() on every poll — no caching; live provider truth always reflected

**Issues Resolved:**
- Discovered `JWTAuthorizationFilter` populates SecurityContext even for unrestricted endpoints (else branch in doFilterInternal) — confirmed that ROLE_ADMIN is visible to `show-details: when-authorized` on port 8367

**Issues Deferred:**
- B2B-01, B2B-02: OrangeClient `channelUserMsisdn` fix deferred to future milestone (use case TBD)

**Technical Debt:**
- `getHealth()` hardcodes port 8367 — acceptable for current single-server deployment; would need reconfiguration if management port changes or is behind a reverse proxy

---

*For current project status, see .planning/ROADMAP.md*
