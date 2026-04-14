---
phase: 37-webhook-subsystem-fixes
plan: "01"
subsystem: webhook
tags: [n+1-fix, bulk-load, query-count, quartz, integration-test]
dependency_graph:
  requires: []
  provides: [WEBHOOK-01-fix]
  affects: [WebhookDeliveryService, WebhookDeliveryJob]
tech_stack:
  added: []
  patterns:
    - Bulk findAllById before loop (N+1 prevention)
    - Two-arg service overload for pre-loaded entity
key_files:
  created:
    - src/test/java/com/softropic/payam/webhook/WebhookDeliveryJobIT.java
  modified:
    - src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java
decisions:
  - Keep single-arg attemptDelivery(WebhookDeliveryLog) to avoid breaking existing callers and enqueue path
  - New two-arg overload trusts the caller-supplied Tenant — no tenantRepository.findById inside
  - QueryCountVerifier reset immediately before loadTenants() to isolate that SELECT cost precisely
metrics:
  duration_seconds: 722
  completed_date: "2026-04-14"
  tasks_completed: 2
  tasks_total: 2
  files_created: 1
  files_modified: 2
---

# Phase 37 Plan 01: Webhook N+1 Tenant Query Fix Summary

## One-liner

Bulk-load tenant map once per Quartz tick via `findAllById` IN-clause, replacing per-delivery `findById` loop to eliminate N+1 tenant queries (WEBHOOK-01).

## What Was Built

### Task 1: WebhookDeliveryService + WebhookDeliveryJob

Added `loadTenants(Set<Long> tenantIds)` to `WebhookDeliveryService` — wraps `tenantRepository.findAllById(tenantIds)` and returns `Map<Long, Tenant>`. Added `attemptDelivery(WebhookDeliveryLog, Tenant)` two-arg overload that trusts the pre-loaded tenant and calls `attemptDeliveryInternal` directly with no `findById` call.

Updated `WebhookDeliveryJob.runDelivery()` to: collect distinct `tenantId` values from pending deliveries, call `deliveryService.loadTenants(tenantIds)` once before the loop, then iterate using the two-arg `attemptDelivery(delivery, tenantMap.get(...))` overload. Added early-return guard on empty pending list.

The original `attemptDelivery(WebhookDeliveryLog)` single-arg overload is retained — the `enqueue()` path and existing callers depend on it.

### Task 2: WebhookDeliveryJobIT

Created new integration test `WebhookDeliveryJobIT` with two test methods:

- `loadTenants_bulkFetchesAllInOneSelect`: seeds 3 tenants, calls `loadTenants(ids)` directly, asserts map has 3 entries and `assertSelectCountAtMost(1)`.
- `jobTickPath_oneTenantSelectPerTickAcrossNDeliveries`: mirrors the job flow — `findPendingDeliveries()` then `loadTenants(tenantIds)` — asserts `assertSelectCountAtMost(1)` with the query counter reset immediately before the bulk load.

Uses `@TestPropertySource(properties = {"log.database.spy=true", "datasource.container=true"})` to activate the datasource-proxy `QueryCountVerifier` infrastructure.

## Verification Results

- `mvn verify -Dit.test=WebhookDeliveryJobIT`: 2/2 tests pass, both `assertSelectCountAtMost(1)` assertions satisfied
- `mvn verify -Dit.test=WebhookDeliveryIT`: 3/3 existing tests still pass — enqueue path unaffected
- `mvn compile -pl . -q`: exits 0

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1    | 47f9252 | feat(37-01): eliminate N+1 tenant query in WebhookDeliveryJob (WEBHOOK-01) |
| 2    | 9dd4edf | test(37-01): add WebhookDeliveryJobIT N+1 regression test (WEBHOOK-01) |

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None.

## Self-Check: PASSED

- `/Users/mokwen/dev/gitrepos/bluegithub/payam/src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java` — exists, contains `loadTenants` and two-arg `attemptDelivery`
- `/Users/mokwen/dev/gitrepos/bluegithub/payam/src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java` — exists, contains `deliveryService.loadTenants` and `tenantMap.get`
- `/Users/mokwen/dev/gitrepos/bluegithub/payam/src/test/java/com/softropic/payam/webhook/WebhookDeliveryJobIT.java` — exists, 2 test methods, both pass
- Commit 47f9252 — confirmed in git log
- Commit 9dd4edf — confirmed in git log
