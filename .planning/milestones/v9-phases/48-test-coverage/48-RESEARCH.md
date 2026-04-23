# Phase 48: Test Coverage - Research

**Researched:** 2026-04-22
**Domain:** JUnit 5 / AssertJ / Mockito / PITest / Testcontainers — unit and integration test authoring for double-entry ledger
**Confidence:** HIGH

---

## Summary

Phase 48 completes the v9 test suite for the ledger disbursement support milestone. All production code required by v9 is already committed (Phases 46 and 47): `LedgerFlow`, `LedgerPosting`, and the switch-routed `LedgerService.postEntry()` are live; the V25 balance-check trigger is active; `Transaction.flow` and `getEffectiveFlow()` exist. Phase 48 is a pure test-authoring phase — no production source changes.

The seven requirements divide into three groups: (1) unit tests for `LedgerService` COLLECTION and DISBURSEMENT paths (TEST-01, TEST-02, TEST-03) — extending `LedgerBalanceGuardTest` with a disbursement case and adding a new `LedgerServiceTest` (or extending the existing guard class); (2) `LedgerPosting` compact-constructor rejection tests (TEST-04) — the bulk of this work already exists in `LedgerPostingTest.java`, which must be audited and completed; (3) integration test for a real disbursement group persisted via Testcontainers PostgreSQL (TEST-06) — extending `LedgerServiceIT.java`; and (4) `LedgerVerifier` disbursement helper method (TEST-07). TEST-05 is the PITest gate: `LedgerBalanceGuardTest` must exercise `LedgerService` disbursement paths so PITest's MUT-02 target class (`LedgerService`) achieves the 90% mutation kill threshold.

**Primary recommendation:** All unit tests go in `LedgerBalanceGuardTest` (PITest `targetTests` is `com.softropic.payam.domain.*`) or a sibling in that package. Do NOT put new unit tests in a different package or PITest will miss them. The IT extension goes in the existing `LedgerServiceIT.java`. `LedgerVerifier` gets one new public method.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TEST-01 | Unit test: COLLECTION flow → exactly 2 entries, balanced, correct account codes | `LedgerBalanceGuardTest.postEntry_createsBalancedDoubleEntry()` already covers size=2 and balance; must add explicit account-code assertions for `CUSTOMER_WALLET` / `PROVIDER_CLEARING` — these already exist in current test |
| TEST-02 | Unit test: DISBURSEMENT flow fee > 0 → exactly 3 entries, gross = principal + fee, balanced | No disbursement unit test exists in `LedgerBalanceGuardTest`; must be added there (PITest target package) |
| TEST-03 | Unit test: DISBURSEMENT flow fee = 0 → exactly 3 entries including zero-amount `PROVIDER_FEE`, balanced | No zero-fee disbursement unit test exists; must be added alongside TEST-02 |
| TEST-04 | Unit test: `LedgerPosting` compact constructor rejects negative principal, negative fee, null currency, null flow | `LedgerPostingTest.java` exists and covers all 4 rejection cases (`constructor_rejectsNegativePrincipal`, `constructor_rejectsNegativeFee`, `constructor_rejectsNullCurrency`, `constructor_rejectsNullFlow`); audit confirms they are already present — TEST-04 is already satisfied by existing tests |
| TEST-05 | `LedgerBalanceGuardTest` updated with disbursement case to maintain PITest MUT-02 kill rate ≥ 90% | PITest config targets `com.softropic.payam.transaction.service.LedgerService` and runs tests in `com.softropic.payam.domain.*`; adding disbursement tests to `LedgerBalanceGuardTest` directly satisfies this |
| TEST-06 | `LedgerServiceIT` integration test: disbursement group of 3 rows persisted in real PostgreSQL via Testcontainers; no constraint violation; amounts balanced | `LedgerServiceIT.java` exists with Testcontainers setup; two collection tests are present; need to add a disbursement test method that calls `LedgerPosting.disbursement()` and verifies 3 rows, amounts, and `entry_group_id` grouping |
| TEST-07 | `LedgerVerifier.assertDisbursementLedgerBalanced(txId, principal, fee)` added; existing `assertLedgerBalanced` unchanged | `LedgerVerifier.java` exists with `assertLedgerBalanced` (collection) and `assertEntryCount` helpers; add `assertDisbursementLedgerBalanced` following the same JDBC query pattern |
</phase_requirements>

---

## Current State Inventory — What Already Exists

This is a test-authoring phase. Understanding what already exists is critical to avoid duplication and to identify the exact gaps.

### TEST-01 (COLLECTION unit test)

`LedgerBalanceGuardTest.postEntry_createsBalancedDoubleEntry()` at
`src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java`:

- Calls `LedgerPosting.collection(amount, "XAF")` — already migrated to v9 signature
- Asserts `hasSize(2)` — satisfies "exactly 2 entries"
- Asserts `debit.getAmount().isEqualByComparingTo(credit.getAmount())` — satisfies "balanced"
- Asserts `debit.getAccountCode().isEqualTo("CUSTOMER_WALLET")` and `credit.getAccountCode().isEqualTo("PROVIDER_CLEARING")` — satisfies "correct account codes"

**Finding:** TEST-01 is ALREADY SATISFIED by the existing `LedgerBalanceGuardTest`. The planner should verify this is correct before adding a new test, but no new test is needed for TEST-01.

### TEST-04 (LedgerPosting rejection tests)

`LedgerPostingTest.java` at `src/test/java/com/softropic/payam/transaction/contract/LedgerPostingTest.java` already has:
- `constructor_rejectsNullFlow` — covers null flow
- `constructor_rejectsNullCurrency` — covers null currency
- `constructor_rejectsNullPrincipal` — covers null principal (NullPointerException path)
- `constructor_rejectsNegativePrincipal` — covers negative principal
- `constructor_rejectsNegativeFee` — covers negative fee

TEST-04 requires "negative principal, negative fee, null currency, and null flow — four distinct rejection cases". All four are present. **TEST-04 is ALREADY SATISFIED.**

Note: the requirement says "four distinct rejection cases" — null principal, negative principal, null fee, and negative fee each cause rejection via `compareTo(ZERO)`. Null is caught by the null-short-circuit in `principal == null || principal.compareTo(ZERO) <= 0` which throws NPE before reaching the compareTo if null. The tests cover all branches.

### TEST-02, TEST-03, TEST-05 (DISBURSEMENT unit tests — MISSING)

No disbursement test exists in `com.softropic.payam.domain.*` (PITest's target test package). These must be added to `LedgerBalanceGuardTest` or a sibling class in `com.softropic.payam.domain`.

### TEST-06 (LedgerServiceIT disbursement — MISSING)

`LedgerServiceIT.java` has two collection tests (`postEntry_insertsTwoRows_debitAndCredit`, `postEntry_balancedCheck`) but no disbursement test. A third test method must be added.

### TEST-07 (LedgerVerifier.assertDisbursementLedgerBalanced — MISSING)

`LedgerVerifier.java` has `assertLedgerBalanced` (collection: 2 entries, CUSTOMER_WALLET + PROVIDER_CLEARING), `assertEntryCount`, and `assertNoLedgerEntries`. The `assertDisbursementLedgerBalanced(txId, principal, fee)` method is absent.

---

## Architecture Patterns

### PITest Configuration (CRITICAL)

PITest is configured in `pom.xml` under the `mutation-coverage` profile:

```xml
<targetClasses>
    ...
    <param>com.softropic.payam.transaction.service.LedgerService</param>
    ...
</targetClasses>
<targetTests>
    <param>com.softropic.payam.domain.*</param>
</targetTests>
<mutationThreshold>90</mutationThreshold>
```

**The `targetTests` glob is `com.softropic.payam.domain.*`** — only tests in that package are used by PITest to kill mutations. New disbursement unit tests MUST be in `com.softropic.payam.domain.LedgerBalanceGuardTest` (or a sibling class in that package like `LedgerDisbursementGuardTest`). Tests placed anywhere else will not kill LedgerService mutations.

Recommended approach: add the disbursement tests to the existing `LedgerBalanceGuardTest` class. The class Javadoc already identifies it as "MUT-02: Ledger balance == debit amount check mutation kill."

### LedgerBalanceGuardTest Pattern (for TEST-02, TEST-03)

The existing test uses:
1. `mock(LedgerEntryRepository.class)` — Mockito mock, no Spring context
2. `new LedgerService(repo)` — real LedgerService, constructor-injected
3. `ArgumentCaptor<List<LedgerEntry>>` — captures the list passed to `saveAll()`
4. AssertJ assertions on the captured list

For disbursement (3 entries), the pattern is the same but assertions change:
- `hasSize(3)` instead of 2
- One DEBIT entry and two CREDIT entries — must filter by direction
- DEBIT account code = `MERCHANT_WALLET`, amount = principal + fee
- CREDIT entries: one `CUSTOMER_WALLET` = principal, one `PROVIDER_FEE` = fee (or zero)
- All three share the same `entryGroupId`
- Sum of CREDITs equals DEBIT amount

```java
// Pattern for TEST-02 (disbursement, fee > 0)
@Test
@SuppressWarnings("unchecked")
void postEntry_disbursement_createsThreeBalancedEntries() {
    LedgerEntryRepository repo = mock(LedgerEntryRepository.class);
    LedgerService service = new LedgerService(repo);

    BigDecimal principal = new BigDecimal("1000.00");
    BigDecimal fee = new BigDecimal("50.00");
    BigDecimal gross = principal.add(fee); // 1050.00

    service.postEntry("txn-disb-001", 1L,
        LedgerPosting.disbursement(principal, fee, "XAF"));

    ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
    verify(repo).saveAll(captor.capture());

    List<LedgerEntry> entries = captor.getValue();

    assertThat(entries).hasSize(3);

    LedgerEntry debit = entries.stream()
        .filter(e -> e.getDirection() == LedgerDirection.DEBIT)
        .findFirst().orElseThrow();

    List<LedgerEntry> credits = entries.stream()
        .filter(e -> e.getDirection() == LedgerDirection.CREDIT)
        .toList();

    assertThat(credits).hasSize(2);

    // Gross debit = principal + fee
    assertThat(debit.getAmount()).isEqualByComparingTo(gross);
    assertThat(debit.getAccountCode()).isEqualTo("MERCHANT_WALLET");

    // All share same entry group
    String groupId = debit.getEntryGroupId();
    assertThat(credits).allMatch(e -> e.getEntryGroupId().equals(groupId));

    // Credit sum balances gross debit
    BigDecimal creditSum = credits.stream()
        .map(LedgerEntry::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(creditSum).isEqualByComparingTo(gross);

    // Account codes
    assertThat(credits).anyMatch(e -> "CUSTOMER_WALLET".equals(e.getAccountCode())
        && e.getAmount().compareTo(principal) == 0);
    assertThat(credits).anyMatch(e -> "PROVIDER_FEE".equals(e.getAccountCode())
        && e.getAmount().compareTo(fee) == 0);
}
```

For TEST-03 (fee = 0): same structure, `fee = BigDecimal.ZERO`, gross = principal, PROVIDER_FEE credit has `amount.compareTo(BigDecimal.ZERO) == 0`.

### LedgerServiceIT Pattern (for TEST-06)

The existing IT uses:
1. `@SpringBootTest` + `@Import(TestConfig.class)` — starts full Spring context with Testcontainers PostgreSQL + Redis
2. `@BeforeEach` seeds JWT sec row, creates tenant, creates transaction row (FK required for ledger_entry)
3. `@AfterEach` deletes all seeded rows in dependency order
4. `ledgerEntryRepository.findByTransactionId(transactionId)` — queries back persisted rows

For the disbursement IT test:
- Call `ledgerService.postEntry(transactionId, tenantId, LedgerPosting.disbursement(principal, fee, "XAF"))`
- Assert `findByTransactionId` returns 3 entries
- Assert exactly one DEBIT (`MERCHANT_WALLET`, gross amount)
- Assert exactly two CREDITs (`CUSTOMER_WALLET` + `PROVIDER_FEE`)
- Assert all share same `entryGroupId`
- Assert `debitSum == creditSum` (balance)
- No need to assert trigger behavior — that is `LedgerConstraintIT`'s domain; just confirm 3 rows committed successfully (no exception thrown)

The V25 deferrable trigger will silently succeed for a balanced 3-entry group — this is already proven by `LedgerConstraintIT.threeEntryDisbursementGroup_succeeds()`. The IT for TEST-06 confirms the service-level path (via `LedgerPosting.disbursement()`) also works end-to-end.

### LedgerVerifier.assertDisbursementLedgerBalanced Pattern (for TEST-07)

Follows the existing `assertLedgerBalanced` pattern using `JdbcTemplate`:

```java
public void assertDisbursementLedgerBalanced(String transactionId,
                                              BigDecimal principal,
                                              BigDecimal fee) {
    List<Map<String, Object>> entries = jdbc.queryForList(
        "SELECT direction, account_code, amount FROM main.ledger_entry WHERE transaction_id = ?",
        transactionId);

    assertThat(entries)
        .as("disbursement ledger entry count for transactionId=%s", transactionId)
        .hasSize(3);

    Map<String, Object> debit = entries.stream()
        .filter(e -> "DEBIT".equals(e.get("direction")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No DEBIT entry for transactionId=" + transactionId));

    List<Map<String, Object>> credits = entries.stream()
        .filter(e -> "CREDIT".equals(e.get("direction")))
        .toList();

    assertThat(credits).hasSize(2);

    BigDecimal gross = principal.add(fee);
    assertThat(toBigDecimal(debit.get("amount"))).isEqualByComparingTo(gross);
    assertThat(debit.get("account_code")).isEqualTo("MERCHANT_WALLET");

    BigDecimal creditSum = credits.stream()
        .map(e -> toBigDecimal(e.get("amount")))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(creditSum).isEqualByComparingTo(gross);

    assertThat(credits).anyMatch(e -> "CUSTOMER_WALLET".equals(e.get("account_code"))
        && toBigDecimal(e.get("amount")).compareTo(principal) == 0);
    assertThat(credits).anyMatch(e -> "PROVIDER_FEE".equals(e.get("account_code"))
        && toBigDecimal(e.get("amount")).compareTo(fee) == 0);
}
```

The private `toBigDecimal(Object)` helper already exists in `LedgerVerifier` — no duplication.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Spring context for unit tests | `@SpringBootTest` on LedgerBalanceGuardTest | `mock(LedgerEntryRepository.class)` + real LedgerService constructor | PITest mutations run per-mutant — each must be fast; Spring context would make PITest unusably slow |
| Custom DB assertions in IT | Inline JDBC SQL for each assertion | `ledgerEntryRepository.findByTransactionId()` + AssertJ stream filters | Already established pattern in LedgerServiceIT; consistent with existing collection tests |
| Separate Testcontainers setup for disbursement IT | New `@TestConfiguration` | Extend `LedgerServiceIT` with a new `@Test` method | `TestConfig` already provides postgres:14.18 + redis; no new infra needed |
| Manual BigDecimal comparison | `bd1.equals(bd2)` | `isEqualByComparingTo()` from AssertJ | Scale-insensitive; `new BigDecimal("0.00")` equals `BigDecimal.ZERO` via `compareTo` not `equals` |

---

## Common Pitfalls

### Pitfall 1: Placing Disbursement Unit Tests Outside `com.softropic.payam.domain`

**What goes wrong:** Tests added to `com.softropic.payam.transaction.service` or any other package will not be picked up by PITest's `<targetTests><param>com.softropic.payam.domain.*</param>` filter. LedgerService mutations in the disbursement path will survive, failing the 90% kill-rate threshold gate.
**Why it happens:** PITest's `targetTests` glob is package-scoped, not project-wide.
**How to avoid:** Add all disbursement unit tests to `LedgerBalanceGuardTest` (existing class in `com.softropic.payam.domain`) or a new class in that same package.
**Warning signs:** `mvn pitest:mutationCoverage` reports < 90% kill rate for `LedgerService`.

### Pitfall 2: Missing `entry_group_id` Assertion in LedgerServiceIT Disbursement Test

**What goes wrong:** The test passes (3 rows committed) but fails to assert that all three rows share the same `entry_group_id`. If `buildDisbursementEntries()` ever generates a separate `groupId` per entry, the V25 trigger would fire per-group and reject the unbalanced singletons — but this defect would not be caught by an IT that only checks row count.
**Why it happens:** The `entry_group_id` is a grouping key for the balance trigger; its uniqueness across all 3 entries is the invariant being protected.
**How to avoid:** Explicitly assert `entries.stream().map(LedgerEntry::getEntryGroupId).distinct().count() == 1` in the disbursement IT.
**Warning signs:** Defect survives if future refactoring accidentally splits groupId generation.

### Pitfall 3: Zero-Fee Disbursement and `CHECK (amount >= 0)` Confusion

**What goes wrong:** Tests for TEST-03 may assume the DB would reject `amount = 0` for the PROVIDER_FEE entry, masking a real schema concern. V25 explicitly relaxed the check from `> 0` to `>= 0` (SCHEMA-03).
**Why it happens:** Pre-v9 codebase had `amount > 0` — zero was invalid. V25 changed this.
**How to avoid:** In the unit test for fee=0, assert `PROVIDER_FEE credit amount == 0.00` without any `assertThatThrownBy` — it should succeed. The IT for TEST-06 can use a zero-fee disbursement to also confirm `amount >= 0` is accepted by the live DB.
**Warning signs:** Test author wraps zero-fee case in `assertThatNoException()` out of confusion — it should just be a plain positive assertion.

### Pitfall 4: LedgerServiceIT Teardown Order

**What goes wrong:** `LedgerServiceIT.tearDown()` deletes `ledger_entry` before `transaction`, then `tenant_api_key`, then `tenant`, then `sec`. Adding a disbursement test using a different `transactionId` is fine — the `@BeforeEach` creates a fresh one per test. But if a test method creates extra rows, teardown order must remain correct (ledger_entry first for FK safety).
**Why it happens:** `ledger_entry.transaction_id` is a FK to `transaction`; `transaction.tenant_id` is a FK to `tenant`.
**How to avoid:** Reuse the existing `transactionId` seeded in `@BeforeEach` for the disbursement IT test — no extra setup needed.

### Pitfall 5: TEST-04 Duplication

**What goes wrong:** Writing NEW rejection tests in a new file when `LedgerPostingTest.java` already satisfies all four rejection cases.
**Why it happens:** TEST-04 says "four distinct rejection cases" — appears to be a gap, but is already implemented.
**How to avoid:** Read `LedgerPostingTest.java` first. If the four cases are present (they are), mark TEST-04 complete and do not add duplicate tests. Duplicate tests in different files cause maintenance confusion without adding mutation coverage.
**Warning signs:** Running `mvn test -Dtest=LedgerPostingTest` should show 8 passing tests, including all four rejection cases.

---

## Standard Stack

No new dependencies required. All needed tools are already on the classpath.

| Library | Version | Purpose |
|---------|---------|---------|
| JUnit Jupiter | 5.x (via Spring Boot BOM) | Test runner, `@Test`, `@BeforeEach`, `@AfterEach` |
| AssertJ | 3.24.2 | Fluent assertions (`assertThat`, `isEqualByComparingTo`, `hasSize`, `anyMatch`) |
| Mockito | 5.x (via Spring Boot BOM) | `mock()`, `ArgumentCaptor` for unit tests |
| Testcontainers PostgreSQL | via spring-boot-testcontainers | Real DB for IT — already wired in `TestConfig.postgresContainer()` |
| Spring Boot Test | via BOM | `@SpringBootTest`, `@Import`, `@ActiveProfiles` for IT |

**No `mvn install` step required** — all dependencies already resolved.

---

## State of the Art

| Old Test Coverage | v9 Coverage (after Phase 48) | Impact |
|-------------------|------------------------------|--------|
| COLLECTION only in `LedgerBalanceGuardTest` | COLLECTION + DISBURSEMENT (fee>0) + DISBURSEMENT (fee=0) | PITest kills mutations in `buildDisbursementEntries` |
| `LedgerServiceIT` — 2 collection tests | +1 disbursement IT — 3 rows persisted in real DB | Proves V25 trigger accepts 3-entry balanced group via service layer |
| `LedgerVerifier` — collection-only helper | + `assertDisbursementLedgerBalanced` | Reusable in Phase 49 E2E tests when cashout path is wired |
| `LedgerPostingTest` — complete | Unchanged — already satisfies TEST-04 | No work needed |

---

## File Map — What Gets Modified vs. Created

| File | Action | Reason |
|------|--------|--------|
| `src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java` | MODIFY — add 2 new `@Test` methods (disbursement fee>0, disbursement fee=0) | TEST-02, TEST-03, TEST-05 |
| `src/test/java/com/softropic/payam/transaction/LedgerServiceIT.java` | MODIFY — add 1 new `@Test` method (`postEntry_disbursement_persistsThreeRows`) | TEST-06 |
| `src/test/java/com/softropic/payam/e2e/verify/LedgerVerifier.java` | MODIFY — add `assertDisbursementLedgerBalanced(txId, principal, fee)` | TEST-07 |
| `src/test/java/com/softropic/payam/transaction/contract/LedgerPostingTest.java` | NO CHANGE — already satisfies TEST-04 | — |
| `src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java` | TEST-01 already satisfied by existing method | — |

**No new files to create. No production source changes.**

---

## Environment Availability

Step 2.6: SKIPPED — Phase 48 is a pure test-authoring phase. The Testcontainers infrastructure (PostgreSQL 14.18, Redis 7-alpine) and Maven build are already in place from previous phases. `mvn verify` is the full gate command.

---

## Validation Architecture

`workflow.nyquist_validation` is absent from `.planning/config.json` — treated as enabled.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) + AssertJ 3.24.2 + Mockito + Testcontainers |
| Config file | `pom.xml` (Maven Surefire for unit tests, Failsafe for IT suffix) |
| Quick run command | `mvn test -Dtest=LedgerBalanceGuardTest` |
| Full suite command | `mvn verify` |
| PITest command | `mvn pitest:mutationCoverage -P mutation-coverage` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TEST-01 | COLLECTION → 2 entries, balanced, correct account codes | unit | `mvn test -Dtest=LedgerBalanceGuardTest#postEntry_createsBalancedDoubleEntry` | Already satisfied |
| TEST-02 | DISBURSEMENT fee>0 → 3 entries, gross=principal+fee, balanced | unit | `mvn test -Dtest=LedgerBalanceGuardTest#postEntry_disbursement_createsThreeBalancedEntries` | Missing — Wave 1 |
| TEST-03 | DISBURSEMENT fee=0 → 3 entries, zero-amount PROVIDER_FEE, balanced | unit | `mvn test -Dtest=LedgerBalanceGuardTest#postEntry_disbursementZeroFee_zeroProviderFeeEntry` | Missing — Wave 1 |
| TEST-04 | LedgerPosting rejects negative principal, negative fee, null currency, null flow | unit | `mvn test -Dtest=LedgerPostingTest` | Already satisfied |
| TEST-05 | PITest MUT-02 kill rate ≥ 90% for LedgerService | mutation | `mvn pitest:mutationCoverage -P mutation-coverage` | Missing (depends on TEST-02, TEST-03 being added) — Wave 1 |
| TEST-06 | LedgerServiceIT disbursement: 3 rows in real DB, no violation, amounts balanced | integration | `mvn verify -Dit.test=LedgerServiceIT` | Missing — Wave 1 |
| TEST-07 | `LedgerVerifier.assertDisbursementLedgerBalanced` exists and is correct | unit (compile + call site) | `mvn test-compile` + `mvn verify` | Missing — Wave 1 |

### Sampling Rate

- **Per task commit:** `mvn test -Dtest=LedgerBalanceGuardTest,LedgerPostingTest`
- **Per wave merge:** `mvn verify`
- **Phase gate:** `mvn verify` green + `mvn pitest:mutationCoverage -P mutation-coverage` ≥ 90% for LedgerService

### Wave 0 Gaps

None — no new test infrastructure needed. All framework, config, and Testcontainers wiring is already in place. Wave 1 begins immediately with modifications to existing files.

---

## Open Questions

1. **Is TEST-01 already complete or should the plan verify it?**
   - What we know: `LedgerBalanceGuardTest.postEntry_createsBalancedDoubleEntry()` asserts size=2, balance, and account codes (`CUSTOMER_WALLET` debit, `PROVIDER_CLEARING` credit). All three conditions in the TEST-01 success criterion are met.
   - What's unclear: Whether the success criterion intended a test with a different name or more assertions.
   - Recommendation: Treat TEST-01 as complete. The plan task for TEST-01 should be a verification step (run the existing test, confirm it passes), not an authoring step.

2. **Should the new disbursement unit tests be a separate class or added to LedgerBalanceGuardTest?**
   - What we know: PITest `targetTests` is `com.softropic.payam.domain.*` — both approaches work.
   - Recommendation: Add to `LedgerBalanceGuardTest`. The class is already purpose-built for MUT-02 mutation killing. A single class is easier to reason about for PITest coverage purposes. A sibling class (e.g., `LedgerDisbursementGuardTest`) is a valid alternative if the test class grows too large.

3. **Does `LedgerServiceIT` use the `*IT.java` naming convention for Failsafe?**
   - What we know: The file is named `LedgerServiceIT.java`. Maven Failsafe default includes `**/*IT.java`. The existing IT methods (`postEntry_insertsTwoRows_debitAndCredit`, `postEntry_balancedCheck`) already run as integration tests via `mvn verify`.
   - Recommendation: Confirm by checking if `mvn verify` runs the file. The naming convention is correct — no change needed.

---

## Sources

### Primary (HIGH confidence)

- Direct source reading — `LedgerService.java`, `LedgerPosting.java`, `LedgerFlow.java` (confirmed Phase 47 complete)
- Direct source reading — `LedgerBalanceGuardTest.java` (existing collection test, exact assertion inventory)
- Direct source reading — `LedgerPostingTest.java` (all 8 tests confirmed; TEST-04 satisfied)
- Direct source reading — `LedgerServiceIT.java` (existing collection IT tests; disbursement IT gap confirmed)
- Direct source reading — `LedgerVerifier.java` (`assertLedgerBalanced` present; `assertDisbursementLedgerBalanced` absent confirmed)
- Direct source reading — `LedgerConstraintIT.java` (3-entry and zero-fee DB patterns confirmed working)
- Direct source reading — `pom.xml` PITest configuration (`targetClasses`, `targetTests`, `mutationThreshold=90`)
- Direct source reading — `TestConfig.java` (Testcontainers postgres:14.18 + redis wiring confirmed)

### Secondary (MEDIUM confidence)

- Phase 47 RESEARCH.md — confirmed Phase 47 completion state and architectural decisions
- REQUIREMENTS.md — TEST-01 through TEST-08 exact success criteria

### Tertiary (LOW confidence)

- None.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries confirmed on classpath; no new dependencies
- Architecture: HIGH — all patterns derived from direct source reading of existing tests
- Pitfalls: HIGH — derived from PITest config, BigDecimal behavior, FK order, and package scoping

**Research date:** 2026-04-22
**Valid until:** 2026-05-22 (stable domain — pure Java test authoring with no external dependencies)
