# Phase 32: Email Notification Infrastructure - Context

**Gathered:** 2026-04-08
**Status:** Ready for planning

<domain>
## Phase Boundary

Wire transactional email notifications for 6 tenant lifecycle events. Every triggering operation (API key generated/rotated, key revoked/reactivated, webhook secret regenerated, tenant suspended/reactivated/email-changed/webhookUrl-changed) must send notifications after the triggering database transaction commits. No new domain logic — this phase is purely the notification wiring layer on top of completed Phase 31 service methods.

</domain>

<decisions>
## Implementation Decisions

### Recipient Routing

- **D-01:** Admin notification address = `payam.platform.notification-email` config property (reuses existing pattern from `PlatformConfigEmailListener` — do not query `User` table or ROLE_ADMIN users dynamically).
- **D-02:** Every notification always reaches `payam.platform.notification-email`. The tenant copy (`tenant.email`) is best-effort — if `tenant.email` is null, skip the tenant recipient silently (no exception, no blocking) and send only to admin.
- **D-03:** NOTIF-06 email-changed event → notification goes to the **old address only** (per REQUIREMENTS.md out-of-scope section: "old address only; reducing blast radius of potential enumeration"). `payam.platform.notification-email` still receives a copy.

### Template Strategy

- **D-04:** 6 dedicated Thymeleaf HTML templates, one per event type. Template names and `EmailTemplate` enum values are fully specified in the UI-SPEC (`32-UI-SPEC.md`). Do not use a single shared template with a dynamic action variable.
- **D-05:** Template variable contracts, HTML structure, color, typography, and i18n subject keys are all fully specified in `32-UI-SPEC.md`. Downstream agents must read that file before creating templates.

### Delivery Guarantee

- **D-06:** Email sends via `MailManager.sendEmailFromTemplate()` which is annotated `@TransactionalEventListener(phase = AFTER_COMMIT)` — this is the existing pattern; no rollback sends. Established in STATE.md; no new infrastructure needed.

### Claude's Discretion

- Event publishing location: publisher can be injected directly into `TenantService` / `ApiKeyService`, or a new `TenantNotificationService` can wrap calls and publish events. Choose the approach that minimizes coupling and keeps service methods testable.
- Specific domain event record types: create lightweight Spring application event records (POJOs) in `tenant/contract/event/` following the `PlatformConfigChangedEvent` pattern.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### UI Design Contract (template specifications)
- `.planning/phases/32-email-notification-infrastructure/32-UI-SPEC.md` — Full visual/interaction contract for all 6 Thymeleaf email templates: enum values, variable contracts, HTML structure, i18n keys, color/typography, copywriting. MANDATORY read before creating any template.

### Existing email infrastructure (patterns to follow exactly)
- `src/main/java/com/softropic/payam/email/contract/EmailTemplate.java` — Enum to extend with 6 new values
- `src/main/java/com/softropic/payam/email/contract/Envelope.java` — Record used to dispatch emails
- `src/main/java/com/softropic/payam/email/contract/Recipient.java` — Recipient POJO
- `src/main/java/com/softropic/payam/email/service/MailManager.java` — `@TransactionalEventListener(AFTER_COMMIT)` handler; do not modify
- `src/main/java/com/softropic/payam/email/infrastructure/listener/PlatformConfigEmailListener.java` — Reference pattern for admin-only notification (uses `payam.platform.notification-email`)
- `src/main/java/com/softropic/payam/email/infrastructure/listener/AccountChangeEmailListener.java` — Reference pattern for user-targeted notification

### Existing domain event pattern
- `src/main/java/com/softropic/payam/platform/contract/event/PlatformConfigChangedEvent.java` — Reference POJO event record pattern

### Tenant service methods being wired (read before choosing event publishing approach)
- `src/main/java/com/softropic/payam/tenant/service/TenantService.java` — createTenant, updateEmail, updateWebhookUrl, suspend, reactivate, regenerateWebhookSecret
- `src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java` — generateAndStore, rotate, revoke, reactivate

### Requirements
- `.planning/REQUIREMENTS.md` §NOTIF — NOTIF-01 through NOTIF-06 acceptance criteria

### Existing templates (structural reference)
- `src/main/resources/mails/platformConfigChanged.html` — Reference for inline-CSS HTML email structure and Thymeleaf syntax

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `MailManager.sendEmailFromTemplate(Envelope)`: entry point for all email sends — call via Spring `ApplicationEventPublisher.publishEvent(envelope)` so it fires `@TransactionalEventListener(AFTER_COMMIT)`
- `Envelope` record: wrap recipients + template + deadline + data map + sendId — reuse as-is
- `Recipient` POJO: set email, langKey; firstname/lastname optional (admin recipient has no name — matches `PlatformConfigEmailListener` pattern)
- `EmailTemplate` enum: add 6 new entries following existing naming convention

### Established Patterns
- 2-step event pattern: Service `publishEvent(DomainEvent)` → `@EventListener` listener converts to `Envelope` → `MailManager @TransactionalEventListener(AFTER_COMMIT)` sends after commit
- Admin-only notification: `@Value("${payam.platform.notification-email}") String notificationEmail` injected into listener
- Domain event records: plain Java records in `{module}/contract/event/` — no `ApplicationEvent` extension needed (Spring 4.2+)
- Tenant email field: `Tenant.email` (nullable) — read from the `Tenant` entity passed through the domain event

### Integration Points
- `TenantService` and `ApiKeyService` need `ApplicationEventPublisher` injected (or wrapper service created)
- New listener class(es) in `src/main/java/com/softropic/payam/email/infrastructure/listener/`
- New domain event records in `src/main/java/com/softropic/payam/tenant/contract/event/`
- New Thymeleaf templates in `src/main/resources/mails/`
- i18n keys in `src/main/resources/i18n/messages_en.properties` and `messages_fr.properties`

</code_context>

<specifics>
## Specific Ideas

- When `tenant.email` is null: send to `payam.platform.notification-email` only (no exception, no log spam — the notification still goes out to admin)
- NOTIF-06 email-changed: listener must capture the **old** email address before the update commits and route the tenant copy to that old address
- No raw key material in any email body (NOTIF-01, NOTIF-02 constraints in UI-SPEC)
- No webhook secret value in NOTIF-05 email body

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 32-email-notification-infrastructure*
*Context gathered: 2026-04-08*
