# Phase 32: Email Notification Infrastructure - Discussion Log

**Date:** 2026-04-08
**Mode:** discuss (interactive)

---

## Gray Areas Identified

1. Admin recipient identity — who is "admin" in "admin and tenant receive email"?
2. Template strategy — one shared template vs. 6 dedicated templates
3. Event publishing location — inject into services directly vs. new wrapper service
4. Null tenant email handling — what happens when tenant.email is null?

## Areas Selected for Discussion

User selected: **Null tenant email handling** only (1 of 4 areas)

---

## Discussion: Null tenant email handling

**Q:** When tenant.email is null, what should happen?

**Options presented:**
1. Skip silently (Recommended) — WARN log, operation succeeds, tenant copy not sent
2. Skip with structured log — structured WARN for Loki observability
3. Treat as error — throw exception, block operation

**A (user, free text):** "In this case, send the email only to payam.platform.notification-email configured in the active application.yaml file"

**Captured decision:** When `tenant.email` is null, send notification to `payam.platform.notification-email` only. Skip tenant copy silently (no exception). This also confirmed admin recipient identity: `payam.platform.notification-email` (not dynamic ROLE_ADMIN query).

---

## Non-discussed Areas → Claude's Discretion

- **Template strategy:** Resolved by UI-SPEC (`32-UI-SPEC.md`) — 6 dedicated templates, one per event type. Full variable contracts, HTML structure, and i18n keys specified therein.
- **Event publishing location:** Claude's discretion — inject `ApplicationEventPublisher` into existing services directly or create a new wrapper `TenantNotificationService`. Choose the approach with less coupling and better testability.
- **Admin recipient identity:** Confirmed via null-email discussion — `payam.platform.notification-email` config property.

---

*Log created: 2026-04-08*
