---
phase: 24-platform-configuration
plan: 02
subsystem: email
tags: [thymeleaf, spring-events, email, listener, configuration-properties]

# Dependency graph
requires:
  - phase: 24-01
    provides: PlatformConfigChangedEvent POJO event published by PlatformConfigService.update()
  - phase: email-infrastructure
    provides: MailManager, Envelope, EmailTemplate, Recipient — existing email pipeline
provides:
  - EmailTemplate.PLATFORM_CONFIG_CHANGED enum constant with subject key email.platform_config_changed.title
  - messages.properties entries for platform_config_changed email (title + preheader)
  - PlatformConfigEmailListener @Component — @EventListener + @Transactional, dispatches Envelope
  - platformConfigChanged.html Thymeleaf template with provider/oldMsisdn/newMsisdn
  - Complete PCONF-04 event chain: PlatformConfigChangedEvent -> Envelope -> MailManager AFTER_COMMIT
affects:
  - 24-03 (health check plan — no email dependency but same phase)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@EventListener + @Transactional (NOT @TransactionalEventListener) on listener — MailManager handles AFTER_COMMIT; listener just dispatches the Envelope"
    - "Envelope correlation ID from UUID.randomUUID().toString() — no business helpCode needed for admin notifications"

key-files:
  created:
    - src/main/java/com/softropic/payam/email/infrastructure/listener/PlatformConfigEmailListener.java
    - src/main/resources/mails/platformConfigChanged.html
  modified:
    - src/main/java/com/softropic/payam/email/contract/EmailTemplate.java
    - src/main/resources/i18n/messages.properties

key-decisions:
  - "[24-02] PlatformConfigEmailListener uses @EventListener (not @TransactionalEventListener) — matches AccountChangeEmailListener pattern; MailManager already uses @TransactionalEventListener(AFTER_COMMIT) on the Envelope event, so the listener only needs to dispatch the Envelope inside a transaction"
  - "[24-02] Envelope correlation ID is UUID.randomUUID().toString() — admin config change notifications have no user-facing helpCode concept; UUID provides uniqueness for mail logging"

patterns-established:
  - "Admin notification listener pattern: @EventListener @Transactional + @Value-injected target email + Envelope dispatch — no user Recipient fields needed beyond email + langKey"

# Metrics
duration: 2min
completed: 2026-03-30
---

# Phase 24 Plan 02: Platform Config Email Notification Summary

**Email notification for platform MSISDN changes (PCONF-04): EmailTemplate enum entry, Thymeleaf template, and @EventListener listener that dispatches Envelope to MailManager on PlatformConfigChangedEvent**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-30T12:34:56Z
- **Completed:** 2026-03-30T12:37:13Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- `EmailTemplate.PLATFORM_CONFIG_CHANGED` enum constant wired to `email.platform_config_changed.title` subject key
- `PlatformConfigEmailListener` subscribes to `PlatformConfigChangedEvent`, builds Envelope, and publishes to MailManager pipeline
- `platformConfigChanged.html` Thymeleaf template renders provider name, old MSISDN, and new MSISDN
- Full PCONF-04 chain complete: `PlatformConfigService.update()` → `PlatformConfigChangedEvent` → `PlatformConfigEmailListener` → `Envelope` → `MailManager` (AFTER_COMMIT)
- Email is NOT sent if the PUT transaction rolls back (Envelope dispatched to MailManager which uses @TransactionalEventListener AFTER_COMMIT)

## Task Commits

Each task was committed atomically:

1. **Task 1: EmailTemplate enum entry + messages.properties subject key** - `8491c1f` (feat)
2. **Task 2: PlatformConfigEmailListener + platformConfigChanged.html template** - `2905ff4` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `src/main/java/com/softropic/payam/email/contract/EmailTemplate.java` - Added `PLATFORM_CONFIG_CHANGED("email.platform_config_changed.title")` constant
- `src/main/resources/i18n/messages.properties` - Added `email.platform_config_changed.title` and `preheader` keys
- `src/main/java/com/softropic/payam/email/infrastructure/listener/PlatformConfigEmailListener.java` - New @Component listener; @EventListener @Transactional; dispatches Envelope with provider/oldMsisdn/newMsisdn data map
- `src/main/resources/mails/platformConfigChanged.html` - Thymeleaf template using `${map.provider}`, `${map.oldMsisdn}`, `${map.newMsisdn}`

## Decisions Made

1. **`PlatformConfigEmailListener` uses `@EventListener` (not `@TransactionalEventListener`)** — matches `AccountChangeEmailListener` exactly. The listener publishes an `Envelope` event, and `MailManager` is the one annotated `@TransactionalEventListener(phase = AFTER_COMMIT)`. Using `@TransactionalEventListener` on the listener itself would prevent the `Envelope` from being published in the same transaction.

2. **Envelope correlation ID is `UUID.randomUUID().toString()`** — admin config-change notifications have no user-facing helpCode concept (unlike `AccountChangeEmailListener` which uses `ShortCode.shortenInt()`). A random UUID provides uniqueness for mail logging without adding unnecessary complexity.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

- `./mvnw` unavailable (no `.mvn/wrapper/` directory). Used system `mvn` directly. Same as plan 24-01; no impact on output.

## User Setup Required

None — the `payam.platform.notification-email` property was added in plan 24-01 (application.yaml + application-dev.yaml). No additional setup required for this plan.

## Next Phase Readiness

- PCONF-04 is fully implemented; ready for plan 24-03 (health check)
- The Thymeleaf template is resolved lazily (only when an email is sent) — no startup validation needed

---
*Phase: 24-platform-configuration*
*Completed: 2026-03-30*
