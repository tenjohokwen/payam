# Roadmap: Payam

## Milestones

- ✅ **v1 Payment API** — Phases 1–13 (shipped 2026-03-26) — see [milestones/v1-ROADMAP.md](milestones/v1-ROADMAP.md)
- 🚧 **v2 Logging Standardization** — Phases 14–17 (in progress)

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

### 🚧 v2 Logging Standardization (In Progress)

**Milestone Goal:** Full-stack observability — every payment event traceable from Loki logs through Tempo traces to Prometheus metrics without manual correlation.

- [x] **Phase 14: Logging Infrastructure** — JSON encoder, stdout-only, OTel trace correlation — completed 2026-03-26
- [ ] **Phase 15: MDC & Request Lifecycle** — Per-request MDC enrichment + structured HTTP lifecycle events
- [ ] **Phase 16: Business Event Logging** — Structured logs for all payment domain events
- [ ] **Phase 17: Code Standards Enforcement** — No interpolation, no flow logs, no PII

#### Phase 14: Logging Infrastructure
**Goal**: JSON-structured logs flow to stdout with OpenTelemetry trace correlation
**Depends on**: Phase 13 (v1 complete)
**Requirements**: LOG-INF-01, LOG-INF-02, LOG-INF-03
**Success Criteria** (what must be TRUE):
  1. Every log line is valid JSON parseable by Loki/Alloy
  2. Service identity fields (service, environment, version) appear in every log entry
  3. `traceId` and `spanId` appear as top-level JSON fields on every log entry
  4. No file appender active — all output goes to stdout only
**Plans**: TBD

Plans:
- [ ] 14-01: TBD

#### Phase 15: MDC & Request Lifecycle
**Goal**: Every HTTP request emits structured start/end events with full correlation context
**Depends on**: Phase 14
**Requirements**: LOG-MDC-01, LOG-MDC-02, LOG-REQ-01, LOG-REQ-02, LOG-REQ-03
**Success Criteria** (what must be TRUE):
  1. Every request log entry contains `requestId` and `tenantId` as top-level JSON fields
  2. Payment request logs contain `transactionId` and `externalReference` throughout the thread
  3. Structured `request_start` event logged at request entry with `event`, `operation`, `method`, `path`, `requestId`, `clientIp`
  4. Structured `request_end` event logged at completion with `event`, `durationMs`, `status`, `httpStatus`
  5. Structured `request_error` event logged for 5xx responses with `event`, `durationMs`, `errorCode`, `status=ERROR`
**Plans**: TBD

Plans:
- [ ] 15-01: TBD

#### Phase 16: Business Event Logging
**Goal**: All payment lifecycle events are observable in Loki with structured fields
**Depends on**: Phase 15
**Requirements**: LOG-BUS-01, LOG-BUS-02, LOG-BUS-03, LOG-BUS-04, LOG-BUS-05, LOG-BUS-06, LOG-BUS-07
**Success Criteria** (what must be TRUE):
  1. Payment initiations are queryable by `transactionId`, `provider`, `tenantId` in Loki
  2. Every transaction state change emits a log with `fromState`, `toState`, `actor`
  3. Inbound webhook receipt and outbound delivery each emit a dedicated structured log event
  4. Fraud evaluation results (`riskScore`, `blocked`) appear as structured fields in every evaluation log
  5. All MTN/Orange adapter HTTP calls log `externalService`, `externalLatencyMs`, and `status`
  6. Daily reconciliation run emits a single structured summary log with `discrepancyCount` and `status`
**Plans**: TBD

Plans:
- [ ] 16-01: TBD

#### Phase 17: Code Standards Enforcement
**Goal**: All log calls comply with structured field pattern — no interpolation, no flow logs, no PII
**Depends on**: Phase 16
**Requirements**: LOG-CODE-01, LOG-CODE-02, LOG-CODE-03
**Success Criteria** (what must be TRUE):
  1. All contextual data passes through structured field arguments, not string interpolation
  2. No code-flow log statements remain (no "entering", "processing", "step" style messages)
  3. `BodySanitizer` covers all payment fields — no tokens, full MSISDNs, or passwords appear in any log output
**Plans**: TBD

Plans:
- [ ] 17-01: TBD

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
| 15. MDC & Request Lifecycle | v2 | 0/TBD | Not started | - |
| 16. Business Event Logging | v2 | 0/TBD | Not started | - |
| 17. Code Standards Enforcement | v2 | 0/TBD | Not started | - |
