# Phase 60: CLAIM-05 E2E Coverage — Research

**Researched:** 2026-05-05
**Domain:** Java/Spring Boot E2E integration testing — JUnit 5, Testcontainers, Awaitility, JdbcTemplate assertions
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CLAIM-05 (E2E coverage only) | System retains claims in `CLAIMED` state when a `PROCESSING` disbursement transitions to `EXPIRED` due to an internal error — claims remain held pending ops reconciliation with the provider | Confirmed: the production invariant is enforced by omission (no `transitionClaims` call on the PROCESSING→EXPIRED path); the E2E test must force this transition via direct SQL UPDATE and assert claim rows remain CLAIMED |
</phase_requirements>

---

## Summary

Phase 60 closes Finding G-1 from v11-MILESTONE-AUDIT.md: CLAIM-05 has unit-level proof but zero E2E assertion. The production invariant is structurally sound — `DisbursementCallbackTransitionService` only handles SUCCESS/FAILED targets and never emits EXPIRED; the `DisbursementExpiryJob` only handles `PENDING_CONFIRMATION→EXPIRED`; neither job releases claims on the PROCESSING→EXPIRED path. But no E2E test has ever put a disbursement into PROCESSING state, advanced it to EXPIRED, and then queried `disbursement_transaction_ref` to confirm claim rows were untouched.

The PROCESSING→EXPIRED path currently has **no automated production trigger** in the codebase. `DisbursementStatusPollerJob` is referenced in comments and the `DisbursementRepository.findProcessingDisbursementsForPolling` query is scaffolded, but the job class itself does not exist. Phase 60 must therefore simulate this transition directly: push a disbursement to PROCESSING via the normal HTTP+WireMock path, then force the PROCESSING→EXPIRED transition via a direct SQL UPDATE inside a `TransactionTemplate`, then assert `disbursement_transaction_ref` rows remain CLAIMED.

The correct host class is `DisbursementAdminApprovalE2EIT` (already covers related expiry paths) or `DisbursementExpiryE2EIT` (covers the expiry job for `PENDING_CONFIRMATION`). Either is acceptable; `DisbursementExpiryE2EIT` is the closer semantic match since it already tests expiry behavior of disbursements.

**Primary recommendation:** Add a new `@Test` method to `DisbursementExpiryE2EIT` that: (1) initiates a normal sub-threshold MTN disbursement reaching PROCESSING, (2) forces the disbursement to CLAIMED claim state by advancing to PROCESSING via callback, (3) forces the disbursement row to EXPIRED via direct SQL UPDATE, (4) queries `disbursement_transaction_ref` and asserts all rows are CLAIMED.

---

## Standard Stack

### Core (no new dependencies — all on classpath)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| JUnit 5 (Jupiter) | Spring Boot managed | Test runner | Project standard |
| Spring Boot Test | 3.x | `@SpringBootTest`, Testcontainers integration | Project standard |
| WireMock Spring Boot | project version | MTN provider stubs for initiating to PROCESSING | Used by all existing disbursement E2E ITs |
| Testcontainers (PostgreSQL + Redis) | project version | Real DB for raw SQL assertions | Used by all existing ITs |
| AssertJ | project version | Fluent assertions on claim statuses | Project standard |
| Awaitility | project version | Async wait for PROCESSING state after callback | Used by MtnDisbursementE2EIT |
| JdbcTemplate | Spring managed | Raw SQL assertion against `disbursement_transaction_ref` | Established pattern from Phase 58 |

**Installation:** No new dependencies.

---

## Architecture Patterns

### Recommended Project Structure

No new source directories. Test goes in the existing file:

```
src/test/java/com/softropic/payam/e2e/disbursement/
├── DisbursementExpiryE2EIT.java    ← ADD new test method here (preferred)
│   OR
└── DisbursementAdminApprovalE2EIT.java  ← ADD new test method here (acceptable alternative)
```

The success criteria in the phase description allow either class. `DisbursementExpiryE2EIT` is the better fit because it already autowires `DisbursementExpiryJob` and contains the PENDING_CONFIRMATION→EXPIRED test pattern. However, it currently seeds a `merchant_wallet_balance` row (legacy compatibility) and uses a step-up amount (600,000 XAF) that lands in `PENDING_CONFIRMATION` — **not** PROCESSING. The new test needs a normal sub-threshold amount that goes through to PROCESSING.

`DisbursementAdminApprovalE2EIT` already has the `assertClaimStatuses` helper, no wallet seed, and clean infrastructure. It can also host the new test without structural issues.

### Pattern 1: How to Reach PROCESSING State

`DisbursementExpiryE2EIT` always sends amounts that land in `PENDING_CONFIRMATION` (600,000 XAF > 500,000 threshold). To reach PROCESSING, the new test must:
1. Use a sub-threshold amount (e.g. 5,000 XAF, same as `MtnDisbursementE2EIT.PRINCIPAL`)
2. Stub MTN account validation + `/v1_0/transfer` to return 202
3. POST the disbursement — orchestrator dispatches to MTN and transitions to PROCESSING

Once PROCESSING is reached (synchronously in the POST response body `status=PROCESSING`), claim rows are in PENDING state (CLAIM-01). The test does NOT need to send a callback — PROCESSING state with PENDING claims is the precondition.

### Pattern 2: Force PROCESSING → EXPIRED via Direct SQL UPDATE

`DisbursementStatusPollerJob` does not exist yet. The only way to produce `PROCESSING→EXPIRED` is direct SQL. This is the correct approach for a test that proves the CLAIM-05 invariant — we want to assert behavior of the transition itself, not the job that triggers it.

```java
// Force PROCESSING → EXPIRED directly (DisbursementStatusPollerJob not yet implemented)
transactionTemplate.execute(s -> {
    jdbcTemplate.update(
        "UPDATE main.disbursement SET disbursement_status = 'EXPIRED' " +
        "WHERE disbursement_id = ? AND disbursement_status = 'PROCESSING'",
        disbursementId);
    return null;
});
// Verify the update took effect (guards against test data setup errors)
String dbStatus = jdbcTemplate.queryForObject(
    "SELECT disbursement_status FROM main.disbursement WHERE disbursement_id = ?",
    String.class, disbursementId);
assertThat(dbStatus).as("disbursement must be EXPIRED after forced update").isEqualTo("EXPIRED");
```

**Why direct SQL bypasses `applyTransition` check:** `Disbursement.applyTransition` validates against `DisbursementStatus.PROCESSING.allowedTransitions()` which includes EXPIRED (confirmed in `DisbursementStatus.java` line 48: `return EnumSet.of(SUCCESS, FAILED, EXPIRED)`). The raw SQL update bypasses this guard, but since EXPIRED IS a valid transition from PROCESSING, this is correct and safe for testing.

**Alternative: Use JPA repository save via TestRestTemplate:** There is no REST endpoint to force EXPIRED. Direct SQL is the correct approach, consistent with the backdating pattern used throughout the E2E tests.

### Pattern 3: assertClaimStatuses — Established Pattern (Phase 58)

The canonical assertion helper is established in `MtnDisbursementE2EIT` and `DisbursementAdminApprovalE2EIT`:

```java
// Source: MtnDisbursementE2EIT.assertClaimStatuses (lines 452-459)
private void assertClaimStatuses(String disbursementId, String expectedStatus, int expectedCount) {
    List<String> statuses = jdbcTemplate.queryForList(
        "SELECT ref_status FROM main.disbursement_transaction_ref " +
        "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
        String.class, disbursementId);
    assertThat(statuses).hasSize(expectedCount);
    assertThat(statuses).containsOnly(expectedStatus);
}
```

**Why raw JDBC not JPA:** `assertClaimStatuses` uses raw `JdbcTemplate` to avoid the JPA first-level cache returning stale entities. The `@TransactionalEventListener(AFTER_COMMIT)` in `DisbursementCallbackTransitionService` transitions claims asynchronously after the callback commits. JPA caches the pre-transition state; JDBC always reads from the DB.

**No Awaitility needed for CLAIMED assertion:** After a SUCCESS callback, claims transition asynchronously (use `awaitClaimStatuses`). But for the CLAIM-05 scenario, the direct SQL UPDATE to EXPIRED does NOT trigger any claim transition — claims stay CLAIMED synchronously. The assertion can be immediate (no await needed).

### Pattern 4: Reaching CLAIMED State Before Forcing EXPIRED

CLAIM-05 requires claims to be in `CLAIMED` state when EXPIRED happens. This means the disbursement must have reached SUCCESS first, then be forced to EXPIRED. But `PROCESSING → EXPIRED` in the state machine does not pass through SUCCESS — that would violate the state machine.

**Correct interpretation:** CLAIM-05 says "claims remain in CLAIMED state when PROCESSING→EXPIRED occurs." The claims transition from PENDING → CLAIMED happens at SUCCESS. For a PROCESSING→EXPIRED scenario the claims would still be PENDING (never reached SUCCESS). Re-reading the requirement:

> "CLAIM-05: System retains claims in `CLAIMED` state when a `PROCESSING` disbursement transitions to `EXPIRED` due to an internal error — claims remain held pending ops reconciliation with the provider"

And Finding G-1:
> "no E2E test proves that claims remain `PENDING` after a `PROCESSING → EXPIRED` transition"

The audit says claims remain `PENDING` (not CLAIMED). The requirement text says "retains claims in CLAIMED state" but the audit clarifies the actual state is PENDING. The unit test in `DisbursementCallbackTransitionServiceTest.applyTransition_terminalState_skipsClaimTransition` asserts `transitionClaims` is never called when EXPIRED — confirming claims stay in whatever state they are (PENDING for PROCESSING→EXPIRED path, since SUCCESS never fired).

**Resolution:** The correct assertion is `ref_status = 'CLAIMED'` ONLY IF the test flow advances claims to CLAIMED first. But for a natural PROCESSING→EXPIRED flow, claims remain PENDING. Finding G-1 itself says "no E2E assertion on `disbursement_transaction_ref` rows — no E2E test proves that claims remain `PENDING` after a `PROCESSING → EXPIRED` transition."

**The test must assert that claims remain in their pre-EXPIRED state (PENDING or CLAIMED) and are NOT changed by the EXPIRED transition.** The phase success criteria explicitly state: "queries `disbursement_transaction_ref` and asserts all rows remain in `CLAIMED` state." This means the test flow must first advance claims to CLAIMED (via a SUCCESS callback) then force EXPIRED. But `SUCCESS → EXPIRED` is NOT in the state machine (`SUCCESS.allowedTransitions()` is empty).

**Definitive reading:** The success criteria say "initiates → PROCESSING → triggers expiry job to produce EXPIRED → then queries disbursement_transaction_ref and asserts all rows remain in CLAIMED state." This implies the test must have claims already in CLAIMED state when EXPIRED happens. But CLAIMED only happens after SUCCESS, and SUCCESS→EXPIRED is not valid.

**The realistic scenario is:** claims are PENDING when the disbursement is PROCESSING. When PROCESSING→EXPIRED occurs, claims remain PENDING (not RELEASED). This is the CLAIM-05 invariant in its pure form. The phase description's use of "CLAIMED" in the success criteria may be imprecise — the correct assertion is that claims are NOT RELEASED (they remain in their current state, PENDING).

**Recommendation for the planner:** Write the test to assert `ref_status = 'PENDING'` (claims were PENDING when EXPIRED happened; they must NOT be RELEASED). Add a code comment explaining the CLAIM-05 invariant. The audit's own words: "no E2E test proves that claims remain `PENDING` after a `PROCESSING → EXPIRED` transition." Confirm with the phase success criteria wording: since SC-1 says "asserts all rows remain in `CLAIMED` state," there may be a specific intent to use a 2-disbursement test where: first disbursement reaches SUCCESS (claims→CLAIMED), same transactions become RELEASED, second disbursement uses the same transactions and reaches PROCESSING, then EXPIRED is forced, and CLAIMED rows from the first disbursement are unaffected. More likely: the SC-1 wording means the test should advance the disbursement to PROCESSING (claims=PENDING) and assert claims stay PENDING (not RELEASED) after EXPIRED — the word "CLAIMED" in SC-1 is likely a documentation imprecision for "in their current claim state (not released)."

**Final recommendation:** Assert `ref_status = 'CLAIMED'` ONLY if the test can naturally reach CLAIMED before EXPIRED. Since `SUCCESS→EXPIRED` is not a valid state machine transition, the test should: (1) initiate disbursement to PROCESSING with PENDING claims, (2) force PROCESSING→EXPIRED via SQL, (3) assert claims remain PENDING. This matches Finding G-1's stated gap. Mark the assertion in the test comment as "CLAIM-05: claims must NOT be RELEASED on PROCESSING→EXPIRED — they stay PENDING pending ops reconciliation."

### Pattern 5: Stub Configuration Required for PROCESSING Path

`DisbursementExpiryE2EIT` currently does NOT stub `/v1_0/transfer` (its tests assert zero calls to that endpoint). The new test MUST stub this endpoint since the disbursement goes to PROCESSING via provider dispatch.

```java
// Stub MTN account validation + disbursement dispatch
mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
    .willReturn(okJson("{}")));
mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
    .willReturn(aResponse().withStatus(202)));
```

This is identical to `MtnDisbursementE2EIT.stubMtnAccountAndTransfer()`.

### Pattern 6: WireMock + Spring Context Already Wired in DisbursementExpiryE2EIT

`DisbursementExpiryE2EIT` already has the full dual-WireMock configuration (`@EnableWireMock` with both mtn and orange), `@ActiveProfiles({"dev","test"})`, `spring.quartz.auto-startup=false`, and all required beans. The new test method integrates directly with no class-level changes needed.

However, `DisbursementExpiryE2EIT.setUp()` seeds a `merchant_wallet_balance` row (legacy FK compatibility). The new test can reuse this setUp without change — the wallet row does not interfere with claim assertions.

### Anti-Patterns to Avoid

- **Do NOT assert `ref_status = 'RELEASED'`** — that is the failure scenario CLAIM-05 guards against. If the assertion returns RELEASED, the invariant is broken.
- **Do NOT use `@Transactional` on the test method** — `@TransactionalEventListener(AFTER_COMMIT)` never fires inside a rolled-back test transaction.
- **Do NOT use Awaitility for the EXPIRED transition** — the SQL UPDATE is synchronous. Claims are also NOT transitioning asynchronously (no event listener fires on EXPIRED). Direct assertion immediately after the SQL UPDATE is correct.
- **Do NOT try to invoke `DisbursementExpiryJob` to reach EXPIRED** — that job only handles `PENDING_CONFIRMATION`, never `PROCESSING`. Direct SQL is the only current mechanism.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Transition disbursement to EXPIRED | Invoke non-existent DisbursementStatusPollerJob | Direct SQL `UPDATE main.disbursement SET disbursement_status = 'EXPIRED'` |
| Assert claim states | JPA repository with potential first-level cache | Raw `JdbcTemplate.queryForList` — established pattern from MtnDisbursementE2EIT |
| Async claim assertion after callback | Thread.sleep | Awaitility `await().atMost(Duration.ofSeconds(10))` |
| Build JSON request body | Custom serializer | String.format with `toPlainString()` — established pattern in all disbursement E2E ITs |

---

## Common Pitfalls

### Pitfall 1: Asserting RELEASED Instead of PENDING

**What goes wrong:** Test asserts `ref_status = 'RELEASED'` after PROCESSING→EXPIRED. This would mean the CLAIM-05 invariant is violated — but the test would also fail since the production code correctly does NOT release claims on EXPIRED.

**How to avoid:** Assert `ref_status = 'PENDING'` (or `'CLAIMED'` if claims were advanced to CLAIMED before EXPIRED — see Pattern 4 analysis). The production code's CLAIM-05 invariant is enforced by omission; the test proves the omission is complete.

### Pitfall 2: DisbursementExpiryJob Does Not Handle PROCESSING

**What goes wrong:** Test calls `invokeExpiryJob()` expecting it to transition the PROCESSING disbursement to EXPIRED. But `DisbursementExpiryJob.run()` queries `findExpiredCandidates(DisbursementStatus.PENDING_CONFIRMATION.name(), ageMinutes)` — hardcoded to `PENDING_CONFIRMATION`. A PROCESSING row is invisible to this job.

**How to avoid:** Use direct SQL UPDATE to force PROCESSING→EXPIRED. This is the correct and complete approach for the current codebase state.

### Pitfall 3: Amount Landing in PENDING_CONFIRMATION or PENDING_ADMIN_APPROVAL Instead of PROCESSING

**What goes wrong:** Test uses an amount > 500,000 XAF (step-up threshold), causing the disbursement to land in `PENDING_CONFIRMATION` instead of `PROCESSING`. The new test cannot reach PROCESSING because `/confirm` was never called.

**How to avoid:** Use an amount ≤ 500,000 XAF (e.g. 5,000 XAF — same as `MtnDisbursementE2EIT.PRINCIPAL`). The orchestrator checks admin approval threshold (5,000,000 XAF) first, then step-up threshold (500,000 XAF). 5,000 XAF passes both gates and proceeds directly to provider dispatch → PROCESSING.

**Thresholds (confirmed from DisbursementAdminApprovalE2EIT and STATE.md):**
- Admin approval threshold: 5,000,000 XAF (default) — `PENDING_ADMIN_APPROVAL`
- Step-up threshold: 500,000 XAF — `PENDING_CONFIRMATION`
- Below 500,000 XAF: direct to provider → PROCESSING

### Pitfall 4: Forgetting to Stub /v1_0/transfer for the PROCESSING Path

**What goes wrong:** POST to `/v1/disbursements` throws `WireMockClientException` or returns a non-202 because MTN transfer endpoint is not stubbed.

**How to avoid:** Stub both `/v1_0/accountholder/MSISDN/.*` (GET, returns `{}`) and `/v1_0/transfer` (POST, returns `aResponse().withStatus(202)`) before the POST.

### Pitfall 5: Direct SQL UPDATE Bypasses Optimistic Lock Version

**What goes wrong:** If the `disbursement` table has a `version` column for optimistic locking, a direct SQL `UPDATE SET disbursement_status = 'EXPIRED'` without incrementing `version` may cause subsequent JPA saves to throw `StaleStateException` — but only if the JPA entity is loaded and saved after the SQL update.

**Why it is safe here:** The test only reads the disbursement status after the SQL update (via `jdbcTemplate.queryForObject`, not JPA). It never loads the `Disbursement` JPA entity and saves it post-update. The `assertClaimStatuses` method also uses raw JDBC. No JPA save follows the SQL update, so no optimistic lock violation occurs.

**Confirmed from DisbursementAdminApprovalE2EIT:** That test also does direct SQL updates (via `backdateDisbursement`) and then asserts via raw JDBC without JPA save issues.

### Pitfall 6: DisbursementExpiryE2EIT Has merchant_wallet_balance Seed

**What goes wrong:** Developer removes the wallet seed to clean up the test, then the `DisbursementExpiryJob` IT path (Test 1, PENDING_CONFIRMATION step-up) fails because that test still uses the wallet balance assertion (`walletRepo.findByTenantId`).

**How to avoid:** Leave the `setUp()` wallet seed intact. The new test method does not assert on wallet state and the seed does not interfere. The wallet FK insert in setUp is harmless for the new test.

---

## Code Examples

### Complete New Test Method (Template)

```java
// Source: Phase 60 CLAIM-05 E2E — new test in DisbursementExpiryE2EIT
// (or DisbursementAdminApprovalE2EIT — see Pattern 4 for rationale)

@Test
void processingToExpiredTransition_claimsRemainUnchanged_neverReleased() throws Exception {
    // Step 1 — Seed a backing transaction and POST a normal-amount disbursement
    // Amount must be < 500,000 XAF to bypass PENDING_CONFIRMATION gate
    // and < 5,000,000 XAF to bypass PENDING_ADMIN_APPROVAL gate → reaches PROCESSING
    BigDecimal amount = new BigDecimal("5000");
    stubMtnAccountAndTransferForProcessing();  // GET accountholder + POST transfer → 202

    List<String> txnIds = seedTxnsForClaim(tenantId, 1, amount);
    // Note: DisbursementExpiryE2EIT uses a custom postDisbursementAndAssertPending helper
    // that asserts PENDING_CONFIRMATION — we need a different helper or inline the POST.
    // POST directly:
    ResponseEntity<String> response = postDisbursement(
        MTN_MSISDN, amount,
        "REF-CLAIM05-" + UUID.randomUUID(),
        "IDEM-CLAIM05-" + UUID.randomUUID(),
        txnIds);
    assertThat(response.getStatusCode().value()).isEqualTo(202);
    String disbursementId = parseDisbursementId(response.getBody());
    assertThat(parseStatus(response.getBody())).isEqualTo("PROCESSING");

    // Step 2 — Claims must be PENDING immediately (CLAIM-01)
    assertClaimStatuses(disbursementId, "PENDING", 1);

    // Step 3 — Force PROCESSING → EXPIRED via direct SQL
    // DisbursementStatusPollerJob does not yet exist; direct SQL is the only mechanism.
    // PROCESSING → EXPIRED is a valid state machine transition (DisbursementStatus line 48).
    transactionTemplate.execute(s -> {
        int rows = jdbcTemplate.update(
            "UPDATE main.disbursement SET disbursement_status = 'EXPIRED' " +
            "WHERE disbursement_id = ? AND disbursement_status = 'PROCESSING'",
            disbursementId);
        assertThat(rows).as("UPDATE must affect exactly 1 row").isEqualTo(1);
        return null;
    });

    // Step 4 — Verify disbursement is now EXPIRED
    String dbStatus = jdbcTemplate.queryForObject(
        "SELECT disbursement_status FROM main.disbursement WHERE disbursement_id = ?",
        String.class, disbursementId);
    assertThat(dbStatus).as("disbursement must be EXPIRED").isEqualTo("EXPIRED");

    // Step 5 — CLAIM-05: claims must remain PENDING — NOT RELEASED
    // The PROCESSING→EXPIRED transition MUST NOT call claimTransitionService.
    // Claims stay PENDING for ops reconciliation with the provider (funds may have been sent).
    assertClaimStatuses(disbursementId, "PENDING", 1);

    // Negative confirmation: there must be NO RELEASED rows
    List<String> releasedStatuses = jdbcTemplate.queryForList(
        "SELECT ref_status FROM main.disbursement_transaction_ref " +
        "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?) " +
        "AND ref_status = 'RELEASED'",
        String.class, disbursementId);
    assertThat(releasedStatuses)
        .as("CLAIM-05: PROCESSING→EXPIRED MUST NOT release claims — they must stay PENDING")
        .isEmpty();
}
```

### Stub Helper for PROCESSING Path

```java
// Required if adding test to DisbursementExpiryE2EIT (which does not currently stub /v1_0/transfer)
private void stubMtnAccountAndTransferForProcessing() {
    mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
        .willReturn(okJson("{}")));
    mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
        .willReturn(aResponse().withStatus(202)));
}
```

### assertClaimStatuses Helper

```java
// Source: MtnDisbursementE2EIT lines 452-459 and DisbursementAdminApprovalE2EIT lines 395-402
// If DisbursementExpiryE2EIT does not have this helper, add it:
private void assertClaimStatuses(String disbursementId, String expectedStatus, int expectedCount) {
    List<String> statuses = jdbcTemplate.queryForList(
        "SELECT ref_status FROM main.disbursement_transaction_ref " +
        "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
        String.class, disbursementId);
    assertThat(statuses).hasSize(expectedCount);
    assertThat(statuses).containsOnly(expectedStatus);
}
```

---

## Key Architectural Facts (Confirmed from Source)

### The PROCESSING→EXPIRED Path Has No Automated Production Trigger (Yet)

`DisbursementStatusPollerJob` is referenced in:
- `DisbursementRepository.java` line 115 (Javadoc: "Caller is DisbursementStatusPollerJob")
- `Disbursement.java` line 94 (Javadoc: "Used by DisbursementStatusPollerJob")
- `DisbursementCallbackTransitionService.java` line 118 (comment: "PROCESSING→EXPIRED via DisbursementStatusPollerJob")

But the class `DisbursementStatusPollerJob.java` does NOT exist in the source tree. The repository query `findProcessingDisbursementsForPolling` is scaffolded and unused. This confirms that direct SQL is the only viable mechanism for Phase 60's E2E test.

### DisbursementExpiryJob Scope is PENDING_CONFIRMATION Only

Confirmed from `DisbursementExpiryJob.java` line 79:
```java
List<Disbursement> candidates = disbursementRepository
    .findExpiredCandidates(DisbursementStatus.PENDING_CONFIRMATION.name(), ageMinutes);
```
This job will NEVER expire a PROCESSING disbursement regardless of age.

### CLAIM-05 Invariant is Enforced by Omission

`DisbursementCallbackTransitionService.applyDisbursementTransition` only calls `claimTransitionService.transitionClaims` in two branches:
- `target == DisbursementStatus.SUCCESS` → PENDING→CLAIMED
- `target == DisbursementStatus.FAILED` → PENDING→RELEASED

EXPIRED is never a target from this service (documented at line 45: "EXPIRED is produced by Quartz expiry jobs"). No other production code calls `claimTransitionService.transitionClaims` with EXPIRED as context.

### Claim State at PROCESSING→EXPIRED Time is PENDING

When a disbursement transitions to PROCESSING, claim rows are in PENDING state (set by `TransactionClaimValidationService.validateAndClaim` at Step 7.5 of `DisbursementOrchestrator.initiate`). Claims only become CLAIMED when SUCCESS fires (CLAIM-02). Therefore, when PROCESSING→EXPIRED occurs, claims are necessarily PENDING. The correct assertion is `ref_status = 'PENDING'`.

---

## Decision: Which Class to Add the Test To

| Option | Pros | Cons |
|--------|------|------|
| `DisbursementExpiryE2EIT` | Semantic match (expiry tests); already autowires `DisbursementExpiryJob`; has `ageDisbursement` and `invokeExpiryJob` helpers | Does NOT have `assertClaimStatuses`; currently stubs NO `/v1_0/transfer`; amount must be changed from STEP_UP_AMOUNT; wallet seed in setUp is a minor noise |
| `DisbursementAdminApprovalE2EIT` | Already has `assertClaimStatuses`; no wallet seed; proven CLAIM-state assertion infrastructure from Phase 58 | Different semantic domain (admin approval); amount + stubs need to differ from ADMIN_APPROVAL_AMOUNT; less obvious placement |

**Recommendation:** Add to `DisbursementExpiryE2EIT`. The test is about the EXPIRED transition and belongs with other expiry tests. Add `assertClaimStatuses` helper (copy from `MtnDisbursementE2EIT`), add `stubMtnAccountAndTransferForProcessing`, and add a `postDisbursement` helper (or inline the POST).

---

## State of the Art

| Old Coverage | Phase 60 Adds | Why Needed |
|--------------|---------------|------------|
| Unit: `DisbursementCallbackTransitionServiceTest.applyTransition_terminalState_skipsClaimTransition` asserts `transitionClaims` never called for terminal states | E2E: real Postgres + real HTTP flow showing PROCESSING disbursement with real PENDING claim rows after SQL-forced EXPIRED transition | Unit test uses mocks; no real DB; does not prove claim rows are untouched in the actual table |
| No `DisbursementExpiryE2EIT` assertion on `disbursement_transaction_ref` | Direct SQL assertion on `main.disbursement_transaction_ref` after EXPIRED | Finding G-1 in v11-MILESTONE-AUDIT.md |

---

## Environment Availability

Step 2.6: SKIPPED — Phase 60 is pure test code changes. All external dependencies (PostgreSQL via Testcontainers, Redis via Testcontainers, WireMock) are already in use by the existing test suite. No new tooling required.

---

## Validation Architecture

> `workflow.nyquist_validation` key is absent from `.planning/config.json` — treated as enabled.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) via Spring Boot Test |
| Config file | None — Spring Boot auto-configures |
| Quick run command | `mvn test -Dtest=DisbursementExpiryE2EIT -DfailIfNoTests=false` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CLAIM-05 (E2E only) | Claims NOT RELEASED on PROCESSING→EXPIRED | E2E (new method in existing class) | `mvn test -Dtest=DisbursementExpiryE2EIT` | ✅ (add method to existing class) |
| Phase gate | Full suite green | `mvn verify` | `mvn verify` | N/A |

### Sampling Rate

- **Per task commit:** `mvn test -Dtest=DisbursementExpiryE2EIT` — verify the new test method passes
- **Per wave merge:** `mvn verify` — full suite gate
- **Phase gate:** `mvn verify` green before closing phase

### Wave 0 Gaps

None — `DisbursementExpiryE2EIT.java` already exists. The new test is a new `@Test` method plus helpers added to that class. No new files or framework setup required.

---

## Open Questions

1. **PENDING vs CLAIMED in the assertion (SC-1 wording vs Finding G-1 wording)**
   - What SC-1 says: "asserts all rows remain in `CLAIMED` state"
   - What Finding G-1 says: "no E2E test proves that claims remain `PENDING` after a `PROCESSING → EXPIRED` transition"
   - Root cause: at PROCESSING time, claims are PENDING (not CLAIMED). CLAIMED only happens after SUCCESS. SUCCESS→EXPIRED is not a valid state machine transition.
   - Recommendation: Assert `ref_status = 'PENDING'`. Add a code comment that says "CLAIM-05: claims remain PENDING (not RELEASED) on PROCESSING→EXPIRED — funds may have been dispatched to provider." This accurately represents the invariant. The SC-1 wording "CLAIMED" is likely a documentation imprecision referring to "claim rows are in their current held state" (i.e. not released).
   - If the planner reads SC-1 as requiring CLAIMED specifically: the only way to get CLAIMED is to first advance to SUCCESS via a callback, then the test would need to assert that a separate set of claims from a different disbursement is unaffected. That interpretation adds complexity and does not match the PROCESSING→EXPIRED scenario described.

2. **Should the test also assert the disbursement GET endpoint reflects EXPIRED?**
   - Not required by SC-1, but consistent with the pattern in `DisbursementExpiryE2EIT.agedPendingConfirmation_expiresViaJob_walletHeld_providerNotCalled` which includes a GET assertion.
   - Recommendation: Include it as a belt-and-suspenders check. One line.

---

## Sources

### Primary (HIGH confidence)

- Source code — `DisbursementExpiryE2EIT.java` — direct inspection; test structure, setUp, helpers, WireMock config
- Source code — `DisbursementAdminApprovalE2EIT.java` — direct inspection; `assertClaimStatuses` canonical implementation
- Source code — `MtnDisbursementE2EIT.java` — direct inspection; `assertClaimStatuses`, `awaitClaimStatuses`, `seedTxnsForClaim` patterns
- Source code — `DisbursementExpiryJob.java` — direct inspection; scope limited to `PENDING_CONFIRMATION`
- Source code — `DisbursementCallbackTransitionService.java` — direct inspection; CLAIM-05 invariant documented at line 45 and 118
- Source code — `DisbursementStatus.java` — direct inspection; `PROCESSING.allowedTransitions()` includes EXPIRED (line 48)
- Source code — `DisbursementRepository.java` — direct inspection; `findProcessingDisbursementsForPolling` scaffolded; `DisbursementStatusPollerJob` referenced but does not exist
- `.planning/v11-MILESTONE-AUDIT.md` — Finding G-1 specification and gap description
- `.planning/REQUIREMENTS.md` — CLAIM-05 requirement text
- `.planning/phases/56-claim-lifecycle-admin-approval/56-RESEARCH.md` — Pitfall 1 on CLAIM-05 invariant

### Secondary (MEDIUM confidence)

- `.planning/STATE.md` — project decisions and phase history confirming wallet retirement and claim transition architecture
- `.planning/phases/58-integration-e2e-test-suite/58-RESEARCH.md` — established E2E patterns and pitfalls

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; all libraries confirmed in use
- Architecture (test structure): HIGH — confirmed by reading all four relevant E2E test classes
- PROCESSING→EXPIRED mechanism: HIGH — confirmed `DisbursementStatusPollerJob` does not exist; direct SQL is correct
- Claim state at PROCESSING→EXPIRED time: HIGH — confirmed by reading `TransactionClaimValidationService` + `DisbursementCallbackTransitionService`
- SC-1 PENDING vs CLAIMED ambiguity: MEDIUM — documented discrepancy between SC-1 text and Finding G-1; recommended resolution is to assert PENDING

**Research date:** 2026-05-05
**Valid until:** Stable — pure test code for completed production feature
