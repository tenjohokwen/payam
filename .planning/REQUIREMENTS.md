# Requirements: Payam Tenant & API Key Management

**Defined:** 2026-04-02
**Milestone:** v5
**Core Value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## v1 Requirements

### TENT: Tenant Identity & Lifecycle

- [x] **TENT-01**: Admin can create a tenant by providing a name; system auto-generates a TenantRef (UUID, non-editable) and an initial PROD API key (shown once) and WebhookSecret
- [x] **TENT-02**: Admin can update a tenant's name
- [x] **TENT-03**: Admin can update a tenant's email address (optional field used for notifications)
- [x] **TENT-04**: Admin can update a tenant's webhookUrl
- [ ] **TENT-05**: Admin can view a paginated, searchable list of all tenants with their status
- [ ] **TENT-06**: Admin can view a tenant's detail page (name, email, TenantRef, status, webhookUrl)
- [x] **TENT-07**: Admin can suspend an active tenant; all API keys across all environments are immediately revoked
- [x] **TENT-08**: Admin can reactivate a suspended tenant; system automatically generates a new PROD key shown to admin exactly once
- [ ] **TENT-09**: Tenant with SUSPENDED status cannot process API requests (auth rejects all keys regardless of key status)

### AKEY: API Key Specification & Lifecycle

- [ ] **AKEY-01**: API keys follow format `PREFIX_UUID` where prefix is derived from the first 3 characters of the tenant name at tenant creation time (uppercase, 0-padded to 3 chars with "0"), immutable even if tenant name later changes
- [x] **AKEY-02**: Admin can generate a key for a specific environment (PROD, DEV, or SANDBOX) for a tenant; raw key shown exactly once, never retrievable again
- [ ] **AKEY-03**: A tenant can have at most one ACTIVE key per environment at any time (enforced by database-level partial unique index)
- [x] **AKEY-04**: Admin can rotate a key; the old key enters ROTATED status (remains valid for 24 hours), the new key is ACTIVE immediately; raw new key shown exactly once
- [ ] **AKEY-05**: System automatically moves ROTATED keys to REVOKED status after 24 hours via an automated job
- [x] **AKEY-06**: Admin can manually revoke a key (immediate, no grace period; status moves to REVOKED)
- [ ] **AKEY-07**: One-time key display modal requires admin to confirm they have copied the key before it can be dismissed
- [x] **AKEY-08**: If a key is rotated while another ROTATED key is still in its grace period for the same environment, the still-ROTATED key is immediately moved to REVOKED (no two overlapping grace periods per environment)

### WSEC: WebhookSecret

- [x] **WSEC-01**: A unique WebhookSecret (UUID) is auto-generated when a tenant is created
- [ ] **WSEC-02**: Admin can reveal the current WebhookSecret via a dedicated "reveal" action (eye icon in UI)
- [x] **WSEC-03**: Admin can trigger regeneration of the WebhookSecret; new secret replaces the old one

### NOTIF: Notifications

- [ ] **NOTIF-01**: System sends email to platform notification address AND tenant email (if set) on API key generation or rotation
- [ ] **NOTIF-02**: System sends email to platform notification address AND tenant email (if set) on API key revocation or tenant reactivation
- [ ] **NOTIF-03**: System sends email to platform notification address AND tenant email (if set) on WebhookSecret generation/regeneration
- [ ] **NOTIF-04**: System sends email to platform notification address AND tenant email (if set) on tenant status change (ACTIVE ↔ SUSPENDED)
- [ ] **NOTIF-05**: System sends email to platform notification address AND tenant email (if set) when webhookUrl changes
- [ ] **NOTIF-06**: System sends email to platform notification address AND tenant email (if set) when tenant email changes

### AUDIT: Audit & Traceability

- [x] **AUDIT-01**: All changes to tenant fields (name, email, status, webhookUrl, webhookSecret) are captured by Hibernate Envers
- [x] **AUDIT-02**: All changes to API key states (generation, rotation, revocation) are captured by Hibernate Envers
- [ ] **AUDIT-03**: Every key generation and rotation event is logged with the acting admin's ID and a timestamp

## v2 Requirements (deferred to future milestone)

- Audit log viewer UI — Envers data is captured in v5; admin UI to browse/query it deferred
- Key permission scopes (read-only, write-only API keys)
- Self-service tenant portal — admin-managed only in v5
- Bulk tenant operations
- DEV/SANDBOX auto-generation on tenant create — only PROD auto-generated; other envs are on-demand

## Out of Scope

| Feature | Reason |
|---------|--------|
| WebhookSecret encrypted at rest | Reveal requirement makes hash-only storage impossible; plaintext accepted with null-in-list-responses discipline |
| API key permission scopes | Not in v5 spec |
| Tenant self-service portal | Admin-only management in v5 |
| Multi-environment auto-provisioning | Only PROD generated automatically; DEV/SANDBOX on-demand |

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| WebhookSecret stored as plaintext | Required for admin reveal; null in all list/detail responses; only returned on explicit reveal endpoint |
| Re-rotation during grace period immediately revokes old ROTATED key | No two overlapping grace periods per environment; confirmed as expected behavior |
| DEV/SANDBOX keys are on-demand only | Spec generates only PROD on create and reactivation |
| Suspension cascade uses bulk JPQL `@Modifying` update | Atomicity guarantee; entity loop is vulnerable to partial failure |
| Auth filter checks both key status AND tenant status | Defense-in-depth; key-level revocation + tenant-level suspension both enforced |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| TENT-01 | Phase 28 | Complete |
| TENT-02 | Phase 28 | Complete |
| TENT-03 | Phase 28 | Complete |
| TENT-04 | Phase 28 | Complete |
| TENT-05 | Phase 31 | Pending |
| TENT-06 | Phase 31 | Pending |
| TENT-07 | Phase 28 | Complete |
| TENT-08 | Phase 28 | Complete |
| TENT-09 | Phase 31 | Pending |
| AKEY-01 | Phase 27 | Pending |
| AKEY-02 | Phase 28 | Complete |
| AKEY-03 | Phase 27 | Pending |
| AKEY-04 | Phase 28 | Complete |
| AKEY-05 | Phase 29 | Pending |
| AKEY-06 | Phase 28 | Complete |
| AKEY-07 | Phase 32 | Pending |
| AKEY-08 | Phase 28 | Complete |
| WSEC-01 | Phase 28 | Complete |
| WSEC-02 | Phase 32 | Pending |
| WSEC-03 | Phase 28 | Complete |
| NOTIF-01 | Phase 30 | Pending |
| NOTIF-02 | Phase 30 | Pending |
| NOTIF-03 | Phase 30 | Pending |
| NOTIF-04 | Phase 30 | Pending |
| NOTIF-05 | Phase 30 | Pending |
| NOTIF-06 | Phase 30 | Pending |
| AUDIT-01 | Phase 28 | Complete |
| AUDIT-02 | Phase 28 | Complete |
| AUDIT-03 | Phase 28 | Pending |
