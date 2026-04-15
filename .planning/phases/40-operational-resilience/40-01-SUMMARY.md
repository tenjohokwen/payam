---
phase: 40-operational-resilience
plan: "01"
subsystem: mtn-poller, orange-poller
tags: [ops, transaction-timeout, advisory-lock, quartz, resilience]
dependency_graph:
  requires: []
  provides: [OPS-01]
  affects: [MtnStatusPollerJob, OrangeStatusPollerJob]
tech_stack:
  added: []
  patterns: ["@Transactional(timeout = N) to bound advisory lock hold time"]
key_files:
  created:
    - src/test/java/com/softropic/payam/mtn/service/MtnStatusPollerJobTimeoutTest.java
    - src/test/java/com/softropic/payam/orange/service/OrangeStatusPollerJobTimeoutTest.java
  modified:
    - src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java
    - src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java
decisions:
  - "@Transactional(timeout=300) chosen to match the 5-minute Quartz re-fire interval — a tick cannot outlast its successor"
  - "Constant named POLLER_TRANSACTION_TIMEOUT_SECONDS (not _MILLIS) to prevent the common milliseconds confusion"
  - "Reflection-based tests chosen over integration tests — no Spring context needed; tests are fast and authoritative"
metrics:
  duration: "5 minutes"
  completed: "2026-04-15"
  tasks_completed: 2
  files_changed: 4
---

# Phase 40 Plan 01: Operational Resilience — Poller Transaction Timeout Summary

**One-liner:** `@Transactional(timeout=300)` added to MTN and Orange poller executeInternal, bounding pg_try_advisory_xact_lock hold time to 300 seconds and closing OPS-01.

## What Was Built

Two one-line production changes and two reflection-based unit tests:

- `MtnStatusPollerJob.executeInternal` annotated `@Transactional(timeout = POLLER_TRANSACTION_TIMEOUT_SECONDS)` with constant `= 300`.
- `OrangeStatusPollerJob.executeInternal` annotated `@Transactional(timeout = POLLER_TRANSACTION_TIMEOUT_SECONDS)` with constant `= 300`.
- `MtnStatusPollerJobTimeoutTest`: reflection test pinning timeout = 300 on MTN poller.
- `OrangeStatusPollerJobTimeoutTest`: reflection test pinning timeout = 300 on Orange poller.

## Why This Matters

Before this change, both poller jobs declared bare `@Transactional` with no timeout. A node crash, hung provider HTTP call, or pathologically slow batch would hold `pg_try_advisory_xact_lock` indefinitely — potentially much longer than the 5-minute Quartz re-fire interval — blocking all other nodes from running the poller.

After this change: when the 300-second budget is exceeded, Postgres raises `57014 query_canceled`, Spring rolls back the transaction, and the transaction-level advisory lock is released automatically. No indefinite lock hold is possible.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Add 300-second transaction timeout to both poller jobs | 345beff | MtnStatusPollerJob.java, OrangeStatusPollerJob.java |
| 2 | Add reflection-based unit tests pinning the timeout | 8342762 | MtnStatusPollerJobTimeoutTest.java, OrangeStatusPollerJobTimeoutTest.java |

## Verification

- `grep -c "POLLER_TRANSACTION_TIMEOUT_SECONDS = 300" MtnStatusPollerJob.java OrangeStatusPollerJob.java` — both return 1.
- `@Transactional(timeout = POLLER_TRANSACTION_TIMEOUT_SECONDS)` present on `executeInternal` in both files.
- `mvn -q -Dtest='MtnStatusPollerJobTimeoutTest,OrangeStatusPollerJobTimeoutTest' test` — both tests pass (Tests run: 2, Failures: 0, Errors: 0).
- `mvn -q -DskipTests compile` — exits 0.

## Requirements Closed

- **OPS-01**: Both poller transactions carry `@Transactional(timeout = 300)`. A crashed node cannot hold `pg_try_advisory_xact_lock` for longer than 300 seconds — the transaction rolls back and Postgres releases the advisory lock automatically.

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None.

## Self-Check: PASSED

- `src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java` — FOUND (modified)
- `src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java` — FOUND (modified)
- `src/test/java/com/softropic/payam/mtn/service/MtnStatusPollerJobTimeoutTest.java` — FOUND (created)
- `src/test/java/com/softropic/payam/orange/service/OrangeStatusPollerJobTimeoutTest.java` — FOUND (created)
- Commit 345beff — production changes
- Commit 8342762 — test files
