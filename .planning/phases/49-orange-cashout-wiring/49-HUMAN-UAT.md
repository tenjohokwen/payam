---
status: partial
phase: 49-orange-cashout-wiring
source: [49-VERIFICATION.md]
started: 2026-04-23T00:00:00.000Z
updated: 2026-04-23T00:00:00.000Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. OrangeMoneyPortIT cashout integration tests pass against live Docker

expected: Both `cashout_success_posts_disbursement_ledger` and `cashout_with_null_fee_posts_zero_fee_disbursement` pass; all 8 OrangeMoneyPortIT tests pass; 3 balanced ledger rows asserted per test
result: [pending]

Command: `mvn test -pl . -Dtest=OrangeMoneyPortIT`

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
