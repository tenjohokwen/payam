# Roadmap: Payam

## Milestones

- ✅ **v1 Payment API** — Phases 1–13 (shipped 2026-03-26) — see [milestones/v1-ROADMAP.md](milestones/v1-ROADMAP.md)
- ✅ **v2 Logging Standardization** — Phases 14–17 (shipped 2026-03-27) — see [milestones/v2-ROADMAP.md](milestones/v2-ROADMAP.md)
- ✅ **v3 E2E Test Suite** — Phases 18–23 (shipped 2026-03-28) — see [milestones/v3-ROADMAP.md](milestones/v3-ROADMAP.md)
- ✅ **v4 Platform Config & Health** — Phases 24–26 (shipped 2026-04-02) — see [milestones/v4-ROADMAP.md](milestones/v4-ROADMAP.md)
- 🚧 **v5 Tenant & API Key Management** — Phases 27–33 (in progress)

## Phases

<details>
<summary>✅ v1 Payment API (Phases 1–13) — SHIPPED 2026-03-26</summary>

- [x] Phase 1: Multi-Tenant Foundation (3/3 plans) — completed 2026-03-23
- [x] Phase 2: Transaction Core + Event Sourcing (3/3 plans) — completed 2026-03-23
- [x] Phase 3: Orange Money Adapter (4/4 plans) — completed 2026-03-24
- [x] Phase 4: MTN MoMo Adapter (2/2 plans) — completed 2026-03-24
- [x] Phase 5: Payment Orchestration (2/2 plans) — completed 2026-03-24
- [x] Phase 6: Webhook Processing (3/3 plans) — completed 2026-03-24
- [x] Phase 7: Fraud Engine (2/2 plans) — completed 2026-03-24
- [x] Phase 8: Admin Dashboard + Monitoring (3/3 plans) — completed 2026-03-24
- [x] Phase 9: Reconciliation (2/2 plans) — completed 2026-03-25
- [x] Phase 10: Operational Hardening (4/4 plans) — completed 2026-03-25
- [x] Phase 11: Fee Exposure (1/1 plan) — completed 2026-03-25
- [x] Phase 12: Test & Doc Polish (1/1 plan) — completed 2026-03-25
- [x] Phase 13: Ledger Wiring + Webhook Access Control (1/1 plan) — completed 2026-03-26

</details>

<details>
<summary>✅ v2 Logging Standardization (Phases 14–17) — SHIPPED 2026-03-27</summary>

- [x] Phase 14: Logging Infrastructure (1/1 plans) — completed 2026-03-26
- [x] Phase 15: MDC & Request Lifecycle (2/2 plans) — completed 2026-03-27
- [x] Phase 16: Business Event Logging (5/5 plans) — completed 2026-03-27
- [x] Phase 17: Code Standards Enforcement (4/4 plans) — completed 2026-03-27

</details>

<details>
<summary>✅ v3 E2E Test Suite (Phases 18–23) — SHIPPED 2026-03-28</summary>

- [x] Phase 18: Test Infrastructure (2/2 plans) — completed 2026-03-27
- [x] Phase 19: Verifiers + Test Data Builders (2/2 plans) — completed 2026-03-27
- [x] Phase 20: Payment Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 21: Webhook Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 22: Fraud, Reconciliation, and Admin Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 23: Domain Invariants, Concurrency, State Machine, and Mutation Tests (5/5 plans) — completed 2026-03-28

</details>

<details>
<summary>✅ v4 Platform Config & Health (Phases 24–26) — SHIPPED 2026-04-02</summary>

- [x] Phase 24: Platform Configuration (3/3 plans) — completed 2026-03-30
- [x] Phase 25: Provider Health Indicators (1/1 plan) — completed 2026-03-31
- [x] Phase 26: Health Dashboard UI (1/1 plan) — completed 2026-04-02

</details>

#### Phase 27: Schema and Enum Migration
**Goal**: The entity model and database constraints correctly represent the v5 tenant/key specification — v1 defects corrected, environment enum migrated, partial unique index in place
**Depends on**: Phase 26
**Requirements**: AKEY-01, AKEY-03
**Success Criteria** (what must be TRUE):
  1. `Tenant` entity has a non-nullable `keyPrefix` column (`updatable = false`) that stores the 3-char uppercase prefix derived from the tenant name at creation time
  2. `TenantApiKey.environment` maps to `ApiKeyEnvironment` enum with values `PROD`, `DEV`, `SANDBOX` — the legacy `LIVE` value no longer exists in DB or code
  3. A partial unique index `(tenant_id, environment) WHERE key_status = 'ACTIVE'` exists in the database and is enforced by Flyway migration
  4. A UNIQUE constraint on `key_hash` exists on the `tenant_api_key` table
  5. Flyway runs cleanly from a fresh schema with no `UPDATE before CHECK` ordering errors
**Plans**: 2/2 — completed 2026-04-03

Plans:
- [x] 27-01-PLAN.md — Flyway migrations V18/V19 + ApiKeyEnvironment enum + entity model + service updates
- [x] 27-02-PLAN.md — LIVE-to-PROD call site migration across all test files

#### Phase 28: Service Layer
**Goal**: Complete tenant and API key service layer — tenant lifecycle (create/update/suspend/reactivate), per-environment key generation and rotation, WebhookSecret management, and Hibernate Envers audit trail
**Depends on**: Phase 27
**Requirements**: TENT-01, AKEY-02, AKEY-04, AKEY-06, AKEY-08, WSEC-01, AUDIT-01, AUDIT-02, AUDIT-03
**Success Criteria** (what must be TRUE):
  1. Admin can create a tenant with auto-generated TenantRef (UUID), initial PROD API key (raw key returned once), and WebhookSecret
  2. Admin can update a tenant's name, email, and webhookUrl
  3. Admin can suspend a tenant — all API keys across all environments are immediately revoked
  4. Admin can reactivate a suspended tenant — a new PROD key is auto-generated and returned once
  5. Admin can generate a per-environment key (PROD/DEV/SANDBOX) and receive raw key exactly once
  6. Admin can rotate a key — old key enters ROTATED (24h grace), new ACTIVE key raw value returned once; if another ROTATED key exists for same environment it is immediately REVOKED
  7. Admin can manually revoke a key (immediate, no grace period)
  8. Admin can regenerate WebhookSecret (new secret replaces old)
  9. Hibernate Envers captures all Tenant and TenantApiKey mutations; every key generation/rotation event logs acting admin ID and timestamp
**Plans**: 2 plans

Plans:
- [x] 28-01-PLAN.md — Flyway V20 Envers audit tables + TenantService lifecycle + ApiKeyService guards
- [x] 28-02-PLAN.md — TenantServiceIT + TenantAuditIT integration tests + TenantProvisioningIT webhookSecret assertion

#### Phase 28.1: API Key Format Fix (AKEY-01)
**Goal**: Raw API keys returned to callers follow the `PREFIX_UUID` format — human-readable, tenant-namespaced key strings at create, rotate, and reactivate events
**Depends on**: Phase 28
**Requirements**: AKEY-01
**Gap Closure:** Closes AKEY-01 gap from v1.0 audit — `generateSecureKey()` currently returns opaque Base64; secondary fix to `ApiKeyBuilder` test builder and `ApiKeyAuthenticationFilter` debug log
**Success Criteria** (what must be TRUE):
  1. `ApiKeyService.generateSecureKey(tenant)` returns `tenant.getKeyPrefix() + "_" + UUID.randomUUID()` (e.g. `ACM_550e8400-e29b-41d4-a716-446655440000`)
  2. `ApiKeyBuilder` test builder derives `keyPrefix` from the tenant name prefix, not `rawKey.substring(0,8)`
  3. Existing integration tests pass with the new key format
  4. `ApiKeyAuthenticationFilter` debug log no longer uses raw key substring
**Plans**: 1/1 — completed 2026-04-06

Plans:
- [x] 28.1-01-PLAN.md — Fix generateSecureKey PREFIX_UUID format, ApiKeyBuilder prefix lookup, ApiKeyAuthenticationFilter log

#### Phase 29: Quartz Rotation Cleanup Job
**Goal**: Expired ROTATED API keys are automatically revoked after their 24-hour grace period ends — a Quartz scheduled job running every 5 minutes queries for ROTATED keys past their `rotatedAt + 24h` threshold and moves them to REVOKED
**Depends on**: Phase 28, Phase 28.1
**Requirements**: AKEY-05
**Success Criteria** (what must be TRUE):
  1. A Quartz job bean (`RotatedKeyCleanupJob`) is wired in the application context and scheduled to run every 5 minutes
  2. The job queries `TenantApiKey` for records with `status = ROTATED` and `rotatedAt < now() - 24h`
  3. Each qualifying key is moved to `REVOKED` status with `revokedAt` set to the current timestamp
  4. The job is idempotent: re-running it within the same window causes no additional state changes
  5. An integration test (`RotatedKeyCleanupJobIT`) verifies the job revokes an over-24h ROTATED key and leaves an under-24h ROTATED key untouched

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Multi-Tenant Foundation | v1 | 3/3 | Complete | 2026-03-23 |
| 2. Transaction Core + Event Sourcing | v1 | 3/3 | Complete | 2026-03-23 |
| 3. Orange Money Adapter | v1 | 4/4 | Complete | 2026-03-24 |
| 4. MTN MoMo Adapter | v1 | 2/2 | Complete | 2026-03-24 |
| 5. Payment Orchestration | v1 | 2/2 | Complete | 2026-03-24 |
| 6. Webhook Processing | v1 | 3/3 | Complete | 2026-03-24 |
| 7. Fraud Engine | v1 | 2/2 | Complete | 2026-03-24 |
| 8. Admin Dashboard + Monitoring | v1 | 3/3 | Complete | 2026-03-24 |
| 9. Reconciliation | v1 | 2/2 | Complete | 2026-03-25 |
| 10. Operational Hardening | v1 | 4/4 | Complete | 2026-03-25 |
| 11. Fee Exposure | v1 | 1/1 | Complete | 2026-03-25 |
| 12. Test & Doc Polish | v1 | 1/1 | Complete | 2026-03-25 |
| 13. Ledger Wiring + Webhook Access Control | v1 | 1/1 | Complete | 2026-03-26 |
| 14. Logging Infrastructure | v2 | 1/1 | Complete | 2026-03-26 |
| 15. MDC & Request Lifecycle | v2 | 2/2 | Complete | 2026-03-27 |
| 16. Business Event Logging | v2 | 5/5 | Complete | 2026-03-27 |
| 17. Code Standards Enforcement | v2 | 4/4 | Complete | 2026-03-27 |
| 18. Test Infrastructure | v3 | 2/2 | Complete | 2026-03-27 |
| 19. Verifiers + Test Data Builders | v3 | 2/2 | Complete | 2026-03-27 |
| 20. Payment Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 21. Webhook Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 22. Fraud, Reconciliation, Admin Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 23. Domain Invariants, Concurrency, SM, Mutation | v3 | 5/5 | Complete | 2026-03-28 |
| 24. Platform Configuration | v4 | 3/3 | Complete | 2026-03-30 |
| 25. Provider Health Indicators | v4 | 1/1 | Complete | 2026-03-31 |
| 26. Health Dashboard UI | v4 | 1/1 | Complete | 2026-04-02 |
| 27. Schema and Enum Migration | v5 | 2/2 | Complete | 2026-04-03 |
| 28. Service Layer | v5 | 2/2 | Complete    | 2026-04-06 |
| 28.1. API Key Format Fix (AKEY-01) | v5 | 1/1 | Complete    | 2026-04-06 |
| 29. Quartz Rotation Cleanup Job | v5 | 0/? | Not started | — |
| 30. Email Notifications | v5 | 0/? | Not started | — |
| 31. REST API Expansion (+ HTTP surface for tenant lifecycle) | v5 | 0/? | Not started | — |
| 32. Admin UI | v5 | 0/? | Not started | — |
| 33. E2E Tests | v5 | 0/? | Not started | — |
