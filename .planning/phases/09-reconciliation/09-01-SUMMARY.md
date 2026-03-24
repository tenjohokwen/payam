---
phase: 09-reconciliation
plan: "01"
subsystem: reconciliation
tags: [quartz, reconciliation, mtn, orange, discrepancy, flyway, jpa]

dependency-graph:
  requires:
    - "03-orange-adapter: OrangeMoneyPort.getTransactionStatus() used by OrangeReportAdapter"
    - "04-mtn-adapter: MtnMoMoPort.getTransactionStatus() used by MtnReportAdapter"
    - "02-transaction-core: Transaction entity and TransactionRepository"
    - "05-payment-orchestration: TransactionStatus enum terminal states (SUCCESS, FAILED)"
  provides:
    - "ReconciliationReport + ReconciliationDiscrepancy entities (V12 schema)"
    - "ProviderReportPort abstraction with MTN + Orange implementations"
    - "Daily Quartz job at 02:00 UTC via CronScheduleBuilder"
    - "ReconciliationService: comparison engine producing discrepancy rows"
    - "LedgerSnapshotService: findForReconciliation JPQL query"
  affects:
    - "09-02: admin API will surface ReconciliationReport + ReconciliationDiscrepancy via new endpoints"

tech-stack:
  added: []
  patterns:
    - "QuartzJobBean pattern: executeInternal @Transactional (execute() is final)"
    - "ProviderReportPort: List<ProviderReportPort> injected; provider() method maps to EnumMap"
    - "Orange adapter: catch ALL exceptions including CallNotPermittedException; never propagate"
    - "Per-provider isolation: each provider loop in try/catch so one failure never aborts the other"
    - "TSID ID generation via @Tsid on BaseEntity; no sequences needed"

key-files:
  created:
    - src/main/resources/db/migration/V12__reconciliation_schema.sql
    - src/main/java/com/softropic/payam/reconciliation/ReconciliationModule.java
    - src/main/java/com/softropic/payam/reconciliation/contract/DiscrepancyType.java
    - src/main/java/com/softropic/payam/reconciliation/contract/DiscrepancySeverity.java
    - src/main/java/com/softropic/payam/reconciliation/repo/ReconciliationReport.java
    - src/main/java/com/softropic/payam/reconciliation/repo/ReconciliationDiscrepancy.java
    - src/main/java/com/softropic/payam/reconciliation/repo/ReconciliationReportRepository.java
    - src/main/java/com/softropic/payam/reconciliation/repo/ReconciliationDiscrepancyRepository.java
    - src/main/java/com/softropic/payam/reconciliation/port/ProviderReportPort.java
    - src/main/java/com/softropic/payam/reconciliation/port/ProviderTransactionRecord.java
    - src/main/java/com/softropic/payam/reconciliation/port/MtnReportAdapter.java
    - src/main/java/com/softropic/payam/reconciliation/port/OrangeReportAdapter.java
    - src/main/java/com/softropic/payam/reconciliation/service/LedgerSnapshotService.java
    - src/main/java/com/softropic/payam/reconciliation/service/ReconciliationService.java
    - src/main/java/com/softropic/payam/reconciliation/service/ReconciliationJob.java
    - src/main/java/com/softropic/payam/reconciliation/config/ReconciliationSchedulerConfig.java
    - src/test/java/com/softropic/payam/reconciliation/ReconciliationJobIT.java
  modified:
    - src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java

decisions:
  - id: "09-01-A"
    decision: "DiscrepancyType has no MISSING_IN_PAYAM — provider-side orphan detection impossible"
    rationale: "Neither Orange nor MTN expose a batch listing API; can only compare Payam-side transactions against provider"
  - id: "09-01-B"
    decision: "OrangeReportAdapter catches ALL exceptions including CallNotPermittedException"
    rationale: "Orange API unreachability must degrade to UNCONFIRMED, not crash; circuit-open is an expected failure mode"
  - id: "09-01-C"
    decision: "ProviderReportPort.provider() default method used for EnumMap wiring"
    rationale: "Cleaner than @Qualifier — each adapter self-declares its provider; ReconciliationService builds Map<MobilePaymentProvider, ProviderReportPort> from injected List"
  - id: "09-01-D"
    decision: "OrangeReportAdapter does NOT call OrangeTimeUtil.parseOrangeTimestamp — PayResponse has no createtime field"
    rationale: "P5.1 WAT compliance: WAT guard is only needed where Orange timestamps are parsed; PayResponse (reconciliation path) has no timestamp field; documented with comment in adapter"
  - id: "09-01-E"
    decision: "IT test uses System.nanoTime() & Long.MAX_VALUE for transaction ID seeding"
    rationale: "No sequence for BIGINT primary key — @Tsid generates IDs automatically; for direct JDBC inserts the WebhookDoubleCheckIT pattern (nanoTime) is used"
  - id: "09-01-F"
    decision: "Per-provider exception isolation in ReconciliationService.runForDate()"
    rationale: "Each provider loop wrapped in try/catch; one provider API failure must not abort reconciliation for other providers"

metrics:
  duration: "8m 15s"
  completed: "2026-03-24"
  tasks-completed: 3
  tests-added: 2
  files-created: 17
  files-modified: 1
---

# Phase 9 Plan 1: Reconciliation Infrastructure Summary

**One-liner:** Daily Quartz job (02:00 UTC cron) comparing Payam ledger against MTN/Orange provider records per-transaction, persisting discrepancy rows with UNCONFIRMED fallback on API unreachability.

## What Was Built

The core reconciliation pipeline: a daily Quartz job fires at 02:00 UTC and calls `ReconciliationService.runForDate(yesterday)`. For each of MTN and ORANGE, the service:

1. Queries `TransactionRepository.findForReconciliation()` — transactions with `SUCCESS/FAILED/PROCESSING` status and non-null `providerRef` created in the UTC day window
2. For each transaction, calls `ProviderReportPort.fetchProviderRecord()` — implemented by `MtnReportAdapter` (via `MtnMoMoPort.getTransactionStatus()`) and `OrangeReportAdapter` (via `OrangeMoneyPort.getTransactionStatus()`)
3. Detects discrepancies: `MISSING_IN_PROVIDER`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH`, or `UNCONFIRMED`
4. Persists `ReconciliationReport` and `ReconciliationDiscrepancy` rows

The Orange adapter catches ALL exceptions (including circuit-open `CallNotPermittedException`) and returns `unconfirmed=true` — never propagating to the service layer. Per-provider loops in `ReconciliationService` are individually try/catch isolated.

## Schema (V12)

Two new tables in schema `main`:
- `reconciliation_report` — one row per (report_date, provider) pair with `UNIQUE` constraint; tracks totalChecked/matched/discrepancies
- `reconciliation_discrepancy` — FK to report; records each detected mismatch with type, severity, and both-side status/amount

## Decisions Made

| ID | Decision | Rationale |
|----|----------|-----------|
| 09-01-A | `MISSING_IN_PAYAM` excluded from `DiscrepancyType` | No batch listing API from Orange or MTN |
| 09-01-B | `OrangeReportAdapter` catches ALL exceptions | UNCONFIRMED resilience; circuit-open expected |
| 09-01-C | `ProviderReportPort.provider()` method for wiring | Self-declaring adapters → clean EnumMap injection |
| 09-01-D | No `OrangeTimeUtil` in reconciliation path | `PayResponse` has no `createtime` field; P5.1 WAT compliance documented |
| 09-01-E | `System.nanoTime()` for IT test JDBC IDs | No sequence; @Tsid pattern from `WebhookDoubleCheckIT` |
| 09-01-F | Per-provider isolation in `runForDate()` | Single provider failure must not abort others |

## Deviations from Plan

None — plan executed exactly as written.

The IT test had one deviation in implementation detail: the plan described using `nextval('main.reconciliation_report_id_seq')` for transaction seeding, but no such sequence exists (IDs use `@Tsid`). Fixed automatically using the `System.nanoTime() & Long.MAX_VALUE` pattern established in `WebhookDoubleCheckIT`.

## Test Results

`ReconciliationJobIT` — 2/2 tests pass:
1. `runForDate_producesReportsWithCorrectCounts_whenProviderReturnsMatch` — verifies 2 reports (MTN + ORANGE) with `totalChecked=1`, `status=COMPLETE`
2. `runForDate_createsUnconfirmedDiscrepancy_whenOrangePortThrows` — verifies UNCONFIRMED discrepancy row when `OrangeMoneyPort.getTransactionStatus()` throws `RuntimeException`

## Next Phase Readiness

Phase 09-02 (reconciliation admin API) can proceed immediately:
- `ReconciliationReportRepository` and `ReconciliationDiscrepancyRepository` are available for query
- `ReconciliationDiscrepancyRepository.findByReportId()` ready for detail endpoint
- Both entities have all fields needed for admin display and export
