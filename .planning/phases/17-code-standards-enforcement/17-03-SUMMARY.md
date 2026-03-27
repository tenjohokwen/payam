---
phase: 17-code-standards-enforcement
plan: 03
subsystem: logging
tags: [logstash, structured-logging, pii, security, kv, body-sanitizer, webhook, validation]

requires:
  - phase: 17-01
    provides: LOG-CODE-01/02/03 fixes in orchestrator and provider port layers
  - phase: 17-02
    provides: LOG-CODE-01/02 fixes in poller, reconciliation, and service layers
  - phase: 16-code-standards-enforcement
    provides: Business event log coverage across all payment flows

provides:
  - BodySanitizer.SENSITIVE_KEYS expanded to cover msisdn, merchant_key, merchantKey
  - RestRequestInterceptor: no headers in log output, 2xx body at DEBUG, kv() throughout
  - OrangeCallbackController: payToken removed from all log calls
  - MtnCallbackController: kv() with exception, no {} placeholders
  - Both IP whitelist interceptors: kv() with structured remoteIp field
  - MsisdnRouter: prefix value removed from log
  - CamPhoneValidator and PhoneNumberValidator: full MSISDN removed from all log calls
  - Zero {} placeholder logs across all 10 files in scope

affects:
  - future-logging: BodySanitizer.sanitize() is now safe for any field containing msisdn or merchant_key
  - RestRequestInterceptor: all outbound HTTP call logs now structured and PII-clean

tech-stack:
  added: []
  patterns:
    - "BodySanitizer single-point-of-control: sensitive field coverage via SENSITIVE_KEYS substring matching — add field name once, redacted everywhere"
    - "No headers in log args: headers may contain Authorization — pass content-type string only when needed for body sanitization"
    - "kv() with exception as last arg: log.warn/error('message', kv(...), kv(...), e) — exception stack trace preserved without {} placeholder"
    - "PII removal at call site: payToken, full MSISDN never passed as log arguments regardless of sanitizer coverage"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/common/util/BodySanitizer.java
    - src/main/java/com/softropic/payam/common/client/RestRequestInterceptor.java
    - src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java
    - src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java
    - src/main/java/com/softropic/payam/mtn/web/MtnCallbackController.java
    - src/main/java/com/softropic/payam/orange/web/OrangeIpWhitelistInterceptor.java
    - src/main/java/com/softropic/payam/mtn/web/MtnIpWhitelistInterceptor.java
    - src/main/java/com/softropic/payam/payment/service/MsisdnRouter.java
    - src/main/java/com/softropic/payam/common/validation/CamPhoneValidator.java
    - src/main/java/com/softropic/payam/common/validation/PhoneNumberValidator.java

key-decisions:
  - "[17-03] BodySanitizer SENSITIVE_KEYS adds msisdn, merchant_key, merchantKey: substring matching means msisdn catches any field name containing 'msisdn'; merchant_key and merchantKey added as separate entries since neither contains the other as a substring"
  - "[17-03] RestRequestInterceptor request debug log converted to kv() without headers: headers contain Authorization Bearer token; content-type extracted separately for sanitization only"
  - "[17-03] RestRequestInterceptor 2xx body log downgraded INFO to DEBUG: LOG-BUS-06 structured latency events replace the observability need; raw body at INFO created excessive log volume"
  - "[17-03] OrangeCallbackController line 118 upgraded warn to error: callback processing failure is an error-severity event; exception object passed as last arg (not e.getMessage()) for full stack trace"
  - "[17-03] CamPhoneValidator log upgraded debug to warn: validation failures merit warn level; logs messageKey code (not phone number — no PII exposure)"

patterns-established:
  - "Pattern: Exception as last log arg — pass raw 'e' not 'e.getMessage()' to preserve stack trace without {} placeholder"
  - "Pattern: No headers object in log args — extract only the specific value needed (e.g., content-type) for functional use; never pass the full headers map"

duration: 12min
completed: 2026-03-27
---

# Phase 17 Plan 03: LOG-CODE-03 PII closure and kv() conversion for API/filter/validation layer Summary

**BodySanitizer expanded to redact msisdn and merchant_key fields; RestRequestInterceptor Authorization header exposure eliminated; payToken and full MSISDN values removed from all log call arguments across callback controllers, IP whitelist interceptors, and phone validators**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-03-27T00:00:00Z
- **Completed:** 2026-03-27T00:12:00Z
- **Tasks:** 2
- **Files modified:** 10

## Accomplishments

- BodySanitizer.SENSITIVE_KEYS expanded: `msisdn`, `merchant_key`, `merchantKey` added — any request/response body routed through RestRequestInterceptor now automatically redacts these fields before logging
- RestRequestInterceptor: all four problem areas fixed — 4xx/5xx error log removes headers, 2xx response body downgraded to DEBUG with sanitization, latency log converted to kv(), txnId-set error no longer leaks httpHeaders (Authorization header exposure closed)
- API layer PII closure: payToken values removed from OrangeCallbackController log calls; full MSISDN removed from PhoneNumberValidator; MSISDN routing prefix removed from MsisdnRouter log

## Task Commits

Each task was committed atomically:

1. **Task 1: BodySanitizer expansion and RestRequestInterceptor fixes** - `03273df` (fix)
2. **Task 2: API controllers, filters, validators — convert + PII removal** - `180d2ad` (fix)

## Files Created/Modified

- `src/main/java/com/softropic/payam/common/util/BodySanitizer.java` — SENSITIVE_KEYS expanded with msisdn/merchant_key/merchantKey; line 50 parse-error log converted to kv() without exception message
- `src/main/java/com/softropic/payam/common/client/RestRequestInterceptor.java` — all 4 log areas fixed; request debug log also converted; BodySanitizer.sanitize() applied to all body logging
- `src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java` — auth failure log converted to kv()
- `src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java` — 3 log calls fixed; payToken removed from duplicate/failure logs; HMAC error passes exception object
- `src/main/java/com/softropic/payam/mtn/web/MtnCallbackController.java` — processing failure log converted to kv() with exception
- `src/main/java/com/softropic/payam/orange/web/OrangeIpWhitelistInterceptor.java` — IP rejection log converted to kv()
- `src/main/java/com/softropic/payam/mtn/web/MtnIpWhitelistInterceptor.java` — IP rejection log converted to kv()
- `src/main/java/com/softropic/payam/payment/service/MsisdnRouter.java` — prefix value removed; kv() conversion
- `src/main/java/com/softropic/payam/common/validation/CamPhoneValidator.java` — validation failure log converted to kv(); upgraded debug to warn
- `src/main/java/com/softropic/payam/common/validation/PhoneNumberValidator.java` — full MSISDN removed; kv() with exception object

## Decisions Made

- **BodySanitizer parse-error log:** Original used `log.debug` with `e.getMessage()`. Plan specified `log.warn` — upgraded to warn since parse failures may indicate malformed provider responses. Exception message omitted (may contain PII fragments from body).
- **OrangeCallbackController line 118:** Plan said `log.error`; original code used `log.warn`. Upgraded to error — callback processing failure is error-severity.
- **CamPhoneValidator:** Original log was `log.debug` with `messageKey` and `fallbackMessage` (no phone number — already safe). Converted to kv() and upgraded to warn; `reason` field uses `messageKey` (a code string, not user input).
- **RestRequestInterceptor request debug log:** The unscoped debug log at lines 51-57 also had `{}` placeholders and logged `request.getHeaders()` (could contain Authorization). Fixed as part of Task 1 to satisfy zero-`{}` requirement.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] RestRequestInterceptor request debug log had {} placeholders and logged full headers**

- **Found during:** Task 1 (RestRequestInterceptor verification)
- **Issue:** Plan specified 4 areas in RestRequestInterceptor; lines 51-57 had a fifth `{}` placeholder log that also passed `request.getHeaders()` (which contains Authorization) — would fail the zero-`{}` verification check
- **Fix:** Converted to kv() without headers; request body routed through BodySanitizer.sanitize()
- **Files modified:** RestRequestInterceptor.java
- **Verification:** `grep -n '{}'` on the file returns zero matches
- **Committed in:** `03273df` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - additional {} placeholder in RestRequestInterceptor request debug log)
**Impact on plan:** Auto-fix necessary to satisfy the zero-{} must-have and closes an additional Authorization header exposure path. No scope creep.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- LOG-CODE-03 PII closure complete: BodySanitizer covers all payment-domain sensitive fields; no MSISDN, payToken, or merchant key value appears in log output
- LOG-CODE-01 {} placeholder elimination complete for all 10 plan-03 files
- Authorization header exposure via RestRequestInterceptor eliminated
- Phase 17 plan 03 of 3 complete — code standards enforcement phase at its final plan

---
*Phase: 17-code-standards-enforcement*
*Completed: 2026-03-27*
