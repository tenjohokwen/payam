---
phase: 32-email-notification-infrastructure
verified: 2026-04-08T21:00:00Z
status: human_needed
score: 10/10 must-haves verified
re_verification:
  previous_status: gaps_found
  previous_score: 9/10
  gaps_closed:
    - "Admin and tenant receive email when API key is generated, rotated, revoked, or reactivated (NOTIF-04 chain now complete)"
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Verify email actually delivered end-to-end for tenant lifecycle events"
    expected: "Admin and tenant inbox receive correctly formatted emails on API key generation, rotation, revocation, reactivation, webhook secret regeneration, suspension, reactivation, and email/URL changes"
    why_human: "End-to-end email delivery requires running application with configured mail provider; cannot verify programmatically without live environment"
  - test: "Thymeleaf template rendering in real email client"
    expected: "The SUSPENDED case in tenantStatusChanged.html renders with red left-border paragraph; text 'Your account has been suspended' displayed correctly"
    why_human: "Email client CSS rendering varies; inline CSS behavior must be visually confirmed"
---

# Phase 32: Email Notification Infrastructure Verification Report

**Phase Goal:** Build the email notification infrastructure that fires transactional emails for all tenant lifecycle events (API key generated, rotated, revoked, reactivated; webhook secret regenerated; tenant status changed).
**Verified:** 2026-04-08T21:00:00Z
**Status:** human_needed
**Re-verification:** Yes — after gap closure (Plan 32-03 closed NOTIF-04)

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|---------|
| 1 | Six new EmailTemplate enum values exist with correct subjectKey references | VERIFIED | EmailTemplate.java: TENANT_API_KEY_GENERATED through TENANT_STATUS_CHANGED, all with `email.tenant.*.title` keys |
| 2 | Three domain event record types exist for API key, status change, and webhook secret events | VERIFIED | TenantApiKeyEvent.java, TenantStatusChangedEvent.java, TenantWebhookSecretRegeneratedEvent.java all exist as plain Java records in `tenant.contract.event` package |
| 3 | Six Thymeleaf HTML templates render correct content for each event type | VERIFIED | All 6 files exist in `mails/`, all have `xmlns:th`, inline CSS, `#{email.closing}`, `${map.helpCode}`, correct variable bindings; tenantStatusChanged.html has `th:switch` with all 4 cases |
| 4 | i18n subject keys exist in messages.properties, messages_en.properties, and messages_fr.properties | VERIFIED | All 6 `email.tenant.*.title` keys present in all 3 property files; French translations are distinct |
| 5 | Admin and tenant receive email when API key is generated, rotated, revoked, or reactivated | VERIFIED | GENERATED (TenantService.createTenant), ROTATED (ApiKeyService.rotate), REVOKED (ApiKeyService.revoke), REACTIVATED (ApiKeyService.reactivate) — all publish TenantApiKeyEvent with correct Action; full chain wired |
| 6 | Admin and tenant receive email on webhook secret regeneration with no secret in body | VERIFIED | TenantService.regenerateWebhookSecret publishes TenantWebhookSecretRegeneratedEvent; tenantWebhookSecretRegenerated.html has no secret variable binding |
| 7 | Admin and tenant receive email on tenant suspended, reactivated, and webhookUrl changed | VERIFIED | TenantService publishes TenantStatusChangedEvent for SUSPENDED, REACTIVATED, WEBHOOK_URL_CHANGED |
| 8 | On email change, notification goes to old address only plus admin | VERIFIED | TenantService.updateEmail captures `oldEmail` before setter call; listener routes EMAIL_CHANGED to `event.oldValue()` not `event.tenantEmail()` |
| 9 | All emails fire after transaction commit, not during or on rollback | VERIFIED | Services are `@Transactional`; listener uses `@Transactional @EventListener` which publishes Envelope to MailManager; MailManager uses `@TransactionalEventListener(AFTER_COMMIT)` |
| 10 | When tenant.email is null, notification goes to admin only without error | VERIFIED | buildRecipients() null-checks tenantEmail; tests confirm 1-recipient envelope on null email |

**Score:** 10/10 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/softropic/payam/tenant/contract/event/TenantApiKeyEvent.java` | Domain event for API key lifecycle | VERIFIED | Plain record with fields: tenantName, tenantEmail, keyPrefix, environment, action (enum GENERATED/ROTATED/REVOKED/REACTIVATED), occurredAt |
| `src/main/java/com/softropic/payam/tenant/contract/event/TenantStatusChangedEvent.java` | Domain event for tenant status changes | VERIFIED | Plain record with fields: tenantName, tenantEmail, eventType (enum), occurredAt, oldValue, newValue |
| `src/main/java/com/softropic/payam/tenant/contract/event/TenantWebhookSecretRegeneratedEvent.java` | Domain event for webhook secret regeneration | VERIFIED | Plain record; no secret field |
| `src/main/java/com/softropic/payam/email/contract/EmailTemplate.java` | Extended enum with 6 new TENANT_* values | VERIFIED | 6 values added with correct subjectKeys matching i18n keys |
| `src/main/resources/mails/tenantApiKeyGenerated.html` | Thymeleaf template for NOTIF-01 | VERIFIED | keyPrefix, environment, generatedAt, monospace code block, security note |
| `src/main/resources/mails/tenantApiKeyRotated.html` | Thymeleaf template for NOTIF-02 | VERIFIED | rotatedAt in data; monospace key prefix |
| `src/main/resources/mails/tenantApiKeyRevoked.html` | Thymeleaf template for NOTIF-03 | VERIFIED | revokedAt in data |
| `src/main/resources/mails/tenantApiKeyReactivated.html` | Thymeleaf template for NOTIF-04 | VERIFIED | reactivatedAt in data; now has a live publishing path via ApiKeyService.reactivate() |
| `src/main/resources/mails/tenantWebhookSecretRegenerated.html` | Thymeleaf template for NOTIF-05 | VERIFIED | No secret variable; security note directs to admin portal |
| `src/main/resources/mails/tenantStatusChanged.html` | Thymeleaf template for NOTIF-06 with th:switch | VERIFIED | th:switch on eventType with SUSPENDED (red border), REACTIVATED, EMAIL_CHANGED, WEBHOOK_URL_CHANGED (with oldValue/newValue table), and default |
| `src/main/java/com/softropic/payam/email/infrastructure/listener/TenantLifecycleEmailListener.java` | Event listener converting domain events to Envelope | VERIFIED | @Component; 3 @Transactional @EventListener methods; buildRecipients helper; mapActionToTemplate and mapActionToTimestampKey helpers |
| `src/main/java/com/softropic/payam/tenant/service/TenantService.java` | Updated service publishing events on all lifecycle ops | VERIFIED | Publishes for createTenant(GENERATED), updateEmail(EMAIL_CHANGED), updateWebhookUrl(WEBHOOK_URL_CHANGED), suspend(SUSPENDED), reactivate(REACTIVATED tenant status), regenerateWebhookSecret |
| `src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java` | Updated service publishing TenantApiKeyEvent for rotate/revoke/reactivate | VERIFIED | publishEvent for ROTATED (rotate()), REVOKED (revoke()), REACTIVATED (reactivate()); AKEY-02 guard in reactivate() |
| `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java` | REST endpoint for key reactivation | VERIFIED | POST /{tenantId}/keys/{keyId}/reactivate returns 204, @PreAuthorize(HAS_ADMIN_ROLE), delegates to apiKeyService.reactivate(keyId) |
| `src/test/java/com/softropic/payam/email/infrastructure/listener/TenantLifecycleEmailListenerTest.java` | Unit tests for listener | VERIFIED | 12 tests pass; covers all 4 API key actions, null tenant email, all 4 status event types, webhook secret; EMAIL_CHANGED old-address routing; security constraint |
| `src/test/java/com/softropic/payam/tenant/service/ApiKeyServiceReactivateTest.java` | Unit tests for reactivate() | VERIFIED | 5 tests pass: happy path, not-found, ACTIVE guard, ROTATED guard, AKEY-02 conflict |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| EmailTemplate.java | messages.properties | subjectKey matching `email.tenant.*.title` | WIRED | All 6 subjectKey strings match all 6 keys present in messages.properties |
| EmailTemplate.java | mails/ templates | CaseFormat.UPPER_UNDERSCORE.to(LOWER_CAMEL) resolution | WIRED | TENANT_API_KEY_GENERATED -> tenantApiKeyGenerated.html; all 6 mapping names match file names |
| TenantService.java | TenantLifecycleEmailListener.java | ApplicationEventPublisher.publishEvent(domainEvent) | WIRED | publisher.publishEvent() called in 5 service methods; listener @EventListener handles all 3 event types |
| ApiKeyService.reactivate() | TenantLifecycleEmailListener.onApiKeyEvent() | publisher.publishEvent(new TenantApiKeyEvent(..., Action.REACTIVATED, ...)) | WIRED | Line 146 of ApiKeyService.java: `TenantApiKeyEvent.Action.REACTIVATED` published; listener already handles all Action values |
| TenantAdminResource.reactivateKey() | ApiKeyService.reactivate() | apiKeyService.reactivate(keyId) | WIRED | Line 122 of TenantAdminResource.java; POST /{tenantId}/keys/{keyId}/reactivate at line 118 |
| TenantLifecycleEmailListener.java | MailManager | publisher.publishEvent(Envelope) -> @TransactionalEventListener(AFTER_COMMIT) | WIRED | Listener publishes Envelope via publisher; follows same pattern as PlatformConfigEmailListener |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|-------------------|--------|
| tenantApiKeyGenerated.html | `${map.keyPrefix}` | TenantLifecycleEmailListener.onApiKeyEvent -> event.keyPrefix() -> TenantService/ApiKeyService | Yes — keyPrefix is from saved TenantApiKey entity | FLOWING |
| tenantApiKeyReactivated.html | `${map.keyPrefix}` | ApiKeyService.reactivate() -> event.keyPrefix() -> key.getKeyPrefix() from DB | Yes — keyPrefix comes from real TenantApiKey entity fetched by ID | FLOWING |
| tenantStatusChanged.html | `${map.eventType}` | TenantLifecycleEmailListener.onStatusChanged -> event.eventType().name() | Yes — enum value from TenantStatusChangedEvent | FLOWING |
| tenantWebhookSecretRegenerated.html | `${map.regeneratedAt}` | TenantLifecycleEmailListener.onWebhookSecretRegenerated -> event.occurredAt().toString() | Yes — Instant from service | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| ApiKeyServiceReactivateTest passes | `mvn test -Dtest=ApiKeyServiceReactivateTest` | Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS | PASS |
| TenantLifecycleEmailListenerTest passes (regression) | `mvn test -Dtest=TenantLifecycleEmailListenerTest` | Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS | PASS |
| Action.REACTIVATED published by service | `grep -n "Action.REACTIVATED" ApiKeyService.java` | Line 146: `TenantApiKeyEvent.Action.REACTIVATED,` | PASS |
| REST endpoint delegates to service | `grep -n "apiKeyService.reactivate" TenantAdminResource.java` | Line 122: `apiKeyService.reactivate(keyId)` | PASS |
| Security: no secret in webhook template | grep for `${map.secret}` binding | Only prose text mentions "secret"; no variable binding | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|---------|
| NOTIF-01 | 32-01, 32-02 | Email when API key generated | SATISFIED | TenantService.createTenant publishes TenantApiKeyEvent(GENERATED); listener maps to TENANT_API_KEY_GENERATED template; template exists with keyPrefix, environment, generatedAt |
| NOTIF-02 | 32-01, 32-02 | Email when API key rotated (prefix + env, no raw key) | SATISFIED | ApiKeyService.rotate publishes TenantApiKeyEvent(ROTATED) with keyPrefix (not rawKey); tenantApiKeyRotated.html has rotatedAt; no raw key variable |
| NOTIF-03 | 32-01, 32-02 | Email when API key manually revoked | SATISFIED | ApiKeyService.revoke publishes TenantApiKeyEvent(REVOKED); tenantApiKeyRevoked.html exists |
| NOTIF-04 | 32-01, 32-02, 32-03 | Email when revoked API key is reactivated | SATISFIED | ApiKeyService.reactivate(Long keyId) added; validates REVOKED status; AKEY-02 guard applied; publishes TenantApiKeyEvent(REACTIVATED); REST endpoint POST /{tenantId}/keys/{keyId}/reactivate; 5 unit tests pass |
| NOTIF-05 | 32-01, 32-02 | Email when webhook secret regenerated (no secret in body) | SATISFIED | TenantService.regenerateWebhookSecret publishes TenantWebhookSecretRegeneratedEvent; template has no secret binding; test asserts `doesNotContainKey("secret")` |
| NOTIF-06 | 32-01, 32-02 | Email on suspended, reactivated, email changed (old addr), webhookUrl changed | SATISFIED | All 4 TenantStatusChangedEvent.EventType variants published from TenantService; EMAIL_CHANGED captures oldEmail before setter; tenantStatusChanged.html handles all 4 with th:switch |

### Anti-Patterns Found

No anti-patterns found. No TODO/FIXME/placeholder comments in any phase 32 files. No hardcoded empty data flows to rendering. No raw key material or webhook secret values in any template or event. The previously-flagged dead code (REACTIVATED action unreachable) is now resolved.

### Human Verification Required

#### 1. End-to-End Email Delivery

**Test:** Configure a test tenant in a running environment. Perform each operation: generate API key, rotate key, revoke key, reactivate key, regenerate webhook secret, suspend tenant, reactivate tenant, change tenant email, change tenant webhookUrl.
**Expected:** Admin and tenant inbox receive emails for each event with correct content (key prefix shown, no raw key, correct routing for email-changed to old address, no webhook secret in body).
**Why human:** Requires a live running application with a configured SMTP/mail provider. Cannot verify delivery programmatically without infrastructure.

#### 2. Thymeleaf Template Rendering in Email Clients

**Test:** Trigger the SUSPENDED case in tenantStatusChanged.html and verify the red left-border paragraph renders correctly in a real email client.
**Expected:** Red border (`border-left: 4px solid #c0392b`) visible; text "Your account has been suspended" displayed.
**Why human:** Email client CSS rendering varies; inline CSS behavior must be visually confirmed.

### Gaps Summary

No gaps remain. The single NOTIF-04 gap from the initial verification (no publisher for `TenantApiKeyEvent(REACTIVATED)`) was closed by Plan 32-03:

- `ApiKeyService.reactivate(Long keyId)` added (lines 126-149), validates REVOKED status, enforces AKEY-02 safety, sets ACTIVE, publishes event
- `POST /{tenantId}/keys/{keyId}/reactivate` endpoint added to `TenantAdminResource` (lines 117-123)
- `ApiKeyServiceReactivateTest` added with 5 tests covering happy path, not-found, ACTIVE guard, ROTATED guard, AKEY-02 conflict — all pass
- Complete NOTIF-04 chain confirmed: REST endpoint -> ApiKeyService.reactivate() -> TenantApiKeyEvent(REACTIVATED) -> TenantLifecycleEmailListener.onApiKeyEvent() -> TENANT_API_KEY_REACTIVATED template -> tenantApiKeyReactivated.html

All 6 requirements (NOTIF-01 through NOTIF-06) are SATISFIED. The phase goal is fully achieved in code.

---

_Verified: 2026-04-08T21:00:00Z_
_Verifier: Claude (gsd-verifier)_
