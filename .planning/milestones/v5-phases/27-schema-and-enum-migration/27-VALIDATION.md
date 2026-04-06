---
phase: 27
slug: schema-and-enum-migration
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-02
---

# Phase 27 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test |
| **Config file** | `src/test/resources/application-test.yml` |
| **Quick run command** | `./mvnw test -pl . -Dtest="*Tenant*,*ApiKey*" -q` |
| **Full suite command** | `./mvnw verify -q` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw test -pl . -Dtest="*Tenant*,*ApiKey*" -q`
- **After every plan wave:** Run `./mvnw verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 27-01-01 | 01 | 1 | AKEY-01 | unit | `./mvnw test -Dtest="TenantEntityTest"` | ❌ W0 | ⬜ pending |
| 27-01-02 | 01 | 1 | AKEY-01 | integration | `./mvnw test -Dtest="TenantRepositoryTest"` | ❌ W0 | ⬜ pending |
| 27-02-01 | 02 | 1 | AKEY-03 | unit | `./mvnw test -Dtest="ApiKeyEnvironmentTest"` | ❌ W0 | ⬜ pending |
| 27-02-02 | 02 | 1 | AKEY-03 | integration | `./mvnw test -Dtest="TenantApiKeyRepositoryTest"` | ❌ W0 | ⬜ pending |
| 27-03-01 | 03 | 2 | AKEY-01,AKEY-03 | integration | `./mvnw test -Dtest="FlywayMigrationTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/.../TenantEntityTest.java` — stubs for AKEY-01 keyPrefix field
- [ ] `src/test/java/.../ApiKeyEnvironmentTest.java` — stubs for AKEY-03 enum migration
- [ ] `src/test/java/.../FlywayMigrationTest.java` — stubs for migration ordering validation

*Existing test infrastructure covers framework; only new test stubs needed.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Flyway migration runs cleanly on fresh schema | AKEY-01, AKEY-03 | Requires running against real PostgreSQL | Drop schema, run `./mvnw flyway:migrate`, verify no errors |
| `key_hash` UNIQUE constraint enforced by DB | AKEY-03 | Requires actual constraint violation test | Insert duplicate key_hash, expect constraint violation |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
