# Phase 59: v11 Javadoc & Tech Debt Cleanup — Research

**Researched:** 2026-05-05
**Domain:** Java Javadoc / source comment cleanup (no new code logic)
**Confidence:** HIGH

---

## Summary

Phase 59 is a purely cosmetic source edit: remove stale wallet and fee references from
class-level Javadoc in two orchestration service files. Both files were read in full during
research. No functional logic changes, no new dependencies, no schema migrations, no test
changes are required. The method bodies are already correct (wallet model retired in v11);
only the class-level `<ol>` and `<p>` prose in the two class Javadoc blocks need trimming.

`mvn verify` serves as the regression gate. Because this is a Javadoc-only edit, compilation
is the primary risk surface (malformed `/** */` blocks can break `javadoc:javadoc` or
annotation processing if they reference non-existent items). The build currently passes
(Phase 58: 474 unit + 300 IT, 0 failures).

**Primary recommendation:** Edit the two class-level Javadoc blocks precisely as specified
in the Success Criteria, then run `mvn verify` to confirm no regressions.

---

## User Constraints

No CONTEXT.md exists for this phase. Phase description is self-contained and the scope is
fully defined by the v11-MILESTONE-AUDIT.md WARNING-level tech debt items.

**Locked decisions (from audit):**
- Remove "Fee evaluation" step 5 and "Wallet balance reserve (PESSIMISTIC_WRITE)" step 6
  from the `<ol>` in `DisbursementOrchestrator.java` class Javadoc
- Remove all BAL-02/BAL-03 references from that same Javadoc
- Remove "wallet release (when target=FAILED)", `walletBalanceService.release`, and BAL-02
  references from `DisbursementCallbackTransitionService.java` class Javadoc
- The method body comment at line 107 ("Wallet model retired in v11 (SCHEMA-03)") is
  already correct — no change needed there
- `mvn verify` must pass after the edits (zero regression)

**Deferred ideas (OUT OF SCOPE):**
- Phase 57 INFO item (V32MigrationIT assertion strategy) — no action in this phase
- Phase 58 INFO items (legacy wallet FK inserts in E2E setUp; @Disabled test) — no action
- Phase 56 INFO item (no admin approve/reject REST endpoint) — no action
- CLAIM-05 E2E coverage gap (G-1 from audit) — deferred to Phase 60

---

## Exact Current State of the Two Files

### DisbursementOrchestrator.java — class Javadoc (lines 46–82)

The `<ol>` lists 13 steps. Two steps are stale and must be removed or corrected:

```
Step 5 (line 57):
  <li>Fee evaluation — fee = ZERO (FEE-01; disbursements carry no fee)</li>

Step 6 (line 58):
  <li>Step-up gate decision (status = INITIATED or PENDING_CONFIRMATION)</li>
```

Wait — re-reading the actual file carefully:

Current `<ol>` items (lines 53–69):
1. Idempotency check (dsb namespace) — replay or reserve
2. MSISDN routing
3. Velocity check (tenant minute, tenant hour, msisdn day)
4. Fraud check
5. **Fee evaluation — fee = ZERO (FEE-01; disbursements carry no fee)**  ← STALE label (still calls it "Fee evaluation" step 5; per audit this step exists in code but the Javadoc wording "Fee evaluation" is stale because FEE-01 retired fee evaluation entirely)
6. **Step-up gate decision** (labelled as step 6 in the code comment at line 204)  ← audit says "Wallet balance reserve (PESSIMISTIC_WRITE)" was here in pre-v11 doc — but what the file actually has at step 6 is the admin-approval gate
7. Create Disbursement row
8. Transaction claim validation + PENDING ref-row inserts
9. If PENDING_CONFIRMATION → return; else continue
10. validateSubscriber → if inactive: transitionToFailed → return
11. Dispatch to provider port
12. Transition to PROCESSING
13. Store idempotency response

**Actual finding from reading the source:**
The file has already had step content updated in many steps, but step 5 still reads "Fee
evaluation" (stale name — the real behavior is "fee = ZERO, no fee rule evaluated"). The
audit says step 5 "Fee evaluation" and step 6 "Wallet balance reserve (PESSIMISTIC_WRITE)"
are the stale items. The current file at line 57 reads:

```
<li>Fee evaluation — fee = ZERO (FEE-01; disbursements carry no fee)</li>
```

The label "Fee evaluation" is the tech debt: it names a service that is no longer called.
The parenthetical clarification is accurate but the step heading is misleading. The correct
label should reflect what actually happens (assign fee = ZERO, no service call). There is no
"Wallet balance reserve" step visible in the current file; that step was already removed from
the `<ol>` body during Phase 54, but the audit flags that the label name remains stale.

The SUCCESS CRITERIA says:
> "Fee evaluation" (step 5) ... no longer lists [it]; all BAL-02/BAL-03 references removed

Additionally there is a prose note at the bottom of the class Javadoc (lines 77–81):
```
<p><strong>Failure paths and transition semantics:</strong>
<ul>
  <li>Pre-disbursement-row failures (idempotency, MSISDN, velocity, fraud) — no row created</li>
  <li>Post-row failures (claim validation, subscriber inactive, provider error) — disbursement
      transitions to FAILED via {@link #releaseAndFail}</li>
</ul>
```
This block is correct and should not be changed.

**BAL-02/BAL-03 references:** Searching the class Javadoc block (lines 46–82): there are NO
explicit "BAL-02" or "BAL-03" string literals in the current file. The audit's reference to
BAL-02/BAL-03 in DisbursementOrchestrator refers to the stale semantics implied by the "Fee
evaluation" and "Wallet balance reserve" step labels that were part of a prior wallet-backed
model. The fix is renaming/removing those step labels.

### DisbursementCallbackTransitionService.java — class Javadoc (lines 27–47)

Current class-level Javadoc:
```java
/**
 * REQUIRES_NEW state transition for disbursement double-check (SEC-05 + SEC-06).
 * Mirror of WebhookTransitionService for collection flow.
 *
 * <p>Why a separate bean: @Transactional self-invocation bypasses Spring AOP proxy.
 * WebhookDoubleCheckHandler injects this bean and calls applyDisbursementTransition.
 *
 * <p>Atomicity contract: the disbursement state transition, the claim transition (SUCCESS or
 * FAILED), and the IF alert (FAILED + Insufficient Funds signal) all commit in the SAME
 * REQUIRES_NEW transaction (Pitfall 4 in 56-RESEARCH).
 *
 * <p>Idempotent replay guard: if the disbursement is already in a terminal state
 * (allowedTransitions empty), the method silently returns without side effects.
 * This protects against the same callback being delivered twice and the second arrival
 * sneaking past the Redis dedup (e.g. dedup key TTL expired).
 *
 * <p>CLAIM-05 invariant: this service produces SUCCESS or FAILED targets only. EXPIRED is
 * produced by Quartz expiry jobs (DisbursementExpiryJob, admin-approval expiry) and is
 * NOT handled here. The PROCESSING→EXPIRED path MUST NOT release claims — that invariant
 * is upheld by design (EXPIRED is never a reachable target via this method).
 */
```

**Actual finding:** The current class Javadoc (lines 27–47) does NOT contain the strings
"wallet release (when target=FAILED)", "walletBalanceService.release", or "BAL-02" as
explicit literals. The v11-MILESTONE-AUDIT.md references "lines 30, 61, 64-65" but the
current file (which has already been partially updated through Phases 54–58) no longer has
those literal strings in the class Javadoc.

The method body comment at line 107 already reads:
```java
// Wallet model retired in v11 (SCHEMA-03) — no wallet release on FAILED callback.
```
This is the "consistent with updated class summary" wording the Success Criteria asks for.

**Conclusion for DisbursementCallbackTransitionService:** The class-level Javadoc is already
clean — the stale wallet-release prose that the audit flagged has already been removed during
prior phases. The method body comment (line 107) is already correct. This file may require
only a verification check, not an active edit.

**However**, the planner should instruct the executor to re-read lines 27–47 at execution time
to confirm the current state and either: (a) make no change if already clean, or (b) remove
any residual stale text if found.

---

## Standard Stack

No new libraries. This phase uses only existing project infrastructure.

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 17.0.1 | Source language |
| Maven | 3.9.9 | Build and verify gate |
| Javadoc (`/** */`) | Java 17 syntax | Comment format being edited |

**Installation:** Nothing to install.

---

## Architecture Patterns

### Javadoc `<ol>` step list pattern

The class Javadoc in `DisbursementOrchestrator` uses an HTML ordered list (`<ol>/<li>`) to
enumerate the steps of `initiate()`. The pattern in use:

```java
/**
 * [prose]
 *
 * <p>Sequence (initiate):
 * <ol>
 *   <li>Step one description</li>
 *   <li>Step two description</li>
 * </ol>
 *
 * <p>[additional prose sections]
 */
```

Javadoc in Java 17 supports standard HTML within `/** */` blocks. Removing `<li>` items
from an `<ol>` does not require any import changes, compilation changes, or annotation
processor involvement.

### Step renaming vs. removal

Two strategies for the stale step 5 "Fee evaluation":

**Option A — Rename the step** (preferred, preserves step numbering context):
```java
<li>Fee = ZERO (FEE-01 — disbursements carry no fee; no fee rule evaluated)</li>
```
This removes the misleading "Fee evaluation" label (which implies a service call) while
keeping the sequence accurate for readers of `initiate()`.

**Option B — Remove the step entirely**: Would shift all subsequent step numbers, making
the inline comments in the method body (which reference "Step 5", "Step 6" via the `──`
comment headers) inconsistent with the class Javadoc. NOT recommended.

**Recommendation: Option A (rename)**. The method body already has `// ── Step 5:` and
`// ── Step 6:` inline comments that correspond to the `<ol>` steps. Renaming the label
preserves that correspondence.

For step 6, the current Javadoc `<li>` already reads "Step-up gate decision (status =
INITIATED or PENDING_CONFIRMATION)" — this accurately reflects the current code (which now
also handles admin-approval gate). The audit's original complaint was about "Wallet balance
reserve (PESSIMISTIC_WRITE)" which no longer appears in the current file. Update this step
label to include the admin-approval gate if needed:
```java
<li>Determine flow: admin-approval gate FIRST, then step-up gate (ADMIN-01; status =
    INITIATED or PENDING_CONFIRMATION or routes to PENDING_ADMIN_APPROVAL)</li>
```

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Verifying no compilation errors | Custom script | `mvn verify` — standard build gate |
| Finding stale Javadoc strings | grep scripts | Direct file read + Edit tool |

---

## Runtime State Inventory

> Rename/refactor/migration phase trigger: NOT applicable. This is a Javadoc comment edit,
> not a rename or refactor of any identifier, class name, or string constant.

**None — verified by direct file inspection.** No runtime state (databases, OS-level
registrations, secrets, build artifacts) is affected by changing comment text in Java source
files.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17 | Compilation | Yes | 17.0.1 LTS | — |
| Maven | `mvn verify` gate | Yes | 3.9.9 | — |
| Docker/Testcontainers | Integration tests in `mvn verify` | Assumed yes | — | Run `mvn test` (unit only) if Docker unavailable |

No missing blocking dependencies. Docker is required for the full `mvn verify` IT suite but
Phase 58 already confirmed the suite passes on this machine.

---

## Validation Architecture

`workflow.nyquist_validation` is not explicitly set to `false` in `.planning/config.json`
(key is absent). Section included.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 via Maven Surefire + Failsafe |
| Config file | `pom.xml` (Surefire/Failsafe plugin config) |
| Quick run command | `mvn test -pl . -q` (unit tests only, ~474 tests) |
| Full suite command | `mvn verify` (unit + IT, ~774 tests) |

### Phase Requirements → Test Map

This phase has no new requirements (no REQ-IDs). The only verification needed is:

| Check | Behavior | Test Type | Command |
|-------|----------|-----------|---------|
| SC-1 | DisbursementOrchestrator Javadoc no longer lists "Fee evaluation" | Manual grep | `grep -n "Fee evaluation" src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java` → must return empty |
| SC-2 | DisbursementOrchestrator Javadoc no longer lists "Wallet balance reserve" | Manual grep | `grep -n "Wallet balance reserve" src/main/java/.../DisbursementOrchestrator.java` → empty |
| SC-3 | DisbursementOrchestrator Javadoc no BAL-02/BAL-03 references | Manual grep | `grep -n "BAL-02\|BAL-03" src/main/java/.../DisbursementOrchestrator.java` → empty |
| SC-4 | DisbursementCallbackTransitionService Javadoc clean | Manual grep | `grep -n "wallet release\|walletBalanceService.release\|BAL-02" src/main/java/.../DisbursementCallbackTransitionService.java` → empty |
| SC-5 | No regressions | Full suite | `mvn verify` → EXIT_CODE=0 |

### Wave 0 Gaps

None — no new test files required. Verification is by grep and `mvn verify`.

---

## Common Pitfalls

### Pitfall 1: Broken `<ol>` after `<li>` removal
**What goes wrong:** Removing a `<li>` and leaving orphaned closing `</ol>` or mismatched
HTML tags causes `javadoc:javadoc` warnings (treated as errors in strict mode).
**How to avoid:** After editing, verify the `<ol>` still has matching `<li>...</li>` items
and the closing `</ol>` is present.

### Pitfall 2: Step number drift between Javadoc and method body
**What goes wrong:** If a `<li>` step is removed from the `<ol>`, the method body's inline
`// ── Step N:` comments become misaligned (e.g., Javadoc says step 5 = X, body says
`// ── Step 5:` for something different).
**How to avoid:** Rename the step rather than remove it. Keep the 13-step structure intact.

### Pitfall 3: Editing the wrong scope
**What goes wrong:** Editing a method-level Javadoc instead of the class-level Javadoc, or
editing the method body comment instead of the class `/** */` block.
**How to avoid:** The class-level Javadoc starts at the line immediately before `@Service`
and `public class`. The method body comment at line 107 in DisbursementCallbackTransitionService
is already correct — do NOT change it.

### Pitfall 4: Over-editing DisbursementCallbackTransitionService
**What goes wrong:** The audit identified stale text at original "lines 30, 61, 64-65" but
the current file no longer has those strings. Over-confident editing could inadvertently
remove correct prose.
**How to avoid:** Read the current file first at execution time, grep for the stale strings,
and only edit if they are actually present.

---

## Code Examples

### Correct post-edit step 5 Javadoc `<li>` for DisbursementOrchestrator

```java
// BEFORE (stale):
<li>Fee evaluation — fee = ZERO (FEE-01; disbursements carry no fee)</li>

// AFTER (accurate — removes misleading "Fee evaluation" label):
<li>fee = BigDecimal.ZERO (FEE-01 — disbursements carry no fee; no FeeEvaluationService call)</li>
```

### Correct post-edit step 6 Javadoc `<li>` for DisbursementOrchestrator

```java
// BEFORE (if "Wallet balance reserve (PESSIMISTIC_WRITE)" appears — no longer in file):
<li>Wallet balance reserve (PESSIMISTIC_WRITE)</li>

// CURRENT (step 6 already reads):
<li>Step-up gate decision (status = INITIATED or PENDING_CONFIRMATION)</li>

// AFTER (update to reflect admin-approval gate added in Phase 56):
<li>Determine flow — admin-approval gate (ADMIN-01) first, then step-up gate;
    routes to PENDING_ADMIN_APPROVAL, PENDING_CONFIRMATION, or INITIATED</li>
```

### Grep commands to verify success criteria at execution time

```bash
# SC-1: must return no matches
grep -n "Fee evaluation" \
  src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java

# SC-2: must return no matches
grep -n "Wallet balance reserve" \
  src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java

# SC-3: must return no matches in Javadoc block (lines 46-82)
grep -n "BAL-02\|BAL-03" \
  src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java

# SC-4a: must return no matches
grep -n "wallet release\|walletBalanceService\.release\|BAL-02" \
  src/main/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionService.java

# SC-5: full build gate
mvn verify
```

---

## Open Questions

1. **DisbursementCallbackTransitionService may already be clean**
   - What we know: The current file at lines 27–47 does not contain the stale strings
     "wallet release (when target=FAILED)", "walletBalanceService.release", or "BAL-02"
     as explicit literals.
   - What's unclear: The audit was written from static analysis of the file at audit time
     (2026-05-05); the file may have been updated during Phase 54–58 execution.
   - Recommendation: At plan execution time, grep the file before editing. If already clean,
     record "verified clean, no edit needed" in the plan task output. Do not force an edit
     where none is required.

2. **Step 6 in DisbursementOrchestrator — how to describe the admin-approval gate**
   - What we know: Step 6 currently reads "Step-up gate decision (status = INITIATED or
     PENDING_CONFIRMATION)" which omits the admin-approval path added in Phase 56.
   - What's unclear: Whether the planner wants to expand step 6 or leave it as-is and only
     fix the "Fee evaluation" step 5 label.
   - Recommendation: Fix both — step 5 rename (required by SC-1) and step 6 expansion
     (improves accuracy and is consistent with the current method body at lines 204–216).

---

## Sources

### Primary (HIGH confidence)
- Direct read of `DisbursementOrchestrator.java` (lines 1–571, 2026-05-05)
- Direct read of `DisbursementCallbackTransitionService.java` (lines 1–183, 2026-05-05)
- Direct read of `.planning/v11-MILESTONE-AUDIT.md` — authoritative tech debt list

### Secondary (MEDIUM confidence)
- `.planning/REQUIREMENTS.md` — FEE-01 definition confirms fee = ZERO semantics
- `.planning/STATE.md` — carry-forward decisions confirm wallet model retirement (SCHEMA-03)

### Tertiary
None.

---

## Metadata

**Confidence breakdown:**
- Exact edit targets: HIGH — files read in full; stale text pinpointed at specific lines
- Architecture: HIGH — pure Javadoc edit, no code logic
- Pitfalls: HIGH — based on direct inspection of file structure
- DisbursementCallbackTransitionService stale text: MEDIUM — audit says stale, current
  file reading suggests already partially cleaned; grep at execution time is required

**Research date:** 2026-05-05
**Valid until:** Stable — Javadoc state is fixed until next source change to these files
