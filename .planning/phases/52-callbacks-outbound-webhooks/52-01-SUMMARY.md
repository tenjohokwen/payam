---
phase: 52-callbacks-outbound-webhooks
plan: "01"
subsystem: disbursement-callbacks-foundation
tags:
  - flyway-migration
  - disbursement
  - webhook
  - security
  - ip-whitelist
dependency_graph:
  requires:
    - "51-04: DisbursementOrchestrator, DisbursementRepository, V28 migration"
  provides:
    - "V29 poll_attempts column (DisbursementStatusPollerJob dependency)"
    - "V30 transaction_status column on webhook_delivery_log"
    - "DisbursementRepository polling queries (findProcessingDisbursementsForPolling, findByProviderRef, findByReference)"
    - "IP whitelist + public endpoint registration for /v1/callbacks/mtn/disbursement/* and /v1/callbacks/orange/disbursement"
    - "OutboundWebhookPayload.of() authoritative status factory (SEC-06)"
  affects:
    - "WebhookDeliveryService (status derivation fixed)"
    - "52-02: callback controllers depend on repository queries and path registration"
    - "52-03: DisbursementStatusPollerJob depends on poll_attempts column and findProcessingDisbursementsForPolling"
tech_stack:
  added: []
  patterns:
    - "FOR UPDATE SKIP LOCKED native query — same as TransactionRepository.findProcessingTransactionsSkipLocked"
    - "Flyway ADD COLUMN IF NOT EXISTS pattern for backward-safe migrations"
    - "TransactionStatus enum-driven status derivation (replaces eventType.contains() string check)"
    - "enqueue() sets transactionStatus on WebhookDeliveryLog for authoritative status persistence"
key_files:
  created:
    - src/main/resources/db/migration/V29__disbursement_poll_attempts.sql
    - src/main/resources/db/migration/V30__webhook_delivery_log_status.sql
    - src/test/java/com/softropic/payam/disbursement/repo/DisbursementRepositoryIT.java
    - src/test/java/com/softropic/payam/webhook/service/WebhookDeliveryServicePayloadTest.java
    - src/test/java/com/softropic/payam/security/config/AppEndpointsTest.java
    - src/test/java/com/softropic/payam/mtn/web/MtnWebConfigTest.java
    - src/test/java/com/softropic/payam/orange/web/OrangeWebConfigTest.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/repo/Disbursement.java
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java
    - src/main/java/com/softropic/payam/webhook/contract/OutboundWebhookPayload.java
    - src/main/java/com/softropic/payam/webhook/repo/WebhookDeliveryLog.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java
    - src/main/java/com/softropic/payam/security/config/AppEndpoints.java
    - src/main/java/com/softropic/payam/mtn/web/MtnWebConfig.java
    - src/main/java/com/softropic/payam/orange/web/OrangeWebConfig.java
decisions:
  - "Null-safe legacy fallback in attemptDeliveryInternal: pre-V30 rows without transactionStatus fall back to eventType.contains('SUCCESS') — avoids breaking in-flight retries during zero-downtime deploy"
  - "V30 backfill UPDATE derives from event_type for collection-era rows — consistent with how outbound webhook history looked before this fix"
  - "OutboundWebhookPayload.of() factory is additive (original record constructor preserved) — existing tests and callers requiring the direct constructor do not break"
metrics:
  duration: "23 minutes"
  completed_date: "2026-04-25"
  tasks_completed: 3
  tasks_total: 3
  files_created: 7
  files_modified: 8
  tests_added: 13
---

# Phase 52 Plan 01: Disbursement Callbacks Foundation Summary

V29 schema (poll_attempts), V30 webhook_delivery_log status column, DisbursementRepository polling queries (FOR UPDATE SKIP LOCKED), IP whitelist + public endpoint registration for disbursement callback paths, and OutboundWebhookPayload status-derivation fix (SEC-06: DISBURSEMENT_COMPLETED no longer silently maps to FAILED).

## Objective

Lay the foundation for Phase 52 disbursement callbacks: V29 schema, Disbursement entity field, repository queries, IP whitelist + public endpoint registration for new callback paths, and fix for OutboundWebhookPayload status-derivation so disbursement event types resolve correctly.

## Tasks Completed

### Task 1: V29 Flyway migration + Disbursement.pollAttempts + repository queries
**Commit:** `8cee1cf`

Created `V29__disbursement_poll_attempts.sql` adding `poll_attempts INTEGER NOT NULL DEFAULT 0` to `main.disbursement` and nullable `poll_attempts INTEGER` to `main.disbursement_aud`. Added `@Column(name = "poll_attempts", nullable = false) @Builder.Default Integer pollAttempts = 0` to `Disbursement` entity plus `incrementPollAttempts()` null-safe helper. Added three new methods to `DisbursementRepository`: `findProcessingDisbursementsForPolling` (native `FOR UPDATE SKIP LOCKED` with status/provider/cutoff/batchSize params), `findByProviderRef`, and `findByReference`. Created `DisbursementRepositoryIT` with 5 integration tests (Testcontainers, real PostgreSQL) — all green.

### Task 2: OutboundWebhookPayload status fix + WebhookDeliveryLog status field + WebhookDeliveryService refactor
**Commit:** `2071252`

**Bug fixed:** `WebhookDeliveryService.attemptDeliveryInternal()` used `eventType.contains("SUCCESS")` to derive payload status — `DISBURSEMENT_COMPLETED` does not contain "SUCCESS", so disbursement completions were sending `status="FAILED"` to tenants.

Created `V30__webhook_delivery_log_status.sql` adding `transaction_status VARCHAR(20)` to `main.webhook_delivery_log` with backfill of existing rows and audit table mirror. Added `@Enumerated(EnumType.STRING) @Column(name = "transaction_status") TransactionStatus transactionStatus` to `WebhookDeliveryLog` entity. Added static factory `OutboundWebhookPayload.of(txId, TransactionStatus, eventType, timestamp, externalReference, feeAmount)` — maps `SUCCESS` → `"SUCCESS"`, all other statuses → `"FAILED"`. Updated `WebhookDeliveryService.enqueue()` to persist `transactionStatus` on the new column; updated `attemptDeliveryInternal()` to use `OutboundWebhookPayload.of()` with null-safe legacy fallback for pre-V30 in-flight rows. Created `WebhookDeliveryServicePayloadTest` with 5 unit tests proving SEC-06 disbursement types resolve correctly.

### Task 3: AppEndpoints + MtnWebConfig + OrangeWebConfig path registration
**Commit:** `936d9f8`

Added `/v1/callbacks/mtn/disbursement/*` and `/v1/callbacks/orange/disbursement` to `AppEndpoints.PUBLIC_ENDPOINTS`. Updated `MtnWebConfig.addInterceptors()` to register `MtnIpWhitelistInterceptor` for both `/v1/callbacks/mtn` and `/v1/callbacks/mtn/disbursement/*`. Updated `OrangeWebConfig.addInterceptors()` to register `OrangeIpWhitelistInterceptor` for both `/v1/callbacks/orange` and `/v1/callbacks/orange/disbursement`. Created 3 unit tests (`AppEndpointsTest`, `MtnWebConfigTest`, `OrangeWebConfigTest`) — 5 test methods all green.

## Deviations from Plan

None — plan executed exactly as written.

## Decisions Made

1. **Null-safe legacy fallback in `attemptDeliveryInternal`:** Pre-V30 rows without `transactionStatus` populated fall back to `eventType.contains("SUCCESS")` — avoids breaking in-flight webhook retries during a zero-downtime deployment window. New rows always have `transactionStatus` set via `enqueue()`.

2. **V30 backfill UPDATE:** Derives status from `event_type LIKE '%SUCCESS%'` for pre-V30 collection-era rows so old rows serialize correctly if the retry job re-attempts them after the migration.

3. **`OutboundWebhookPayload.of()` is additive:** The original record canonical constructor is preserved — existing direct-constructor usages in tests remain valid without modification.

## Test Coverage

| Test Class | Type | Count | Green |
|-----------|------|-------|-------|
| DisbursementRepositoryIT | Integration (Testcontainers) | 5 | 5/5 |
| WebhookDeliveryServicePayloadTest | Unit | 5 | 5/5 |
| AppEndpointsTest | Unit | 3 | 3/3 |
| MtnWebConfigTest | Unit | 1 | 1/1 |
| OrangeWebConfigTest | Unit | 1 | 1/1 |
| **Total** | | **15** | **15/15** |

Note: 13 per plan spec (5 + 5 + 3); 2 additional web config tests bring total to 15.

## Known Stubs

None — all new code is fully wired.

## Self-Check: PASSED

- V29 migration: FOUND
- V30 migration: FOUND
- DisbursementRepositoryIT: FOUND
- WebhookDeliveryServicePayloadTest: FOUND
- AppEndpointsTest: FOUND
- MtnWebConfigTest: FOUND
- OrangeWebConfigTest: FOUND
- Commit 8cee1cf: FOUND
- Commit 2071252: FOUND
- Commit 936d9f8: FOUND
