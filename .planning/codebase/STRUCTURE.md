# Codebase Structure

**Analysis Date:** 2026-03-06

## Directory Layout

```
payam/                                    # Project root
├── pom.xml                               # Maven build, dependency management, frontend plugin
├── mvnw / mvnw.cmd                       # Maven wrapper scripts
├── src/
│   ├── main/
│   │   ├── java/com/softropic/payam/
│   │   │   ├── PayamApplication.java     # Spring Boot entry point
│   │   │   ├── common/                   # Shared cross-cutting infrastructure
│   │   │   │   ├── client/               # AbstractClient + RestTemplate wrapper
│   │   │   │   ├── config/               # Jackson, common beans
│   │   │   │   ├── consumer/             # Consumer interface + Location model
│   │   │   │   ├── dto/                  # Shared DTOs (PhoneNumberDto)
│   │   │   │   ├── enums/                # Shared enums (Picker, Unit)
│   │   │   │   ├── exception/            # ApplicationException hierarchy, ErrorCode
│   │   │   │   ├── logging/              # Log keys, inventory codes
│   │   │   │   ├── message/              # Success, Failure, Response, ErrorDto
│   │   │   │   ├── payment/              # Payment enums/interfaces
│   │   │   │   ├── persistence/          # BaseEntity, AbstractAuditingEntity, EntityStatus
│   │   │   │   ├── refund/               # Refund enums
│   │   │   │   ├── threadpool/           # MDC propagation, executor wrappers
│   │   │   │   ├── util/                 # JsonUtil, TimeUtil, PhoneNumberUtil, etc.
│   │   │   │   └── validation/           # Custom constraint annotations + validators
│   │   │   ├── config/                   # Top-level DataSourceConfig
│   │   │   ├── email/                    # Email feature module
│   │   │   │   ├── api/                  # EmailTemplate enum, Envelope event, MailManager
│   │   │   │   ├── config/               # Email provider configuration beans
│   │   │   │   ├── persistence/          # Email tracking entities + repositories
│   │   │   │   └── service/              # MailService, MailSenderProvider, ResendEmailService
│   │   │   └── security/                 # Security feature module
│   │   │       ├── api/                  # REST controllers, AccountManagementFacade, DTOs
│   │   │       │   ├── dto/              # Request DTOs (AddressDto, ChangePasswordRequestDto, etc.)
│   │   │       │   ├── ratelimit/        # @RateLimited annotation + AOP aspect
│   │   │       │   └── registration/     # EmailRegistrationStrategy, SmsRegistrationStrategy
│   │   │       ├── audit/                # HTTP audit trail
│   │   │       │   ├── api/              # AuditTrail entity, AuditEvent, mapper
│   │   │       │   ├── filter/           # LoggingFilter
│   │   │       │   ├── listener/         # Audit event listeners
│   │   │       │   ├── repository/       # Audit persistence
│   │   │       │   └── shared/event/     # Shared audit event types
│   │   │       ├── common/               # Shared security contracts
│   │   │       │   ├── domain/           # LoginData interface, LoginInfo entity
│   │   │       │   ├── event/            # Internal security events
│   │   │       │   ├── service/          # LoginTokenManager interface
│   │   │       │   └── util/             # SecurityConstants
│   │   │       ├── config/               # SecurityConfiguration, AppEndpoints, CorsConfig, MvcConfig
│   │   │       ├── core/
│   │   │       │   ├── filter/           # SecondFactorLoginFilter, SecurityAdviceFilter, SessionRefreshFilter
│   │   │       │   └── mapper/           # UserMapper (MapStruct)
│   │   │       ├── domain/               # User, Customer, Authority, Address, PersistentToken, Challenge
│   │   │       ├── exception/            # UserNotFoundException, UserNotActivatedException, etc.
│   │   │       ├── exposed/              # Public API types for other modules to import
│   │   │       │   ├── event/            # SendMailEvent
│   │   │       │   ├── exception/        # AjaxLogoutSuccessHandler, AuthorizationException, SecException, etc.
│   │   │       │   └── util/             # SecurityUtil, ClientContextProvider, RequestMetadata, Cryptopher
│   │   │       ├── jwt/                  # JWT implementation
│   │   │       │   ├── api/              # JwtManagerImpl, TokenCreator, TokenValidator, ClaimsExtractor
│   │   │       │   │   ├── exception/    # JWT-specific exceptions
│   │   │       │   │   └── filter/       # JWTAuthenticationFilter, JWTAuthorizationFilter
│   │   │       │   └── JwtSecretProvider
│   │   │       ├── listener/             # Spring event listeners (auth success/failure, fraud, mail)
│   │   │       ├── manager/              # FraudAwareAuthenticationManager, LoginDecisionManager, TwoFactorLoginManager
│   │   │       ├── repository/           # UserRepository, AuthorityRepository, LoginInfoRepository
│   │   │       ├── secret/               # SecretService, SecKeyService, PermutedSecretKey
│   │   │       │   └── repository/       # SecKey, Secret JPA entities + repos
│   │   │       └── service/              # UserService, UserRegistrationService, UserProfileService, etc.
│   │   └── resources/
│   │       ├── application.yaml          # Main config (DB, mail, CORS, MoMo client, Flyway)
│   │       ├── application-dev.yaml      # Dev overrides
│   │       ├── config/
│   │       │   ├── logback-spring.xml    # Logging config
│   │       │   ├── blacklisted-names.json
│   │       │   └── whitelisted-emails.json
│   │       ├── i18n/                     # Message bundles (error-messages.properties, messages*.properties)
│   │       └── mails/                    # Thymeleaf HTML email templates
│   ├── test/
│   │   └── java/com/softropic/payam/
│   │       ├── common/                   # Shared test utilities (HttpTestClient, TestClockProvider)
│   │       ├── config/                   # Test Spring config, CustomPostgresContainer
│   │       ├── email/api/                # MailManagerIT, MailManagerResilienceTest
│   │       ├── security/                 # SecurityIT, SecurityFilterChainIT
│   │       │   ├── api/ratelimit/        # RateLimitingAspectIT
│   │       │   ├── jwt/api/              # JwtManagerImplTest, JWTAuthenticationFilterTest
│   │       │   ├── manager/              # LoginAttemptsServiceTest
│   │       │   ├── repo/                 # UserRepositoryIT
│   │       │   ├── secret/               # SecretServiceIT
│   │       │   └── service/              # PasswordResetIT, UserServiceIT
│   │       └── utils/                    # DbCleaner, sql query helpers (QueryRecorderListener, etc.)
│   └── frontend/                         # Quasar/Vue 3 SPA
│       ├── src/
│       │   ├── App.vue                   # Root component
│       │   ├── api/                      # API client modules
│       │   │   ├── index.js              # Barrel export
│       │   │   ├── auth.api.js           # Login, logout, OTP, checkAuth
│       │   │   ├── account.api.js        # Account management
│       │   │   ├── profile.api.js        # Profile management
│       │   │   └── session.api.js        # Session/token refresh
│       │   ├── assets/                   # Static assets
│       │   ├── boot/                     # Quasar boot files (run before app mount)
│       │   │   ├── axios.js              # Axios instance, interceptors, fingerprint
│       │   │   └── i18n.js               # Vue I18n setup
│       │   ├── components/               # Reusable Vue components
│       │   │   ├── common/               # Shared UI components
│       │   │   └── profile/              # Profile-specific components
│       │   ├── composables/              # Vue 3 composables
│       │   │   ├── useErrorHandler.js    # Global error handling composable
│       │   │   ├── useLoading.js         # Loading state composable
│       │   │   └── useSession.js         # Session lifecycle composable
│       │   ├── css/                      # Global styles
│       │   ├── i18n/                     # Frontend translations
│       │   │   ├── en-US/
│       │   │   └── fr-FR/
│       │   ├── layouts/
│       │   │   └── MainLayout.vue        # Root layout wrapping all pages
│       │   ├── pages/                    # Route-level components
│       │   │   ├── auth/                 # LoginPage, RegisterPage, OtpPage, ForgotPasswordPage, etc.
│       │   │   ├── DashboardPage.vue
│       │   │   ├── ProfilePage.vue
│       │   │   └── ErrorNotFound.vue
│       │   ├── plugins/
│       │   │   └── sessionManager.js     # Session monitoring, refresh, cleanup
│       │   ├── router/
│       │   │   ├── index.js              # Vue Router setup with auth guards
│       │   │   └── routes.js             # Route definitions
│       │   └── stores/
│       │       ├── index.js              # Pinia initialization
│       │       └── example-store.js      # Example store template
│       ├── public/                       # Static public assets (icons, favicon)
│       └── dist/spa/                     # Built SPA output (copied to target/classes/static)
├── docs/                                 # Project documentation
└── .planning/codebase/                   # GSD planning documents
```

## Directory Purposes

**`src/main/java/com/softropic/payam/common/`:**
- Purpose: Infrastructure shared by all feature modules; no business logic
- Contains: Base entity classes, cross-cutting utilities, shared exception hierarchy, outbound HTTP client base, thread-pool MDC propagation, validation annotations
- Key files: `common/persistence/BaseEntity.java`, `common/persistence/AbstractAuditingEntity.java`, `common/exception/ApplicationException.java`, `common/message/Success.java`, `common/client/AbstractClient.java`

**`src/main/java/com/softropic/payam/security/`:**
- Purpose: The dominant feature module; handles all authentication, authorization, user management, and audit
- Contains: Spring Security configuration, JWT filters, user domain model, repositories, services, API controllers, event listeners, rate limiting
- Key files: `security/config/SecurityConfiguration.java`, `security/config/AppEndpoints.java`, `security/domain/User.java`, `security/api/AccountResource.java`, `security/api/AccountManagementFacade.java`

**`src/main/java/com/softropic/payam/email/`:**
- Purpose: Self-contained email delivery module; receives `Envelope` events and delivers via SMTP
- Contains: Thymeleaf-based `MailService`, `MailSenderProvider` (round-robin multi-provider), email tracking persistence
- Key files: `email/api/MailManager.java`, `email/service/MailService.java`, `email/api/Envelope.java`, `email/api/EmailTemplate.java`

**`src/main/java/com/softropic/payam/security/exposed/`:**
- Purpose: Public API surface of the security module; types here are safe for other modules to import
- Contains: `Principal`, `UserDto`, `SecurityUtil`, `ClientContextProvider`, `RequestMetadata`, `Cryptopher`, exception handlers
- Key files: `security/exposed/Principal.java`, `security/exposed/util/SecurityUtil.java`, `security/exposed/util/ClientContextProvider.java`

**`src/main/resources/mails/`:**
- Purpose: Thymeleaf HTML email templates rendered by `MailService`
- Contains: `activation.html`, `passwordReset.html`, `creationDup.html`, `profileChange.html`, `sendOtp.html`

**`src/frontend/src/boot/`:**
- Purpose: Quasar boot plugins; run once before app is mounted
- Contains: `axios.js` (API client + browser fingerprint setup), `i18n.js` (locale setup)

**`src/frontend/src/plugins/`:**
- Purpose: Non-Vue services used across components
- Contains: `sessionManager.js` (idle detection, session countdown, refresh scheduling)

**`src/test/java/com/softropic/payam/utils/sql/`:**
- Purpose: Custom SQL assertion helpers for integration tests; intercept and record actual SQL statements
- Contains: `QueryRecorderListener`, `EntityFetchAsserter`, `SqlQuery`, `SelectQuery`, `InsertQuery`, `DeleteQuery`, `UpdateQuery`

## Key File Locations

**Entry Points:**
- `src/main/java/com/softropic/payam/PayamApplication.java`: Spring Boot main class
- `src/frontend/src/App.vue`: Vue SPA root component
- `src/frontend/src/boot/axios.js`: Axios singleton and interceptors

**Configuration:**
- `src/main/resources/application.yaml`: All environment config (DB, mail, CORS, MoMo, Flyway, Actuator)
- `src/main/resources/application-dev.yaml`: Dev-specific overrides
- `src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java`: Spring Security filter chain
- `src/main/java/com/softropic/payam/security/config/AppEndpoints.java`: All endpoint URL constants and authority mappings
- `src/main/java/com/softropic/payam/security/config/CorsConfig.java`: CORS configuration bean
- `src/main/resources/config/logback-spring.xml`: Logback configuration

**Core Logic:**
- `src/main/java/com/softropic/payam/security/api/AccountManagementFacade.java`: Account operation orchestrator
- `src/main/java/com/softropic/payam/security/service/UserService.java`: User query operations
- `src/main/java/com/softropic/payam/security/service/UserRegistrationService.java`: User creation
- `src/main/java/com/softropic/payam/security/service/PasswordResetService.java`: Password reset flow
- `src/main/java/com/softropic/payam/security/common/service/LoginTokenManager.java`: JWT management interface
- `src/main/java/com/softropic/payam/security/jwt/api/filter/JWTAuthenticationFilter.java`: Login endpoint filter
- `src/main/java/com/softropic/payam/security/jwt/api/filter/JWTAuthorizationFilter.java`: Per-request auth filter
- `src/main/java/com/softropic/payam/email/service/MailService.java`: Email delivery

**Routing:**
- `src/frontend/src/router/routes.js`: All SPA routes with `requiresAuth` / `requiresGuest` meta
- `src/frontend/src/router/index.js`: Router instance with navigation guards

**API Clients (Frontend):**
- `src/frontend/src/api/auth.api.js`: Login, logout, OTP
- `src/frontend/src/api/account.api.js`: Account management
- `src/frontend/src/api/profile.api.js`: Profile management
- `src/frontend/src/api/session.api.js`: Token refresh

**Testing:**
- `src/test/java/com/softropic/payam/config/CustomPostgresContainer.java`: Testcontainers PostgreSQL setup
- `src/test/java/com/softropic/payam/config/TestConfig.java`: Test Spring context
- `src/test/java/com/softropic/payam/utils/DbCleaner.java`: Between-test DB cleanup

## Naming Conventions

**Files (Java):**
- Controllers: `*Resource.java` (e.g., `AccountResource`, `ProfileResource`)
- Facades: `*Facade.java` (e.g., `AccountManagementFacade`)
- Services: `*Service.java`
- Repositories: `*Repository.java`
- Entities / Domain: PascalCase noun (e.g., `User`, `Authority`, `PersistentToken`)
- DTOs: `*Dto.java` or record types (e.g., `UserDto`, `KeyAndPasswordDto`)
- Mappers: `*Mapper.java` (MapStruct)
- Filters: `*Filter.java`
- Listeners: `*Listener.java`
- Managers: `*Manager.java` (complex orchestration beyond a single service)
- Config classes: `*Config.java` or `*Configuration.java`
- Exception classes: Named for the condition (e.g., `UserNotFoundException`, `JWTExpiredException`)
- Integration tests: `*IT.java`
- Unit tests: `*Test.java`

**Files (Frontend):**
- Pages: `*Page.vue` (e.g., `LoginPage.vue`, `DashboardPage.vue`)
- Layouts: `*Layout.vue`
- API modules: `*.api.js` (e.g., `auth.api.js`)
- Composables: `use*.js` (e.g., `useSession.js`, `useLoading.js`)

**Directories (Java):**
- Feature modules at top level: `security/`, `email/`
- Sub-packages by role: `api/`, `service/`, `domain/`, `repository/`, `config/`, `exception/`
- Public cross-module types: `exposed/` sub-package within a module

**Directories (Frontend):**
- Organized by responsibility: `pages/`, `components/`, `composables/`, `api/`, `stores/`, `boot/`, `plugins/`

## Where to Add New Code

**New REST Endpoint:**
- Controller: `src/main/java/com/softropic/payam/security/api/` (or create a new top-level module directory alongside `security/` and `email/`)
- Register public paths in: `src/main/java/com/softropic/payam/security/config/AppEndpoints.java`
- Integration test: `src/test/java/com/softropic/payam/security/`

**New Service:**
- Implementation: `src/main/java/com/softropic/payam/security/service/` (for user-related) or new module `service/` directory
- Unit test: mirror package path in `src/test/java/`

**New JPA Entity:**
- Domain class: extend `BaseEntity` or `AbstractAuditingEntity` from `src/main/java/com/softropic/payam/common/persistence/`
- Place in: `src/main/java/com/softropic/payam/[module]/domain/`
- Repository: `src/main/java/com/softropic/payam/[module]/repository/`
- Schema: add Flyway migration in `src/main/resources/db/migration/` (directory not yet created; follow `V{version}__{description}.sql` naming)

**New Email Template:**
- Add enum value to: `src/main/java/com/softropic/payam/email/api/EmailTemplate.java`
- Add Thymeleaf template to: `src/main/resources/mails/` (filename = enum name in lowerCamel, e.g., `passwordReset.html`)
- Add subject i18n key to: `src/main/resources/i18n/messages.properties` and locale variants

**New Validation Constraint:**
- Annotation: `src/main/java/com/softropic/payam/common/validation/`
- Validator class: same package, named `[AnnotationName]Validator.java`

**New Frontend Page:**
- Component: `src/frontend/src/pages/[name]Page.vue`
- Register route: `src/frontend/src/router/routes.js`
- Add `meta: { requiresAuth: true }` or `meta: { requiresGuest: true }` as appropriate

**New Frontend API Module:**
- File: `src/frontend/src/api/[domain].api.js`
- Export from barrel: `src/frontend/src/api/index.js`

**New Composable:**
- File: `src/frontend/src/composables/use[Name].js`

**New Pinia Store:**
- File: `src/frontend/src/stores/[name].js`

**Shared Utilities:**
- Java utilities: `src/main/java/com/softropic/payam/common/util/`
- Java constants: `src/main/java/com/softropic/payam/security/common/util/SecurityConstants.java` (for security constants) or `src/main/java/com/softropic/payam/common/Constants.java` (for app-wide constants)

## Special Directories

**`src/frontend/dist/spa/`:**
- Purpose: Quasar build output; copied to `target/classes/static/` by Maven during build
- Generated: Yes
- Committed: No

**`src/frontend/node/`:**
- Purpose: Node.js runtime installed by `frontend-maven-plugin`
- Generated: Yes
- Committed: No

**`src/frontend/node_modules/`:**
- Purpose: npm dependencies
- Generated: Yes
- Committed: No

**`target/`:**
- Purpose: Maven build artifacts including the packaged JAR
- Generated: Yes
- Committed: No

**`.planning/codebase/`:**
- Purpose: GSD architecture and convention documentation
- Generated: No
- Committed: Yes

---

*Structure analysis: 2026-03-06*
