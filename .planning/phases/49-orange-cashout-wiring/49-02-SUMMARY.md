---
phase: 49-orange-cashout-wiring
plan: "02"
subsystem: payments
tags: [java, spring-boot, orange-money, cashout, ledger, disbursement, integration-tests, testcontainers]

# Dependency graph
requires:
  - phase: 49-orange-cashout-wiring/49-01
    provides: PaymentCommand.feeAmount() (nullable BigDecimal) populated by PaymentOrchestrator via FeeEvaluationService
  - phase: 47-contract-types-ledgerservice-rewrite
    provides: LedgerService.postEntry(txId, tenantId, LedgerPosting) + LedgerPosting.disbursement() factory
  - phase: 46-flyway-v25-schema-migration
    provides: V25 balance-check trigger accepting amount >= 0 (zero-fee PROVIDER_FEE credit allowed)
provides:
  - OrangeMoneyPort.initiateCashout(cmd) — working implementation (CASHOUT-02)
  - Orange /cashout provider call wired via OrangeMoneyClient.cashout(token, CashoutRequest)
  - Disbursement ledger entry posted on provider 2xx via LedgerPosting.disbursement + transactionTemplate.execute
  - Null feeAmount fallback to BigDecimal.ZERO (zero-fee disbursement produces 3 balanced rows)
  - LedgerService constructor-injected as 9th parameter into OrangeMoneyPort
  - Two new OrangeMoneyPortIT tests replacing the UnsupportedOperationException stub test
affects: [v9-milestone-completion, phase-50-if-any, cashout-e2e-tests]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "TransactionTemplate.execute (not @Transactional) for ledger writes inside non-transactional provider HTTP methods — consistent with initiateMerchantPayment pattern"
    - "Null-safe fee: cmd.feeAmount() != null ? cmd.feeAmount() : BigDecimal.ZERO — prevents LedgerPosting compact constructor NPE"
    - "isEqualByComparingTo for BigDecimal assertions — scale-insensitive; avoids BigDecimal.equals scale mismatch false failures"
    - "compareTo(BigDecimal.ZERO) == 0 for zero-fee assertion — correct: BigDecimal(0) vs BigDecimal(0.00) would fail with .equals()"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java
    - src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java

key-decisions:
  - "No @Transactional on initiateCashout — consistent with initiateMerchantPayment: outbound HTTP must not hold DB connection; only the ledger write is wrapped in TransactionTemplate.execute"
  - "No @CircuitBreaker/@Retry on initiateCashout — consistent with stub predecessor; can be added when real cashout HTTP semantics confirmed (out of scope v9)"
  - "cmd.feeAmount() null-check inline (ternary) rather than in LedgerPosting — LedgerPosting compact constructor already validates fee >= 0; null would throw; ternary is the right interception point"
  - "tearDown adds ledger_entry DELETE before payment_event_log — FK-safe order: ledger_entry has no FK to payment_event_log but both have FK to transaction; ledger_entry must be deleted before transaction"

patterns-established:
  - "Constructor injection for services added to ports: append as Nth parameter, no Spring config changes needed (auto-wires by type)"
  - "Integration test tearDown: always delete child tables before parent tables in FK dependency order"

requirements-completed: [CASHOUT-02]

# Metrics
duration: ~45min (including Docker startup wait)
completed: 2026-04-22
---

# Phase 49 Plan 02: Orange Cashout Wiring — Provider Call + Disbursement Ledger Summary

**OrangeMoneyPort.initiateCashout wired to POST /cashout + transactionTemplate-scoped LedgerPosting.disbursement with null-fee fallback; 8 OrangeMoneyPortIT tests green including 2 new cashout integration tests asserting 3 balanced ledger rows**

## Performance

- **Duration:** ~45 min (including Docker Desktop startup wait of ~60s)
- **Started:** 2026-04-22T07:45:00Z
- **Completed:** 2026-04-22T08:30:00Z
- **Tasks:** 3 (Task 1: implementation, Task 2: tests, Task 3: full regression gate)
- **Files modified:** 2

## Accomplishments

- Replaced `UnsupportedOperationException` stub in `OrangeMoneyPort.initiateCashout` with working implementation: get bearer token, strip country code, build `CashoutRequest`, POST to Orange `/cashout` via `OrangeMoneyClient`, and on HTTP 2xx post a `LedgerPosting.disbursement(principal, fee, currency)` inside a `transactionTemplate.execute` block (no `@Transactional` on the method)
- Null `feeAmount` on `PaymentCommand` falls back to `BigDecimal.ZERO`, producing a 3-row disbursement group where the `PROVIDER_FEE` credit row has amount zero — satisfies the V25 balance-check trigger because `gross (principal + 0) == principal == sum(CREDITs)`
- `LedgerService` injected as 9th constructor parameter (Spring auto-wires by type; no config changes)
- Deleted `initiateCashout_throws_UnsupportedOperationException` test (stub resolved); replaced with two integration tests asserting real ledger DB state via `LedgerEntryRepository.findByTransactionId`
- Extended `OrangeMoneyPortIT.tearDown()` to `DELETE FROM main.ledger_entry` before `payment_event_log` (FK-safe order)
- Full `mvn verify`: 312 unit tests (0 failures) + 224 integration tests (1 pre-existing failure: `LedgerConstraintIT.flowColumn_existsAndIsNullable`)

## Final OrangeMoneyPort.initiateCashout Implementation (lines 167–194)

```java
public ProviderResult initiateCashout(PaymentCommand cmd) {
    String token = orangeTokenService.getAccessToken();
    String nationalMsisdn = stripCountryCode(cmd.msisdn());
    String merchantKey = platformConfigService.findByProvider("ORANGE").platformMsisdn();

    CashoutRequest request = new CashoutRequest();
    request.setMerchantKey(merchantKey);
    request.setAmount(cmd.amount().toPlainString());
    request.setCurrency(cmd.currency());
    request.setReference(cmd.externalReference() != null ? cmd.externalReference() : cmd.transactionId());
    request.setMsisdn(nationalMsisdn);

    ResponseEntity<Map> response = orangeMoneyClient.cashout(token, request);

    if (response.getStatusCode().is2xxSuccessful()) {
        BigDecimal fee = cmd.feeAmount() != null ? cmd.feeAmount() : BigDecimal.ZERO;
        transactionTemplate.execute(status -> {
            ledgerService.postEntry(
                cmd.transactionId(),
                cmd.tenantId(),
                LedgerPosting.disbursement(cmd.amount(), fee, cmd.currency())
            );
            return null;
        });
        return ProviderResult.success(null, "CASHOUT_SUCCESS");
    }
    return ProviderResult.pending(null, "CASHOUT_PENDING");
}
```

No `@Transactional` on this method — confirmed by `grep -B2 "public ProviderResult initiateCashout"` showing no annotation before the method.

## New Test Methods in OrangeMoneyPortIT.java

| Method | Lines | What it asserts |
|--------|-------|-----------------|
| `cashout_success_posts_disbursement_ledger` | 186–232 | WireMock 200, feeAmount=50: 3 rows (DEBIT MERCHANT_WALLET 550, CREDIT CUSTOMER_WALLET 500, CREDIT PROVIDER_FEE 50), shared entryGroupId, currency XAF |
| `cashout_with_null_fee_posts_zero_fee_disbursement` | 234–278 | WireMock 200, 13-arg ctor (feeAmount=null): 3 rows (DEBIT MERCHANT_WALLET 500, CREDIT CUSTOMER_WALLET 500, CREDIT PROVIDER_FEE 0), `compareTo(ZERO) == 0` check |

## mvn verify Result

- **Surefire (unit tests):** `Tests run: 312, Failures: 0, Errors: 0` — BUILD SUCCESS
- **Failsafe (integration tests):** `Tests run: 224, Failures: 1, Errors: 0`
  - 1 pre-existing failure: `LedgerConstraintIT.flowColumn_existsAndIsNullable` — expected `20`, got `255`
  - This failure first appeared in Phase 46 and was documented in Phase 48-02-SUMMARY as pre-existing and out-of-scope
  - Root cause: V25 migration declares `flow VARCHAR(20)` but Testcontainers Postgres reports `character_maximum_length = 255`; our phase 49-02 changes do not touch schema migrations or production source affecting this column

## Task Commits

Each task was committed atomically:

1. **Task 1: Rewrite OrangeMoneyPort.initiateCashout with provider call + ledger posting** - `418d1d1` (feat)
2. **Task 2: Replace stub cashout test with success + null-fee integration tests** - `5f117a8` (test)

## Files Created/Modified

- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` — Added `CashoutRequest`, `LedgerPosting`, `LedgerService`, `ResponseEntity`, `BigDecimal`, `Map` imports; added `LedgerService ledgerService` field as 9th constructor parameter; replaced `initiateCashout` stub body with working provider call + disbursement ledger post
- `src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java` — Added `LedgerDirection`, `LedgerEntry`, `LedgerEntryRepository`, `List` imports; added `@Autowired LedgerEntryRepository`; extended tearDown; removed stub test; added 2 new cashout integration tests

## Decisions Made

- No `@Transactional` on `initiateCashout` — outbound HTTP must not hold a DB connection (same rule as `initiateMerchantPayment`); only the ledger write is wrapped in `transactionTemplate.execute`
- No `@CircuitBreaker`/`@Retry` on `initiateCashout` — consistent with predecessor stub; out of scope for v9; adds no value before real Orange cashout HTTP semantics are proven against sandbox
- Null `feeAmount` ternary in `initiateCashout` rather than inside `LedgerPosting` — `LedgerPosting` compact constructor validates `fee >= 0` and rejects null; the caller (OrangeMoneyPort) is the right place for the null-to-ZERO coercion
- `tearDown` ledger_entry DELETE before payment_event_log — both reference `main.transaction`; ledger_entry has no FK to payment_event_log, but transaction row must exist when both child tables are populated; correct FK-safe order is ledger_entry → payment_event_log → transaction

## Deviations from Plan

None — plan executed exactly as written. Docker daemon was not running at execution start; opened Docker Desktop, waited for it to start, then ran tests. Not a code deviation.

## Issues Encountered

- Docker Desktop was not running when tests were first attempted. Opened it via `open -a Docker`, waited ~60 seconds for the daemon to start, then all tests ran successfully. Pre-existing `LedgerConstraintIT.flowColumn_existsAndIsNullable` failure noted and documented — not introduced by this phase.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- CASHOUT-01 (Plan 01) + CASHOUT-02 (this plan) together complete the v9 milestone requirements
- `OrangeMoneyPort.initiateCashout` is fully wired: provider call + disbursement ledger entry on success
- `mvn verify` is green modulo the one pre-existing schema-column-size failure in `LedgerConstraintIT` (documented in Phase 48 and Phase 46 as out of scope)
- v9 milestone: all CASHOUT requirements (CASHOUT-01, CASHOUT-02) satisfied

---

**v9 ledger disbursement support: CASHOUT-01 + CASHOUT-02 complete**

*Phase: 49-orange-cashout-wiring*
*Completed: 2026-04-22*
