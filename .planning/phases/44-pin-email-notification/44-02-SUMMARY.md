---
phase: 44-pin-email-notification
plan: "02"
subsystem: email
tags: [spring-events, thymeleaf, mockito, testcontainers, awaitility, email-notification]

# Dependency graph
requires:
  - phase: 44-01
    provides: PlatformConfigChangedEvent record (provider, oldMsisdn, newMsisdn, msisdnChanged, pinChanged, changedBy)
  - phase: 42-pin-service
    provides: PlatformConfigService.update() with PIN-10 event suppression logic
  - phase: 41-pin-schema-encryption-config
    provides: PlatformConfig.pin field, PayamPlatformProperties.pinEncryptionSecret
provides:
  - PlatformConfigEmailListener extended data map (7 keys: provider, oldMsisdn, newMsisdn, msisdnChanged, pinChanged, changedBy, changedAt)
  - platformConfigChanged.html with conditional th:if rendering for MSISDN/PIN rows, never exposing PIN value
  - 8 Mockito unit tests (PlatformConfigEmailListenerTest) covering all data map keys and PIN-11 security rule
  - 3 IT tests in PlatformConfigAdminResourceIT covering PIN-10 fire and suppression rules end-to-end
affects: [any future email templates, any future platform config change audit features]

# Tech tracking
tech-stack:
  added: [awaitility (IT async assertion)]
  patterns: [TDD RED/GREEN for event listener data map, clear-then-assert pattern for ConcurrentHashMap envelope iteration]

key-files:
  created:
    - src/test/java/com/softropic/payam/email/infrastructure/listener/PlatformConfigEmailListenerTest.java
  modified:
    - src/main/java/com/softropic/payam/email/infrastructure/listener/PlatformConfigEmailListener.java
    - src/main/resources/mails/platformConfigChanged.html
    - src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java
    - src/test/java/com/softropic/payam/utils/TestMailManager.java

key-decisions:
  - "Use @EventListener (not @TransactionalEventListener) on PlatformConfigEmailListener to avoid double-wrapping since MailManager already handles AFTER_COMMIT internally"
  - "Template shows 'PIN has been updated (value not shown for security)' instead of any PIN value — security by design"
  - "IT clears TestMailManager between sequential PUTs to avoid ConcurrentHashMap ordering ambiguity when asserting oldMsisdn"
  - "payam.platform.pin-encryption-secret injected via @SpringBootTest(properties) to satisfy Cryptopher bean init in test context"

patterns-established:
  - "PIN-11 security pattern: data map contains only boolean pinChanged, never the PIN ciphertext or plaintext"
  - "Clear-then-assert pattern: testMailManager().clear() after first envelope, then assert second envelope in isolation"

requirements-completed: [PIN-11]

# Metrics
duration: 90min
completed: 2026-04-18
---

# Phase 44 Plan 02: PIN Email Notification — Listener Data Map and Template

**Extended PlatformConfigEmailListener to populate 7-key Envelope data map with conditional Thymeleaf template rendering, fully suppressing PIN values in email bodies (PIN-11), with 8 unit tests and 3 IT tests verifying PIN-10 suppression and PIN-11 security end-to-end.**

## Performance

- **Duration:** ~90 min
- **Started:** 2026-04-18T16:30:00Z
- **Completed:** 2026-04-18T18:52:00Z
- **Tasks:** 3/3
- **Files modified:** 5

## Accomplishments

### Task 1 — RED: PlatformConfigEmailListenerTest (8 unit tests)

Created `PlatformConfigEmailListenerTest` using `@ExtendWith(MockitoExtension.class)` with `ArgumentCaptor<Envelope>`. Tests initially failed (5 failures) because the listener only placed provider, oldMsisdn, newMsisdn in the data map — the 4 new keys (msisdnChanged, pinChanged, changedBy, changedAt) were absent.

Tests cover:
- MSISDN-only change data map population
- PIN-only change data map population
- Both fields changed
- No PIN value leaked (only `pinChanged` boolean allowed; no key containing "pin" except "pinChanged"; no ENC()-prefixed ciphertext)
- Correct EmailTemplate enum used
- Correct recipient email used
- null oldMsisdn fallback to empty string
- null changedBy fallback to "unknown"

### Task 2 — GREEN: Listener + Template

Extended `PlatformConfigEmailListener.onConfigChanged()` with 4 additional `data.put()` calls:
- `msisdnChanged` — boolean from event
- `pinChanged` — boolean from event
- `changedBy` — event value, defaulting to "unknown" on null
- `changedAt` — `Instant.now(ClockProvider.getClock()).toString()`

Replaced `platformConfigChanged.html` with conditional Thymeleaf template:
- `th:if="${map.msisdnChanged}"` row shows old/new MSISDN with arrow
- `th:if="${map.pinChanged}"` row shows "PIN has been updated (value not shown for security)"
- Unconditional rows for changedBy and changedAt
- No `${map.pin}` or any PIN-value reference anywhere

All 28 unit tests pass after GREEN.

### Task 3 — IT: PlatformConfigAdminResourceIT (3 new tests)

Added to `PlatformConfigAdminResourceIT`:
- `putConfig_shouldDispatchEmailWithMsisdnChangedTrueOnMsisdnUpdate` — real HTTP PUT changes MSISDN; verifies Envelope data map has `msisdnChanged=true`, `pinChanged=false`, correct oldMsisdn/newMsisdn values
- `putConfig_shouldNotDispatchEmailWhenMsisdnUnchangedAndPinBlank` — PUT with unchanged MSISDN and no PIN; verifies zero envelopes dispatched (PIN-10 no-op suppression)
- `putConfig_shouldNotDispatchEmailOnFirstTimePinCreation` — PUT on fresh row creates first-time PIN; verifies zero envelopes dispatched (PIN-10 first-time suppression)

Helper methods added: `envelopeCount()`, `waitForEnvelopeCount(int)`, `latestEnvelopeData()`.
`TestMailManager.getEnvelopes()` added to expose the ConcurrentHashMap for IT iteration.

All 10 IT tests pass (7 pre-existing + 3 new).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] RED test neverPutsPinValueInDataMap false positive**
- **Found during:** Task 1 (RED verification)
- **Issue:** Original assertion `noneMatch(v -> v.toString().matches("^[a-zA-Z0-9]{4,8}$"))` also matched "ORANGE" (6 alphanumeric chars), causing the test to fail for the wrong reason
- **Fix:** Removed the regex assertion; kept only `noneMatch(v -> v.toString().startsWith("ENC("))` which correctly targets ciphertext without false positives
- **Commit:** ce601de

**2. [Rule 1 - Bug] ConcurrentHashMap iteration order ambiguity in IT assertion**
- **Found during:** Task 3
- **Issue:** `latestEnvelopeData()` used `ConcurrentHashMap.values().stream().reduce((a,b)->b)` which doesn't guarantee returning the most recent envelope; the oldMsisdn assertion got the first envelope ("") instead of the second ("111111")
- **Fix:** Added `testMailManager().clear()` after the initial PUT's envelope is captured, then asserted on the second PUT's envelope in isolation
- **Commit:** 28d29b6

**3. [Rule 2 - Missing Functionality] payam.platform.pin-encryption-secret required for Cryptopher bean**
- **Found during:** Task 3 (IT context startup)
- **Issue:** `Cryptopher` bean requires a non-empty `payam.platform.pin-encryption-secret` property; the YAML default is empty (`${PLATFORM_PIN_ENCRYPTION_SECRET:}`) causing `MISSING_SECRET` error in test context
- **Fix:** Added `"payam.platform.pin-encryption-secret=test-pin-secret-for-tests"` to `@SpringBootTest(properties)`
- **Commit:** 28d29b6

## Known Stubs

None — all data map keys are wired to real event fields; the template renders all values from live request data.

## Self-Check: PASSED

- FOUND: PlatformConfigEmailListenerTest.java
- FOUND: PlatformConfigEmailListener.java
- FOUND: platformConfigChanged.html
- FOUND: commit ce601de (RED test)
- FOUND: commit 4a97a6c (GREEN impl + template)
- FOUND: commit 28d29b6 (IT coverage)
