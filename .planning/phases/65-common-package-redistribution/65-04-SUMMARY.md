---
phase: 65-common-package-redistribution
plan: "04"
subsystem: platform.security
tags: [java, spring-boot, package-refactoring, common, platform-security, consumer, gender]

# Dependency graph
requires:
  - phase: 65-common-package-redistribution
    plan: "01"
    provides: "infrastructure.exception, infrastructure.message, infrastructure.config, infrastructure.logging at new paths"
  - phase: 65-common-package-redistribution
    plan: "02"
    provides: "infrastructure.client, infrastructure.threadpool, infrastructure.util, infrastructure.validation at new paths; Consumer.java PhoneNumber import already retargeted to infrastructure.validation.PhoneNumber"
  - phase: 65-common-package-redistribution
    plan: "03"
    provides: "payment.core.contract for common.payment + common.refund redistribution"
  - phase: 64-provider-infrastructure-encapsulation
    provides: "payment.provider.mtn and payment.provider.orange — no mtn/orange flat refs remain"
provides:
  - "platform.security.contract.Consumer — interface for user domain (4 production callers: Customer, UserRepository, CustomerService, UserService)"
  - "platform.security.contract.Location — companion interface to Consumer"
  - "platform.security.contract.Gender — enum with @JsonCreator (13 callers: 8 prod + 5 test, all platform.security.*)"
  - "infrastructure.util.Picker — generic enum utility interface (zero external callers)"
  - "infrastructure.util.Unit — measurement enum legacy remnant (zero external callers)"
  - "Empty production common/ shell — Plan 05 deletes the parent directory"
affects:
  - "platform.security.contract — Consumer, Location, Gender co-located with Principal, UserDto, PermutedSecretKey"
  - "platform.security.repo — Customer, UserRepository updated imports"
  - "platform.security.service — CustomerService, UserService, SecurityUtil, UserProfileService updated imports"
  - "platform.security.infrastructure.jwt — ClaimsExtractorImpl, JwtManagerImpl updated imports"
  - "platform.security.api.dto — UpdateUserInfoDto updated import"
  - "infrastructure.util — gains Picker + Unit (zero-caller moves per RESEARCH.md Open Question 2)"

# Tech stack
tech-stack:
  added: []
  patterns:
    - "macOS sed single-file-at-a-time pattern (shell variable with multiple paths treated as single filename)"
    - "Same-package imports in Java: Principal.java and UserDto.java retain import com.softropic.payam.platform.security.contract.Gender — redundant but legal"
    - "Pitfall 4 intra-domain import preservation: Consumer.java address import unchanged after move to same platform.security domain"

# Key files
key-files:
  created:
    - path: "src/main/java/com/softropic/payam/platform/security/contract/Consumer.java"
      note: "Moved from common.consumer — package declaration updated; Gender import retargeted to same-package; PhoneNumber + Address imports preserved"
    - path: "src/main/java/com/softropic/payam/platform/security/contract/Location.java"
      note: "Moved from common.consumer — package declaration updated; no project imports"
    - path: "src/main/java/com/softropic/payam/platform/security/contract/Gender.java"
      note: "Moved from common (root level) — package declaration updated from com.softropic.payam.common; @JsonCreator preserved"
    - path: "src/main/java/com/softropic/payam/infrastructure/util/Picker.java"
      note: "Moved from common.enums — package declaration updated; zero external callers"
    - path: "src/main/java/com/softropic/payam/infrastructure/util/Unit.java"
      note: "Moved from common.enums — package declaration updated; zero external callers; likely dead code (future cleanup)"
  deleted:
    - "src/main/java/com/softropic/payam/common/consumer/ (entire directory)"
    - "src/main/java/com/softropic/payam/common/enums/ (entire directory)"
    - "src/main/java/com/softropic/payam/common/Gender.java"
    - "src/main/java/com/softropic/payam/common/dto/ (empty parent left by Plan 02 — rmdir'd)"
  modified:
    - "src/main/java/com/softropic/payam/platform/security/api/dto/UpdateUserInfoDto.java (Gender import retargeted)"
    - "src/main/java/com/softropic/payam/platform/security/contract/Principal.java (Gender import retargeted — now same-package)"
    - "src/main/java/com/softropic/payam/platform/security/contract/UserDto.java (Gender import retargeted — now same-package)"
    - "src/main/java/com/softropic/payam/platform/security/infrastructure/jwt/ClaimsExtractorImpl.java (Gender import retargeted)"
    - "src/main/java/com/softropic/payam/platform/security/infrastructure/jwt/JwtManagerImpl.java (Gender import retargeted)"
    - "src/main/java/com/softropic/payam/platform/security/repo/Customer.java (Gender + Consumer imports retargeted)"
    - "src/main/java/com/softropic/payam/platform/security/repo/UserRepository.java (Consumer import retargeted)"
    - "src/main/java/com/softropic/payam/platform/security/service/CustomerService.java (Consumer import retargeted)"
    - "src/main/java/com/softropic/payam/platform/security/service/SecurityUtil.java (Gender import retargeted)"
    - "src/main/java/com/softropic/payam/platform/security/service/UserProfileService.java (Gender import retargeted)"
    - "src/main/java/com/softropic/payam/platform/security/service/UserService.java (Consumer import retargeted)"
    - "src/test/java/com/softropic/payam/platform/security/SecurityIT.java (Gender import retargeted)"
    - "src/test/java/com/softropic/payam/platform/security/api/AccountManagementFacadeIT.java (Gender import retargeted)"
    - "src/test/java/com/softropic/payam/platform/security/infrastructure/jwt/JwtManagerImplTest.java (Gender import retargeted)"
    - "src/test/java/com/softropic/payam/platform/security/infrastructure/jwt/filter/JWTAuthenticationFilterTest.java (Gender import retargeted)"
    - "src/test/java/com/softropic/payam/platform/security/service/UserServiceIT.java (Gender import retargeted)"

# Decisions
decisions:
  - "macOS sed cannot handle multiple file paths in a shell variable as separate args — must loop or call sed once per file (same pattern as Plans 01-03)"
  - "Principal.java and UserDto.java retain import com.softropic.payam.platform.security.contract.Gender after move — same-package imports are redundant but legal Java; sed retargets them, compiler accepts them without error"
  - "Consumer.java's import of platform.security.repo.Address preserved byte-for-byte (Pitfall 4 invariant) — intra-domain import is legal after Consumer moves to platform.security.contract"
  - "Picker.java + Unit.java placed in infrastructure.util per RESEARCH.md Open Question 2 disposition — zero external callers, no domain claim; Unit is likely dead code but safe to defer deletion"

# Metrics
metrics:
  duration: "~5 minutes"
  completed: "2026-05-12"
  tasks_completed: 1
  files_created: 5
  files_deleted: 4
  files_modified: 16
  total_files_changed: 21
---

# Phase 65 Plan 04: Security Domain Type Redistribution Summary

**One-liner:** Security domain types (Gender, Consumer, Location) moved from common to platform.security.contract; dead-code enums (Picker, Unit) moved to infrastructure.util; 16 external callers updated; production common/ now empty of .java files.

## Objective Achieved

Redistributed the remaining security-domain types from `common/` to their owning bounded context. After this plan, `common/` production directory contains zero `.java` files — only the empty parent shell remains for Plan 05 to delete. CMN-03 fully satisfied.

## Files Moved (5 production files)

**To `platform.security.contract` (3 files — security domain types with 13+ callers):**

| File | Old Location | Callers |
|------|-------------|---------|
| Consumer.java | common.consumer.Consumer | 4 prod (Customer, UserRepository, CustomerService, UserService) |
| Location.java | common.consumer.Location | 0 external (used transitively via Consumer) |
| Gender.java | common.Gender (root) | 8 prod + 5 test — all in platform.security.* |

**To `infrastructure.util` (2 files — zero-caller dead-code enums):**

| File | Old Location | Callers |
|------|-------------|---------|
| Picker.java | common.enums.Picker | 0 external |
| Unit.java | common.enums.Unit | 0 external |

## External Callers Updated (16 unique files)

The following 16 files had their import statements updated from `common.*` to the new locations:

**Production (11 files):**
- `platform.security.api.dto.UpdateUserInfoDto` — Gender
- `platform.security.contract.Principal` — Gender (now same-package import, redundant but legal)
- `platform.security.contract.UserDto` — Gender (now same-package import, redundant but legal)
- `platform.security.infrastructure.jwt.ClaimsExtractorImpl` — Gender
- `platform.security.infrastructure.jwt.JwtManagerImpl` — Gender
- `platform.security.repo.Customer` — Gender + Consumer
- `platform.security.repo.UserRepository` — Consumer
- `platform.security.service.CustomerService` — Consumer
- `platform.security.service.SecurityUtil` — Gender
- `platform.security.service.UserProfileService` — Gender
- `platform.security.service.UserService` — Consumer

**Test (5 files):**
- `platform.security.SecurityIT` — Gender
- `platform.security.api.AccountManagementFacadeIT` — Gender
- `platform.security.infrastructure.jwt.JwtManagerImplTest` — Gender
- `platform.security.infrastructure.jwt.filter.JWTAuthenticationFilterTest` — Gender
- `platform.security.service.UserServiceIT` — Gender

## Critical Invariants Honored

**Pitfall 4 — Consumer.java's Address import PRESERVED:**
`import com.softropic.payam.platform.security.repo.Address;` was preserved byte-for-byte in Consumer.java after the move. This intra-domain import (`platform.security.contract` → `platform.security.repo`) is legal and was NOT modified.

**Pitfall 2 — platform.security.common.* files NOT touched:**
13 files in `com.softropic.payam.platform.security.common.event.*` and `com.softropic.payam.platform.security.common.util.*` packages remain completely untouched. Their package declarations still read `com.softropic.payam.platform.security.common.*` — no sed sweep touched them.

**PhoneNumber import (Plan 02 retarget) PRESERVED:**
Consumer.java's `import com.softropic.payam.infrastructure.validation.PhoneNumber;` (retargeted by Plan 02) was preserved by Plan 04.

**@JsonCreator PRESERVED:**
Gender.java's Jackson `@JsonCreator` annotation preserved — Spring/Jackson deserializes Gender from JSON strings correctly.

**Consumer interface declaration PRESERVED:**
`public interface Consumer extends Serializable` declaration preserved — all 4 production implementors compile correctly.

## Directories Cleaned Up

| Directory | Status |
|-----------|--------|
| `common/consumer/` | Deleted (git mv + rmdir) |
| `common/enums/` | Deleted (git mv + rmdir) |
| `common/dto/` | Already removed by Plan 02 |
| `common/Gender.java` | Deleted (git mv) |

## Preserved Prior Plan Work

All zero-stale-reference checks passed after this plan:

| Prior Plan | Pattern | Count |
|-----------|---------|-------|
| Plan 01 | common.{exception,message,config,logging}.* | 0 |
| Plan 02 | common.{client,threadpool,util,validation,dto}.* | 0 |
| Plan 02 root | common.{ClockProvider,Constants,Predicate,TimeGuru,TransactionIdProvider} | 0 |
| Plan 03 | common.{payment,refund}.* | 0 |
| Phase 64 | {mtn,orange}.* | 0 |

## State of common/ After Plan 04

**Production (`src/main/java/.../common/`):**
- Zero `.java` files — completely empty of production sources
- Only the empty parent shell directory `common/` remains
- Plan 05 deletes the empty shell (CMN-04 gate)

**Test (`src/test/java/.../common/`):**
- Still contains 4 test utilities: `AdminLogin.java`, `HttpTestClient.java`, `TransactionExceptionSimulator.java`, `configtest/JacksonTest.java`
- These 4 files are NOT moved by any plan — they are test infrastructure that legitimately lives in `common/` for test support
- Plan 05 will NOT move them either (they are test files, not production common/* redistribution scope)

## Build Result

- `mvn -q test-compile`: EXIT 0 (compile gate passed)
- `mvn -q verify`: EXIT 0 (full integration suite — SecurityIT, JwtManagerImplTest, AccountManagementFacadeIT, UserServiceIT, JWTAuthenticationFilterTest all exercised Gender + Consumer through new platform.security.contract location)

## Deviations from Plan

**[Deviation - Technique] macOS sed multi-file variable expansion**
- **Found during:** Step C (package declaration rewrite)
- **Issue:** macOS BSD sed does not expand shell variables containing multiple space-separated file paths — treats the entire variable as a single filename. `CONTRACT_FILES="f1 f2 f3"; sed -i '' ... $CONTRACT_FILES` fails with "No such file or directory".
- **Fix:** Changed to explicit `for f in "file1" "file2" ...; do sed -i '' ... "$f"; done` loop pattern — consistent with how Plans 01-03 handled this in their actual execution.
- **Impact:** No functional change; same sed patterns applied. All 5 files correctly updated.
- **Files modified:** No additional files.

## Known Stubs

None — this plan performs pure package relocation. No data sources, no APIs, no UI components.

## Self-Check

Files exist:
- `src/main/java/com/softropic/payam/platform/security/contract/Consumer.java`: FOUND
- `src/main/java/com/softropic/payam/platform/security/contract/Location.java`: FOUND
- `src/main/java/com/softropic/payam/platform/security/contract/Gender.java`: FOUND
- `src/main/java/com/softropic/payam/infrastructure/util/Picker.java`: FOUND
- `src/main/java/com/softropic/payam/infrastructure/util/Unit.java`: FOUND

Deleted directories absent:
- `common/consumer/`: DELETED
- `common/enums/`: DELETED
- `common/dto/`: DELETED (by Plan 02)
- `common/Gender.java`: DELETED

Commit: d448edc — FOUND

## Self-Check: PASSED
