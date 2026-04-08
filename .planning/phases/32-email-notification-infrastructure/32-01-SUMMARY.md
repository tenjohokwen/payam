---
phase: 32-email-notification-infrastructure
plan: "01"
subsystem: email / tenant-notifications
tags: [email, thymeleaf, i18n, domain-events, tenant]
dependency_graph:
  requires: []
  provides:
    - TenantApiKeyEvent record (GENERATED/ROTATED/REVOKED/REACTIVATED)
    - TenantStatusChangedEvent record (SUSPENDED/REACTIVATED/EMAIL_CHANGED/WEBHOOK_URL_CHANGED)
    - TenantWebhookSecretRegeneratedEvent record
    - EmailTemplate enum: 6 new TENANT_* values
    - 6 Thymeleaf HTML email templates in mails/
    - i18n keys in messages.properties / messages_en.properties / messages_fr.properties
  affects:
    - plan 32-02 (event publishing + listener wire-up)
tech_stack:
  added: []
  patterns:
    - Plain Java record domain events (POJO, no ApplicationEvent extension)
    - Thymeleaf inline-CSS email templates (no linked stylesheets)
    - th:switch/th:case for conditional email body rendering
key_files:
  created:
    - src/main/java/com/softropic/payam/tenant/contract/event/TenantApiKeyEvent.java
    - src/main/java/com/softropic/payam/tenant/contract/event/TenantStatusChangedEvent.java
    - src/main/java/com/softropic/payam/tenant/contract/event/TenantWebhookSecretRegeneratedEvent.java
    - src/main/resources/mails/tenantApiKeyGenerated.html
    - src/main/resources/mails/tenantApiKeyRotated.html
    - src/main/resources/mails/tenantApiKeyRevoked.html
    - src/main/resources/mails/tenantApiKeyReactivated.html
    - src/main/resources/mails/tenantWebhookSecretRegenerated.html
    - src/main/resources/mails/tenantStatusChanged.html
  modified:
    - src/main/java/com/softropic/payam/email/contract/EmailTemplate.java
    - src/main/resources/i18n/messages.properties
    - src/main/resources/i18n/messages_en.properties
    - src/main/resources/i18n/messages_fr.properties
decisions:
  - "[32-01] Domain events are plain Java records — no ApplicationEvent extension, following PlatformConfigChangedEvent pattern"
  - "[32-01] No raw key or webhook secret value in any event or template — security constraint enforced at the contract layer"
  - "[32-01] tenantStatusChanged.html uses th:switch on map.eventType for conditional rendering of 4 distinct body variants"
  - "[32-01] tenantWebhookSecretRegenerated.html has no detail table for the secret value — admin portal is the retrieval path"
metrics:
  duration: "~10 minutes"
  completed: "2026-04-08T18:03:25Z"
  tasks_completed: 2
  files_created: 13
  files_modified: 4
---

# Phase 32 Plan 01: Tenant Notification Contracts and Templates Summary

**One-liner:** Three plain-record domain event types, six EmailTemplate enum values with i18n subject keys, and six Thymeleaf inline-CSS HTML templates covering the full tenant API key and status notification contract.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | Domain event records, EmailTemplate enum, i18n keys | 832912e | TenantApiKeyEvent.java, TenantStatusChangedEvent.java, TenantWebhookSecretRegeneratedEvent.java, EmailTemplate.java, messages*.properties (3 files) |
| 2 | 6 Thymeleaf HTML email templates | 09ddf8c | tenantApiKeyGenerated.html, tenantApiKeyRotated.html, tenantApiKeyRevoked.html, tenantApiKeyReactivated.html, tenantWebhookSecretRegenerated.html, tenantStatusChanged.html |

## Decisions Made

1. **[32-01] Plain record domain events** — All three event types follow the `PlatformConfigChangedEvent` pattern: plain Java records with no `ApplicationEvent` extension. Spring 4.2+ dispatches POJO events natively.

2. **[32-01] Security constraint at contract layer** — No raw API key value and no webhook secret value appear in any event record or template. `TenantApiKeyEvent` carries `keyPrefix` only; `TenantWebhookSecretRegeneratedEvent` carries no secret field. Templates enforce this by design.

3. **[32-01] th:switch conditional rendering in tenantStatusChanged.html** — Single template handles all four `TenantStatusChangedEvent.EventType` variants via Thymeleaf's `th:switch="${map.eventType}"` block, avoiding 4 separate near-identical templates.

4. **[32-01] Inline CSS only** — All templates use `style=""` attributes exclusively; no linked stylesheets. Required for email client compatibility (most strip linked CSS).

5. **[32-01] Monospace code block for key prefix** — Key prefix displayed with `font-family: 'Courier New', Courier, monospace` and `border-left: 4px solid #1a56db` per UI-SPEC for visual distinction from prose text.

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — all templates have correct variable bindings. No placeholder data flows to rendering; all `map.*` variables are set by the event listener implemented in plan 32-02.

## Self-Check: PASSED

Files created:
- src/main/java/com/softropic/payam/tenant/contract/event/TenantApiKeyEvent.java — FOUND
- src/main/java/com/softropic/payam/tenant/contract/event/TenantStatusChangedEvent.java — FOUND
- src/main/java/com/softropic/payam/tenant/contract/event/TenantWebhookSecretRegeneratedEvent.java — FOUND
- src/main/resources/mails/tenantApiKeyGenerated.html — FOUND
- src/main/resources/mails/tenantApiKeyRotated.html — FOUND
- src/main/resources/mails/tenantApiKeyRevoked.html — FOUND
- src/main/resources/mails/tenantApiKeyReactivated.html — FOUND
- src/main/resources/mails/tenantWebhookSecretRegenerated.html — FOUND
- src/main/resources/mails/tenantStatusChanged.html — FOUND

Commits: 832912e (Task 1), 09ddf8c (Task 2) — both verified in git log.

Compilation: `mvn compile` exits 0 with no errors.
