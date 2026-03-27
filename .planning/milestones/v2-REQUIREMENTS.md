# Requirements Archive: v2 Logging Standardization

**Archived:** 2026-03-27
**Status:** ✅ SHIPPED

This is the archived requirements specification for v2.
For current requirements, see `.planning/REQUIREMENTS.md` (created for next milestone).

---

# Requirements: Payam

**Defined:** 2026-03-26
**Core Value:** Full-stack observability — every payment event traceable from Loki logs through Tempo traces to Prometheus metrics without manual correlation.

## v2 Requirements

Requirements for logging standardization milestone. Each maps to roadmap phases.

### Infrastructure

- [x] **LOG-INF-01**: `logback-spring.xml` uses `LoggingEventCompositeJsonEncoder` with providers: timestamp, level, thread, logger, message, mdc, arguments, stackTrace, and service identity fields (service, environment, version)
- [x] **LOG-INF-02**: All log output goes to stdout — no file appender in production config; Docker/K8s log pipeline (Alloy → Loki) picks up from stdout
- [x] **LOG-INF-03**: `traceId` and `spanId` are injected by OpenTelemetry into MDC and appear as top-level fields in every log entry

### MDC Standardization

- [x] **LOG-MDC-01**: Every inbound HTTP request populates MDC with `requestId` and `tenantId` (from `TenantPrincipal` or API key context) before the first log statement fires
- [x] **LOG-MDC-02**: Every payment request propagates `transactionId` and `externalReference` into MDC upon transaction creation; all downstream logs in the same request thread inherit these fields automatically

### Request Lifecycle Logging

- [x] **LOG-REQ-01**: `LoggingFilter` emits a structured `request_start` log at INFO with fields: `event=request_start`, `operation`, `method`, `path`, `requestId`, `clientIp`
- [x] **LOG-REQ-02**: `LoggingFilter` emits a structured `request_end` log at INFO with fields: `event=request_end`, `operation`, `durationMs`, `status` (SUCCESS/ERROR), `httpStatus`
- [x] **LOG-REQ-03**: `LoggingFilter` emits a structured `request_error` log at ERROR (when response ≥ 500) with fields: `event=request_error`, `operation`, `durationMs`, `errorCode`, `status=ERROR`

### Business Event Logging

- [x] **LOG-BUS-01**: Payment initiation logs a structured INFO event with: `operation=initiate_payment`, `tenantId`, `transactionId`, `provider`, `msisdn` (last 4 digits only), `durationMs`, `status`
- [x] **LOG-BUS-02**: Every transaction state transition logs a structured INFO event with: `operation=transaction_state_change`, `transactionId`, `fromState`, `toState`, `actor`
- [x] **LOG-BUS-03**: Inbound webhook receipt logs a structured INFO event with: `operation=webhook_received`, `provider`, `transactionId`, `externalReference`, `providerStatus`
- [x] **LOG-BUS-04**: Outbound webhook delivery logs a structured INFO/WARN event with: `operation=webhook_delivery`, `transactionId`, `tenantId`, `durationMs`, `httpStatus`, `status`, `retryCount`
- [x] **LOG-BUS-05**: Fraud evaluation logs a structured INFO/WARN event with: `operation=fraud_evaluation`, `transactionId`, `riskScore`, `blocked`, `durationMs`
- [x] **LOG-BUS-06**: All provider adapter HTTP calls (MTN, Orange) log a structured INFO event with: `externalService`, `operation`, `externalLatencyMs`, `status`
- [x] **LOG-BUS-07**: Daily reconciliation job logs a structured INFO event with: `operation=reconciliation_run`, `date`, `totalChecked`, `discrepancyCount`, `durationMs`, `status`

### Code Standards

- [x] **LOG-CODE-01**: No log call uses string interpolation for contextual data — all structured fields passed as `kv("field", value)` arguments so they appear as top-level JSON fields in Loki
- [x] **LOG-CODE-02**: No log calls log code flow ("Entering method", "Processing step 1") — only business events (PaymentInitiated, FraudBlocked, WebhookDelivered)
- [x] **LOG-CODE-03**: No sensitive data in any log call — no tokens, passwords, full MSISDNs, or personal data; `BodySanitizer` coverage verified to include payment fields

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| LOG-INF-01 | Phase 14 | Complete |
| LOG-INF-02 | Phase 14 | Complete |
| LOG-INF-03 | Phase 14 | Complete |
| LOG-MDC-01 | Phase 15 | Complete |
| LOG-MDC-02 | Phase 15 | Complete |
| LOG-REQ-01 | Phase 15 | Complete |
| LOG-REQ-02 | Phase 15 | Complete |
| LOG-REQ-03 | Phase 15 | Complete |
| LOG-BUS-01 | Phase 16 | Complete |
| LOG-BUS-02 | Phase 16 | Complete |
| LOG-BUS-03 | Phase 16 | Complete |
| LOG-BUS-04 | Phase 16 | Complete |
| LOG-BUS-05 | Phase 16 | Complete |
| LOG-BUS-06 | Phase 16 | Complete |
| LOG-BUS-07 | Phase 16 | Complete |
| LOG-CODE-01 | Phase 17 | Complete |
| LOG-CODE-02 | Phase 17 | Complete |
| LOG-CODE-03 | Phase 17 | Complete |

---

## Milestone Summary

**Shipped:** 18 of 18 v2 requirements
**Adjusted:** None — all requirements implemented as specified
**Dropped:** None

---
*Archived: 2026-03-27 as part of v2 milestone completion*
