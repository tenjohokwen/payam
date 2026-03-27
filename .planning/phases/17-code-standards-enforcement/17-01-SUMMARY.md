---
phase: 17-code-standards-enforcement
plan: 01
subsystem: payments
tags: [logstash, structured-logging, kv, LOG-CODE-01, LOG-CODE-02, LOG-CODE-03, payment-domain]

requires:
  - phase: 16-business-event-logging
    provides: kv() structured log events at all applyTransition() sites and provider port operations

provides:
  - Zero {} placeholder log calls across all 9 payment domain files
  - All LOG-CODE-02 duplicate/code-flow lines deleted (5 explicit + 3 additional identified during execution)
  - PII removed from log args: no payToken value, notifToken value, or full MSISDN in any log statement
  - kv static import added to WebhookDoubleCheckHandler and WebhookDeliveryJob (previously missing)

affects:
  - 17-02-PLAN.md
  - 17-03-PLAN.md

tech-stack:
  added: []
  patterns:
    - "All log calls in payment domain use kv() structured arguments — zero {} placeholders"
    - "Duplicate post-transition logs deleted: structured transaction_state_change event is the canonical signal"
    - "PII (payToken, notifToken, MSISDN) never passed as log argument — sentinel/boolean flags used instead"
    - "API-error catch blocks preserve throwable as last kv() argument for stack trace in Loki"
    - "Still-PENDING poller paths use log.info (not log.debug) so Loki can query backpressure"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java
    - src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java
    - src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java
    - src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDoubleCheckHandler.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java

key-decisions:
  - "Still-PENDING log upgraded from log.debug to log.info in both pollers: poller backpressure is a Loki-queryable signal, not internal noise"
  - "No-webhook-URL log upgraded from log.debug to log.warn in WebhookDeliveryService: silently skipping delivery is a billable-path event worth visibility"
  - "Exception arg preserved as last positional arg in all error/warn catch blocks: stack trace attached to structured Loki entry"
  - "OrangeMoneyPort.assertPayTokenFresh() previously had no log — added kv() warn before throwing PayTokenExpiredException"

patterns-established:
  - "LOG-CODE-01: All log string arguments in payment domain use kv() — no {} placeholders"
  - "LOG-CODE-02: Post-transition duplicate logs deleted; structured transaction_state_change is the single source"
  - "LOG-CODE-03: PII tokens (payToken, notifToken, MSISDN) absent from log args; boolean mismatch flags or status strings used instead"

duration: 6min
completed: 2026-03-27
---

# Phase 17 Plan 01: Code Standards Enforcement — Payment Domain Summary

**LOG-CODE-01/02/03 fully enforced across all 9 payment domain files: zero {} placeholder logs, 8 duplicate code-flow lines deleted, and payToken/notifToken/MSISDN values removed from all log arguments**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-27T06:05:10Z
- **Completed:** 2026-03-27T06:11:31Z
- **Tasks:** 3
- **Files modified:** 9

## Accomplishments

- Converted all {} placeholder log calls across PaymentOrchestrator, OrangeMoneyPort, MtnMoMoPort, both poller jobs, WebhookTransitionService, WebhookDeliveryService, WebhookDoubleCheckHandler, and WebhookDeliveryJob to kv() structured arguments
- Deleted 8 LOG-CODE-02 duplicate/code-flow log lines: WebhookReceivedEvent published logs (OrangeMoneyPort:205, MtnMoMoPort:199), max-poll-attempts warns (OrangeStatusPollerJob:88, MtnStatusPollerJob:81), post-transition info logs (OrangeStatusPollerJob:124, MtnStatusPollerJob:117, WebhookTransitionService:112), and no-token skips already covered by structured events
- Removed PII from all log calls: Orange notifToken mismatch now logs `mismatch=true` (boolean), no-transaction-found warning omits payToken, MSISDN routing failure emits no msisdn value, assertPayTokenFresh() log emits only ageMinutes and status

## Task Commits

1. **Task 1: PaymentOrchestrator, OrangeMoneyPort, MtnMoMoPort — convert + PII removal** - `fb5bee8` (refactor)
2. **Task 2: OrangeStatusPollerJob, MtnStatusPollerJob, WebhookTransitionService — convert + delete duplicates** - `1968c5b` (refactor)
3. **Task 3: WebhookDeliveryService, WebhookDoubleCheckHandler, WebhookDeliveryJob — convert all** - `6b1f64c` (refactor)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` - Lines 128/139/145/390 converted; MSISDN removed from routing-failure log
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` - notifToken mismatch uses boolean; line 205 deleted; payToken removed from no-transaction log; assertPayTokenFresh() log added with kv()
- `src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java` - Lines 62/71/79-80/127/130 converted; lines 88 and 124 deleted
- `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` - Line 170 converted; line 199 deleted
- `src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java` - Lines 64/74/120/123 converted; lines 81 and 117 deleted
- `src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java` - Lines 70-71 converted; line 112 deleted
- `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java` - Lines 85/115/145-146/166-167/237-238 converted; throwable args preserved
- `src/main/java/com/softropic/payam/webhook/service/WebhookDoubleCheckHandler.java` - Added kv static import; lines 46-47/59/63/69 converted
- `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java` - Added kv static import; lines 37/42-43 converted

## Decisions Made

- **Still-PENDING logs upgraded from log.debug to log.info in both pollers.** The plan spec'd info level; the actual code was debug. Debug is invisible in production Loki. Upgraded to info so poller backpressure (many transactions stuck pending) is Loki-queryable.
- **No-webhook-URL log upgraded from log.debug to log.warn.** The plan spec'd warn level for this line; the code had debug. Upgraded as specified — silently skipping delivery is a billable-path signal.
- **Exception arg preserved as last positional arg in catch blocks.** kv() varargs followed by throwable is the correct SLF4J overload pattern; stack traces are included in structured Loki entries.
- **OrangeMoneyPort.assertPayTokenFresh() previously had the log as the only fix.** The existing code had `log.warn("payToken expired for transaction={}, age={}min", ...)` with `{}` placeholders — converted to kv().

## Deviations from Plan

None - plan executed exactly as written. The "still PENDING" debug-to-info upgrade and "no webhook URL" debug-to-warn upgrade were specified by the plan; this is per-spec behavior.

## Issues Encountered

Maven wrapper (`./mvnw`) was missing its properties file. Used system `mvn compile -q` instead. BUILD SUCCESS on all three compile checks.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Payment domain (9 files) is fully LOG-CODE-01/02/03 clean
- All {} placeholder violations eliminated; structured kv() log fields are Loki-queryable across every poller scan, webhook processing, and delivery failure
- Phase 17-02 can proceed to enforce standards in remaining service layer files
- Phase 17-03 can enforce standards in infrastructure/adapter layer

---
*Phase: 17-code-standards-enforcement*
*Completed: 2026-03-27*
