---
phase: 60
slug: claim-05-e2e-coverage
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-05
---

# Phase 60 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter) via Spring Boot Test |
| **Config file** | None — Spring Boot auto-configures |
| **Quick run command** | `mvn test -Dtest=DisbursementExpiryE2EIT -DfailIfNoTests=false` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~3–5 minutes (E2E with DB) |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest=DisbursementExpiryE2EIT -DfailIfNoTests=false`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~5 minutes

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 60-01-01 | 01 | 1 | CLAIM-05 | E2E | `mvn test -Dtest=DisbursementExpiryE2EIT` | ✅ (add method) | ⬜ pending |
| Phase gate | 01 | 1 | CLAIM-05 | Full suite | `mvn verify` | N/A | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Existing infrastructure covers all phase requirements. `DisbursementExpiryE2EIT.java` already exists — no new files or framework setup required.

---

## Manual-Only Verifications

All phase behaviors have automated verification.

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
