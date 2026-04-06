# Project Retrospective: Payam

*A living document updated after each milestone. Lessons feed forward into future planning.*

---

## Milestone: v3 — E2E Test Suite

**Shipped:** 2026-03-28
**Phases:** 6 (18–23) | **Plans:** 18

### What Was Built
- Abstract E2E base class hierarchy (template method + final orchestrator methods) on real Testcontainers infrastructure
- 10 domain invariant verifiers + 8 test data builders enabling one-call invariant assertions
- MTN/Orange full payment E2E coverage: happy paths, polling fallback, fraud-blocked, idempotency race (20 threads), circuit breaker
- Inbound webhook double-check + replay protection; outbound delivery with HMAC signing and retry verification
- PITest mutation testing ≥90% on 6 critical domain classes; SM path matrix covering all 32 illegal transitions

### What Worked
- **Real database over mocks:** Testcontainers caught a JSONB quoting production bug (in both pollers) that would have been invisible with mocked tests — validates the no-mock strategy completely
- **Template method base class pattern:** final runFlow()/runFailureScenario() prevented all subclasses from diverging; consistent test phase ordering across all 32 classes
- **Gap closure phases (23-04, 23-05):** Adding decimal gap-closure plans to an existing phase worked cleanly — no renumbering overhead
- **Incremental E2E build:** Building infra → verifiers → flows → invariants progressively meant each layer was testable before the next was added

### What Was Inefficient
- **PITest targetClasses scope defined too narrowly upfront:** Had to expand from 3 to 6 classes in a correction round (23-05). Should define MUT-02 target list at plan-time, not post-verification
- **Progress table not updated for Phases 19 and 20:** ROADMAP.md progress table showed "0/2 Not started" for two completed phases — cosmetic but created confusion at milestone completion
- **QueryCountVerifier + 4 builders built but never consumed:** Phase 19 built them speculatively; Phases 20-23 never used ApiKeyBuilder, OrangeWebhookPayloadBuilder, FraudSignalBuilder, ReconciliationReportBuilder

### Patterns Established
- **No-mock integration test rule:** All E2E tests use real PostgreSQL + Redis containers. Mock test failures cannot represent production database behavior.
- **AbstractFailureFlowTest pattern:** Failure flows extend AbstractPayamE2ETest directly (not AbstractPaymentFlowTest) because they inject faults before executeFlow — a distinct phase structure
- **noRetryRestTemplate pattern:** Needed for circuit-breaker tests — Apache HC default retry behavior masks whether the CB is actually open
- **PROPAGATION_REQUIRES_NEW for JDBC backdating:** When raw JDBC must survive Hibernate L1 cache flush on TransactionTemplate commit, use REQUIRES_NEW + new TransactionTemplate

### Key Lessons
1. **Build verifiers before tests, not alongside them.** Phase 19 delivered all verifiers upfront; this made Phases 20-23 significantly faster and more consistent
2. **Concrete scope for PITest targetClasses at plan time.** Discovering the correct set post-verification forces a correction round
3. **Update ROADMAP.md progress table immediately after each phase.** Stale rows persist and mislead milestone readiness checks
4. **Speculative builders have low ROI.** Unless a consumer phase is in the same milestone, defer builder creation to when the consumer phase is planned

### Cost Observations
- Sessions: ~3 (2026-03-26 → 2026-03-28)
- Notable: 131 commits in 3 days — high velocity due to yolo mode and clear phase sequencing

---

## Milestone: v4 — Platform Config & Health

**Shipped:** 2026-04-02
**Phases:** 3 (24–26) | **Plans:** 5

### What Was Built
- Platform MSISDN CRUD (backend + email notification + admin Vue UI)
- Two Spring Boot Actuator HealthIndicator beans with live validateSubscriber() calls and CB state
- Admin health dashboard with ROLE_ADMIN-gated component display and access-denied banner

### What Worked
- **POJO event pattern for email notification:** `PlatformConfigChangedEvent` as a plain record + `@EventListener` + MailManager's AFTER_COMMIT boundary worked cleanly without double-wrapping complexity
- **Actuator `show-details: when-authorized`:** JWT cookie auth on the separate management port (8367) confirmed working — concern about cross-port auth was unfounded; Spring Security context populated correctly
- **Live verification catching real behavior:** Testing against the actual endpoint (not a mock) immediately confirmed the 503 DOWN-state body extraction pattern and the auth behavior on port 8367

### What Was Inefficient
- **Phase 26 SUMMARY.md not written during execution:** Had to be created retroactively at milestone completion. Write SUMMARY immediately after plan execution.
- **Verification done last:** Health dashboard was implemented but not verified until `/gsd:complete-milestone` triggered a check. Earlier verification would have caught the 503 handling requirement sooner.

### Patterns Established
- **Actuator HealthIndicator pattern:** HealthIndicator bean + CircuitBreakerRegistry + PlatformConfigService lookup + validateSubscriber() — reusable for any future provider
- **503 DOWN-body extraction pattern:** `catch (error) { if (error?.response?.data?.status) health.value = error.response.data }` — required when Spring returns 503 for DOWN status but body contains full JSON

### Key Lessons
1. **Write SUMMARY.md immediately after plan completion.** Retroactive creation at milestone time loses nuance and requires re-reading the code.
2. **Verify feature end-to-end before milestone completion.** The 503 handling in the Vue component was a non-obvious requirement not in the plan — only discovered during live verification.
3. **Cross-port cookie auth just works** in Spring Boot when both servers are on localhost and CORS is configured. Don't assume separate management port breaks JWT auth.

---

## Milestone: v5 — Tenant & API Key Management Service Layer

**Shipped:** 2026-04-06
**Phases:** 4 (27–29, including 28.1) | **Plans:** 6

### What Was Built
- Flyway V18/V19/V20/V21 migrations covering schema evolution from v1 to v5 specification
- Full TenantService lifecycle (create/update/suspend/reactivate) and ApiKeyService (generate/rotate/revoke) with AKEY-02/08 guards
- Hibernate Envers audit trail with Flyway V20 DDL and admin identity capture
- AKEY-01 key format fix (PREFIX_UUID) — targeted Phase 28.1 inserted after audit finding
- Quartz rotation cleanup job (AKEY-05) — every 5 minutes, idempotent, with Envers audit per revocation

### What Worked
- **Milestone audit driving gap closure:** The v1.0 audit caught that AKEY-01 was claimed satisfied but not actually implemented. Running an audit before declaring complete is worth doing — it found a real defect.
- **Decimal phase for gap closure:** Phase 28.1 inserted after audit cleanly with zero numbering disruption. One-plan gap closure executed in ~6 minutes.
- **TIMESTAMPTZ migration fixed Quartz test failures:** Root cause of Quartz cleanup job test failures was timezone mismatch (Postgres session in Europe/Berlin, JVM Instant in UTC). V21 migration to TIMESTAMPTZ eliminated the entire class of problem — a good architectural principle: always use TIMESTAMPTZ for columns compared with JVM Instants.
- **Entity-level ops for Envers auditing:** Choosing entity-level load+save (vs bulk JPQL) in `revokeExpiredRotatedKeys()` correctly captures one audit revision per revocation — the right trade-off for auditability.
- **`saveAndFlush` pattern for constrained sibling rows:** Discovered in Phase 27 (rotate() constraint violation), re-confirmed in Phase 28. Now a documented pattern in Key Decisions.

### What Was Inefficient
- **Milestone audit ran against phases 18-28, not 18-29:** AKEY-05 was listed as "Pending" in the audit because Phase 29 hadn't run yet. Re-running audit after all phases complete would give a cleaner final picture.
- **Phase 29 marked as `wip` with a verification pause:** The git log shows `wip: 29-quartz-rotation-cleanup-job paused at verification`. The Quartz job implementation itself was clean — the pause was for TIMESTAMPTZ diagnosis. Timezone issues in Testcontainers are a recurring risk.
- **Accomplishments extracted poorly by gsd-tools CLI:** The `milestone complete` CLI extracted "Task 1 — Test builder updates: / One-liner:" from wrong summary sections. Manual correction required.

### Patterns Established
- **Always TIMESTAMPTZ for Instant-compared timestamp columns.** TIMESTAMP without timezone causes timezone mismatch in Testcontainers environments where Postgres session timezone != JVM timezone. V21 migration pattern: `ALTER COLUMN rotated_at TYPE TIMESTAMPTZ USING rotated_at AT TIME ZONE 'UTC'`.
- **`saveAndFlush` before constrained sibling INSERT in `@Transactional`.** Hibernate batches within the transaction; without explicit flush, constraint ordering is non-deterministic. Pattern: always `saveAndFlush(entity)` before inserting a row that must satisfy a constraint against the flushed entity.
- **Entity-level ops (not bulk JPQL) when Envers audit trail is required.** Bulk JPQL bypasses Envers; if per-row audit is required, load+modify+save each entity individually.

### Key Lessons
1. **Run the milestone audit after all phases complete, not mid-milestone.** An audit run before the final phases execute will correctly show gaps that later phases close — but the audit status remains `gaps_found` and the archived audit document needs manual annotation.
2. **Timezone mismatch is a Testcontainers trap.** If a Quartz/scheduled job test fails with unexpected threshold behavior, check Postgres session timezone (`SHOW timezone`) in the container. Use TIMESTAMPTZ and `NOW() - INTERVAL '...'` in test backdating SQL to sidestep JVM ↔ Postgres timezone conversions.
3. **Document `saveAndFlush` at the site, not just in tests.** The constraint violation error from rotate() was non-obvious; a comment at the `saveAndFlush` call explaining the ordering requirement would save future-developer time.

### Cost Observations
- Sessions: ~4 (2026-04-03 → 2026-04-06)
- Notable: 4 days for schema + full service layer + audit trail + key format fix + Quartz job — high velocity from clear requirement IDs (TENT-xx, AKEY-xx) enabling precise per-requirement tracking

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Phases | Plans | Key Change |
|-----------|--------|-------|------------|
| v1 | 13 | 29 | Greenfield — established module pattern + all domain features |
| v2 | 4 | 12 | Observability layer — zero-mock strategy; kv() structured logging |
| v3 | 6 | 18 | Test suite — Testcontainers over mocks validated by catching JSONB bug |
| v4 | 3 | 5 | Platform ops — Actuator health indicators + admin MSISDN + health dashboard |
| v5 | 4 | 6 | Service layer — tenant/key lifecycle, Envers audit, Quartz cleanup; gap closure via decimal phase |

### Cumulative Quality

| Milestone | New Test Classes | Notable Coverage |
|-----------|-----------------|-----------------|
| v1 | — | Domain + unit tests |
| v2 | — | No new tests |
| v3 | 32 E2E classes | Mutation ≥90%, concurrency races, SM path matrix |
| v4 | — | No new test classes |
| v5 | 4 integration test classes | TenantServiceIT (9), TenantAuditIT (3), TenantProvisioningIT (6 existing), RotatedKeyCleanupJobIT (4) |

### Top Lessons (Verified Across Milestones)

1. **Real infrastructure over mocks.** v3 caught a production JSONB bug that mocks would have masked — confirms the no-mock approach as non-negotiable for this codebase.
2. **Phase sequencing matters.** Building infrastructure → verifiers → flows → invariants progressively made each milestone cleanly deliverable.
3. **Gap-closure decimal plans work.** 23-04, 23-05, and Phase 28.1 all inserted cleanly without renumbering. Use freely for audit findings and verification failures.
4. **Always TIMESTAMPTZ for Instant-compared columns.** Timestamp without timezone causes timezone mismatch in Testcontainers (v5 lesson). One migration to fix; zero risk if done upfront.
