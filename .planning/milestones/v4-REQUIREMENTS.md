# Requirements Archive: v4 Platform Configuration & Health

**Archived:** 2026-04-02
**Milestone:** v4 — all 11 v1 requirements shipped
**Original defined:** 2026-03-30

---

# Requirements: Payam Platform Configuration & Health

**Defined:** 2026-03-30
**Core Value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## v1 Requirements

### PCONF: Platform Configuration

- [x] **PCONF-01**: Admin can view the configured platform MSISDN for each provider (Orange and MTN) in the admin UI
  - *Outcome: Validated — PlatformConfigPage.vue GET on mount; both providers displayed*
- [x] **PCONF-02**: Admin can update the Orange Money platform MSISDN in the admin UI
  - *Outcome: Validated — PUT /v1/admin/platform-config/{id}; persists and reloads*
- [x] **PCONF-03**: Admin can update the MTN MoMo platform MSISDN in the admin UI
  - *Outcome: Validated — same endpoint; per-provider save buttons*
- [x] **PCONF-04**: System sends email notification to the address in `payam.platform.notification-email` whenever any platform MSISDN is modified
  - *Outcome: Validated — PlatformConfigEmailListener @EventListener + AFTER_COMMIT pattern via MailManager*

### HLTH: Platform Health

- [x] **HLTH-01**: `/actuator/health` verifies the Orange platform MSISDN is active by calling the Orange subscriber validation endpoint on every poll
  - *Outcome: Validated — OrangePlatformHealthIndicator calls validateSubscriber() on every health check*
- [x] **HLTH-02**: `/actuator/health` verifies the MTN platform MSISDN is active by calling the MTN account holder validation endpoint on every poll
  - *Outcome: Validated — MtnPlatformHealthIndicator calls validateSubscriber() on every health check*
- [x] **HLTH-03**: Health status is `UP` only when both Orange and MTN platform MSISDNs pass provider validation
  - *Outcome: Validated — both indicators return Health.down() on failure; live response confirmed DOWN with sandbox expired creds*
- [x] **HLTH-04**: Health check includes the circuit breaker status for the Orange Money provider adapter
  - *Outcome: Validated — withDetail("circuitBreaker", cbState) present in live response*
- [x] **HLTH-05**: Health check includes the circuit breaker status for the MTN MoMo provider adapter
  - *Outcome: Validated — withDetail("circuitBreaker", cbState) present in live response*
- [x] **HLTH-06**: Admin can view a health dashboard in the admin UI showing all health check results
  - *Outcome: Validated — HealthDashboardPage.vue; live response confirmed components visible for ROLE_ADMIN*
- [x] **HLTH-07**: Health dashboard is restricted to admin users — client/tenant users cannot access it
  - *Outcome: Validated — show-details: when-authorized + roles: ROLE_ADMIN; non-admin sees banner*

## v2 Requirements (deferred to future milestone)

### B2B: B2B Transaction Integration

- **B2B-01**: B2B transactions use the configured platform MSISDN for the relevant provider *(use case TBD)*
- **B2B-02**: `OrangeClient` uses the Orange platform MSISDN as the `channelUserMsisdn` in outbound requests *(fix deferred)*

## Out of Scope

| Feature | Reason |
|---------|--------|
| Notification email configurable in admin UI | In application config only; exposing it in UI increases attack surface |
| `/actuator/health` result caching | Live provider call required on every poll |
| OrangeClient `channelUserMsisdn` fix | Out of this scope; tracked as B2B-02 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| PCONF-01 | Phase 24 | ✅ Complete |
| PCONF-02 | Phase 24 | ✅ Complete |
| PCONF-03 | Phase 24 | ✅ Complete |
| PCONF-04 | Phase 24 | ✅ Complete |
| HLTH-01 | Phase 25 | ✅ Complete |
| HLTH-02 | Phase 25 | ✅ Complete |
| HLTH-03 | Phase 25 | ✅ Complete |
| HLTH-04 | Phase 25 | ✅ Complete |
| HLTH-05 | Phase 25 | ✅ Complete |
| HLTH-06 | Phase 26 | ✅ Complete |
| HLTH-07 | Phase 26 | ✅ Complete |

**Coverage:** 11/11 v1 requirements shipped ✓

---
*Archived: 2026-04-02 after v4 milestone completion*
