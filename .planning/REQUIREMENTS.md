# Requirements: Payam Platform Configuration & Health

**Defined:** 2026-03-30
**Core Value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## v1 Requirements

### PCONF: Platform Configuration

- [ ] **PCONF-01**: Admin can view the configured platform MSISDN for each provider (Orange and MTN) in the admin UI
- [ ] **PCONF-02**: Admin can update the Orange Money platform MSISDN in the admin UI
- [ ] **PCONF-03**: Admin can update the MTN MoMo platform MSISDN in the admin UI
- [ ] **PCONF-04**: System sends email notification to the address in `payam.platform.notification-email` whenever any platform MSISDN is modified

### HLTH: Platform Health

- [ ] **HLTH-01**: `/actuator/health` verifies the Orange platform MSISDN is active by calling the Orange subscriber validation endpoint on every poll
- [ ] **HLTH-02**: `/actuator/health` verifies the MTN platform MSISDN is active by calling the MTN account holder validation endpoint on every poll
- [ ] **HLTH-03**: Health status is `UP` only when both Orange and MTN platform MSISDNs pass provider validation
- [ ] **HLTH-04**: Health check includes the circuit breaker status for the Orange Money provider adapter
- [ ] **HLTH-05**: Health check includes the circuit breaker status for the MTN MoMo provider adapter
- [ ] **HLTH-06**: Admin can view a health dashboard in the admin UI showing all health check results
- [ ] **HLTH-07**: Health dashboard is restricted to admin users — client/tenant users cannot access it

## v2 Requirements

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

Which phases cover which requirements. Updated by create-roadmap.

| Requirement | Phase | Status |
|-------------|-------|--------|
| PCONF-01 | TBD | Pending |
| PCONF-02 | TBD | Pending |
| PCONF-03 | TBD | Pending |
| PCONF-04 | TBD | Pending |
| HLTH-01 | TBD | Pending |
| HLTH-02 | TBD | Pending |
| HLTH-03 | TBD | Pending |
| HLTH-04 | TBD | Pending |
| HLTH-05 | TBD | Pending |
| HLTH-06 | TBD | Pending |
| HLTH-07 | TBD | Pending |

**Coverage:**
- v1 requirements: 11 total
- Mapped to phases: 0
- Unmapped: 11 ⚠️

---
*Requirements defined: 2026-03-30*
*Last updated: 2026-03-30 after initial definition*
