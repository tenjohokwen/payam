---
phase: 32-email-notification-infrastructure
plan: "02"
subsystem: email / tenant-notifications
tags: [email, domain-events, event-listener, tenant, spring-events]
dependency_graph:
  requires:
    - plan 32-01 (TenantApiKeyEvent, TenantStatusChangedEvent, TenantWebhookSecretRegeneratedEvent, EmailTemplate enum values)
  provides:
    - TenantLifecycleEmailListener (converts domain events to Envelope, dispatches via publisher)
    - TenantService event publishing for all 6 lifecycle operations
    - ApiKeyService event publishing for rotate and revoke
  affects:
    - Email notification pipeline — tenant lifecycle operations now trigger emails
tech_stack:
  added: []
  patterns:
    - 2-step event pattern: Service publishEvent(DomainEvent) -> @EventListener listener -> MailManager @TransactionalEventListener(AFTER_COMMIT)
    - Admin-only fallback when tenant.email is null (no exception, silent skip)
    - EMAIL_CHANGED routes to old address only (D-03)
key_files:
  created:
    - src/main/java/com/softropic/payam/email/infrastructure/listener/TenantLifecycleEmailListener.java
    - src/test/java/com/softropic/payam/email/infrastructure/listener/TenantLifecycleEmailListenerTest.java
  modified:
    - src/main/java/com/softropic/payam/tenant/service/TenantService.java
    - src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java
decisions:
  - "[32-02] No event publishing in generateAndStore() — callers publish appropriate events (GENERATED from TenantService.createTenant, ROTATED from ApiKeyService.rotate) to avoid double-event on rotation"
  - "[32-02] revoke() publishes REVOKED after keyRepository.save() while JPA session is open — tenant lazy-load resolves without extra query"
  - "[32-02] updateEmail captures oldEmail before tenant.setEmail() — ensures EMAIL_CHANGED event carries pre-mutation address for correct routing"
metrics:
  duration: "~8 minutes"
  completed: "2026-04-08T18:10:00Z"
  tasks_completed: 2
  files_created: 2
  files_modified: 2
---

# Phase 32 Plan 02: Event Publishing and TenantLifecycleEmailListener Summary

**One-liner:** TenantLifecycleEmailListener converts 3 domain event types to Envelope objects with correct recipient routing; TenantService and ApiKeyService wired to publish events for all 6 tenant lifecycle operations with post-commit delivery guarantee.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | TenantLifecycleEmailListener + event publishing in TenantService and ApiKeyService | 873fc71 | TenantLifecycleEmailListener.java, TenantService.java, ApiKeyService.java |
| 2 | Unit tests for TenantLifecycleEmailListener | ee0ed17 | TenantLifecycleEmailListenerTest.java |

## Decisions Made

1. **[32-02] No event in generateAndStore()** — `ApiKeyService.generateAndStore()` is called from both `TenantService.createTenant()` (GENERATED) and `ApiKeyService.rotate()` (ROTATED). Publishing inside `generateAndStore()` would fire two events on rotation. Callers publish semantically correct events at the business operation level.

2. **[32-02] Lazy-load safety in revoke()** — `key.getTenant()` triggers lazy load inside the `@Transactional` session — safe without extra fetch. JPA session is open for the full method duration.

3. **[32-02] oldEmail captured before setter** — `updateEmail()` captures `String oldEmail = tenant.getEmail()` before calling `tenant.setEmail(email)`, ensuring the EVENT_CHANGED event and EMAIL_CHANGED routing use the pre-update address (D-03 compliance).

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — listener builds all Envelope fields from domain event data. No hardcoded empty values flow to template rendering.

## Self-Check: PASSED

Files created:
- src/main/java/com/softropic/payam/email/infrastructure/listener/TenantLifecycleEmailListener.java — FOUND
- src/test/java/com/softropic/payam/email/infrastructure/listener/TenantLifecycleEmailListenerTest.java — FOUND

Files modified:
- src/main/java/com/softropic/payam/tenant/service/TenantService.java — FOUND
- src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java — FOUND

Commits: 873fc71 (Task 1), ee0ed17 (Task 2) — verified.

Compilation: `mvn compile` exits 0 with no errors.
Tests: `TenantLifecycleEmailListenerTest` — 12/12 tests pass.
