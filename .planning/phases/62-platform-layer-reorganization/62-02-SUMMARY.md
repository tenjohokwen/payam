---
phase: 62-platform-layer-reorganization
plan: 02
subsystem: infra
tags: [java, spring-boot, package-refactoring, email, notifications, alerts]

requires:
  - phase: 61-infrastructure-layer-creation
    provides: "infrastructure.web, infrastructure.config, infrastructure.persistence packages established"

provides:
  - "platform.notification package (29 production + 7 test files): config/, contract/, api/, infrastructure/, infrastructure/listener/, repo/, service/ sub-packages"
  - "email/ and alert/ source packages deleted (production + test)"
  - "AlertNotificationListener now co-located with MailManager in platform.notification.service"
  - "All external callers (7 production + 8 test) updated to import from platform.notification"

affects:
  - "PLAT-05: platform.admin imports email contracts — already updated"
  - "Any future phase importing MailManager, EmailTemplate, Envelope, Recipient, AlertFiredEvent"

tech-stack:
  added: []
  patterns:
    - "Package merge pattern: two packages merged into single platform.notification preserving sub-package structure (no file renaming)"
    - "Fully-qualified-name detection: grep for FQN references in addition to import lines to catch non-import usages"

key-files:
  created:
    - src/main/java/com/softropic/payam/platform/notification/service/MailManager.java
    - src/main/java/com/softropic/payam/platform/notification/service/AlertNotificationListener.java
    - src/main/java/com/softropic/payam/platform/notification/contract/EmailTemplate.java
    - src/main/java/com/softropic/payam/platform/notification/contract/AlertFiredEvent.java
    - src/main/java/com/softropic/payam/platform/notification/api/AlertRuleAdminResource.java
  modified:
    - src/main/java/com/softropic/payam/security/api/AccountManagementFacade.java
    - src/main/java/com/softropic/payam/security/api/registration/EmailRegistrationStrategy.java
    - src/main/java/com/softropic/payam/security/contract/event/SendMailEvent.java
    - src/main/java/com/softropic/payam/security/infrastructure/listener/SendMailListener.java
    - src/main/java/com/softropic/payam/security/service/TwoFactorLoginService.java
    - src/main/java/com/softropic/payam/config/AsyncConfig.java
    - src/test/java/com/softropic/payam/config/TestConfig.java
    - src/test/java/com/softropic/payam/config/TestMailConfig.java
    - src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java
    - src/test/java/com/softropic/payam/security/SecurityFilterChainIT.java
    - src/test/java/com/softropic/payam/security/SecurityIT.java
    - src/test/java/com/softropic/payam/security/api/AccountManagementFacadeIT.java
    - src/test/java/com/softropic/payam/security/service/PasswordResetIT.java
    - src/test/java/com/softropic/payam/utils/TestMailManager.java

key-decisions:
  - "Plan stated 23 email production files but codebase has 22 (DisbursementOpsAlertEmailListener.java does not exist — pre-existing delta between research counts and actual files)"
  - "PlatformConfigAdminResourceIT used 2 fully-qualified class references (not import statements) that the sed import sweep missed — caught by test-compile failure, fixed inline"
  - "AlertRuleIT package declaration was com.softropic.payam.alert (no trailing dot) so the sed pattern alert. did not match — caught by stale-package check, fixed manually"
  - "Javadoc @link references to old package paths left as-is in moved files (AlertFiredEvent, AlertRuleRepository, AlertRule) — updated to platform.notification to keep Javadoc accurate"

patterns-established:
  - "Import sweep: use grep for ^import lines (not just any line) to find actual Java import statements vs Javadoc comments"
  - "FQN sweep: after import sweep, also grep for bare package paths in non-comment code (catches @Autowired field declarations)"
  - "Package declaration check: grep for ^package with exact package name (including terminal dot or end of line) to catch package declarations that sed misses"

requirements-completed: [PLAT-03]

duration: 39min
completed: 2026-05-07
---

# Phase 62 Plan 02: Merge email and alert into platform.notification Summary

**email/ (22 prod + 6 test) and alert/ (7 prod + 1 test) merged into platform.notification/ with all 15 external callers updated; mvn verify BUILD SUCCESS 278 ITs, 0 failures**

## Performance

- **Duration:** 39 min (execution) + 38 min (mvn verify)
- **Started:** 2026-05-07T00:32:00Z
- **Completed:** 2026-05-07T02:10:34Z
- **Tasks:** 2
- **Files modified:** 51

## Accomplishments

- Merged 22 email + 7 alert production files into `platform/notification/` preserving sub-package structure (`api/`, `config/`, `contract/`, `infrastructure/listener/`, `repo/`, `service/`)
- Updated all 15 external callers (7 production + 8 test) to import from `com.softropic.payam.platform.notification.*`
- Deleted old `email/` and `alert/` directories from both `src/main` and `src/test`
- Verified zero stale imports, zero stale package declarations
- `mvn verify` passed: 278 integration tests, 0 failures, 0 errors

## Task Commits

Both tasks committed atomically as one refactor commit:

1. **Task 1 + Task 2 combined:** Move files + update external callers - `fded655` (refactor)

## Files Created

- `src/main/java/com/softropic/payam/platform/notification/` — 29 production files (22 from email/, 7 from alert/)
- `src/test/java/com/softropic/payam/platform/notification/` — 7 test files (6 from email/, 1 from alert/)

## Files Modified (external callers)

- `src/main/java/com/softropic/payam/security/api/AccountManagementFacade.java` — email.contract imports
- `src/main/java/com/softropic/payam/security/api/registration/EmailRegistrationStrategy.java` — email.contract imports
- `src/main/java/com/softropic/payam/security/contract/event/SendMailEvent.java` — email.contract import
- `src/main/java/com/softropic/payam/security/infrastructure/listener/SendMailListener.java` — email.contract imports
- `src/main/java/com/softropic/payam/security/service/TwoFactorLoginService.java` — email.contract import
- `src/main/java/com/softropic/payam/config/AsyncConfig.java` — Javadoc reference updated
- `src/test/java/com/softropic/payam/config/TestConfig.java` — email.service.MailManager import
- `src/test/java/com/softropic/payam/config/TestMailConfig.java` — email.service.MailManager import
- `src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java` — 2 FQN references
- `src/test/java/com/softropic/payam/security/SecurityFilterChainIT.java` — email.contract + email.service imports
- `src/test/java/com/softropic/payam/security/SecurityIT.java` — email.contract + email.service imports
- `src/test/java/com/softropic/payam/security/api/AccountManagementFacadeIT.java` — email imports
- `src/test/java/com/softropic/payam/security/service/PasswordResetIT.java` — email imports
- `src/test/java/com/softropic/payam/utils/TestMailManager.java` — email imports

## Decisions Made

- Plan stated 23 email production files but codebase has 22 (`DisbursementOpsAlertEmailListener.java` not present); proceeding with 22 email + 7 alert = 29 total production files (plan may have counted it separately or it was already removed).
- `PlatformConfigAdminResourceIT` used fully-qualified class references (`com.softropic.payam.email.service.MailManager` and `com.softropic.payam.email.contract.Envelope`) as field type annotations — not caught by the import-line sed sweep; caught by `mvn test-compile` failure and fixed inline.
- `AlertRuleIT` test had `package com.softropic.payam.alert;` (no trailing dot) — missed by sed pattern `alert\.`; caught by stale-package grep check and fixed manually.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed AlertRuleIT stale package declaration**
- **Found during:** Task 1 verification (stale package check)
- **Issue:** `package com.softropic.payam.alert;` (no trailing dot) not matched by sed pattern `s/package com\.softropic\.payam\.alert\./`
- **Fix:** Manually updated package declaration to `com.softropic.payam.platform.notification`
- **Files modified:** `src/test/java/com/softropic/payam/platform/notification/AlertRuleIT.java`
- **Verification:** `grep -rln "package com.softropic.payam.alert\b" src` returned 0 results
- **Committed in:** `fded655`

**2. [Rule 1 - Bug] Fixed PlatformConfigAdminResourceIT fully-qualified class references**
- **Found during:** Task 2 (`mvn test-compile` failure)
- **Issue:** Two fully-qualified class references `com.softropic.payam.email.service.MailManager` and `com.softropic.payam.email.contract.Envelope` in field declarations and method bodies — not import statements, missed by sed import sweep
- **Fix:** Updated both FQN references to `com.softropic.payam.platform.notification.service.MailManager` and `com.softropic.payam.platform.notification.contract.Envelope`
- **Files modified:** `src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java`
- **Verification:** `mvn test-compile` passed after fix
- **Committed in:** `fded655`

---

**Total deviations:** 2 auto-fixed (2 Rule 1 bugs)
**Impact on plan:** Both fixes necessary for correctness. Sed-based import sweep does not catch fully-qualified references outside import blocks or package declarations without trailing dot.

## Issues Encountered

None beyond the 2 auto-fixed deviations above.

## Known Stubs

None — all production behavior preserved; package rename only.

## Next Phase Readiness

- `platform.notification` package ready for use by all downstream callers
- PLAT-03 requirement satisfied; PLAT-04 (health + ops → platform.monitoring) is next
- Zero regressions; all 278 integration tests pass

---
*Phase: 62-platform-layer-reorganization*
*Completed: 2026-05-07*
