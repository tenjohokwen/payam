# Phase 56: Claim Lifecycle & Admin Approval — Research

**Researched:** 2026-05-04
**Domain:** Disbursement claim state transitions, admin-approval routing, Quartz expiry, Insufficient Funds alerting
**Confidence:** HIGH — all findings drawn from existing codebase; no speculative library selection required

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CLAIM-01 | PENDING claim rows created atomically at disbursement acceptance (already done in Phase 55 `TransactionClaimValidationService.validateAndClaim`) | Confirm claim-create is complete; CLAIM-01 is the insertion step — Phase 56 covers transitions OUT of PENDING |
| CLAIM-02 | All claims transition PENDING → CLAIMED when disbursement reaches SUCCESS | `DisbursementCallbackTransitionService.applyDisbursementTransition` must call a new claim-transition method on SUCCESS |
| CLAIM-03 | All claims transition to RELEASED when disbursement reaches FAILED (any reason) | Same service; FAILED path also calls claim-release; also in `DisbursementOrchestrator.releaseAndFail` |
| CLAIM-04 | All claims transition to RELEASED when PENDING_ADMIN_APPROVAL auto-expires | New `AdminApprovalExpiryJob` or extension of `DisbursementExpiryJob` calls claim-release on EXPIRED from admin path |
| CLAIM-05 | Claims remain CLAIMED when PROCESSING disbursement times out to EXPIRED | `DisbursementCallbackTransitionService` EXPIRED path — do NOT release claims; v10 expiry job only hits PENDING_CONFIRMATION |
| ADMIN-01 | Disbursements > admin-approval threshold route to PENDING_ADMIN_APPROVAL; Platform Ops notified | New branch in `DisbursementOrchestrator.initiate()` after step-up gate; new `PayamDisbursementProperties` config class |
| ADMIN-02 | `admin_note` stored on disbursement row; field never returned in public API | `Disbursement.adminNote` field already exists (V31 column); must populate it on PENDING_ADMIN_APPROVAL transition |
| ADMIN-03 | PENDING_ADMIN_APPROVAL auto-expires after `payam.disbursement.admin-approval-timeout-hours`; claims released | New Quartz job (or extend existing) + claim-release on EXPIRED transition |
| ALERT-01 | Provider Insufficient Funds → FAILED + claims released + high-priority alert to Platform Ops | Detect IF error code in `DisbursementCallbackTransitionService` and `DisbursementOrchestrator`; publish alert event |
</phase_requirements>

---

## Summary

Phase 56 wires the claim lifecycle transitions that Phase 55 set up the infrastructure for, adds the PENDING_ADMIN_APPROVAL routing branch to the orchestrator, creates a second Quartz expiry job for admin-approval timeout, and introduces a best-effort alert mechanism for Insufficient Funds errors.

Every schema artifact and entity field needed for Phase 56 already exists: `disbursement_transaction_ref` table with V31 DDL, `DisbursementTransactionRef` JPA entity, `DisbursementRefStatus` enum (PENDING/CLAIMED/RELEASED), `DisbursementTransactionRefRepository`, `Disbursement.adminNote` field, `Disbursement.retryCount` field, and the `PENDING_ADMIN_APPROVAL` state in `DisbursementStatus`. Phase 56 is a pure service-layer and configuration phase — zero new migrations required.

The primary implementation sites are: (1) `DisbursementTransactionRefRepository` — needs bulk-transition query methods, (2) `DisbursementCallbackTransitionService` — must call claim-transition on SUCCESS/FAILED, (3) `DisbursementOrchestrator.releaseAndFail()` — must call claim-release, (4) `DisbursementOrchestrator.initiate()` — needs admin-approval routing branch, (5) a new `DisbursementAdminApprovalExpiryJob` Quartz job, (6) a new `DisbursementProperties` config class, and (7) an `InsufficientFundsAlertService` for ALERT-01.

**Primary recommendation:** Implement claim-lifecycle transitions as `@Modifying` bulk JPQL updates in `DisbursementTransactionRefRepository` — they are multi-row atomic operations where audit trail does not require per-row revision capture, consistent with the `TenantService.suspend()` key-revocation precedent.

---

## Standard Stack

### Core (all pre-existing — no new dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Data JPA `@Modifying` | Spring Boot 3.5 | Bulk `UPDATE` for claim status transitions | Project pattern for multi-row atomic state changes (see `TenantService.suspend()` precedent) |
| Spring `TransactionTemplate` | Spring Boot 3.5 | Programmatic transaction boundaries without class-level `@Transactional` | Established disbursement pattern — prevents holding DB connection across provider HTTP |
| Quartz `QuartzJobBean` | Spring Boot 3.5 (bundled) | Scheduled expiry of PENDING_ADMIN_APPROVAL disbursements | Existing pattern: `DisbursementExpiryJob`, `MtnStatusPollerJob`; `@DisallowConcurrentExecution` for cluster safety |
| `@ConfigurationProperties` | Spring Boot 3.5 | Bind `payam.disbursement.*` config block | Existing pattern: `PayamPlatformProperties`, `EmailProperties`, `MtnMoMoConfig` |
| Spring `ApplicationEventPublisher` | Spring Boot 3.5 | Best-effort alert event dispatch for ADMIN-01/ALERT-01 notifications | Established pattern: `WebhookEnqueueRequestedEvent`, `PlatformConfigChangedEvent` |
| `MailManager` / `Envelope` / `EmailTemplate` | Existing email module | Ops email notification for ADMIN-01 alert | Same infrastructure as all existing email notifications |

### No New Dependencies

Phase 56 requires zero new Maven dependencies. Slack/PagerDuty integration for ALERT-01 is out of scope (best-effort email matches project capability); the spec says "Slack/PagerDuty/Email" — email is the implemented channel.

---

## Architecture Patterns

### Recommended Project Structure

All new code goes inside the existing `disbursement` module. No new module or package needed.

```
disbursement/
├── config/
│   ├── DisbursementSchedulerConfig.java          (existing — extend with new job beans)
│   └── DisbursementAdminApprovalSchedulerConfig  (new — OR add to existing config class)
├── contract/
│   └── DisbursementOrchestratorError.java        (existing — add INSUFFICIENT_PROVIDER_FUNDS)
├── repo/
│   └── DisbursementTransactionRefRepository.java (existing — add bulk-update query methods)
├── service/
│   ├── DisbursementOrchestrator.java             (existing — add admin-approval branch in initiate())
│   ├── DisbursementCallbackTransitionService.java (existing — add claim-transition calls)
│   ├── DisbursementService.java                  (existing — add transitionToPendingAdminApproval)
│   ├── DisbursementClaimTransitionService.java   (NEW — claim bulk-update orchestration)
│   ├── DisbursementAdminApprovalExpiryJob.java   (NEW — Quartz job for ADMIN-03)
│   └── InsufficientFundsAlertService.java        (NEW — ALERT-01 best-effort notification)
└── infrastructure/
    └── listener/
        └── DisbursementOpsAlertEmailListener.java (NEW — @EventListener for alert event)
```

### Pattern 1: Bulk JPQL Claim-Status Transitions

**What:** `@Modifying` JPQL `UPDATE` on `DisbursementTransactionRef` keyed by `disbursementId` and current `refStatus`. Single SQL UPDATE for all claims of a disbursement — atomic, no N+1.

**When to use:** Any point in the disbursement lifecycle where ALL claims of a disbursement change status together (SUCCESS → CLAIMED, FAILED → RELEASED, admin-EXPIRED → RELEASED).

**Precedent in codebase:**
```java
// From TenantService.suspend() — bulk JPQL for multi-row atomic revocation:
// @Modifying
// @Query("UPDATE TenantApiKey k SET k.status = 'REVOKED' WHERE k.tenantId = :tenantId")
// int revokeAllKeysForTenant(@Param("tenantId") Long tenantId);
```

**New repository methods needed:**
```java
// DisbursementTransactionRefRepository

/** CLAIM-02: PENDING → CLAIMED when disbursement reaches SUCCESS. */
@Modifying
@Query("UPDATE DisbursementTransactionRef r SET r.refStatus = :target " +
       "WHERE r.disbursementId = :disbursementId AND r.refStatus = :current")
int updateRefStatusForDisbursement(
    @Param("disbursementId") Long disbursementId,
    @Param("current") DisbursementRefStatus current,
    @Param("target") DisbursementRefStatus target);

/** CLAIM-03/04: bulk RELEASED — works for both FAILED and admin-EXPIRED paths. */
// Reuse same method above with current=PENDING, target=RELEASED
```

**Why a single parameterized method:** PENDING→CLAIMED (SUCCESS path) and PENDING→RELEASED (FAILED/admin-expired path) share identical structure — only the `current` and `target` enum values differ. One method covers all transitions, is testable in isolation, and avoids duplication.

### Pattern 2: PENDING_ADMIN_APPROVAL Branch in DisbursementOrchestrator

**What:** After the existing step-up gate (Step 6), add a second gate checking `request.amount().compareTo(adminApprovalThreshold) > 0`. The admin-approval gate takes priority over the provider-dispatch path. Claims are still created in Step 7.5 (PENDING state) so the backing transactions are locked during the approval window.

**State machine decision:**
- `amount > STEP_UP_THRESHOLD (500K XAF)` AND `amount <= adminApprovalThreshold` → `PENDING_CONFIRMATION` (merchant step-up, unchanged)
- `amount > adminApprovalThreshold` → `PENDING_ADMIN_APPROVAL` (bypasses merchant step-up entirely)

**Critical ordering question — which threshold is larger?** The admin-approval threshold is expected to be LARGER than the step-up threshold (e.g., 5,000,000 XAF vs 500,000 XAF). If `adminApprovalThreshold <= STEP_UP_THRESHOLD`, the PENDING_CONFIRMATION path would never be reached for those amounts. The planner must document the threshold ordering assumption clearly. The safest implementation evaluates admin-approval FIRST (highest priority gate), then step-up, so the precedence is unambiguous regardless of configured values.

```java
// In DisbursementOrchestrator.initiate() — Step 6 replacement
boolean adminApproval = request.amount().compareTo(disbursementProperties.getAdminApprovalThreshold()) > 0;
boolean stepUp        = !adminApproval &&
                        request.amount().compareTo(STEP_UP_THRESHOLD) > 0;

DisbursementStatus initialStatus = adminApproval
        ? DisbursementStatus.PENDING_ADMIN_APPROVAL
        : stepUp
            ? DisbursementStatus.PENDING_CONFIRMATION
            : DisbursementStatus.INITIATED;
```

After Step 7.5 (claims created), add early-return for `adminApproval`:
```java
if (adminApproval) {
    disbursementService.transitionToPendingAdminApproval(disbursementId, adminNote);
    alertService.notifyOpsAdminApprovalRequired(dsb);       // ADMIN-02 best-effort
    DisbursementResponse response = DisbursementResponse.accepted(
            disbursementId, "PENDING_ADMIN_APPROVAL", null, ...);
    idempotencyService.store(tenantId, request.idempotencyKey(), 202, JsonUtil.toJson(response));
    return response;
}
```

### Pattern 3: Claim Transition in DisbursementCallbackTransitionService

**What:** After `locked.applyTransition(target)` in `applyDisbursementTransition()`, call the bulk-update method to transition all associated claims atomically in the SAME `REQUIRES_NEW` transaction.

```java
// After: locked.applyTransition(target); disbursementRepository.save(locked);
if (target == DisbursementStatus.SUCCESS) {
    claimTransitionService.transitionClaims(
        locked.getId(), DisbursementRefStatus.PENDING, DisbursementRefStatus.CLAIMED);
} else if (target == DisbursementStatus.FAILED) {
    claimTransitionService.transitionClaims(
        locked.getId(), DisbursementRefStatus.PENDING, DisbursementRefStatus.RELEASED);
    // ALERT-01: check if failure was insufficient funds
    if (isInsufficientFunds(result)) {
        alertService.notifyOpsInsufficientFunds(locked, result);
    }
}
// CLAIM-05: target == EXPIRED (PROCESSING → EXPIRED) — no claim transition
```

**Why `locked.getId()` not `locked.getDisbursementId()`:** The `DisbursementTransactionRef.disbursementId` field is the BIGINT PK (from `AbstractAuditingEntity`), not the UUID string `disbursementId`. This is established in `TransactionClaimValidationService.validateAndClaim()` which passes `dsb.getId()`.

### Pattern 4: Admin Approval Expiry Job (ADMIN-03)

**What:** Second Quartz job mirroring `DisbursementExpiryJob` exactly, but targeting `PENDING_ADMIN_APPROVAL` status with the configurable `admin-approval-timeout-hours` window. Releases all claims to RELEASED on expiry.

**Key differences from DisbursementExpiryJob:**
- Status filter: `PENDING_ADMIN_APPROVAL` instead of `PENDING_CONFIRMATION`
- Age window: configurable hours (from `DisbursementProperties`) instead of hardcoded 15 minutes
- Post-transition: MUST call `claimTransitionService.transitionClaims(..., PENDING, RELEASED)` (CLAIM-04)
- Does NOT release wallet (wallet model retired — same as expiry job)

```java
@DisallowConcurrentExecution
@Component
public class DisbursementAdminApprovalExpiryJob extends QuartzJobBean {
    // ...
    private void run() {
        long ageMinutes = disbursementProperties.getAdminApprovalTimeoutHours() * 60L;
        List<Disbursement> candidates = disbursementRepository
                .findExpiredCandidates(DisbursementStatus.PENDING_ADMIN_APPROVAL.name(), ageMinutes);
        for (Disbursement candidate : candidates) {
            transactionTemplate.execute(status -> {
                Disbursement locked = disbursementRepository
                        .findByDisbursementIdForUpdate(candidate.getDisbursementId()).orElse(null);
                if (locked == null) return false;
                if (locked.getDisbursementStatus() != DisbursementStatus.PENDING_ADMIN_APPROVAL) return false;
                locked.applyTransition(DisbursementStatus.EXPIRED);
                claimTransitionService.transitionClaims(
                    locked.getId(), DisbursementRefStatus.PENDING, DisbursementRefStatus.RELEASED);
                return true;
            });
        }
    }
}
```

**Reuse `findExpiredCandidates`:** The existing `DisbursementRepository.findExpiredCandidates(status, ageMinutes)` native query accepts the status as a String parameter — it already supports arbitrary statuses. No new repository query is needed.

### Pattern 5: DisbursementProperties Config Class

**What:** New `@ConfigurationProperties(prefix = "payam.disbursement")` class holding `adminApprovalThreshold` and `adminApprovalTimeoutHours`.

**Pattern:** Mirrors `PayamPlatformProperties` exactly.

```java
@ConfigurationProperties(prefix = "payam.disbursement")
public class DisbursementProperties {
    /** Amount threshold above which disbursements require admin approval. Default: 5,000,000 XAF. */
    private BigDecimal adminApprovalThreshold = BigDecimal.valueOf(5_000_000);
    /** Hours after which a PENDING_ADMIN_APPROVAL disbursement auto-expires. Default: 24. */
    private int adminApprovalTimeoutHours = 24;
    // getters/setters
}
```

**Registration:** Add `@EnableConfigurationProperties(DisbursementProperties.class)` to a config class — e.g., a new `DisbursementConfig.java` in `disbursement/config/`.

**YAML binding:**
```yaml
payam:
  disbursement:
    admin-approval-threshold: 5000000
    admin-approval-timeout-hours: 24
```

### Pattern 6: releaseAndFail() Claim Release (CLAIM-03)

**What:** `DisbursementOrchestrator.releaseAndFail()` currently only calls `disbursementService.transitionToFailed()`. It must also release claims. However, `releaseAndFail` is called from error paths where a disbursement row may or may not have claims (e.g., if claim creation itself threw, the disbursement has no claims yet). The implementation must handle the zero-claim case gracefully.

```java
private void releaseAndFail(Long tenantId, BigDecimal totalAmount, String disbursementId) {
    try {
        transactionTemplate.execute(st -> {
            Disbursement locked = disbursementRepository
                .findByDisbursementIdForUpdate(disbursementId).orElseThrow(...);
            locked.applyTransition(DisbursementStatus.FAILED);
            // Release any existing PENDING claims (may be 0 if claim creation failed)
            claimTransitionService.transitionClaimsByDisbursementId(
                locked.getId(), DisbursementRefStatus.PENDING, DisbursementRefStatus.RELEASED);
            return null;
        });
    } catch (Exception ex) { ... }
}
```

**Note:** `claimTransitionService.transitionClaims()` calling `@Modifying` bulk UPDATE with 0 matching rows returns 0 — no exception thrown. This makes it safe to call unconditionally.

### Anti-Patterns to Avoid

- **Entity-level save per claim row:** Do NOT load each `DisbursementTransactionRef`, set status, and call `save()`. This is N+1 writes. Use bulk `@Modifying` JPQL. The audit trail per row is NOT required here — `DisbursementTransactionRef` is `@Audited` but the audit table captures the UPDATE regardless.
- **`@Transactional` on `DisbursementOrchestrator`:** The orchestrator is intentionally NOT `@Transactional` at the class level. Each discrete write is wrapped in `transactionTemplate.execute()`. Do not break this invariant — adding class-level `@Transactional` would hold a DB connection across all 11 initiation steps including provider HTTP calls.
- **Claim transition outside the disbursement-state transaction:** Claim transitions and disbursement status transitions MUST commit atomically. In `DisbursementCallbackTransitionService` (which is `@Transactional(REQUIRES_NEW)`), both the `locked.applyTransition()` save and the `claimTransitionService.transitionClaims()` update execute in the same `REQUIRES_NEW` transaction. In the `DisbursementOrchestrator.releaseAndFail()` path, both must be inside the same `transactionTemplate.execute()` block.
- **Calling WalletBalanceService anywhere:** Wallet model retired in Phase 54/55 (SCHEMA-03). The `DisbursementAdminApprovalExpiryJob` must NOT call `WalletBalanceService.release()`. Claims are released; wallet is not touched.
- **Self-invocation of `@Transactional` methods:** The `DisbursementCallbackTransitionService` uses a separate bean (`@Transactional(REQUIRES_NEW)`) to avoid self-invocation proxy bypass — same pattern must be followed if `DisbursementClaimTransitionService` uses `@Transactional`.
- **New Flyway migration:** No migration is needed for Phase 56. All columns (`admin_note`, `retry_count`) and tables (`disbursement_transaction_ref`) were created in V31. The state machine already has `PENDING_ADMIN_APPROVAL`. Adding a migration would be wrong.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Multi-row claim status update | Loop + individual entity saves | `@Modifying` JPQL bulk UPDATE | Existing project precedent (`TenantService.suspend()`); single SQL vs N writes; atomic |
| Scheduled admin-approval expiry | Custom thread/timer | Quartz `QuartzJobBean` | Already used for `DisbursementExpiryJob`; cluster-safe via JDBC store; `@DisallowConcurrentExecution` |
| Property binding | `@Value` annotations scattered in services | `@ConfigurationProperties` class | Existing pattern; type-safe; Spring Boot refreshable; testable with `@SpringBootTest` property override |
| Ops email notification | Direct `JavaMailSender` call | `Envelope`/`EmailTemplate`/`MailManager` | Full infrastructure already built with retry, scheduling, circuit breaker |
| Concurrent lock on disbursement for expiry | Read + update in same non-locking query | `findByDisbursementIdForUpdate` + re-check under lock | Established pattern; prevents race with /confirm or callback arriving during expiry job tick |

---

## Runtime State Inventory

Step 2.5 SKIPPED — Phase 56 is not a rename/refactor/migration phase. No stored string keys, service config, or OS registrations are changing.

---

## Environment Availability

Step 2.6 SKIPPED — Phase 56 has no external tool dependencies beyond the existing PostgreSQL + Redis + WireMock stack that is already verified operational.

---

## Common Pitfalls

### Pitfall 1: Forgetting CLAIM-05 — claims stay CLAIMED on PROCESSING → EXPIRED
**What goes wrong:** A developer sees "EXPIRED" and instinctively releases claims, reasoning "the disbursement failed." But PROCESSING → EXPIRED is an internal timeout — the provider may have already sent the funds. Claims must stay CLAIMED for manual ops reconciliation.
**Why it happens:** The FAILED path releases claims; EXPIRED looks similar. The distinction is the SOURCE state: PENDING_ADMIN_APPROVAL → EXPIRED releases claims (CLAIM-04); PROCESSING → EXPIRED does NOT (CLAIM-05).
**How to avoid:** In `DisbursementCallbackTransitionService`, only release claims when `target == DisbursementStatus.FAILED`. When `target == DisbursementStatus.EXPIRED`, do nothing with claims. The admin-approval expiry job handles its own EXPIRED path explicitly.
**Warning signs:** A test that verifies `PROCESSING → EXPIRED` and asserts `refStatus = RELEASED` — it should assert `refStatus = CLAIMED`.

### Pitfall 2: Admin-approval threshold ordering ambiguity
**What goes wrong:** If `adminApprovalThreshold <= STEP_UP_THRESHOLD (500,000)`, the step-up gate never fires for those amounts. The `PENDING_CONFIRMATION` path becomes unreachable.
**Why it happens:** The two thresholds are checked independently; there is no guard preventing misconfiguration.
**How to avoid:** Evaluate admin-approval FIRST (larger amount wins). Document in code that `adminApprovalThreshold` is expected to exceed `STEP_UP_THRESHOLD`. Add a startup assertion or `@PostConstruct` warning log if `adminApprovalThreshold <= STEP_UP_THRESHOLD`.
**Warning signs:** `StepUpConfirmationE2EIT` starts failing for amounts above `adminApprovalThreshold` — they are routed to `PENDING_ADMIN_APPROVAL` instead of `PENDING_CONFIRMATION`.

### Pitfall 3: releaseAndFail called when claims don't exist yet
**What goes wrong:** `releaseAndFail()` is called when `validateAndClaim()` itself throws (claim creation failed). At that point, there are zero `DisbursementTransactionRef` rows for this disbursement. The bulk UPDATE returns 0 rows — which is fine — but if the code is written as "find all claims, update each," a null/empty list causes a no-op silently, which is correct behavior. The bug only appears if the code asserts `count > 0`.
**How to avoid:** Bulk `@Modifying` UPDATE returning 0 is not an error. Do not assert a positive row count in the `releaseAndFail` path.

### Pitfall 4: Claim transition outside the REQUIRES_NEW transaction boundary
**What goes wrong:** If `claimTransitionService.transitionClaims()` is called AFTER the `REQUIRES_NEW` transaction in `DisbursementCallbackTransitionService` commits, the claim transition runs in a separate transaction. A crash between the two leaves the disbursement FAILED but claims still PENDING — those transactions appear claimed but the disbursement is done.
**How to avoid:** `DisbursementClaimTransitionService` must be `@Transactional` with default propagation (`REQUIRED`). When called from inside the `@Transactional(REQUIRES_NEW)` `applyDisbursementTransition()` method, Spring joins the existing `REQUIRES_NEW` transaction. Both the disbursement update and the claim update commit atomically.

### Pitfall 5: DisbursementAdminApprovalExpiryJob using wrong disbursementId type
**What goes wrong:** `claimTransitionService.transitionClaims()` takes `Long disbursementId` (the BIGINT PK). The job iterates `Disbursement` candidates and may accidentally pass `candidate.getDisbursementId()` (the UUID String) instead of `candidate.getId()` (the BIGINT PK).
**How to avoid:** Always use `locked.getId()` (the `AbstractAuditingEntity` PK) when looking up `DisbursementTransactionRef` rows. The `DisbursementTransactionRef.disbursementId` column is defined as BIGINT FK to `main.disbursement(id)` — confirmed in V31 DDL and entity.

### Pitfall 6: DisbursementExpiryJob existing tests broken by admin-approval job
**What goes wrong:** If the admin-approval expiry job is wired in the same `DisbursementSchedulerConfig` and the test uses `spring.quartz.auto-startup=false`, the existing `DisbursementExpiryJobIT` and `DisbursementExpiryE2EIT` will still pass. But if the new job uses `@Autowired DisbursementExpiryJob` in the admin path by mistake, tests fail.
**How to avoid:** Keep the two jobs as separate beans. `DisbursementExpiryJob` handles `PENDING_CONFIRMATION`. `DisbursementAdminApprovalExpiryJob` handles `PENDING_ADMIN_APPROVAL`. Wire separately.

### Pitfall 7: INSUFFICIENT_PROVIDER_FUNDS detection — no standard error code from MTN/Orange
**What goes wrong:** MTN and Orange do not use a unified Insufficient Funds error code. The detection must be based on the provider's raw error response, which may arrive via callback (async) or from the initial disbursement call (sync). The mapping is provider-specific and may need to be looked up against the actual provider API spec.
**How to avoid:** Introduce an `InsufficientFundsDetector` utility that examines the provider `rawStatus` or HTTP response body for known patterns. Keep it conservative: only fire the alert when the signal is unambiguous. Best-effort means: if the detection is uncertain, log a warning but do not alert. The error code `INSUFFICIENT_PROVIDER_FUNDS` (for IDEM-03 in Phase 57) must be stored on the disbursement's failure metadata.
**Current state:** No existing `INSUFFICIENT_PROVIDER_FUNDS` error detection exists in the codebase. `MtnStatusMapper` and `OrangeStatusMapper` map provider raw statuses to `TransactionStatus` (SUCCESS/FAILED etc.) but do not distinguish failure sub-reasons. Phase 56 must add this distinction.

### Pitfall 8: `admin_note` leaking into public API response
**What goes wrong:** `admin_note` is populated on the `Disbursement` entity (ADMIN-02). If `DisbursementResponse` is auto-serialized from the entity or maps all fields, the note leaks to the merchant.
**How to avoid:** `DisbursementResponse` is a separate record/DTO (confirmed in existing code). It does NOT have an `adminNote` field. The note is set on the entity only — it never appears in `DisbursementResponse`. Verify via a test or `@JsonIgnore` annotation.

---

## Code Examples

### Verified: DisbursementTransactionRefRepository bulk-update pattern

The existing `findClaimedTransactionIds` JPQL query confirms the `DisbursementTransactionRef` is fully queryable by `transactionId` and `refStatus`. The new bulk-update method follows the same JPQL style:

```java
// Pattern: mirrors TenantApiKey bulk revocation in TenantService.suspend()
@Modifying
@Query("UPDATE DisbursementTransactionRef r " +
       "SET r.refStatus = :target " +
       "WHERE r.disbursementId = :disbursementId " +
       "AND r.refStatus = :current")
int updateRefStatusForDisbursement(
    @Param("disbursementId") Long disbursementId,
    @Param("current") DisbursementRefStatus current,
    @Param("target") DisbursementRefStatus target);
```

### Verified: DisbursementRepository.findExpiredCandidates reuse

The existing query accepts `status` as a `String` parameter and `ageMinutes` as a `long`. The admin-approval expiry job reuses it with `DisbursementStatus.PENDING_ADMIN_APPROVAL.name()` and `adminApprovalTimeoutHours * 60L`:

```java
// Existing in DisbursementRepository — no change needed
@Query(value = "SELECT * FROM main.disbursement d " +
               "WHERE d.disbursement_status = :status " +
               "AND d.created_date < NOW() - CAST(:ageMinutes || ' minutes' AS INTERVAL)",
       nativeQuery = true)
List<Disbursement> findExpiredCandidates(
        @Param("status") String status, @Param("ageMinutes") long ageMinutes);
```

### Verified: Quartz Job pattern

From `DisbursementExpiryJob`:
```java
@DisallowConcurrentExecution
@Component
public class DisbursementAdminApprovalExpiryJob extends QuartzJobBean {

    @Override
    protected void executeInternal(JobExecutionContext context) {
        Observation.createNotStarted("quartz.admin-approval-expiry", observationRegistry)
                .lowCardinalityKeyValue("job", "DisbursementAdminApprovalExpiryJob")
                .observe(this::run);
    }
    // ...
}
```

### Verified: Email notification pattern (for ADMIN-02 and ALERT-01)

From `PlatformConfigEmailListener`:
```java
// @EventListener (synchronous) — consistent with project convention
// MailManager handles AFTER_COMMIT on the Envelope — no double-wrapping
@EventListener
public void onAdminApprovalRequired(DisbursementAdminApprovalEvent event) {
    final Envelope envelope = new Envelope(
        List.of(opsRecipient),
        EmailTemplate.DISBURSEMENT_ADMIN_APPROVAL_REQUIRED,
        Instant.now().plus(Duration.ofDays(7)),
        data,
        UUID.randomUUID().toString()
    );
    publisher.publishEvent(envelope);
}
```

### Verified: DisbursementCallbackTransitionService transaction propagation

The `applyDisbursementTransition()` method is `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Any `@Transactional` service called from within it (default `REQUIRED` propagation) joins the `REQUIRES_NEW` transaction. This is the correct way to achieve atomicity without nested transaction complexity.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Wallet balance release on FAILED | Claim transition to RELEASED on FAILED | Phase 54 (V31 migration) | Wallet model retired; claim table is now the locking mechanism |
| Single-threshold expiry (PENDING_CONFIRMATION only) | Two-threshold expiry (PENDING_CONFIRMATION at 15min + PENDING_ADMIN_APPROVAL at configurable hours) | Phase 56 (this phase) | Need separate Quartz job — cannot reuse same job without conditional branching |
| No admin-approval flow | PENDING_ADMIN_APPROVAL state with configurable threshold | Phase 54 added state; Phase 56 adds production logic | Orchestrator now has three routing paths: small → INITIATED, medium → PENDING_CONFIRMATION, large → PENDING_ADMIN_APPROVAL |

**Deprecated/outdated after Phase 56:**
- `DisbursementExpiryJobIT` still uses `MerchantWalletBalance` — that test must be updated to remove wallet assertions (the wallet assertion tests BAL-03 which is now moot in v11)

---

## Open Questions

1. **Insufficient Funds raw status detection**
   - What we know: MTN and Orange both return some form of "insufficient funds" in their provider callback or transfer status response. No existing mapping distinguishes IF from other failures in `MtnStatusMapper`/`OrangeStatusMapper`.
   - What's unclear: The exact `rawStatus` string from MTN (`FAILED` + error reason in body?) and Orange for Insufficient Funds. This needs to be checked against the actual MTN MoMo Disbursements API spec and Orange spec.
   - Recommendation: For Phase 56, detect based on HTTP 4xx response during initial dispatch (provider returns error synchronously) or a known `rawStatus` pattern in callback. If uncertain, implement the alert as "fire on any FAILED that occurred in the provider dispatch step with a specific error keyword." Phase 58 E2E will validate this against WireMock.

2. **Admin-approval threshold default value**
   - What we know: Requirements say "default: 500,000 XAF, configurable" — but 500,000 XAF is also the STEP_UP_THRESHOLD. If both are 500K, the admin-approval path would always trigger before merchant step-up, making PENDING_CONFIRMATION unreachable.
   - What's unclear: Whether the 500K default in the requirements is intentional (replacing step-up) or a documentation error (should be a larger value).
   - Recommendation: Implement admin-approval threshold default as 5,000,000 XAF (10× the step-up threshold) and document this assumption. The requirements text "500,000 XAF" for admin-approval appears to be copied from the step-up threshold — the two flows are explicitly described as co-existing and distinct (ADMIN-01 says "existing PENDING_CONFIRMATION merchant step-up flow is unaffected"), which implies they target different amounts. The planner should verify with the user.

3. **`DisbursementClaimTransitionService` vs direct repository call**
   - What we know: The bulk update can be called directly from `DisbursementCallbackTransitionService` and from `DisbursementAdminApprovalExpiryJob`.
   - What's unclear: Whether a dedicated `DisbursementClaimTransitionService` wrapper adds value or is unnecessary indirection.
   - Recommendation: Extract into a dedicated service for two reasons: (a) it is called from multiple sites (callback transition, orchestrator `releaseAndFail`, admin expiry job), and (b) it encapsulates the "which status transitions are valid" logic in one place, making it independently testable.

---

## Validation Architecture

**Framework:** JUnit 5 + Spring Boot Test + Testcontainers (real PostgreSQL) + WireMock
**Config file:** None (Spring Boot auto-config; test profiles `dev,test`)
**Quick run command:** `mvn test -pl . -Dtest=DisbursementClaimTransitionServiceTest,DisbursementAdminApprovalExpiryJobIT -q`
**Full suite command:** `mvn verify -q`

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CLAIM-01 | PENDING claim rows created atomically | Unit + IT (Phase 55 done) | `mvn test -Dtest=TransactionClaimValidationServiceTest` | Yes |
| CLAIM-02 | PENDING → CLAIMED on SUCCESS | Unit | `mvn test -Dtest=DisbursementCallbackTransitionServiceTest` | Yes (extend) |
| CLAIM-03 | PENDING → RELEASED on FAILED (any reason) | Unit + IT | `mvn test -Dtest=DisbursementCallbackTransitionServiceTest` | Yes (extend) |
| CLAIM-04 | PENDING → RELEASED on admin-EXPIRED | IT | `mvn test -Dtest=DisbursementAdminApprovalExpiryJobIT` | No — Wave 0 |
| CLAIM-05 | Claims stay CLAIMED on PROCESSING → EXPIRED | Unit | `mvn test -Dtest=DisbursementCallbackTransitionServiceTest` | Yes (extend) |
| ADMIN-01 | Amount > threshold → PENDING_ADMIN_APPROVAL | Unit + IT | `mvn test -Dtest=DisbursementOrchestratorTest,DisbursementOrchestratorIT` | Yes (extend) |
| ADMIN-02 | `admin_note` stored; not in public API response | Unit | `mvn test -Dtest=DisbursementOrchestratorTest` | Yes (extend) |
| ADMIN-03 | Auto-expiry after timeout-hours | IT | `mvn test -Dtest=DisbursementAdminApprovalExpiryJobIT` | No — Wave 0 |
| ALERT-01 | IF error → FAILED + RELEASED + alert published | Unit | `mvn test -Dtest=DisbursementCallbackTransitionServiceTest` | Yes (extend) |

### Sampling Rate

- **Per task commit:** `mvn test -Dtest=<affected test class> -q`
- **Per wave merge:** `mvn verify -q`
- **Phase gate:** `mvn verify` full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `DisbursementAdminApprovalExpiryJobIT` — covers CLAIM-04, ADMIN-03; new file
- [ ] `DisbursementClaimTransitionServiceTest` — unit tests for bulk-update method (covers CLAIM-02, CLAIM-03, CLAIM-04, CLAIM-05 at unit level)
- [ ] New `EmailTemplate` entries: `DISBURSEMENT_ADMIN_APPROVAL_REQUIRED`, `DISBURSEMENT_INSUFFICIENT_FUNDS_ALERT` — needed before listener can compile
- [ ] New `DisbursementProperties` class — needed before orchestrator extension compiles

---

## Project Constraints (from CLAUDE.md)

No `CLAUDE.md` found in the working directory. Constraints are inferred from existing codebase conventions documented in PROJECT.md and STATE.md.

**Inferred mandatory conventions:**
- `mvn verify` must pass before every commit — non-negotiable
- No `@Transactional` at the `DisbursementOrchestrator` class level — use `TransactionTemplate` per discrete write
- Log with `kv()` structured arguments (logstash), never `{}` interpolation
- No `WalletBalanceService` calls anywhere in the disbursement path (SCHEMA-03 — retired)
- `BigDecimal` comparisons use `compareTo()` not `equals()` (scale-insensitive)
- `@Modifying` JPQL preferred over entity-level saves for multi-row atomic operations
- All new Quartz jobs: `@DisallowConcurrentExecution`, `QuartzJobBean` subclass, `ObservationRegistry` wrapping
- New email templates require a Thymeleaf HTML file in `src/main/resources/mails/`
- `adminNote` field must NEVER appear in any `DisbursementResponse` constructor or field mapping

---

## Sources

### Primary (HIGH confidence)

- Codebase — `DisbursementOrchestrator.java` — initiation steps 1–11, step-up gate pattern
- Codebase — `DisbursementCallbackTransitionService.java` — REQUIRES_NEW propagation, transition atomicity contract
- Codebase — `DisbursementExpiryJob.java` — Quartz pattern, findExpiredCandidates reuse, ObservationRegistry wrapping
- Codebase — `DisbursementTransactionRef.java` + `DisbursementTransactionRefRepository.java` — entity shape, query style
- Codebase — `DisbursementStatus.java` — state machine; PENDING_ADMIN_APPROVAL transitions verified
- Codebase — `DisbursementRefStatus.java` — PENDING/CLAIMED/RELEASED semantics documented
- Codebase — `DisbursementRepository.java` — findExpiredCandidates signature; findByDisbursementIdForUpdate pattern
- Codebase — `V31__disbursement_transaction_ref.sql` — V31 schema confirmed; `admin_note`, `retry_count`, partial unique index
- Codebase — `Disbursement.java` — `adminNote` field exists; `retryCount` field exists
- Codebase — `PayamPlatformProperties.java` — @ConfigurationProperties pattern
- Codebase — `PlatformConfigEmailListener.java` — @EventListener email dispatch pattern
- Codebase — `TransactionClaimValidationService.java` — CLAIM-01 already implemented in Phase 55; `dsb.getId()` is the BIGINT FK

### Secondary (MEDIUM confidence)

- STATE.md decisions — confirmed: PENDING_ADMIN_APPROVAL has no FAILED transition (goes through PROCESSING); admin rejection flows through PROCESSING; DisbursementOrchestrator uses TransactionTemplate pattern
- REQUIREMENTS.md CLAIM/ADMIN/ALERT section — defines transition semantics and config property names

### Tertiary (LOW confidence)

- Insufficient Funds raw status string from MTN MoMo Disbursements API — not verified against actual provider response; detection logic requires provider spec confirmation before implementation

---

## Metadata

**Confidence breakdown:**
- Claim lifecycle transitions: HIGH — schema, entities, state machine all verified in codebase
- Admin-approval routing: HIGH — DisbursementStatus, Orchestrator pattern, DisbursementProperties pattern all confirmed
- Quartz admin-expiry job: HIGH — exact pattern from DisbursementExpiryJob; findExpiredCandidates reuse confirmed
- Insufficient Funds alerting: MEDIUM — alert mechanism (email via existing infrastructure) is HIGH; IF detection (rawStatus pattern from providers) is LOW

**Research date:** 2026-05-04
**Valid until:** 2026-06-04 (stable domain; no external library churn risk)
