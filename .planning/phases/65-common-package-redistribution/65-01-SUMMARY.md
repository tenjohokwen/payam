---
phase: 65-common-package-redistribution
plan: "01"
subsystem: infrastructure.exception, infrastructure.message, infrastructure.config, infrastructure.logging
tags: [package-move, common, infrastructure, cmn-02, v12, phase-65-wave1]
dependency_graph:
  requires: [Phase 64 complete — payment.provider.mtn and payment.provider.orange at new paths (PROV-01, PROV-02)]
  provides: [infrastructure.exception (7 files), infrastructure.message (6 files), infrastructure.config +2 (CommonConfig + LongFromStringDeserializer), infrastructure.logging (2 files); CMN-02 partially satisfied]
  affects: [29 external caller files (25 exception + 5 message + 1 config test + 1 PlatformConfigServiceTest FQN body ref); 5 common.* sibling files retargeted ahead of their own moves in Plans 02-04]
tech_stack:
  added: []
  patterns:
    - "macOS BSD sed two-pass rewrite: sub-package decls first (ending with dot), root decls second (ending with semicolon)"
    - "git mv for directory-level rename — git detects 63-97% similarity and preserves full history"
    - "Single atomic commit for all 17 moved files + 29 external caller updates + 5 common sibling retargets = 52 total file changes"
    - "Broad FQN sweep (no anchor) catches both import lines AND inline FQN body expressions"
key_files:
  created:
    - src/main/java/com/softropic/payam/infrastructure/exception/ErrorCode.java
    - src/main/java/com/softropic/payam/infrastructure/exception/ApplicationException.java
    - src/main/java/com/softropic/payam/infrastructure/exception/ResourceNotFoundException.java
    - src/main/java/com/softropic/payam/infrastructure/exception/AppSetupException.java
    - src/main/java/com/softropic/payam/infrastructure/exception/ApplicationError.java
    - src/main/java/com/softropic/payam/infrastructure/exception/ConsumerNotFoundException.java
    - src/main/java/com/softropic/payam/infrastructure/exception/PaymentError.java
    - src/main/java/com/softropic/payam/infrastructure/message/ErrorDto.java
    - src/main/java/com/softropic/payam/infrastructure/message/ErrorMsg.java
    - src/main/java/com/softropic/payam/infrastructure/message/Failure.java
    - src/main/java/com/softropic/payam/infrastructure/message/FieldErrorDto.java
    - src/main/java/com/softropic/payam/infrastructure/message/Response.java
    - src/main/java/com/softropic/payam/infrastructure/message/Success.java
    - src/main/java/com/softropic/payam/infrastructure/config/CommonConfig.java
    - src/main/java/com/softropic/payam/infrastructure/config/LongFromStringDeserializer.java
    - src/main/java/com/softropic/payam/infrastructure/logging/InventoryCode.java
    - src/main/java/com/softropic/payam/infrastructure/logging/LogKeys.java
  deleted:
    - src/main/java/com/softropic/payam/common/exception/ (entire directory — 7 files)
    - src/main/java/com/softropic/payam/common/message/ (entire directory — 6 files)
    - src/main/java/com/softropic/payam/common/config/ (entire directory — 2 files)
    - src/main/java/com/softropic/payam/common/logging/ (entire directory — 2 files)
  modified:
    - src/main/java/com/softropic/payam/payment/core/contract/OrchestratorError.java
    - src/main/java/com/softropic/payam/payment/disbursement/api/DisbursementResource.java
    - src/main/java/com/softropic/payam/payment/disbursement/contract/DisbursementOrchestratorError.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/contract/exception/MtnApiException.java
    - src/main/java/com/softropic/payam/platform/admin/service/PlatformConfigService.java
    - src/main/java/com/softropic/payam/platform/monitoring/TlsStartupAssertion.java
    - src/main/java/com/softropic/payam/platform/security/api/AccountResource.java
    - src/main/java/com/softropic/payam/platform/security/api/ApiAdvice.java
    - src/main/java/com/softropic/payam/platform/security/api/ProfileResource.java
    - src/main/java/com/softropic/payam/platform/security/audit/listener/SecurityAuditListener.java
    - src/main/java/com/softropic/payam/platform/security/contract/event/SecurityAlertEvent.java
    - src/main/java/com/softropic/payam/platform/security/contract/exception/ (10 files)
    - src/main/java/com/softropic/payam/platform/security/infrastructure/filter/SecondFactorLoginFilter.java
    - src/main/java/com/softropic/payam/platform/security/infrastructure/jwt/JwtConfiguration.java
    - src/main/java/com/softropic/payam/platform/security/infrastructure/jwt/JwtSecretService.java
    - src/main/java/com/softropic/payam/platform/security/infrastructure/jwt/filter/JWTAuthenticationFilter.java
    - src/test/java/com/softropic/payam/platform/admin/service/PlatformConfigServiceTest.java (FQN body ref line 258)
    - src/test/java/com/softropic/payam/platform/security/SecurityIT.java
    - src/test/java/com/softropic/payam/common/configtest/JacksonTest.java
    - src/main/java/com/softropic/payam/common/client/exception/HttpClientException.java
    - src/main/java/com/softropic/payam/common/client/exception/MomoError.java
    - src/main/java/com/softropic/payam/common/util/JsonUtil.java
    - src/main/java/com/softropic/payam/common/validation/CamMobileValidator.java
    - src/main/java/com/softropic/payam/common/validation/CamPhoneValidator.java
decisions:
  - "5 common.* sibling files (common.util, common.client, common.validation) that reference common.exception.* or common.config.* were retargeted in this plan even though those sibling files themselves will move in Plans 02-04 — the alternative would be zero-stale-reference check failing (Rule 2: correctness requirement)"
  - "External caller list from Step A grep contained exactly 29 files matching the planned 29 (25 exception prod + 5 message prod + 1 config test + 1 PlatformConfigServiceTest with FQN body — with AccountResource appearing in both exception and message groups)"
  - "Single atomic commit for all 52 file changes — partial commit would leave codebase uncompilable (consistent with Phase 61/63/64 pattern)"
  - "macOS BSD sed two-pass approach: sub-package declarations first (ending with dot), root package declarations second (ending with semicolon) — consistent with Phase 63-02, 63-07, 64-01, 64-02 decisions in STATE.md"
  - "Phase 64 commits not yet in this worktree branch — merged 0464d86 (orange move) which pulled in both mtn and orange provider encapsulation before starting Wave 1"
metrics:
  duration: "~34 minutes"
  completed: "2026-05-12"
  tasks_completed: 1
  tasks_total: 1
  files_changed: 52
---

# Phase 65 Plan 01: common.exception/message/config/logging → infrastructure.* Summary

**One-liner:** Relocated four foundational common sub-packages (7 exception + 6 message + 2 config + 2 logging files = 17 total) to infrastructure.*, updated 29 external callers + 5 common sibling files, zero stale references, mvn verify green — CMN-02 Wave 1 complete.

## What Was Done

Relocated the four "foundational" common sub-packages to their respective `infrastructure.*` destinations as part of Phase 65 Wave 1. These packages had no dependencies on other common subpackages (with the sole intra-move dep being `InventoryCode` → `ErrorCode`, both moving together). They are the highest-volume dependencies — `ErrorCode` alone imported by 25 production files.

### Files Moved (17 production files)

**common.exception → infrastructure.exception (7 files):**

| File | Notes |
|------|-------|
| ErrorCode.java | Interface — referenced by 25 prod callers |
| ApplicationException.java | Base runtime exception class |
| ApplicationError.java | Error interface |
| ResourceNotFoundException.java | Referenced via FQN in PlatformConfigServiceTest.java line 258 |
| AppSetupException.java | Startup assertion exception |
| ConsumerNotFoundException.java | Consumer-not-found exception |
| PaymentError.java | Payment-domain error enum |

**common.message → infrastructure.message (6 files):**

| File | Notes |
|------|-------|
| Success.java | Response envelope |
| Failure.java | Failure envelope |
| Response.java | Base response wrapper |
| ErrorDto.java | Error DTO |
| ErrorMsg.java | Error message record |
| FieldErrorDto.java | Field-level validation error DTO |

**common.config → infrastructure.config (2 files, joins 3 pre-existing):**

| File | Notes |
|------|-------|
| CommonConfig.java | Spring @Configuration with Jackson ObjectMapper bean |
| LongFromStringDeserializer.java | Jackson JsonDeserializer<Long> for string-encoded longs |

**common.logging → infrastructure.logging (2 files):**

| File | Notes |
|------|-------|
| InventoryCode.java | Intra-move dep retargeted: common.exception.ErrorCode → infrastructure.exception.ErrorCode |
| LogKeys.java | Structured logging key constants |

### External Callers Updated (29 files total)

**From Step A grep — all 29 unique files:**

1. `src/main/java/com/softropic/payam/payment/core/contract/OrchestratorError.java`
2. `src/main/java/com/softropic/payam/payment/disbursement/api/DisbursementResource.java`
3. `src/main/java/com/softropic/payam/payment/disbursement/contract/DisbursementOrchestratorError.java`
4. `src/main/java/com/softropic/payam/payment/provider/mtn/contract/exception/MtnApiException.java`
5. `src/main/java/com/softropic/payam/platform/admin/service/PlatformConfigService.java`
6. `src/main/java/com/softropic/payam/platform/monitoring/TlsStartupAssertion.java`
7. `src/main/java/com/softropic/payam/platform/security/api/AccountResource.java` (imports both exception and message)
8. `src/main/java/com/softropic/payam/platform/security/api/ApiAdvice.java` (imports both exception and message)
9. `src/main/java/com/softropic/payam/platform/security/api/ProfileResource.java`
10. `src/main/java/com/softropic/payam/platform/security/audit/listener/SecurityAuditListener.java`
11. `src/main/java/com/softropic/payam/platform/security/contract/event/SecurityAlertEvent.java`
12. `src/main/java/com/softropic/payam/platform/security/contract/exception/AuthorizationException.java`
13. `src/main/java/com/softropic/payam/platform/security/contract/exception/EncryptionError.java`
14. `src/main/java/com/softropic/payam/platform/security/contract/exception/EncryptionException.java`
15. `src/main/java/com/softropic/payam/platform/security/contract/exception/InvalidJWTDataException.java`
16. `src/main/java/com/softropic/payam/platform/security/contract/exception/JWTExpiredException.java`
17. `src/main/java/com/softropic/payam/platform/security/contract/exception/MissingAuthenticationException.java`
18. `src/main/java/com/softropic/payam/platform/security/contract/exception/OperationNotAllowedException.java`
19. `src/main/java/com/softropic/payam/platform/security/contract/exception/ProfileActionException.java`
20. `src/main/java/com/softropic/payam/platform/security/contract/exception/SecError.java`
21. `src/main/java/com/softropic/payam/platform/security/contract/exception/SecException.java`
22. `src/main/java/com/softropic/payam/platform/security/contract/exception/SecurityError.java`
23. `src/main/java/com/softropic/payam/platform/security/contract/exception/UserDomainException.java`
24. `src/main/java/com/softropic/payam/platform/security/infrastructure/filter/SecondFactorLoginFilter.java`
25. `src/main/java/com/softropic/payam/platform/security/infrastructure/jwt/JwtConfiguration.java`
26. `src/main/java/com/softropic/payam/platform/security/infrastructure/jwt/JwtSecretService.java`
27. `src/main/java/com/softropic/payam/platform/security/infrastructure/jwt/filter/JWTAuthenticationFilter.java`
28. `src/test/java/com/softropic/payam/platform/admin/service/PlatformConfigServiceTest.java` (FQN body ref + import)
29. `src/test/java/com/softropic/payam/platform/security/SecurityIT.java` (common.config.CommonConfig import)

**Additional common.* sibling files retargeted (5 files, not in Step A list because they reside in common/ itself):**
- `src/main/java/com/softropic/payam/common/client/exception/HttpClientException.java`
- `src/main/java/com/softropic/payam/common/client/exception/MomoError.java`
- `src/main/java/com/softropic/payam/common/util/JsonUtil.java`
- `src/main/java/com/softropic/payam/common/validation/CamMobileValidator.java`
- `src/main/java/com/softropic/payam/common/validation/CamPhoneValidator.java`

### Special Cases

**PlatformConfigServiceTest.java FQN body reference (line 258):**
- Before: `.isInstanceOf(com.softropic.payam.common.exception.ResourceNotFoundException.class)`
- After: `.isInstanceOf(com.softropic.payam.infrastructure.exception.ResourceNotFoundException.class)`
- Confirmed: `grep -c 'com.softropic.payam.common.exception.'` returns 0

**InventoryCode.java intra-move dependency:**
- Before: `import com.softropic.payam.common.exception.ErrorCode;`
- After: `import com.softropic.payam.infrastructure.exception.ErrorCode;`
- Both files moved in the same atomic commit — no intermediate broken state

### Spring/JPA/Jackson Annotations Preserved

- `CommonConfig.java`: `@Configuration` annotation preserved
- `ApiAdvice.java`: `@RestControllerAdvice` and all `@ExceptionHandler` annotations preserved
- `ApplicationException.java`: `extends RuntimeException` inheritance preserved
- `LongFromStringDeserializer.java`: `extends JsonDeserializer<Long>` and Jackson annotations preserved
- `PlatformConfigService.java`: `@Service` annotation preserved

### Phase 64 Work Preserved

- `grep -rn 'com.softropic.payam.mtn.' src --include='*.java' | wc -l` = **0**
- `grep -rn 'com.softropic.payam.orange.' src --include='*.java' | wc -l` = **0**
- `test -d src/main/java/com/softropic/payam/mtn` = **false**
- `test -d src/main/java/com/softropic/payam/orange` = **false**

### Other Common Subpackages Remain Intact for Plans 02-04

- `common/payment` directory exists — Plan 03
- `common/refund` (under payment/) — Plan 03
- `common/client` directory exists — Plan 02
- `common/threadpool` directory exists — Plan 02
- `common/util` directory exists — Plan 02
- `common/validation` directory exists — Plan 02
- `common/enums` directory exists — Plan 04
- `common/consumer` directory exists — Plan 04
- `common/dto` directory exists — Plan 02
- `ClockProvider.java` exists — Plan 02
- `Constants.java` exists — Plan 02
- `Gender.java` exists — Plan 04
- `Predicate.java` exists — Plan 02
- `TimeGuru.java` exists — Plan 02
- `TransactionIdProvider.java` exists — Plan 02

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Merged Phase 64 commits before starting Wave 1**

- **Found during:** Pre-flight (Step A pre-check)
- **Issue:** Worktree branch was at Phase 63 completion state — flat `mtn/` and `orange/` packages still existed, causing 92 and 90 stale references respectively. Phase 64 SUMMARY confirmed 0 references but commits weren't in this worktree branch.
- **Fix:** Merged commit `0464d86` (Phase 64-02 orange move) which transitively included `867d282` (Phase 64-01 mtn move) — both provider encapsulations pulled into branch before starting.
- **Files modified:** 32 orange + mtn provider files via merge (no edits, just merge)
- **Commit:** Separate merge commit before the task commit

**2. [Rule 1 - Bug] Extended FQN sweep to common.* sibling files not in external caller list**

- **Found during:** Step I (zero-stale-reference verification)
- **Issue:** After updating all 29 external callers from Step A list, 8 stale `common.exception.*` references and 2 stale `common.config.*` references remained in `common/util/JsonUtil.java`, `common/client/exception/HttpClientException.java`, `common/client/exception/MomoError.java`, `common/validation/CamMobileValidator.java`, `common/validation/CamPhoneValidator.java`. These files reside inside `common/` so were excluded from the Step A grep, but they still reference the newly moved packages.
- **Fix:** Applied the same FQN sweep to all `src/main/java/com/softropic/payam/common/**/*.java` and `src/test/java/com/softropic/payam/common/**/*.java` files. After this, all four moved-package stale reference counts went to 0.
- **Files modified:** 5 files (listed above)
- **Commit:** Included in the atomic task commit (e6c6614)

## mvn verify Result

- `mvn -q test-compile` = **exits 0** (BUILD-01 compile gate)
- `mvn -q verify` = **exits 0** (BUILD-01 full integration suite — unit tests + integration tests including Testcontainers)

## Commits

| Task | Name | Commit | Files |
|------|------|--------|-------|
| Pre-step | Merge Phase 64 provider encapsulation into worktree | merge commit | 57 orange+mtn files |
| 1 | Move common.exception/message/config/logging to infrastructure.* | e6c6614 | 52 files (17 moved + 29 external callers + 5 common siblings) |

## Known Stubs

None — this is a pure package rename with no new functionality, no UI data sources, and no placeholder values introduced.

## Self-Check: PASSED
