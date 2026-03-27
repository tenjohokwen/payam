# Milestone v2: Logging Standardization

**Status:** ✅ SHIPPED 2026-03-27
**Phases:** 14–17
**Total Plans:** 12

## Overview

Full-stack observability layer added to the Payam payment API. Every payment event is traceable from Loki logs through Tempo traces to Prometheus metrics without manual correlation. JSON-structured stdout logging, per-request MDC enrichment, 7 business event types, and codebase-wide code standards enforcement.

## Phases

### Phase 14: Logging Infrastructure

**Goal**: JSON-structured logs flow to stdout with OpenTelemetry trace correlation
**Depends on**: Phase 13 (v1 complete)
**Requirements**: LOG-INF-01, LOG-INF-02, LOG-INF-03
**Plans**: 1 plan

Plans:
- [x] 14-01: Replace logback-spring.xml with LoggingEventCompositeJsonEncoder JSON stdout pipeline; remove Loki4j; add service identity fields

**Details:**
- LoggingEventCompositeJsonEncoder with 8 providers (timestamp, logLevel, thread, logger, message, mdc, arguments, stackTrace)
- ConsoleAppender stdout-only — no file appender, no network appender
- `<mdc/>` provider flattens all MDC keys (including OTel traceId/spanId) as top-level JSON fields
- Service identity fields (service, environment, version) via springProperty + pattern provider
- loki-logback-appender dependency removed from pom.xml

### Phase 15: MDC & Request Lifecycle

**Goal**: Every HTTP request emits structured start/end events with full correlation context
**Depends on**: Phase 14
**Requirements**: LOG-MDC-01, LOG-MDC-02, LOG-REQ-01, LOG-REQ-02, LOG-REQ-03
**Plans**: 2 plans

Plans:
- [x] 15-01: LoggingFilter rewrite — request_start/request_end/request_error structured events + requestId MDC + tenantId MDC via ApiKeyAuthenticationFilter
- [x] 15-02: MDC camelCase rename — transactionId/externalReference in TransactionService; Constants.TXN_ID_NAME aligned; trace_id manual put removed

**Details:**
- requestId populated in MDC before first log call for every HTTP request (LoggingFilter)
- tenantId (UUID tenantRef) populated for all API-key-authenticated requests (ApiKeyAuthenticationFilter)
- transactionId and externalReference propagated via MDC for every payment thread (TransactionService)
- Three lifecycle events: request_start (pre-chain), request_end (post-chain), request_error (5xx only)
- MDC.remove() ownership split: each filter cleans up exactly what it set — traceId/spanId untouched

### Phase 16: Business Event Logging

**Goal**: All payment lifecycle events are observable in Loki with structured fields
**Depends on**: Phase 15
**Requirements**: LOG-BUS-01, LOG-BUS-02, LOG-BUS-03, LOG-BUS-04, LOG-BUS-05, LOG-BUS-06, LOG-BUS-07
**Plans**: 5 plans

Plans:
- [x] 16-01: LOG-BUS-01 initiate_payment (PaymentOrchestrator) + LOG-BUS-05 fraud_evaluation (FraudScoringService)
- [x] 16-02: LOG-BUS-02 transaction_state_change at all 9 applyTransition() sites across 4 files
- [x] 16-03: LOG-BUS-03 webhook_received (MTN + Orange) + LOG-BUS-04 webhook_delivery (all 4 outcome paths)
- [x] 16-04: LOG-BUS-06 provider HTTP latency events for all 14 adapter methods (7 MTN + 7 Orange)
- [x] 16-05: LOG-BUS-07 reconciliation_run with cross-provider totalChecked/discrepancyCount aggregation

**Details:**
- 7 event types covering the full payment lifecycle — all queryable by transactionId, tenantId, provider
- msisdnLast4() privacy helper masks MSISDN in initiate_payment event
- Timer placement before try blocks ensures durationMs is always computable on all outcome paths
- fraud_evaluation ALLOW path upgraded DEBUG → INFO (every payment is now traceable)
- reconciliation_run emits single summary with cross-provider totals after full provider loop

### Phase 17: Code Standards Enforcement

**Goal**: All log calls comply with structured field pattern — no interpolation, no flow logs, no PII
**Depends on**: Phase 16
**Requirements**: LOG-CODE-01, LOG-CODE-02, LOG-CODE-03
**Plans**: 4 plans

Plans:
- [x] 17-01: LOG-CODE-01/02/03 payment domain — PaymentOrchestrator, MtnMoMoPort, OrangeMoneyPort, pollers, WebhookDeliveryService, WebhookDoubleCheckHandler, WebhookDeliveryJob
- [x] 17-02: LOG-CODE-01/02/03 infrastructure + security services — 24 files (reconciliation, alert, cache, IdempotencyService; UserRegistrationService, PasswordResetService, SecurityAuditListener)
- [x] 17-03: LOG-CODE-03 PII closure — BodySanitizer (msisdn/merchant_key/merchantKey added), RestRequestInterceptor (no headers in args), API controllers, filters, validators
- [x] 17-04: LOG-CODE-01/02 gap closure — 9 files missed in original audit (email infrastructure, security filters, AccountResource); 2 additional files found and fixed inline during verification

**Details:**
- Zero {} string interpolation anywhere in src/main/java (LOG-CODE-01 verified by grep)
- Zero ##### decorative logs anywhere in src/main/java (LOG-CODE-02 verified by grep)
- BodySanitizer covers: password family, token family, apiKey, pin, msisdn, merchant_key, merchantKey
- PII contract: usernames, emails, loginIds, reset keys — never log, never hash, omit entirely
- 7 classes had @Slf4j removed after all log calls deleted (no unused field warnings)

---

## Milestone Summary

**Key Decisions:**

- springProperty indirection pattern: `<springProperty source="app.environment">` reads Spring property (not raw env var); YAML app: block resolves from `${ENVIRONMENT:prod}` enabling per-profile defaults + runtime override
- MDC.remove() ownership split: each filter removes exactly what it set; OTel traceId/spanId never manually overridden
- tenantRef (UUID string) as canonical tenantId log value throughout — matches TenantContext and Loki queries
- Constants.TXN_ID_NAME canonical MDC key is "transactionId" (corrected from "txnId" which was never aligned)
- All application MDC keys use camelCase; OTel-owned keys (traceId, spanId) are never manually overridden
- fromState hardcoded as TransactionStatus.PROCESSING.name() at poller/webhook sites (post-transition status is already mutated)
- PII removal contract: usernames, emails, loginIds, reset/activation keys never appear as log arguments; omit entirely (do not hash)
- Exception as last log arg: pass raw `e` not `e.getMessage()` to preserve stack trace

**Issues Resolved:**

- Loki4j network appender conflict with logstash-logback-encoder — removed from POM entirely
- MDC key misalignment: "txnId" vs "transactionId" — aligned to canonical camelCase key
- Manual `MDC.put("trace_id", ...)` was redundant and used wrong snake_case key — removed
- 11 additional files found during Task 2 verification of 17-04 that were outside original audit scope — fixed inline

**Issues Deferred:**

- v3 requirements: LOG-OBS-01 (Loki alerting rules), LOG-OBS-02 (Grafana dashboards) — deferred to future release

**Technical Debt Incurred:**

- None — all LOG-CODE-01/02/03 violations eliminated; verification greps return zero matches

---

_For current project status, see .planning/ROADMAP.md_
