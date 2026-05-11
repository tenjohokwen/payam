# Phase 65: Common Package Redistribution - Research

**Researched:** 2026-05-12
**Domain:** Java package refactoring — Spring Boot, Maven, pure package redistribution
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CMN-01 | `common.payment` and `common.refund` classes relocated to `payment.core` with all imports updated | 6 prod payment files + 3 refund files to move; 44 prod + 40 test external import lines identified; 4 FQN body references in MtnMoMoPort, OrangeMoneyPort, DisbursementResponse Javadoc |
| CMN-02 | All infrastructure-scoped common sub-packages (`common.exception`, `common.message`, `common.validation`, `common.util`, `common.client`, `common.threadpool`, `common.config`, `common.logging`) relocated to `infrastructure.*`; root-level common utilities (ClockProvider, Constants, TransactionIdProvider, Predicate, TimeGuru) also relocated to `infrastructure.util` | 54 files to move; 93 prod + 24 test external import lines identified; 1 FQN body reference in MtnMoMoClient catch block; 1 FQN body reference in PlatformConfigServiceTest |
| CMN-03 | Domain-specific enums in `common.enums` moved to owning domain packages; root-level `Gender` enum moved to `platform.security.contract`; `common.consumer` and `common.dto` moved to `platform.security.contract` | `Picker` interface and `Unit` enum have zero external callers; `Gender` has 13 prod callers all in `platform.security.*`; consumer/dto all in `platform.security.*` |
| CMN-04 | `common` package fully emptied and removed; `find src -name "*.java" | xargs grep "com.softropic.payam.common"` returns no results | Verified by confirming all 74 prod common files are accounted for across CMN-01, CMN-02, CMN-03; plus 7 test-common files also require relocation |
</phase_requirements>

## Summary

Phase 65 is the final redistribution step in the v12 architectural reorganization. The `common` package has served as a catch-all since v1 — it now needs to be fully emptied and deleted, with each class placed in the bounded context that owns it. No new logic is introduced. No Flyway migrations, API contract changes, or behavioral changes are in scope.

The `common` package currently contains **74 production Java files** in 14 sub-packages plus 6 root-level files. Additionally, **7 test utility files** in `src/test/java/com/softropic/payam/common/` must be relocated. Note that `common.persistence` was already moved to `infrastructure.persistence` in Phase 61 — those files are gone and are NOT part of this phase's scope.

The redistribution maps to three destination bounded contexts:
1. **payment.core** — `common.payment` (6 files) and `common.refund` (3 files): the port interfaces and value types that define the mobile money contract
2. **infrastructure** — 11 sub-packages (54 files) plus 6 root-level utilities: generic cross-cutting infrastructure with no domain ownership
3. **platform.security.contract** — `common.enums.Gender`, `common.consumer` (2 files), `common.dto` (1 file): types used exclusively by the security/user domain

The largest challenge is the import sweep: **132 distinct non-common files** need import updates (91 production + 40 test + 1 PlatformConfigServiceTest with FQN in method body). The dominant import frequency is `common.payment.MobilePaymentProvider` which appears in 25+ production files and 20+ test files.

**Primary recommendation:** Execute in four atomic waves aligned to the destination bounded context: Wave 1 (common.payment + common.refund → payment.core), Wave 2 (infrastructure sub-packages — exception/message/validation/util), Wave 3 (infrastructure sub-packages — client/threadpool/config/logging + root utilities), Wave 4 (platform.security enums/consumer/dto + common package deletion). Run `mvn test-compile -q` after each wave, `mvn verify` after Wave 4.

## Standard Stack

### Core (verified from source tree)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | (current project version) | Application framework, component scan | Already in use |
| Maven Failsafe + Surefire | (via Spring Boot BOM) | `mvn verify` integration test execution | Already in use |
| Java 17 | 17 (LTS) | Compilation target | Already in use |
| macOS sed | BSD sed | File text replacement during refactoring | Platform constraint — no `\b` word boundary |

**Installation:** No new dependencies required. This is a pure package redistribution.

## Architecture Patterns

### Target Project Structure (after Phase 65)
```
src/main/java/com/softropic/payam
├── payment/
│   ├── core/
│   │   ├── contract/               NEW additions from common.payment + common.refund
│   │   │   ├── MobileMoneyPort.java
│   │   │   ├── MobilePaymentProvider.java
│   │   │   ├── PaymentCommand.java
│   │   │   ├── PaymentMethod.java
│   │   │   ├── ProviderResult.java
│   │   │   ├── SubscriberStatus.java
│   │   │   ├── ChargeType.java     (was common.refund)
│   │   │   ├── RefundPolicy.java   (was common.refund)
│   │   │   └── RefundType.java     (was common.refund)
│   │   └── ... (existing sub-packages)
│   └── ... (payment.disbursement, payment.fraud, etc.)
├── platform/
│   ├── security/
│   │   ├── contract/
│   │   │   ├── Gender.java         NEW (was common.Gender)
│   │   │   ├── Consumer.java       NEW (was common.consumer.Consumer)
│   │   │   └── Location.java       NEW (was common.consumer.Location)
│   │   └── ... (existing)
│   └── ... (platform.tenant, etc.)
└── infrastructure/
    ├── config/                     (existing: AsyncConfig, DataSourceConfig, ObservabilityConfig)
    │   ├── CommonConfig.java       NEW (was common.config.CommonConfig)
    │   └── LongFromStringDeserializer.java  NEW
    ├── persistence/                (existing from Phase 61)
    ├── web/                        (existing: ApiKeyAuthenticationFilter, etc.)
    ├── client/                     NEW sub-package
    │   ├── AbstractClient.java
    │   ├── Client.java
    │   ├── ClientConfiguration.java
    │   ├── ClientRequest.java
    │   ├── RestRequestInterceptor.java
    │   ├── TcpConfiguration.java
    │   └── exception/
    │       ├── HttpClientException.java
    │       └── MomoError.java
    ├── exception/                  NEW sub-package
    │   ├── AppSetupException.java
    │   ├── ApplicationError.java
    │   ├── ApplicationException.java
    │   ├── ConsumerNotFoundException.java
    │   ├── ErrorCode.java
    │   ├── PaymentError.java
    │   └── ResourceNotFoundException.java
    ├── message/                    NEW sub-package
    │   ├── ErrorDto.java
    │   ├── ErrorMsg.java
    │   ├── Failure.java
    │   ├── FieldErrorDto.java
    │   ├── Response.java
    │   └── Success.java
    ├── threadpool/                 NEW sub-package
    │   ├── ClientThreadContext.java
    │   ├── ExecutorExceptionHandler.java
    │   ├── MdcDecorator.java
    │   ├── MdcWrapper.java
    │   └── TenantContextTaskDecorator.java
    ├── util/                       NEW sub-package
    │   ├── BodySanitizer.java
    │   ├── ClockProvider.java      (was common.ClockProvider)
    │   ├── Constants.java          (was common.Constants)
    │   ├── EnvUtil.java
    │   ├── JsonUtil.java
    │   ├── PhoneNumberUtil.java
    │   ├── Predicate.java          (was common.Predicate)
    │   ├── RandomUtil.java
    │   ├── TimeGuru.java           (was common.TimeGuru)
    │   ├── TimeUtil.java
    │   └── TransactionIdProvider.java  (was common.TransactionIdProvider)
    ├── validation/                 NEW sub-package
    │   ├── CamMobileValidator.java
    │   ├── CamPhone.java
    │   ├── CamPhoneValidator.java
    │   ├── InputValidator.java
    │   ├── IsoLangCodes.java
    │   ├── IsoLangUtil.java
    │   ├── JsonValidator.java
    │   ├── LangIso2.java
    │   ├── LangIso2Validator.java
    │   ├── LangUtil.java
    │   ├── LocalDateWindow.java
    │   ├── LocalDateWindowValidator.java
    │   ├── Name.java
    │   ├── NameValidator.java
    │   ├── Phone.java
    │   ├── PhoneNumber.java
    │   ├── PhoneNumberValidator.java
    │   └── Provider.java
    └── logging/                    NEW sub-package
        ├── InventoryCode.java
        └── LogKeys.java
```

### Pattern 1: Atomic Two-Pass sed (macOS constraint)
**What:** macOS `sed` does not support `\b` word boundaries. All package declaration rewrites require two passes: first for sub-package declarations (ending with dot), second for root package declarations (ending with semicolon).
**When to use:** All package declaration rewriting in this codebase.
**Example:**
```bash
# Two-pass for files moving from common.payment to payment.core.contract
# Pass 1: sub-package declarations
find src -name "*.java" -path "*/common/payment/*" | xargs sed -i '' \
  's/package com\.softropic\.payam\.common\.payment\./package com.softropic.payam.payment.core.contract./g'
# Pass 2: root package declaration
find src -name "*.java" -path "*/common/payment/*" | xargs sed -i '' \
  's/package com\.softropic\.payam\.common\.payment;/package com.softropic.payam.payment.core.contract;/g'
```

### Pattern 2: Import Sweep with FQN Body Scan
**What:** After updating import statements via sed, grep for FQN references in code bodies — several files in this codebase use FQNs outside of `import` blocks.
**When to use:** After every import sweep pass. Established as mandatory in Phase 63-07.
**Example:**
```bash
# After moving common.payment:
grep -rn "com\.softropic\.payam\.common\." src --include="*.java"
# Fix any remaining FQN hits (imports + bodies)
```

### Pattern 3: Atomic Single Commit per Wave
**What:** All file moves AND all corresponding caller import updates must land in a single commit per wave. Partial commits leave the codebase uncompilable.
**When to use:** Always — established in Phase 61 decision log.

### Pattern 4: Spring Component Scan — No Changes Needed
**What:** `@SpringBootApplication` at `com.softropic.payam` scans all sub-packages automatically. Moving classes from `common.*` to `infrastructure.*`, `payment.core.*`, or `platform.security.*` requires no `@ComponentScan` changes.
**When to use:** This pattern means zero Spring configuration files need updating for component discovery.

### Anti-Patterns to Avoid
- **Partial wave commit:** Moving files without updating callers in the same commit — leaves codebase in non-compiling state.
- **Import-only sed sweep:** Only sweeping `import` blocks — misses FQN references in method bodies (MtnMoMoClient catch block, OrangeMoneyPort/MtnMoMoPort method bodies, DisbursementResponse Javadoc `@link`, PlatformConfigServiceTest assertion).
- **Moving common.persistence:** It was already removed in Phase 61 — attempting to move it again will produce file-not-found errors.
- **Moving `platform.security.common.*` files:** These are already correctly named at `platform.security.common.event.*` and `platform.security.common.util.*` — they are NOT part of the `common` package being redistributed. They have `com.softropic.payam.platform.security.common` package declarations, not `com.softropic.payam.common`.

## Complete File Inventory

### CMN-01: common.payment → payment.core.contract (6 files to move)
| File | New Location |
|------|-------------|
| `common/payment/MobileMoneyPort.java` | `payment/core/contract/MobileMoneyPort.java` |
| `common/payment/MobilePaymentProvider.java` | `payment/core/contract/MobilePaymentProvider.java` |
| `common/payment/PaymentCommand.java` | `payment/core/contract/PaymentCommand.java` |
| `common/payment/PaymentMethod.java` | `payment/core/contract/PaymentMethod.java` |
| `common/payment/ProviderResult.java` | `payment/core/contract/ProviderResult.java` |
| `common/payment/SubscriberStatus.java` | `payment/core/contract/SubscriberStatus.java` |

### CMN-01: common.refund → payment.core.contract (3 files to move)
| File | New Location |
|------|-------------|
| `common/refund/ChargeType.java` | `payment/core/contract/ChargeType.java` |
| `common/refund/RefundPolicy.java` | `payment/core/contract/RefundPolicy.java` |
| `common/refund/RefundType.java` | `payment/core/contract/RefundType.java` |

**Note on refund:** `common.refund` has **zero external callers** in production or test code — no file outside of `common/` imports from `common.refund`. The 3 refund files reference each other internally. They still must move to empty the `common` package per CMN-04. Placing them in `payment.core.contract` satisfies the requirement that `common.refund` moves to `payment.core`.

### CMN-02: Infrastructure Sub-Packages (54 files to move)

**common.exception → infrastructure.exception (7 files)**
| File | New Location |
|------|-------------|
| `common/exception/AppSetupException.java` | `infrastructure/exception/AppSetupException.java` |
| `common/exception/ApplicationError.java` | `infrastructure/exception/ApplicationError.java` |
| `common/exception/ApplicationException.java` | `infrastructure/exception/ApplicationException.java` |
| `common/exception/ConsumerNotFoundException.java` | `infrastructure/exception/ConsumerNotFoundException.java` |
| `common/exception/ErrorCode.java` | `infrastructure/exception/ErrorCode.java` |
| `common/exception/PaymentError.java` | `infrastructure/exception/PaymentError.java` |
| `common/exception/ResourceNotFoundException.java` | `infrastructure/exception/ResourceNotFoundException.java` |

**common.message → infrastructure.message (6 files)**
| File | New Location |
|------|-------------|
| `common/message/ErrorDto.java` | `infrastructure/message/ErrorDto.java` |
| `common/message/ErrorMsg.java` | `infrastructure/message/ErrorMsg.java` |
| `common/message/Failure.java` | `infrastructure/message/Failure.java` |
| `common/message/FieldErrorDto.java` | `infrastructure/message/FieldErrorDto.java` |
| `common/message/Response.java` | `infrastructure/message/Response.java` |
| `common/message/Success.java` | `infrastructure/message/Success.java` |

**common.validation → infrastructure.validation (18 files)**
| File | New Location |
|------|-------------|
| `common/validation/CamMobileValidator.java` | `infrastructure/validation/CamMobileValidator.java` |
| `common/validation/CamPhone.java` | `infrastructure/validation/CamPhone.java` |
| `common/validation/CamPhoneValidator.java` | `infrastructure/validation/CamPhoneValidator.java` |
| `common/validation/InputValidator.java` | `infrastructure/validation/InputValidator.java` |
| `common/validation/IsoLangCodes.java` | `infrastructure/validation/IsoLangCodes.java` |
| `common/validation/IsoLangUtil.java` | `infrastructure/validation/IsoLangUtil.java` |
| `common/validation/JsonValidator.java` | `infrastructure/validation/JsonValidator.java` |
| `common/validation/LangIso2.java` | `infrastructure/validation/LangIso2.java` |
| `common/validation/LangIso2Validator.java` | `infrastructure/validation/LangIso2Validator.java` |
| `common/validation/LangUtil.java` | `infrastructure/validation/LangUtil.java` |
| `common/validation/LocalDateWindow.java` | `infrastructure/validation/LocalDateWindow.java` |
| `common/validation/LocalDateWindowValidator.java` | `infrastructure/validation/LocalDateWindowValidator.java` |
| `common/validation/Name.java` | `infrastructure/validation/Name.java` |
| `common/validation/NameValidator.java` | `infrastructure/validation/NameValidator.java` |
| `common/validation/Phone.java` | `infrastructure/validation/Phone.java` |
| `common/validation/PhoneNumber.java` | `infrastructure/validation/PhoneNumber.java` |
| `common/validation/PhoneNumberValidator.java` | `infrastructure/validation/PhoneNumberValidator.java` |
| `common/validation/Provider.java` | `infrastructure/validation/Provider.java` |

**common.util → infrastructure.util (6 files)**
| File | New Location |
|------|-------------|
| `common/util/BodySanitizer.java` | `infrastructure/util/BodySanitizer.java` |
| `common/util/EnvUtil.java` | `infrastructure/util/EnvUtil.java` |
| `common/util/JsonUtil.java` | `infrastructure/util/JsonUtil.java` |
| `common/util/PhoneNumberUtil.java` | `infrastructure/util/PhoneNumberUtil.java` |
| `common/util/RandomUtil.java` | `infrastructure/util/RandomUtil.java` |
| `common/util/TimeUtil.java` | `infrastructure/util/TimeUtil.java` |

**common.client → infrastructure.client (6 files + exception sub-package with 2 files)**
| File | New Location |
|------|-------------|
| `common/client/AbstractClient.java` | `infrastructure/client/AbstractClient.java` |
| `common/client/Client.java` | `infrastructure/client/Client.java` |
| `common/client/ClientConfiguration.java` | `infrastructure/client/ClientConfiguration.java` |
| `common/client/ClientRequest.java` | `infrastructure/client/ClientRequest.java` |
| `common/client/RestRequestInterceptor.java` | `infrastructure/client/RestRequestInterceptor.java` |
| `common/client/TcpConfiguration.java` | `infrastructure/client/TcpConfiguration.java` |
| `common/client/exception/HttpClientException.java` | `infrastructure/client/exception/HttpClientException.java` |
| `common/client/exception/MomoError.java` | `infrastructure/client/exception/MomoError.java` |

**common.threadpool → infrastructure.threadpool (5 files)**
| File | New Location |
|------|-------------|
| `common/threadpool/ClientThreadContext.java` | `infrastructure/threadpool/ClientThreadContext.java` |
| `common/threadpool/ExecutorExceptionHandler.java` | `infrastructure/threadpool/ExecutorExceptionHandler.java` |
| `common/threadpool/MdcDecorator.java` | `infrastructure/threadpool/MdcDecorator.java` |
| `common/threadpool/MdcWrapper.java` | `infrastructure/threadpool/MdcWrapper.java` |
| `common/threadpool/TenantContextTaskDecorator.java` | `infrastructure/threadpool/TenantContextTaskDecorator.java` |

**common.config → infrastructure.config (2 files, joining existing infrastructure.config)**
| File | New Location |
|------|-------------|
| `common/config/CommonConfig.java` | `infrastructure/config/CommonConfig.java` |
| `common/config/LongFromStringDeserializer.java` | `infrastructure/config/LongFromStringDeserializer.java` |

**common.logging → infrastructure.logging (2 files)**
| File | New Location |
|------|-------------|
| `common/logging/InventoryCode.java` | `infrastructure/logging/InventoryCode.java` |
| `common/logging/LogKeys.java` | `infrastructure/logging/LogKeys.java` |

**Root-level common utilities → infrastructure.util (5 files — bundled with common.util wave)**
| File | New Location |
|------|-------------|
| `common/ClockProvider.java` | `infrastructure/util/ClockProvider.java` |
| `common/Constants.java` | `infrastructure/util/Constants.java` |
| `common/Predicate.java` | `infrastructure/util/Predicate.java` |
| `common/TimeGuru.java` | `infrastructure/util/TimeGuru.java` |
| `common/TransactionIdProvider.java` | `infrastructure/util/TransactionIdProvider.java` |

### CMN-03: Domain-Specific Enums and Consumer Types (5 files to move)

**common.Gender → platform.security.contract (root-level enum)**
| File | New Location | Reason |
|------|-------------|--------|
| `common/Gender.java` | `platform/security/contract/Gender.java` | All 13 prod callers are in `platform.security.*` |

**common.enums (2 files — zero external callers)**
| File | New Location | Reason |
|------|-------------|--------|
| `common/enums/Picker.java` | `infrastructure/util/Picker.java` | Generic utility interface, zero callers outside common; infrastructure.util is the best generic home |
| `common/enums/Unit.java` | `infrastructure/util/Unit.java` | Generic enum, zero callers outside common |

**common.consumer → platform.security.contract (2 files)**
| File | New Location | Reason |
|------|-------------|--------|
| `common/consumer/Consumer.java` | `platform/security/contract/Consumer.java` | 4 callers: Customer.java, UserRepository.java, CustomerService.java, UserService.java — all in platform.security |
| `common/consumer/Location.java` | `platform/security/contract/Location.java` | Companion interface to Consumer |

**common.dto → platform.security.contract (1 file)**
| File | New Location | Reason |
|------|-------------|--------|
| `common/dto/PhoneNumberDto.java` | `platform/security/contract/PhoneNumberDto.java` | Used only within platform.security domain |

### Test Common Files to Relocate (7 files)

| File | Current Location | New Location | Reason |
|------|-----------------|-------------|--------|
| `AdminLogin.java` | `test/common/AdminLogin.java` | `test/e2e/AdminLogin.java` | Used by e2e/ and platform admin tests |
| `HttpTestClient.java` | `test/common/HttpTestClient.java` | `test/e2e/HttpTestClient.java` | Used by SecurityFilterChainIT, SecurityIT |
| `TestClockProvider.java` | `test/common/TestClockProvider.java` | `test/infrastructure/util/TestClockProvider.java` | Wraps ClockProvider; mirrors prod location |
| `TransactionExceptionSimulator.java` | `test/common/TransactionExceptionSimulator.java` | `test/infrastructure/TransactionExceptionSimulator.java` | Test-only Spring component |
| `configtest/JacksonTest.java` | `test/common/configtest/JacksonTest.java` | `test/infrastructure/config/JacksonTest.java` | Tests CommonConfig (moved to infrastructure.config) |
| `util/BodySanitizerTest.java` | `test/common/util/BodySanitizerTest.java` | `test/infrastructure/util/BodySanitizerTest.java` | Tests BodySanitizer (moved to infrastructure.util) |
| `validation/CamMobileValidatorTest.java` | `test/common/validation/CamMobileValidatorTest.java` | `test/infrastructure/validation/CamMobileValidatorTest.java` | Tests CamMobileValidator (moved to infrastructure.validation) |
| `validation/InputValidatorTest.java` | `test/common/validation/InputValidatorTest.java` | `test/infrastructure/validation/InputValidatorTest.java` | Tests InputValidator (moved to infrastructure.validation) |

### External Production Caller Files (91 import lines across ~87 distinct files)
By frequency of imports:

**highest-volume — import common.payment.MobilePaymentProvider (25 prod files)**
All files in: `payment/core/`, `payment/disbursement/`, `payment/fraud/`, `payment/ledger/`, `payment/provider/mtn/`, `payment/provider/orange/`, `payment/reconciliation/`, `payment/webhook/`

**common.exception.ErrorCode (14 prod files)**
All in `payment.core`, `payment.disbursement`, `platform.security`

**common.exception.ApplicationException (9 prod files)**
All in `platform.security`, `payment.provider.mtn`

**common.ClockProvider (18 prod files)**
Spread across `infrastructure.persistence`, `platform.*`, security services, tenant services

**common.Gender (8 prod files)**
All in `platform.security.*`

**Full external caller list for import updates:**
- `infrastructure/config/AsyncConfig.java` → imports MdcDecorator, TenantContextTaskDecorator
- `infrastructure/persistence/AuditingDateTimeProvider.java` → imports ClockProvider
- `infrastructure/web/LoggingFilter.java` → imports Constants, Picker (via enums)
- `payment/core/contract/OrchestratorError.java` → imports ErrorCode
- `payment/core/repo/MsisdnPrefixRoute.java` → imports MobilePaymentProvider
- `payment/core/service/MsisdnPrefixRouteCache.java` → imports MobilePaymentProvider
- `payment/core/service/MsisdnRouter.java` → imports MobilePaymentProvider
- `payment/core/service/PaymentOrchestrator.java` → imports MobileMoneyPort, MobilePaymentProvider, PaymentCommand, ProviderResult, HttpClientException, JsonUtil
- `payment/disbursement/api/DisbursementResource.java` → imports ResourceNotFoundException
- `payment/disbursement/contract/DisbursementOrchestratorError.java` → imports ErrorCode
- `payment/disbursement/contract/event/InsufficientFundsAlertEvent.java` → imports MobilePaymentProvider
- `payment/disbursement/repo/Disbursement.java` → imports MobilePaymentProvider
- `payment/disbursement/service/DisbursementCallbackTransitionService.java` → imports MobilePaymentProvider, ProviderResult
- `payment/disbursement/service/DisbursementOrchestrator.java` → imports MobileMoneyPort, MobilePaymentProvider, PaymentCommand, ProviderResult, SubscriberStatus, HttpClientException, JsonUtil
- `payment/disbursement/service/DisbursementService.java` → imports MobilePaymentProvider
- `payment/disbursement/service/InsufficientFundsDetector.java` → imports ProviderResult
- `payment/fraud/service/FraudScoringService.java` → imports PaymentCommand
- `payment/ledger/repo/Transaction.java` → imports MobilePaymentProvider
- `payment/ledger/repo/TransactionRepository.java` → imports MobilePaymentProvider
- `payment/ledger/service/TransactionService.java` → imports MobilePaymentProvider
- `payment/provider/mtn/infrastructure/MtnMoMoClient.java` → imports AbstractClient, RestRequestInterceptor
- `payment/provider/mtn/service/MtnMoMoPort.java` → imports MobileMoneyPort, PaymentCommand, ProviderResult, SubscriberStatus
- `payment/provider/mtn/service/MtnStatusPollerJob.java` → imports MobilePaymentProvider
- `payment/provider/orange/infrastructure/OrangeMoneyClient.java` → imports AbstractClient, RestRequestInterceptor
- `payment/provider/orange/service/OrangeMoneyPort.java` → imports MobileMoneyPort, PaymentCommand, ProviderResult, SubscriberStatus
- `payment/provider/orange/service/OrangeStatusPollerJob.java` → imports MobilePaymentProvider
- `payment/reconciliation/port/MtnReportAdapter.java` → imports MobilePaymentProvider
- `payment/reconciliation/port/OrangeReportAdapter.java` → imports MobilePaymentProvider
- `payment/reconciliation/port/ProviderReportPort.java` → imports MobilePaymentProvider
- `payment/reconciliation/repo/ReconciliationDiscrepancy.java` → imports MobilePaymentProvider
- `payment/reconciliation/repo/ReconciliationReport.java` → imports MobilePaymentProvider
- `payment/reconciliation/repo/ReconciliationReportRepository.java` → imports MobilePaymentProvider
- `payment/reconciliation/service/ReconciliationProviderRunner.java` → imports MobilePaymentProvider
- `payment/reconciliation/service/ReconciliationService.java` → imports MobilePaymentProvider
- `payment/webhook/contract/WebhookReceivedEvent.java` → imports MobilePaymentProvider
- `payment/webhook/service/WebhookDoubleCheckHandler.java` → imports MobilePaymentProvider, ProviderResult
- `payment/webhook/service/WebhookTransitionService.java` → imports MobilePaymentProvider, ProviderResult
- `platform/admin/service/PlatformConfigService.java` → imports ResourceNotFoundException
- `platform/monitoring/TlsStartupAssertion.java` → imports AppSetupException
- `platform/notification/config/AsyncConfig.java` → imports MdcDecorator
- `platform/notification/infrastructure/listener/AccountChangeEmailListener.java` → imports ClockProvider
- `platform/notification/infrastructure/listener/DisbursementOpsAlertEmailListener.java` → imports ClockProvider
- `platform/notification/infrastructure/listener/PlatformConfigEmailListener.java` → imports ClockProvider
- `platform/notification/infrastructure/listener/TenantLifecycleEmailListener.java` → imports ClockProvider
- `platform/security/api/AccountManagementFacade.java` → imports ClockProvider, PhoneNumber, PhoneNumberUtil
- `platform/security/api/AccountResource.java` → imports Response, Success
- `platform/security/api/ApiAdvice.java` → imports ApplicationException, ResourceNotFoundException, ErrorDto, ErrorMsg
- `platform/security/api/ProfileResource.java` → imports Failure, Response, Success
- `platform/security/api/dto/ChangePhoneDto.java` → imports CamPhone
- `platform/security/api/dto/UpdateUserInfoDto.java` → imports Gender
- `platform/security/api/registration/EmailRegistrationStrategy.java` → imports ClockProvider
- `platform/security/audit/listener/AccountChangeEventListener.java` → imports ClockProvider
- `platform/security/audit/listener/SecurityAuditListener.java` → imports ClockProvider, ApplicationException
- `platform/security/contract/Principal.java` → imports Gender
- `platform/security/contract/UserDto.java` → imports Gender, CamPhone
- `platform/security/contract/event/SecurityAlertEvent.java` → imports ApplicationException
- `platform/security/contract/exception/AuthorizationException.java` → imports ApplicationException, ErrorCode
- `platform/security/contract/exception/EncryptionError.java` → imports ErrorCode
- `platform/security/contract/exception/EncryptionException.java` → imports ApplicationException, ErrorCode
- `platform/security/contract/exception/InvalidJWTDataException.java` → imports ErrorCode
- `platform/security/contract/exception/JWTExpiredException.java` → imports ErrorCode
- `platform/security/contract/exception/MissingAuthenticationException.java` → imports ErrorCode
- `platform/security/contract/exception/OperationNotAllowedException.java` → imports ErrorCode
- `platform/security/contract/exception/ProfileActionException.java` → imports ErrorCode
- `platform/security/contract/exception/SecError.java` → imports ErrorCode
- `platform/security/contract/exception/SecException.java` → imports ApplicationException, ErrorCode
- `platform/security/contract/exception/SecurityError.java` → imports ErrorCode
- `platform/security/contract/exception/UserDomainException.java` → imports ApplicationException, ErrorCode
- `platform/security/infrastructure/filter/SecondFactorLoginFilter.java` → imports Success
- `platform/security/infrastructure/jwt/ClaimsExtractorImpl.java` → imports Gender
- `platform/security/infrastructure/jwt/JwtConfiguration.java` → imports AppSetupException
- `platform/security/infrastructure/jwt/JwtManagerImpl.java` → imports ClockProvider, Gender
- `platform/security/infrastructure/jwt/JwtSecretService.java` → imports AppSetupException
- `platform/security/infrastructure/jwt/TokenCreatorImpl.java` → imports ClockProvider
- `platform/security/infrastructure/jwt/TokenValidatorImpl.java` → imports ClockProvider
- `platform/security/infrastructure/jwt/filter/JWTAuthenticationFilter.java` → imports ApplicationException, Success, InputValidator
- `platform/security/repo/Customer.java` → imports Gender, Consumer, PhoneNumber
- `platform/security/repo/User.java` → imports ClockProvider
- `platform/security/repo/UserRepository.java` → imports Consumer
- `platform/security/service/CustomerService.java` → imports Consumer
- `platform/security/service/LoginInfoService.java` → imports ClockProvider
- `platform/security/service/PasswordResetService.java` → imports ClockProvider, RandomUtil
- `platform/security/service/SecurityUtil.java` → imports Gender
- `platform/security/service/TwoFactorLoginService.java` → imports ClockProvider
- `platform/security/service/UserMapper.java` → imports Gender, PhoneNumber, PhoneNumberUtil
- `platform/security/service/UserProfileService.java` → imports Gender, PhoneNumber, PhoneNumberUtil
- `platform/security/service/UserRegistrationService.java` → imports RandomUtil
- `platform/security/service/UserService.java` → imports Consumer
- `platform/tenant/service/ApiKeyService.java` → imports ClockProvider
- `platform/tenant/service/TenantService.java` → imports ClockProvider

### FQN References in Code Bodies (must not be missed by import-only sweep)

| File | FQN Reference | Action |
|------|--------------|--------|
| `payment/provider/mtn/service/MtnMoMoPort.java` (lines 283, 340) | `com.softropic.payam.common.payment.MobilePaymentProvider.MTN` (in method bodies) | Rewrite to `payment.core.contract` FQN |
| `payment/provider/orange/service/OrangeMoneyPort.java` (lines 274, 343) | `com.softropic.payam.common.payment.MobilePaymentProvider.ORANGE` (in method bodies) | Rewrite to `payment.core.contract` FQN |
| `payment/provider/mtn/infrastructure/MtnMoMoClient.java` (line 171) | `com.softropic.payam.common.client.exception.HttpClientException` (in catch block) | Rewrite to `infrastructure.client.exception` FQN |
| `payment/disbursement/contract/DisbursementResponse.java` (lines 50, 78) | `com.softropic.payam.common.payment.MobilePaymentProvider` (in Javadoc `@link`) | Rewrite Javadoc FQN to `payment.core.contract` |
| `platform/admin/service/PlatformConfigServiceTest.java` (line 258) | `com.softropic.payam.common.exception.ResourceNotFoundException.class` (in assertion body) | Rewrite to `infrastructure.exception` FQN |

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Batch import rewriting | Custom parser | `sed` with per-suffix patterns | Proven in Phases 61–64; regex is sufficient for this codebase |
| Component scan registration | `@ComponentScan` additions | Nothing — leave as-is | `@SpringBootApplication` at root scans all `com.softropic.payam.*` sub-packages automatically |
| Spring @Configuration detection | Manual bean registration | Nothing — leave as-is | `CommonConfig` moves to `infrastructure.config` which is already within the component scan root |

**Key insight:** No Spring configuration changes are required. The `@SpringBootApplication` at `com.softropic.payam` auto-discovers all `@Component`, `@Configuration`, `@Service` etc. annotations in any depth of sub-package. The infrastructure sub-packages (`infrastructure.exception`, `infrastructure.util`, etc.) are not Spring-managed anyway — they are plain Java classes. `CommonConfig` (a `@Configuration`) moves to `infrastructure.config` which is already an existing Spring-discovered package.

## Runtime State Inventory

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — no database column, Redis key, or external datastore references a Java FQN from `common.*` | None |
| Live service config | None — no external service (n8n, Datadog, Tailscale, etc.) references `common.*` Java package paths | None |
| OS-registered state | None — Quartz job identity strings (`mtn-status-poller`, `orange-status-poller`) are plain string keys, not FQNs; unchanged | None |
| Secrets/env vars | None — no env var name references `common.*` package paths | None |
| Build artifacts | None — no compiled binaries or installed artifacts specific to `common.*` packages identified | None |
| YAML FQN references | None — grep of `src/main/resources` for `com.softropic.payam.common.` returned zero matches | None |

**Nothing found in runtime state categories** — verified by grep of YAML, properties, and resource files. This phase is purely code + file-system moves.

## Common Pitfalls

### Pitfall 1: FQN References in Code Bodies (Not Just Import Blocks)
**What goes wrong:** The import sweep updates `import` lines but misses FQN usages in method bodies, catch blocks, and Javadoc `@link` tags. Five specific locations identified above contain FQN references outside import blocks.
**Why it happens:** Some code in this codebase uses FQNs inside expressions. Phase 63-07 first discovered this pattern; Phase 64 confirmed it again.
**How to avoid:** After the import sweep sed passes, always run: `grep -rn "com\.softropic\.payam\.common\." src --include="*.java"` and fix all remaining hits.
**Warning signs:** `mvn test-compile` exits 0 but grep still shows common.* references — these are in Javadoc/comments (acceptable) vs code bodies (must fix before commit).

### Pitfall 2: Touching platform.security.common.* Files
**What goes wrong:** Confusion between `com.softropic.payam.common.*` (the package to remove) and `com.softropic.payam.platform.security.common.*` (a sub-package that happens to be named `common` under security). The security package's `common` sub-package contains `event/` and `util/` classes that are ALREADY correctly placed and must NOT be moved.
**Why it happens:** Both paths contain the word `common` but they are completely different packages.
**How to avoid:** Scope all sed patterns and `find` commands to explicitly target `*/com/softropic/payam/common/*` directory paths. Never use a broad `*/common/*` pattern that could match platform.security.common.

### Pitfall 3: Attempting to Move common.persistence
**What goes wrong:** Phase 61 already moved `common.persistence` to `infrastructure.persistence`. The directory `src/main/java/com/softropic/payam/common/` no longer contains a `persistence/` sub-directory. Any sed or `find` command targeting `common.persistence` will silently find nothing.
**Why it happens:** The requirements mention `common.persistence` in CMN-02, but it was resolved early.
**How to avoid:** Verify `ls src/main/java/com/softropic/payam/common/` before starting — `persistence` is absent. Do not include it in file move instructions.

### Pitfall 4: Consumer.java References platform.security.repo.Address
**What goes wrong:** `common.consumer.Consumer.java` has an import for `com.softropic.payam.platform.security.repo.Address`. When Consumer moves to `platform.security.contract`, this import becomes an intra-package import (from `platform.security.contract` to `platform.security.repo`). This is fine — both are within `platform.security.*`. But the import must remain intact.
**Why it happens:** Consumer was always tightly coupled to the security domain.
**How to avoid:** When moving Consumer.java, preserve all its existing imports. The only change is its `package` declaration and its new directory location.

### Pitfall 5: CommonConfig Moves Into Existing infrastructure.config
**What goes wrong:** `infrastructure.config` already contains `AsyncConfig`, `DataSourceConfig`, and `ObservabilityConfig`. `CommonConfig` and `LongFromStringDeserializer` join them. If the file is moved without updating the `package` declaration, Spring will not load `CommonConfig` as a `@Configuration` bean.
**Why it happens:** The package declaration in the file still reads `com.softropic.payam.common.config` after the file move.
**How to avoid:** Apply the two-pass sed to update the package declaration in `CommonConfig.java` and `LongFromStringDeserializer.java` before committing. The sole external caller (`SecurityIT.java` test) imports `CommonConfig` — that import must be updated too.

### Pitfall 6: TestClockProvider Wraps ClockProvider via Package-Private Method
**What goes wrong:** `TestClockProvider.java` calls `ClockProvider.setClock(clock)` — a package-private (`static` with package-private visibility) method. Once `ClockProvider` moves to `infrastructure.util` and `TestClockProvider` moves to `infrastructure.util` (same package), the package-private call continues to work. If placed in different packages, the call will fail to compile.
**Why it happens:** `ClockProvider.setClock()` is package-private — visible only to classes in the same package.
**How to avoid:** Place `TestClockProvider.java` in `src/test/java/com/softropic/payam/infrastructure/util/` — same package as `ClockProvider.java` in production. Do not place it in a different infrastructure sub-package.

### Pitfall 7: InventoryCode.java Imports ErrorCode from common.exception
**What goes wrong:** `common/logging/InventoryCode.java` imports `com.softropic.payam.common.exception.ErrorCode`. When both move to `infrastructure.*` but to different sub-packages (`infrastructure.logging` vs `infrastructure.exception`), the import must be updated.
**Why it happens:** The logging-to-exception dependency exists within the common package.
**How to avoid:** Update `InventoryCode.java`'s import to `infrastructure.exception.ErrorCode` when moving it. Process `infrastructure.exception` first in the wave, then `infrastructure.logging`.

### Pitfall 8: HttpTestClient.java Uses common.client.Client
**What goes wrong:** `test/common/HttpTestClient.java` imports `com.softropic.payam.common.client.Client`. When `Client` moves to `infrastructure.client` and `HttpTestClient` moves to `e2e/`, the import must be updated.
**Why it happens:** Test utility has a production library dependency.
**How to avoid:** When relocating `HttpTestClient`, update its import of `common.client.Client` to `infrastructure.client.Client`.

### Pitfall 9: Phase Commits Must Stay Atomic Per Wave
**What goes wrong:** Committing a subset of files mid-wave (e.g., source files but not test files) leaves the codebase in a non-compiling state where tests reference the old package paths.
**Why it happens:** Large waves with many files tempt incremental staging.
**How to avoid:** Stage and commit all file moves + all import updates in a single `git commit` per wave. Use `mvn test-compile -q` as a verification gate before committing, not after.

## Code Examples

### Sed Pattern for Package Declaration Rewrite (macOS two-pass)
```bash
# Moving common.payment files to payment.core.contract
# Pass 1: sub-package declarations
find src -name "*.java" -path "*/common/payment/*" | xargs sed -i '' \
  's/package com\.softropic\.payam\.common\.payment\./package com.softropic.payam.payment.core.contract./g'
# Pass 2: root package declaration
find src -name "*.java" -path "*/common/payment/*" | xargs sed -i '' \
  's/package com\.softropic\.payam\.common\.payment;/package com.softropic.payam.payment.core.contract;/g'
```

### Sed Pattern for Import Sweep (callers outside common)
```bash
# Single-pass import rewrite for common.payment callers
find src -name "*.java" | xargs grep -l "com\.softropic\.payam\.common\.payment\." | \
  xargs sed -i '' 's/import com\.softropic\.payam\.common\.payment\./import com.softropic.payam.payment.core.contract./g'

# FQN body sweep (catches references outside import blocks)
find src -name "*.java" | xargs grep -l "com\.softropic\.payam\.common\.payment\." | \
  xargs sed -i '' 's/com\.softropic\.payam\.common\.payment\./com.softropic.payam.payment.core.contract./g'
```

### Verification Gate After Each Wave
```bash
# After each wave, verify no common.* references remain in moved files
grep -rn "com\.softropic\.payam\.common\." src --include="*.java" | grep -v "^.*platform/security/common/"
# Should show ZERO results after final wave
```

### Final Verification (CMN-04 success criterion)
```bash
find src -name "*.java" | xargs grep "com.softropic.payam.common"
# Must return zero results
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Flat `common.*` package as catch-all | Classes in bounded-context packages | Phase 65 (this phase) | All callers update imports; no behavioral change |
| `common.persistence` | `infrastructure.persistence` | Phase 61 (done) | Already resolved — not in scope |

**Deprecated/outdated:**
- `common.*` as a package: Fully eliminated after Phase 65. The directory and all its sub-directories are deleted from the source tree.

## Open Questions

1. **Destination for common.refund (zero external callers)**
   - What we know: `common.refund` has no external callers. The requirement says "move to `payment.core`". The classes (`RefundPolicy`, `RefundType`, `ChargeType`) use JPA `@Embeddable` — their domain is payment/commerce, not infrastructure.
   - What's unclear: Whether `payment.core.contract` is the right sub-package vs. a dedicated `payment.core.refund`.
   - Recommendation: Use `payment.core.contract` (consistent with CMN-01's destination for `common.payment`). If they grow into a distinct concept, a future phase can sub-package them further. Zero callers means no compilation risk from this choice.

2. **Destination for `common.enums.Picker` and `common.enums.Unit`**
   - What we know: Both have zero external callers outside the `common` package itself. `Picker` is a generic enum utility interface; `Unit` is a measurement unit enum (KILOGRAM, GRAM, LITRE, etc.) — appears to be a legacy remnant.
   - What's unclear: Whether `Unit` belongs anywhere meaningful or should simply be deleted as dead code.
   - Recommendation: Move both to `infrastructure.util` (safe, no callers to break). If `Unit` is confirmed dead code, a separate tech-debt cleanup phase can remove it. Phase 65 only needs to empty `common` — deletion of unused classes is out of scope.

## Environment Availability

Step 2.6: SKIPPED — this phase is purely code/file-system changes with no external dependencies. All required tools (Maven, Java 17, macOS `sed`) are already in use from Phases 61–64.

## Validation Architecture

Nyquist validation key absent from `.planning/config.json` — treat as enabled.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test (via Maven Failsafe/Surefire) |
| Config file | `pom.xml` — Surefire for unit (`*Test.java`), Failsafe for integration (`*IT.java`) |
| Quick run command | `mvn test-compile -q` (compilation gate — catches import errors before full run) |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CMN-01 | payment.core.contract classes compile at new path | compile gate | `mvn test-compile -q` | N/A |
| CMN-01 | payment.core callers compile with new imports | compile gate | `mvn test-compile -q` | N/A |
| CMN-01 | Provider ports (MtnMoMoPort, OrangeMoneyPort) function correctly | integration | `mvn verify -Dit.test=MtnMoMoPortIT,OrangeMoneyPortIT` | ✅ |
| CMN-01 | Payment orchestration works end-to-end | e2e | `mvn verify -Dit.test=PaymentOrchestratorIT` | ✅ |
| CMN-02 | infrastructure.exception, message, util, client, validation classes compile | compile gate | `mvn test-compile -q` | N/A |
| CMN-02 | Spring context loads (CommonConfig, AsyncConfig recognized) | integration | `mvn verify` | N/A (implicit) |
| CMN-02 | Security filter chain works (ErrorCode, ApplicationException referenced) | integration | `mvn verify -Dit.test=SecurityFilterChainIT` | ✅ |
| CMN-03 | Gender, Consumer callers in platform.security compile | compile gate | `mvn test-compile -q` | N/A |
| CMN-03 | Security login/registration flow works end-to-end | integration | `mvn verify -Dit.test=SecurityIT` | ✅ |
| CMN-04 | No common.* references remain | grep | `find src -name "*.java" \| xargs grep "com.softropic.payam.common"` | N/A (grep) |
| CMN-04 | common directory absent | find | `find src -type d -name "common" -path "*/payam/common"` | N/A (find) |
| BUILD-01 | `mvn verify` passes green after phase commit | integration | `mvn verify` | N/A (gate) |
| BUILD-02 | No functional behavior changes | integration | `mvn verify` | N/A (gate) |
| BUILD-03 | Spring component-scan, security filter, Flyway verified functional | integration | `mvn verify` | N/A (gate) |

### Sampling Rate
- **Per wave compile check:** `mvn test-compile -q` — fast, catches import errors immediately
- **Phase gate:** `mvn verify` — full suite (474 unit + 301 integration tests) green before phase closure
- **Final CMN-04 check:** `find src -name "*.java" | xargs grep "com.softropic.payam.common"` returns zero results

### Wave 0 Gaps
None — existing test infrastructure covers all phase requirements. No new test files or framework config needed.

## Suggested Wave Decomposition

| Wave | Content | Files Moved | External Callers | Risk |
|------|---------|-------------|-----------------|------|
| Wave 1 | common.payment + common.refund → payment.core.contract | 9 source files | 44 prod + 40 test import lines; 4 FQN body refs | HIGH — most callers |
| Wave 2 | common.exception + common.message + common.config → infrastructure | 15 source files | 29 prod + 1 test exception callers; 9 prod message callers; 1 test config caller | MEDIUM |
| Wave 3 | common.validation + common.util + common.client + common.threadpool + common.logging → infrastructure; root utilities (ClockProvider, Constants, TransactionIdProvider, Predicate, TimeGuru) → infrastructure.util | 44 source files | 12 prod validation; 9 prod util; 6 prod client + 1 FQN catch; 3 prod threadpool; 18 prod ClockProvider; 0 logging | MEDIUM |
| Wave 4 | common.enums + common.consumer + common.dto + Gender → platform.security.contract / infrastructure.util; test common files; delete common directory | 8 source files + 8 test files | 4 prod consumer callers; 8 prod Gender callers | LOW — package deletion |

**Alternative:** Merge Waves 2 and 3 into one large infrastructure wave. This reduces the number of `mvn verify` runs but increases blast radius. Either approach is valid; the 4-wave split is safer.

## Sources

### Primary (HIGH confidence)
- Direct source tree inspection — all file paths, package declarations, and import statements verified by `find`/`grep` against the actual source tree at `/Users/mokwen/dev/gitrepos/bluegithub/payam/src`
- `.planning/STATE.md` — Phase 63 and 64 decisions (two-pass sed, FQN body scan, atomic commit requirement, macOS sed limitation)
- `.planning/phases/64-provider-infrastructure-encapsulation/64-RESEARCH.md` — established patterns directly applicable to this phase

### Secondary (MEDIUM confidence)
- `requirements/architecture.md` — confirms destination mapping (payment.core for payment types, infrastructure for persistence/threadpool/util, platform for security domain types)
- `.planning/REQUIREMENTS.md` — CMN-01 through CMN-04 requirement definitions

## Metadata

**Confidence breakdown:**
- File inventory (all 74 prod + 7 test common files): HIGH — verified by `find`/`wc -l` enumeration
- External caller list: HIGH — verified by `grep -rn` across all Java source files
- FQN body references: HIGH — identified by `grep -rn "com\.softropic\.payam\.common\." src --include="*.java" | grep -v "^.*import\|^.*package"`
- Destination mapping (payment vs infrastructure vs platform.security): HIGH — determined by caller analysis (who uses each class)
- Wave decomposition: MEDIUM — logical grouping; planner may adjust boundaries
- Architecture patterns: HIGH — same patterns proved correct in Phases 61–64

**Research date:** 2026-05-12
**Valid until:** 2026-06-12 (stable — no external dependencies, pure code reorganization)
