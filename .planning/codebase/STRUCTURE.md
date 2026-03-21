# Codebase Structure

**Analysis Date:** 2026-03-21

## Directory Layout

```
payam/                                   # Project root
├── src/
│   ├── main/
│   │   ├── java/com/softropic/payam/    # Java backend source root
│   │   │   ├── PayamApplication.java    # Spring Boot entry point
│   │   │   ├── common/                  # Shared cross-cutting utilities
│   │   │   ├── config/                  # Top-level Spring config beans
│   │   │   ├── security/                # Security domain module
│   │   │   └── email/                   # Email domain module
│   │   └── resources/
│   │       ├── application.yaml         # Primary Spring config
│   │       ├── application-dev.yaml     # Dev profile overrides
│   │       ├── config/                  # Runtime config data (blacklists, etc.)
│   │       ├── db/migration/            # Flyway migration scripts
│   │       ├── i18n/                    # Backend message bundles (en, fr)
│   │       ├── mails/                   # Thymeleaf HTML email templates
│   │       └── static/                  # Static files served by Spring
│   ├── test/
│   │   ├── java/com/softropic/payam/    # Java test source root
│   │   │   ├── security/                # Tests mirroring security module
│   │   │   ├── email/                   # Tests mirroring email module
│   │   │   ├── common/                  # Tests for common utilities
│   │   │   ├── config/                  # Test configuration classes
│   │   │   └── utils/                   # Test utility helpers (SQL matchers)
│   │   └── resources/sql/               # SQL fixtures for integration tests
│   └── frontend/                        # Vue/Quasar SPA
│       ├── src/
│       │   ├── App.vue                  # SPA root component
│       │   ├── api/                     # Axios API client modules
│       │   ├── boot/                    # Quasar boot files (axios, i18n)
│       │   ├── components/              # Reusable UI components
│       │   │   ├── common/              # App-wide shared components
│       │   │   └── profile/             # Profile-specific dialog components
│       │   ├── composables/             # Vue composables (useSession, etc.)
│       │   ├── css/                     # Global styles
│       │   ├── i18n/                    # Frontend translations (en-US, fr-FR)
│       │   ├── layouts/                 # Quasar layout components
│       │   ├── pages/                   # Route-level page components
│       │   │   └── auth/                # Auth flow pages
│       │   ├── plugins/                 # Non-Quasar plugins (sessionManager)
│       │   ├── router/                  # Vue Router config and routes
│       │   ├── stores/                  # Pinia store setup
│       │   └── utils/                   # Frontend utility functions
│       ├── public/                      # Static assets (icons, etc.)
│       └── dist/spa/                    # Built SPA output (generated)
├── docs/                                # Architecture and integration docs
├── pom.xml                              # Maven build descriptor
├── docker-compose-lgtm.yaml             # LGTM observability stack
├── prometheus.yml                       # Prometheus scrape config
├── grafana-datasources.yml              # Grafana datasource config
└── .planning/codebase/                  # GSD planning documents
```

## Directory Purposes

### Backend: `src/main/java/com/softropic/payam/`

**`common/`:**
- Purpose: Cross-cutting utilities and shared contracts used by all domain modules
- Key sub-packages:
  - `client/` — base HTTP client abstractions (`AbstractClient.java`, `Client.java`)
  - `config/` — shared Spring config (`CommonConfig.java`)
  - `consumer/` — base consumer abstraction (`Consumer.java`, `Location.java`)
  - `dto/` — shared response wrappers (`Response.java`, `Success.java`, `Failure.java`, `ErrorMsg.java`)
  - `enums/` — global enums (`Gender.java`)
  - `exception/` — base exceptions (`ApplicationException.java`, `ErrorCode.java`, `ResourceNotFoundException.java`)
  - `logging/` — structured log key constants (`LogKeys.java`, `InventoryCode.java`)
  - `message/` — messaging contracts (`ErrorDto.java`, `ErrorMsg.java`)
  - `payment/` — payment type enums (`MobilePaymentProvider.java`, `PaymentMethod.java`)
  - `persistence/` — JPA base entities (`BaseEntity.java`, `AbstractAuditingEntity.java`, `EntityStatus.java`)
  - `refund/` — refund type enums (`RefundPolicy.java`, `RefundType.java`)
  - `threadpool/` — MDC-aware thread pool decorators (`MdcDecorator.java`, `MdcWrapper.java`)
  - `util/` — utility classes (`RandomUtil.java`, `TimeUtil.java`, `JsonUtil.java`, `PhoneNumberUtil.java`)
  - `validation/` — custom JSR-380 validators (`CamPhone.java`, `Name.java`, `LangIso2.java`, etc.)

**`security/`:**
- Purpose: Full authentication and user management domain module
- Structured as a vertical slice with its own api/contract/service/repo/infrastructure/config layers
- Key sub-packages:
  - `api/` — REST controllers and facades (`AccountResource.java`, `ProfileResource.java`, `AdminLoginResource.java`, `AccountManagementFacade.java`, `ApiAdvice.java`)
  - `api/dto/` — request DTOs specific to the security API
  - `api/registration/` — registration strategy pattern (`EmailRegistrationStrategy.java`, `SmsRegistrationStrategy.java`)
  - `api/ratelimit/` — rate limiting annotations/interceptors
  - `audit/` — audit trail sub-module with its own api/filter/listener/repository/service layers
  - `common/` — intra-security shared code (`event/` domain events, `service/` shared services, `util/`)
  - `config/` — security-specific Spring beans (`SecurityConfiguration.java`, `CorsConfig.java`, `AppEndpoints.java`)
  - `contract/` — interfaces/contracts (`event/`, `exception/`, `util/`)
  - `infrastructure/` — technical implementations (`jwt/` token handling, `filter/` servlet filters, `listener/` Spring event listeners, `audit/`)
  - `repo/` — JPA entities and repositories (`User.java`, `LoginInfo.java`, `Authority.java`, `SecKey.java`, `PersistentToken.java`, etc.)
  - `service/` — business logic (`UserService.java`, `LoginAttemptsService.java`, `PasswordResetService.java`, `TwoFactorLoginService.java`, etc.)

**`email/`:**
- Purpose: Email delivery domain module
- Key sub-packages:
  - `config/` — `EmailProperties.java` Spring config
  - `contract/` — interfaces (`MailManager.java`, `MailService.java`, `SenderProvider.java`, `Envelope.java`, `Recipient.java`)
  - `infrastructure/listener/` — Spring event listeners for email triggers (`AccountChangeEmailListener.java`)
  - `repo/` — `EnvelopeEntity.java`, `RecipientEntity.java`, `EnvelopeEntityRepository.java`
  - `service/` — implementations (`ResendEmailService.java`, `EnvelopeMapper.java`)

**`config/`:**
- Purpose: Top-level application config beans (`DataSourceConfig.java`, `ObservabilityConfig.java`)

### Resources: `src/main/resources/`

**`db/migration/`:**
- Purpose: Flyway versioned SQL migration scripts
- Naming: standard Flyway convention (`V{version}__{description}.sql`)

**`mails/`:**
- Purpose: HTML email templates (Thymeleaf)
- Files: `activation.html`, `passwordReset.html`, `profileChange.html`, `sendOtp.html`, `creationDup.html`

**`i18n/`:**
- Purpose: Backend validation and error message bundles
- Files: `messages.properties`, `messages_en.properties`, `messages_fr.properties`, `error-messages.properties`

**`config/`:**
- Purpose: Runtime configuration data files (not Spring YAML)
- Files: `blacklisted-names.json`, `whitelisted-emails.json`, `putInDatabase-blacklistEmail.txt`, `logback-spring.xml`

### Tests: `src/test/java/com/softropic/payam/`

**Mirror structure:** Test packages mirror source packages (e.g., `security/service/` → `security/service/`).

**`utils/sql/matcher/`:**
- Purpose: Custom Hamcrest/AssertJ SQL matchers for database assertions

**`src/test/resources/sql/`:**
- Purpose: SQL fixtures loaded during integration tests
- Key files: `initTestData.sql`, `userData.sql`, `secData.sql`, `account.sql`, `authorityData.sql`, `cleanup.sql`, `createSchema.sql`, `dropAllTables.sql`

### Frontend: `src/frontend/src/`

**`api/`:**
- Purpose: Typed Axios API client modules, one file per backend resource
- Files: `auth.api.js`, `account.api.js`, `session.api.js`, `profile.api.js`
- Re-exported via `api/index.js` barrel

**`boot/`:**
- Purpose: Quasar boot files executed at app startup
- Files: `axios.js` (configures Axios instance, fingerprint cookie, session expiry handling), `i18n.js` (locale setup)

**`components/common/`:**
- Purpose: App-wide reusable UI components
- Files: `GlobalLoadingBar.vue`, `SessionWarningDialog.vue`

**`components/profile/`:**
- Purpose: Profile management dialog components
- Files: `UpdateEmailDialog.vue`, `UpdatePasswordDialog.vue`, `UpdatePhoneDialog.vue`, `UpdateInfoDialog.vue`, `UpdateAddressDialog.vue`, `Toggle2faDialog.vue`

**`composables/`:**
- Purpose: Vue Composition API logic extracted from components
- Files: `useSession.js`, `useErrorHandler.js`, `useLoading.js`

**`layouts/`:**
- Purpose: Quasar layout wrappers (shell around pages)
- Files: `MainLayout.vue` (single layout used by all routes)

**`pages/`:**
- Purpose: Route-level page components, one per route
- Files: `DashboardPage.vue`, `ProfilePage.vue`, `IndexPage.vue`, `ErrorNotFound.vue`
- Sub-directory `auth/`: `LoginPage.vue`, `RegisterPage.vue`, `OtpPage.vue`, `ForgotPasswordPage.vue`, `ResetPasswordPage.vue`, `ActivatePage.vue`

**`plugins/`:**
- Purpose: Plain JS plugins (not Quasar boot)
- Files: `sessionManager.js` (session inactivity tracking, refresh logic)

**`router/`:**
- Purpose: Vue Router configuration
- Files: `routes.js` (all route definitions), `index.js` (router instance)

**`stores/`:**
- Purpose: Pinia store setup; individual store files added here
- Files: `index.js` (Pinia instance), `example-store.js`

**`i18n/`:**
- Purpose: Frontend translation files
- Sub-directories: `en-US/`, `fr-FR/`
- Entry: `i18n/index.js`

## Key File Locations

**Backend Entry Point:**
- `src/main/java/com/softropic/payam/PayamApplication.java`: Spring Boot main class

**Backend Configuration:**
- `src/main/resources/application.yaml`: Primary config (port, JPA, Flyway, etc.)
- `src/main/resources/application-dev.yaml`: Dev profile overrides
- `src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java`: Spring Security filter chain
- `src/main/java/com/softropic/payam/security/config/AppEndpoints.java`: Endpoint URL constants
- `src/main/java/com/softropic/payam/config/DataSourceConfig.java`: DataSource beans
- `src/main/java/com/softropic/payam/config/ObservabilityConfig.java`: Metrics/tracing beans

**REST Controllers:**
- `src/main/java/com/softropic/payam/security/api/AccountResource.java`
- `src/main/java/com/softropic/payam/security/api/ProfileResource.java`
- `src/main/java/com/softropic/payam/security/api/AdminLoginResource.java`
- `src/main/java/com/softropic/payam/security/api/ApiAdvice.java`: Global `@ControllerAdvice`

**Core Domain Services:**
- `src/main/java/com/softropic/payam/security/service/UserService.java`
- `src/main/java/com/softropic/payam/security/service/LoginAttemptsService.java`
- `src/main/java/com/softropic/payam/security/service/PasswordResetService.java`
- `src/main/java/com/softropic/payam/security/service/TwoFactorLoginService.java`
- `src/main/java/com/softropic/payam/security/service/UserRegistrationService.java`
- `src/main/java/com/softropic/payam/security/service/UserProfileService.java`
- `src/main/java/com/softropic/payam/email/service/ResendEmailService.java`

**JPA Entities:**
- `src/main/java/com/softropic/payam/security/repo/User.java`
- `src/main/java/com/softropic/payam/security/repo/LoginInfo.java`
- `src/main/java/com/softropic/payam/security/repo/Authority.java`
- `src/main/java/com/softropic/payam/security/repo/SecKey.java`
- `src/main/java/com/softropic/payam/security/repo/PersistentToken.java`
- `src/main/java/com/softropic/payam/email/repo/EnvelopeEntity.java`
- `src/main/java/com/softropic/payam/common/persistence/BaseEntity.java`: Base entity all entities extend

**Database Migrations:**
- `src/main/resources/db/migration/`: All Flyway scripts

**Frontend Entry:**
- `src/frontend/src/App.vue`: Root Vue component
- `src/frontend/src/router/routes.js`: All route definitions
- `src/frontend/src/boot/axios.js`: Axios instance and interceptors

**Test Infrastructure:**
- `src/test/java/com/softropic/payam/config/`: Spring test config
- `src/test/java/com/softropic/payam/utils/`: Shared test utilities (`HttpTestClient.java`, `TestClockProvider.java`)
- `src/test/resources/sql/`: SQL fixtures loaded per test

## Naming Conventions

### Backend (Java)

**Files:**
- Entities: `{Noun}.java` e.g., `User.java`, `LoginInfo.java`, `EnvelopeEntity.java`
- Repositories: `{Noun}Repository.java` e.g., `UserRepository.java`, `LoginInfoRepository.java`
- Services: `{Noun}Service.java` e.g., `UserService.java`, `LoginAttemptsService.java`
- Controllers: `{Noun}Resource.java` e.g., `AccountResource.java`, `ProfileResource.java`
- DTOs: `{Noun}Dto.java` or `{Noun}DTO.java` e.g., `UserDto.java`, `AddressDto.java`
- Configs: `{Noun}Config.java` or `{Noun}Configuration.java`
- Filters: `{Noun}Filter.java` e.g., `SessionRefreshFilter.java`, `LoggingFilter.java`
- Listeners: `{Noun}Listener.java` e.g., `AuthenticationSuccessListener.java`
- Exceptions: `{Noun}Exception.java` e.g., `ApplicationException.java`, `ConsumerNotFoundException.java`
- Interfaces (contracts): plain noun or `{Noun}Manager.java` e.g., `MailManager.java`, `Consumer.java`
- Test classes: `{Noun}Test.java` (unit), `{Noun}IT.java` (integration)

**Packages:**
- Domain modules at `com.softropic.payam.{domain}` e.g., `security`, `email`
- Internal layers: `api`, `contract`, `service`, `repo`, `infrastructure`, `config`, `common`

### Frontend (Vue/JS)

**Files:**
- Page components: `{Name}Page.vue` e.g., `LoginPage.vue`, `DashboardPage.vue`
- Layout components: `{Name}Layout.vue` e.g., `MainLayout.vue`
- Dialog/UI components: `{Name}Dialog.vue` or `{Name}Bar.vue` e.g., `UpdateEmailDialog.vue`
- API modules: `{domain}.api.js` e.g., `auth.api.js`, `account.api.js`
- Composables: `use{Name}.js` e.g., `useSession.js`, `useErrorHandler.js`
- Plugins: camelCase noun e.g., `sessionManager.js`
- Stores: camelCase noun e.g., `example-store.js`

## Where to Add New Code

### New Backend Domain Module (e.g., `payments`)

1. Create package `src/main/java/com/softropic/payam/payments/`
2. Mirror the vertical slice structure from `security/` or `email/`:
   - `payments/api/` — REST controller (`PaymentsResource.java`)
   - `payments/contract/` — interfaces and event types
   - `payments/service/` — business logic services
   - `payments/repo/` — JPA entities and Spring Data repositories
   - `payments/infrastructure/` — adapters, filters, listeners
   - `payments/config/` — Spring beans specific to this module
3. Add Flyway migration: `src/main/resources/db/migration/V{next}__{description}.sql`
4. Add email templates if needed: `src/main/resources/mails/{template}.html`
5. Add i18n messages: `src/main/resources/i18n/messages.properties` and locale variants

### New Backend Service in Existing Module

1. Add service class to `src/main/java/com/softropic/payam/{module}/service/`
2. Add interface/contract to `src/main/java/com/softropic/payam/{module}/contract/` if publicly consumed
3. Add corresponding integration test to `src/test/java/com/softropic/payam/{module}/service/{Name}IT.java`

### New REST Endpoint in Existing Module

1. Add or update controller in `src/main/java/com/softropic/payam/{module}/api/{Name}Resource.java`
2. Add request/response DTOs to `src/main/java/com/softropic/payam/{module}/api/dto/`
3. Register endpoint URL constant in `src/main/java/com/softropic/payam/security/config/AppEndpoints.java` if security access rules need updating
4. Add integration test to `src/test/java/com/softropic/payam/{module}/api/`

### New JPA Entity

1. Add entity to `src/main/java/com/softropic/payam/{module}/repo/`
2. Extend `src/main/java/com/softropic/payam/common/persistence/BaseEntity.java`
3. Add Spring Data repository in the same `repo/` package
4. Add Flyway migration in `src/main/resources/db/migration/`

### New Custom Validator

1. Add annotation interface and validator pair to `src/main/java/com/softropic/payam/common/validation/`
2. Follow the existing `CamPhone.java` / `CamPhoneValidator.java` paired file pattern

### New Frontend Page

1. Add page component to `src/frontend/src/pages/{Name}Page.vue`
   - Auth pages go in `src/frontend/src/pages/auth/`
2. Register route in `src/frontend/src/router/routes.js`
   - Add `meta: { requiresAuth: true }` for protected pages
   - Add `meta: { requiresGuest: true }` for auth-only pages

### New Frontend API Client

1. Add module `src/frontend/src/api/{domain}.api.js`
2. Export the named API object from `src/frontend/src/api/index.js`

### New Frontend Composable

1. Add file `src/frontend/src/composables/use{Name}.js`
2. Export a named function starting with `use` (e.g., `export function useMyFeature()`)

### New Frontend Component

- App-wide reusable: `src/frontend/src/components/common/`
- Domain-specific: `src/frontend/src/components/{domain}/`
- Dialog components follow `{Action}{Noun}Dialog.vue` naming

### New Pinia Store

1. Add store file to `src/frontend/src/stores/`
2. Register it via Pinia in `src/frontend/src/stores/index.js` if needed globally

## Special Directories

**`src/frontend/dist/spa/`:**
- Purpose: Built SPA output served by Spring Boot from `static/`
- Generated: Yes
- Committed: Yes (built output is committed, served as static content by the backend)

**`src/frontend/.quasar/`:**
- Purpose: Quasar CLI generated internals
- Generated: Yes
- Committed: No (excluded via .gitignore)

**`src/frontend/node_modules/`:**
- Purpose: Frontend npm dependencies
- Generated: Yes
- Committed: No

**`.planning/codebase/`:**
- Purpose: GSD architecture and planning documents
- Generated: Yes (by GSD tooling)
- Committed: Yes

**`docs/`:**
- Purpose: Human-authored architecture and integration documentation
- Key files: `security-api-endpoints.md`, `frontend-integration-guide.md`, `frontend-implementation-spec.md`, `session-refresh-mechanism.md`, `owasp-violations.md`

---

*Structure analysis: 2026-03-21*
