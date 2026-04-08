---
phase: 32-email-notification-infrastructure
verified: 2026-04-08T20:15:00Z
status: gaps_found
score: 9/10 must-haves verified
gaps:
  - truth: "Admin and tenant receive email when API key is generated, rotated, revoked, or reactivated"
    status: partial
    reason: "TenantApiKeyEvent.Action.REACTIVATED is defined, the listener maps it, and tenantApiKeyReactivated.html exists, but no service method ever publishes TenantApiKeyEvent with Action.REACTIVATED. There is no ApiKeyService.reactivate(Long keyId) method. TenantService.reactivate() publishes TenantStatusChangedEvent(REACTIVATED) — a tenant-level event, not a key-level REACTIVATED event. NOTIF-04 is structurally prepared but the publishing hook is missing."
    artifacts:
      - path: "src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java"
        issue: "No reactivate(Long keyId) method; TenantApiKeyEvent.Action.REACTIVATED is never published by any service"
      - path: "src/main/java/com/softropic/payam/tenant/service/TenantService.java"
        issue: "reactivate() publishes TenantStatusChangedEvent(REACTIVATED), not TenantApiKeyEvent(REACTIVATED)"
    missing:
      - "Either add ApiKeyService.reactivate(Long keyId) that publishes TenantApiKeyEvent(REACTIVATED), or clarify that NOTIF-04 maps to tenant reactivation generating a new key (in which case the TenantService.reactivate() method should also publish TenantApiKeyEvent(GENERATED) for the newly-issued key)"
human_verification:
  - test: "Verify email actually delivered end-to-end for tenant lifecycle events"
    expected: "Admin and tenant inbox receive correctly formatted emails on API key generation, rotation, revocation, webhook secret regeneration, suspension, reactivation, and email/URL changes"
    why_human: "End-to-end email delivery requires running application with configured mail provider; cannot verify programmatically without live environment"
---

# Phase 32: Email Notification Infrastructure Verification Report

**Phase Goal:** Implement email notification infrastructure for tenant lifecycle events — API key operations (generated, rotated, revoked, reactivated), status changes (suspended, reactivated, email changed, webhook URL changed), and webhook secret regeneration. Wire event publishing into TenantService and ApiKeyService. Create TenantLifecycleEmailListener that converts domain events to email envelopes. Provide Thymeleaf templates and i18n keys for all 6 notification types.
**Verified:** 2026-04-08T20:15:00Z
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|---------|
| 1 | Six new EmailTemplate enum values exist with correct subjectKey references | VERIFIED | EmailTemplate.java lines 13-18: TENANT_API_KEY_GENERATED through TENANT_STATUS_CHANGED, all with `email.tenant.*.title` keys |
| 2 | Three domain event record types exist for API key, status change, and webhook secret events | VERIFIED | TenantApiKeyEvent.java, TenantStatusChangedEvent.java, TenantWebhookSecretRegeneratedEvent.java all exist as plain Java records in `tenant.contract.event` package |
| 3 | Six Thymeleaf HTML templates render correct content for each event type | VERIFIED | All 6 files exist in `mails/`, all have `xmlns:th`, inline CSS, `#{email.closing}`, `${map.helpCode}`, correct variable bindings; tenantStatusChanged.html has `th:switch` with all 4 cases |
| 4 | i18n subject keys exist in messages.properties, messages_en.properties, and messages_fr.properties | VERIFIED | All 6 `email.tenant.*.title` keys present in all 3 property files; French translations are distinct (not copies of English) |
| 5 | Admin and tenant receive email when API key is generated, rotated, revoked, or reactivated | PARTIAL | GENERATED (TenantService.createTenant), ROTATED (ApiKeyService.rotate), REVOKED (ApiKeyService.revoke) all published. REACTIVATED action is defined and handled by listener+template but **no service publishes TenantApiKeyEvent(REACTIVATED)** — see gap |
| 6 | Admin and tenant receive email on webhook secret regeneration with no secret in body | VERIFIED | TenantService.regenerateWebhookSecret publishes TenantWebhookSecretRegeneratedEvent; tenantWebhookSecretRegenerated.html has no secret variable binding; test asserts `doesNotContainKey("secret")` |
| 7 | Admin and tenant receive email on tenant suspended, reactivated, and webhookUrl changed | VERIFIED | TenantService publishes TenantStatusChangedEvent for SUSPENDED, REACTIVATED, WEBHOOK_URL_CHANGED |
| 8 | On email change, notification goes to old address only plus admin | VERIFIED | TenantService.updateEmail captures `oldEmail` before setter call; listener routes EMAIL_CHANGED to `event.oldValue()` not `event.tenantEmail()`; test verifies old address routing |
| 9 | All emails fire after transaction commit, not during or on rollback | VERIFIED | Services are `@Transactional`; listener uses `@Transactional @EventListener` which publishes Envelope to MailManager; MailManager uses `@TransactionalEventListener(AFTER_COMMIT)` (per existing pattern in PlatformConfigEmailListener chain) |
| 10 | When tenant.email is null, notification goes to admin only without error | VERIFIED | buildRecipients() null-checks tenantEmail; tests confirm 1-recipient envelope on null email; no exception thrown |

**Score:** 9/10 truths verified (Truth 5 is PARTIAL)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/softropic/payam/tenant/contract/event/TenantApiKeyEvent.java` | Domain event for API key lifecycle | VERIFIED | Plain record with fields: tenantName, tenantEmail, keyPrefix, environment, action (enum), occurredAt |
| `src/main/java/com/softropic/payam/tenant/contract/event/TenantStatusChangedEvent.java` | Domain event for tenant status changes | VERIFIED | Plain record with fields: tenantName, tenantEmail, eventType (enum), occurredAt, oldValue, newValue |
| `src/main/java/com/softropic/payam/tenant/contract/event/TenantWebhookSecretRegeneratedEvent.java` | Domain event for webhook secret regeneration | VERIFIED | Plain record with fields: tenantName, tenantEmail, occurredAt; no secret field |
| `src/main/java/com/softropic/payam/email/contract/EmailTemplate.java` | Extended enum with 6 new TENANT_* values | VERIFIED | 6 values added after PLATFORM_CONFIG_CHANGED; correct subjectKeys matching i18n keys |
| `src/main/resources/mails/tenantApiKeyGenerated.html` | Thymeleaf template for NOTIF-01 | VERIFIED | 46 lines; keyPrefix, environment, generatedAt, monospace code block, security note |
| `src/main/resources/mails/tenantApiKeyRotated.html` | Thymeleaf template for NOTIF-02 | VERIFIED | 45 lines; rotatedAt in data; monospace key prefix |
| `src/main/resources/mails/tenantApiKeyRevoked.html` | Thymeleaf template for NOTIF-03 | VERIFIED | 45 lines; revokedAt in data |
| `src/main/resources/mails/tenantApiKeyReactivated.html` | Thymeleaf template for NOTIF-04 | VERIFIED | 45 lines; reactivatedAt in data — template exists but no publisher produces this event |
| `src/main/resources/mails/tenantWebhookSecretRegenerated.html` | Thymeleaf template for NOTIF-05 | VERIFIED | 31 lines; no secret variable; security note directs to admin portal |
| `src/main/resources/mails/tenantStatusChanged.html` | Thymeleaf template for NOTIF-06 with th:switch | VERIFIED | 48 lines; th:switch on eventType with SUSPENDED (red border), REACTIVATED, EMAIL_CHANGED, WEBHOOK_URL_CHANGED (with oldValue/newValue table), and default cases |
| `src/main/java/com/softropic/payam/email/infrastructure/listener/TenantLifecycleEmailListener.java` | Event listener converting domain events to Envelope | VERIFIED | @Component; 3 @Transactional @EventListener methods; buildRecipients helper; mapActionToTemplate and mapActionToTimestampKey helpers |
| `src/main/java/com/softropic/payam/tenant/service/TenantService.java` | Updated service publishing events on all lifecycle ops | PARTIAL | Publishes for createTenant(GENERATED), updateEmail(EMAIL_CHANGED), updateWebhookUrl(WEBHOOK_URL_CHANGED), suspend(SUSPENDED), reactivate(REACTIVATED tenant status), regenerateWebhookSecret. Missing: key-level REACTIVATED event |
| `src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java` | Updated service publishing TenantApiKeyEvent for rotate/revoke | VERIFIED | publishEvent for ROTATED (in rotate()) and REVOKED (in revoke()); no event in generateAndStore() (correct per D-02 decision) |
| `src/test/java/com/softropic/payam/email/infrastructure/listener/TenantLifecycleEmailListenerTest.java` | Unit tests for listener | VERIFIED | 12 tests all pass; covers all 4 API key actions, null tenant email, all 4 status event types, webhook secret; EMAIL_CHANGED old-address routing asserted; security constraint asserted |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| EmailTemplate.java | messages.properties | subjectKey matching `email.tenant.*.title` | WIRED | All 6 subjectKey strings match all 6 keys present in messages.properties |
| EmailTemplate.java | mails/ templates | CaseFormat.UPPER_UNDERSCORE.to(LOWER_CAMEL) resolution | WIRED | TENANT_API_KEY_GENERATED -> tenantApiKeyGenerated.html; verified all 6 mapping names match file names |
| TenantService.java | TenantLifecycleEmailListener.java | ApplicationEventPublisher.publishEvent(domainEvent) | WIRED | `publisher.publishEvent()` called in 5 of 6 service methods; listener @EventListener handles all 3 event types |
| TenantLifecycleEmailListener.java | MailManager | publisher.publishEvent(Envelope) -> @TransactionalEventListener(AFTER_COMMIT) | WIRED | listener publishes Envelope via publisher; follows same pattern as PlatformConfigEmailListener |
| TenantService.java | TenantStatusChangedEvent.java | import and instantiation of event record | WIRED | TenantStatusChangedEvent imported and instantiated in 4 methods (EMAIL_CHANGED, WEBHOOK_URL_CHANGED, SUSPENDED, REACTIVATED) |
| ApiKeyService.java | TenantApiKeyEvent.java | import and instantiation | WIRED | TenantApiKeyEvent imported and instantiated in rotate() (ROTATED) and revoke() (REVOKED) |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|-------------------|--------|
| tenantApiKeyGenerated.html | `${map.keyPrefix}` | TenantLifecycleEmailListener.onApiKeyEvent -> event.keyPrefix() -> ApiKeyService/TenantService | Yes — keyPrefix is from saved TenantApiKey entity | FLOWING |
| tenantStatusChanged.html | `${map.eventType}` | TenantLifecycleEmailListener.onStatusChanged -> event.eventType().name() | Yes — enum value from TenantStatusChangedEvent | FLOWING |
| tenantWebhookSecretRegenerated.html | `${map.regeneratedAt}` | TenantLifecycleEmailListener.onWebhookSecretRegenerated -> event.occurredAt().toString() | Yes — Instant from service | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| TenantLifecycleEmailListenerTest passes | `mvn test -Dtest=TenantLifecycleEmailListenerTest` | Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS | PASS |
| REACTIVATED action published | grep for `Action.REACTIVATED` in service publish calls | No match — no service publishes TenantApiKeyEvent(REACTIVATED) | FAIL |
| Security: no secret in webhook template | grep for rawKey/webhookSecret/secret as variable binding | Only prose text mentions "secret"; no `${map.secret}` binding | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|---------|
| NOTIF-01 | 32-01, 32-02 | Email when API key generated | SATISFIED | TenantService.createTenant publishes TenantApiKeyEvent(GENERATED); listener maps to TENANT_API_KEY_GENERATED template; template exists with keyPrefix, environment, generatedAt |
| NOTIF-02 | 32-01, 32-02 | Email when API key rotated (prefix + env, no raw key) | SATISFIED | ApiKeyService.rotate publishes TenantApiKeyEvent(ROTATED) with keyPrefix (not rawKey); tenantApiKeyRotated.html has rotatedAt; no raw key variable |
| NOTIF-03 | 32-01, 32-02 | Email when API key manually revoked | SATISFIED | ApiKeyService.revoke publishes TenantApiKeyEvent(REVOKED); tenantApiKeyRevoked.html exists |
| NOTIF-04 | 32-01, 32-02 | Email when revoked API key is reactivated | BLOCKED | Infrastructure exists (enum value, listener handler, template) but no service publishes TenantApiKeyEvent(REACTIVATED). No `ApiKeyService.reactivate(Long keyId)` method exists. |
| NOTIF-05 | 32-01, 32-02 | Email when webhook secret regenerated | SATISFIED | TenantService.regenerateWebhookSecret publishes TenantWebhookSecretRegeneratedEvent; template has no secret binding |
| NOTIF-06 | 32-01, 32-02 | Email on suspended, reactivated, email changed (old addr), webhookUrl changed | SATISFIED | All 4 TenantStatusChangedEvent.EventType variants published from TenantService; EMAIL_CHANGED captures oldEmail before setter; tenantStatusChanged.html handles all 4 with th:switch |

### Anti-Patterns Found

| File | Pattern | Severity | Impact |
|------|---------|---------|--------|
| ApiKeyService.java | No `reactivate(Long keyId)` method; TenantApiKeyEvent.Action.REACTIVATED is unreachable in production | Warning | NOTIF-04 cannot fire — listener and template are dead code for the REACTIVATED action |

No TODO/FIXME/placeholder comments found in any phase 32 files. No hardcoded empty data flows to rendering. No raw key material or webhook secret values in any template or event.

### Human Verification Required

#### 1. End-to-End Email Delivery

**Test:** Configure a test tenant in a running environment. Perform each operation: generate API key, rotate key, revoke key, regenerate webhook secret, suspend tenant, reactivate tenant, change tenant email, change tenant webhookUrl.
**Expected:** Admin and tenant inbox receive emails for each event with correct content (key prefix shown, no raw key, correct routing for email-changed to old address).
**Why human:** Requires a live running application with a configured SMTP/mail provider. Cannot verify delivery programmatically without infrastructure.

#### 2. Thymeleaf Template Rendering

**Test:** Trigger the `SUSPENDED` case in tenantStatusChanged.html and verify the red left-border paragraph renders correctly in a real email client.
**Expected:** Red border (`border-left: 4px solid #c0392b`) visible; text "Your account has been suspended" displayed.
**Why human:** Email client CSS rendering varies; inline CSS behavior must be visually confirmed.

### Gaps Summary

One gap blocks full goal achievement:

**NOTIF-04 publisher missing:** `TenantApiKeyEvent.Action.REACTIVATED` is a dead enum value. The enum, listener handler, and Thymeleaf template are all in place, but no code path ever publishes this event. The original plan acknowledged that `ApiKeyService.reactivate()` might not exist and proposed a fallback — but neither fallback was implemented. The result is that the "revoked API key reactivated" email can never fire in production. The fix requires either adding an `ApiKeyService.reactivate(Long keyId)` method with `publisher.publishEvent(new TenantApiKeyEvent(..., Action.REACTIVATED, ...))`, or determining that tenant reactivation (TenantService.reactivate) should also publish a TenantApiKeyEvent(GENERATED) for the newly-issued key (which would change the semantic interpretation of NOTIF-04).

---

_Verified: 2026-04-08T20:15:00Z_
_Verifier: Claude (gsd-verifier)_
