# Roadmap: Payam

## Milestones

- ✅ **v1 Payment API** — Phases 1–13 (shipped 2026-03-26) — see [milestones/v1-ROADMAP.md](milestones/v1-ROADMAP.md)
- ✅ **v2 Logging Standardization** — Phases 14–17 (shipped 2026-03-27) — see [milestones/v2-ROADMAP.md](milestones/v2-ROADMAP.md)
- ✅ **v3 E2E Test Suite** — Phases 18–23 (shipped 2026-03-28) — see [milestones/v3-ROADMAP.md](milestones/v3-ROADMAP.md)
- 🚧 **v4 Platform Config & Health** — Phases 24–26 (in progress)

## Phases

<details>
<summary>✅ v1 Payment API (Phases 1–13) — SHIPPED 2026-03-26</summary>

- [x] Phase 1: Multi-Tenant Foundation (3/3 plans) — completed 2026-03-23
- [x] Phase 2: Transaction Core + Event Sourcing (3/3 plans) — completed 2026-03-23
- [x] Phase 3: Orange Money Adapter (4/4 plans) — completed 2026-03-24
- [x] Phase 4: MTN MoMo Adapter (2/2 plans) — completed 2026-03-24
- [x] Phase 5: Payment Orchestration (2/2 plans) — completed 2026-03-24
- [x] Phase 6: Webhook Processing (3/3 plans) — completed 2026-03-24
- [x] Phase 7: Fraud Engine (2/2 plans) — completed 2026-03-24
- [x] Phase 8: Admin Dashboard + Monitoring (3/3 plans) — completed 2026-03-24
- [x] Phase 9: Reconciliation (2/2 plans) — completed 2026-03-25
- [x] Phase 10: Operational Hardening (4/4 plans) — completed 2026-03-25
- [x] Phase 11: Fee Exposure (1/1 plan) — completed 2026-03-25
- [x] Phase 12: Test & Doc Polish (1/1 plan) — completed 2026-03-25
- [x] Phase 13: Ledger Wiring + Webhook Access Control (1/1 plan) — completed 2026-03-26

</details>

<details>
<summary>✅ v2 Logging Standardization (Phases 14–17) — SHIPPED 2026-03-27</summary>

- [x] Phase 14: Logging Infrastructure (1/1 plans) — completed 2026-03-26
- [x] Phase 15: MDC & Request Lifecycle (2/2 plans) — completed 2026-03-27
- [x] Phase 16: Business Event Logging (5/5 plans) — completed 2026-03-27
- [x] Phase 17: Code Standards Enforcement (4/4 plans) — completed 2026-03-27

</details>

<details>
<summary>✅ v3 E2E Test Suite (Phases 18–23) — SHIPPED 2026-03-28</summary>

- [x] Phase 18: Test Infrastructure (2/2 plans) — completed 2026-03-27
- [x] Phase 19: Verifiers + Test Data Builders (2/2 plans) — completed 2026-03-27
- [x] Phase 20: Payment Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 21: Webhook Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 22: Fraud, Reconciliation, Admin Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 23: Domain Invariants, Concurrency, SM, Mutation (5/5 plans) — completed 2026-03-28

</details>

### 📋 v4 Platform Config & Health (Planned)

**Milestone Goal:** Admin can view and update platform MSISDNs for both providers; Spring Boot Actuator reflects live provider health and circuit breaker state; health dashboard is accessible in the admin UI to admin users only.

#### Phase 24: Platform Configuration ✅
**Goal**: Admin can view and update platform MSISDNs for both providers, with email notification on change
**Depends on**: Phase 23
**Requirements**: PCONF-01, PCONF-02, PCONF-03, PCONF-04
**Success Criteria** (what must be TRUE):
  1. Admin can view the current Orange and MTN platform MSISDNs in the admin UI ✅
  2. Admin can update the Orange platform MSISDN and see it persisted on reload ✅
  3. Admin can update the MTN platform MSISDN and see it persisted on reload ✅
  4. A notification email is sent to the configured address whenever either platform MSISDN is changed ✅
**Plans**: 3/3 — completed 2026-03-30

Plans:
- [x] 24-01: Flyway V17 migration, PlatformConfig entity/repo, PlatformConfigService, PlatformConfigAdminResource (GET + PUT)
- [x] 24-02: EmailTemplate enum entry, PlatformConfigEmailListener, platformConfigChanged.html Thymeleaf template
- [x] 24-03: PlatformConfigPage.vue (Vue 3 Composition API), admin.api.js API functions, routes.js child route

#### Phase 25: Provider Health Indicators
**Goal**: Spring Boot Actuator `/manage/health` reflects live Orange and MTN MSISDN validation and circuit breaker state
**Depends on**: Phase 24
**Requirements**: HLTH-01, HLTH-02, HLTH-03, HLTH-04, HLTH-05
**Success Criteria** (what must be TRUE):
  1. `/manage/health` returns UP when both Orange and MTN platform MSISDNs pass their provider validations
  2. `/manage/health` returns DOWN when either MSISDN fails provider validation
  3. Health response includes circuit breaker status for the Orange Money provider adapter
  4. Health response includes circuit breaker status for the MTN MoMo provider adapter
**Plans**: 1/1 — completed 2026-03-31

Plans:
- [x] 25-01: OrangePlatformHealthIndicator + MtnPlatformHealthIndicator (HealthIndicator beans, validateSubscriber, CB state detail)

#### Phase 26: Health Dashboard UI
**Goal**: Admin UI health dashboard surfaces all health check results; access is restricted to admin users
**Depends on**: Phase 25
**Requirements**: HLTH-06, HLTH-07
**Success Criteria** (what must be TRUE):
  1. Admin users can view a health dashboard page showing all health check results
  2. Non-admin (client/tenant) users see access-denied banner (no component details shown)
  3. Dashboard displays live provider MSISDN validation status and circuit breaker state for both providers
**Plans**: 1 planned

Plans:
- [ ] 26-01: HealthDashboardPage.vue, getHealth() in admin.api.js, health-dashboard route

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Multi-Tenant Foundation | v1 | 3/3 | Complete | 2026-03-23 |
| 2. Transaction Core + Event Sourcing | v1 | 3/3 | Complete | 2026-03-23 |
| 3. Orange Money Adapter | v1 | 4/4 | Complete | 2026-03-24 |
| 4. MTN MoMo Adapter | v1 | 2/2 | Complete | 2026-03-24 |
| 5. Payment Orchestration | v1 | 2/2 | Complete | 2026-03-24 |
| 6. Webhook Processing | v1 | 3/3 | Complete | 2026-03-24 |
| 7. Fraud Engine | v1 | 2/2 | Complete | 2026-03-24 |
| 8. Admin Dashboard + Monitoring | v1 | 3/3 | Complete | 2026-03-24 |
| 9. Reconciliation | v1 | 2/2 | Complete | 2026-03-25 |
| 10. Operational Hardening | v1 | 4/4 | Complete | 2026-03-25 |
| 11. Fee Exposure | v1 | 1/1 | Complete | 2026-03-25 |
| 12. Test & Doc Polish | v1 | 1/1 | Complete | 2026-03-25 |
| 13. Ledger Wiring + Webhook Access Control | v1 | 1/1 | Complete | 2026-03-26 |
| 14. Logging Infrastructure | v2 | 1/1 | Complete | 2026-03-26 |
| 15. MDC & Request Lifecycle | v2 | 2/2 | Complete | 2026-03-27 |
| 16. Business Event Logging | v2 | 5/5 | Complete | 2026-03-27 |
| 17. Code Standards Enforcement | v2 | 4/4 | Complete | 2026-03-27 |
| 18. Test Infrastructure | v3 | 2/2 | Complete | 2026-03-27 |
| 19. Verifiers + Test Data Builders | v3 | 2/2 | Complete | 2026-03-27 |
| 20. Payment Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 21. Webhook Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 22. Fraud, Reconciliation, Admin Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 23. Domain Invariants, Concurrency, SM, Mutation | v3 | 5/5 | Complete | 2026-03-28 |
| 24. Platform Configuration | v4 | 3/3 | Complete | 2026-03-30 |
| 25. Provider Health Indicators | v4 | 1/1 | Complete | 2026-03-31 |
| 26. Health Dashboard UI | v4 | 0/TBD | Not started | - |
