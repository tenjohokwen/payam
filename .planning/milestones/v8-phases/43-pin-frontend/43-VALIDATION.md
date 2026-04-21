---
phase: 43
slug: pin-frontend
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-18
---

# Phase 43 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | vitest (Vue/Vite project) |
| **Config file** | vitest.config.js or vite.config.js |
| **Quick run command** | `npx vitest run --reporter=verbose` |
| **Full suite command** | `npx vitest run` |
| **Estimated runtime** | ~10 seconds |

---

## Sampling Rate

- **After every task commit:** Run `npx vitest run --reporter=verbose`
- **After every plan wave:** Run `npx vitest run`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 43-01-01 | 01 | 1 | PIN-06 | unit | `npx vitest run --reporter=verbose` | ✅ / ❌ W0 | ⬜ pending |
| 43-01-02 | 01 | 1 | PIN-07 | unit | `npx vitest run --reporter=verbose` | ✅ / ❌ W0 | ⬜ pending |
| 43-01-03 | 01 | 2 | PIN-08 | manual | see Manual-Only | n/a | ⬜ pending |
| 43-01-04 | 01 | 2 | PIN-09 | manual | see Manual-Only | n/a | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/tests/PlatformConfigPage.spec.js` — stubs for PIN-06, PIN-07 (reveal/mask cycle, countdown timer)

*Note: Timer/reveal cycle tests are best validated via component tests (Vue Test Utils + vitest). Manual verification covers the 60s countdown UX and dialog behavior.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| 60-second countdown auto-masks field | PIN-07 | Timer behavior hard to unit-test reliably without fake timers | Reveal PIN, wait 60s, verify field re-masks and state clears |
| Early re-mask cancels countdown | PIN-07 | Interaction timing; cancel path | Reveal PIN, click eye icon again before expiry, verify immediate re-mask |
| Add Provider dialog PIN field (no timer) | PIN-09 | Dialog interaction flow | Open Add Provider dialog, verify masked PIN field with eye toggle appears; confirm no auto-mask timer |
| Save preserves existing PIN when left blank | PIN-08 | Backend state verification | Leave PIN blank on save, verify PUT body omits/nulls PIN; reload and reveal to confirm unchanged |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
