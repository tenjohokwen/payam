# Requirements: Payam

**Defined:** 2026-04-17
**Core Value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## v8 Requirements

### Data Model & Encryption

- [ ] **PIN-01**: Admin can persist an AES256-encrypted PIN for a provider — Flyway migration adds nullable `pin` VARCHAR column to `main.platform_config`; plaintext PIN never persists to the database
- [ ] **PIN-02**: System resolves the Cryptopher/Jasypt encryption key from `payam.platform.pin-encryption-secret` (backed by `PLATFORM_PIN_ENCRYPTION_SECRET` env var) — property added to `PayamPlatformProperties`

### Backend API

- [ ] **PIN-03**: Admin can set or update a provider PIN via `PUT /v1/admin/platform-config/{provider}` — optional `pin` field; validated as alphanumeric 4–8 characters (returns 400 on failure); encrypted via Cryptopher before persisting; saved atomically with MSISDN in one transaction
- [ ] **PIN-04**: Admin can see whether a PIN is configured via `GET /v1/admin/platform-config/{provider}` — response includes `pinConfigured: boolean` (`true` if PIN is set); actual PIN value is never returned in this response
- [ ] **PIN-05**: Admin can retrieve the plaintext PIN via `GET /v1/admin/platform-config/{provider}/pin` — PIN is decrypted on demand and returned; returns 404 if no PIN is configured for that provider

### Frontend

- [ ] **PIN-06**: Admin sees an optional PIN input field on each provider card in `PlatformConfigPage.vue` — masked by default (type=password), with Quasar `q-input` password-toggle eye icon
- [ ] **PIN-07**: When admin clicks the eye icon on the provider card, the UI calls `GET /{provider}/pin`, populates the field with plaintext, and starts a strict 60-second countdown that auto-masks the field and clears the plaintext from component state on expiry or on manual re-mask before expiry
- [ ] **PIN-08**: Admin's "Save" button submits MSISDN and PIN in one `PUT` call; an empty PIN field on save retains the existing PIN without overwriting; placeholder text communicates this behaviour to the admin
- [ ] **PIN-09**: Admin sees an optional PIN input field in the Add Provider dialog — same masked Quasar toggle pattern; no auto-mask timer (admin just entered the value)

### Email Notification

- [ ] **PIN-10**: `PlatformConfigChangedEvent` carries `msisdnChanged` (boolean), `pinChanged` (boolean), and `changedBy` (String) — event fires only when MSISDN changed OR PIN changed; first-time PIN creation (was null) does not fire an event
- [ ] **PIN-11**: Email notification states provider name, which field(s) changed (MSISDN / PIN / both), admin username who made the change, and timestamp — PIN value (plaintext or ciphertext) never appears in the email

## Out of Scope

| Feature | Reason |
|---------|--------|
| PIN rotation history / audit trail | No regulatory requirement at this stage; Envers not needed for PlatformConfig |
| Multiple PINs per provider | Single credential per provider is sufficient for current Orange Money spec |
| PIN-protected endpoints beyond platform config | Out of scope for v8; provider API may expand later |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| PIN-01 | Phase 41 | Pending |
| PIN-02 | Phase 41 | Pending |
| PIN-03 | Phase 42 | Pending |
| PIN-04 | Phase 42 | Pending |
| PIN-05 | Phase 42 | Pending |
| PIN-06 | Phase 43 | Pending |
| PIN-07 | Phase 43 | Pending |
| PIN-08 | Phase 43 | Pending |
| PIN-09 | Phase 43 | Pending |
| PIN-10 | Phase 44 | Pending |
| PIN-11 | Phase 44 | Pending |

**Coverage:**
- v8 requirements: 11 total
- Mapped to phases: 11 (100%)
- Unmapped: 0

---
*Requirements defined: 2026-04-17*
*Last updated: 2026-04-17 — traceability mapped to Phases 41–44*
