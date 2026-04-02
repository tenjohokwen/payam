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

## Cross-Milestone Trends

### Process Evolution

| Milestone | Phases | Plans | Key Change |
|-----------|--------|-------|------------|
| v1 | 13 | 29 | Greenfield — established module pattern + all domain features |
| v2 | 4 | 12 | Observability layer — zero-mock strategy; kv() structured logging |
| v3 | 6 | 18 | Test suite — Testcontainers over mocks validated by catching JSONB bug |

### Cumulative Quality

| Milestone | New Test Classes | Notable Coverage |
|-----------|-----------------|-----------------|
| v1 | — | Domain + unit tests |
| v2 | — | No new tests |
| v3 | 32 E2E classes | Mutation ≥90%, concurrency races, SM path matrix |

### Top Lessons (Verified Across Milestones)

1. **Real infrastructure over mocks.** v3 caught a production JSONB bug that mocks would have masked — confirms the no-mock approach as non-negotiable for this codebase.
2. **Phase sequencing matters.** Building infrastructure → verifiers → flows → invariants progressively made each milestone cleanly deliverable.
3. **Gap-closure decimal plans work.** 23-04 and 23-05 inserted cleanly without renumbering. Use freely for verification failures.
