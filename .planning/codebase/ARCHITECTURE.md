# Architecture

**Analysis Date:** 2026-03-21

## Pattern Overview

**Overall:** Layered monolith with domain-module packaging

**Key Characteristics:**
- Single Spring Boot application combining a Java backend and a Quasar/Vue SPA frontend (served as static resources from the same process)
- Backend is organized into vertical domain modules (`security`, `email`) each mirroring the same horizontal layer pattern (`api`, `service`, `repo`, `contract`, `infrastructure`, `config`, `common`)
- Cross-cutting utilities live in the root `common` package and are importable by all modules
- Dependency flow is strictly unidirectional: `api → service → repo`, with `contract` as a zero-dependency shared language accessible from every layer
- `config` is the composition root and is the only location permitted to import across multiple layers simultaneously

## Layers

**`contract` (shared language):**
- Purpose: Passive type definitions — DTOs, enums, events, exceptions, value objects, `@ConfigurationProperties` POJOs. No Spring bean stereotypes.
- Location: `src/main/java/com/softropic/payam/security/contract/`
- Contains: `Principal`, `UserDto`, `LoginIdType`, `ChangePasswordDto`, `PermutedSecretKey`, `LoginData`, `SecurityProperties`, `SecurityError`, `AccountChangeEvent`, sub-packages `exception/`, `event/`, `util/`
- Depends on: Nothing within the module (Java stdlib and third-party libraries only)
- Used by: All other layers

**`api` (entry points):**
- Purpose: REST controllers and facades that translate between HTTP and the service layer.
- Location: `src/main/java/com/softropic/payam/security/api/`
- Contains: `AccountResource`, `ProfileResource`, `AdminLoginResource`, `AccountManagementFacade`, `ApiAdvice` (`@RestControllerAdvice`), sub-packages `dto/`, `ratelimit/`, `registration/`
- Depends on: `service`, `contract`
- Used by: HTTP request pipeline only

**`service` (business logic):**
- Purpose: Domain use-case orchestration; owns business rules, reads/writes via `repo`, publishes events.
- Location: `src/main/java/com/softropic/payam/security/service/`
- Contains: `UserService`, `UserRegistrationService`, `LoginInfoService`, `TwoFactorLoginService`, `PasswordResetService`, `SecretService`, `LoginAttemptsService`, `RateLimitingService`, `UserProfileService`, `UserAdminService`, `CustomerService`, `SecurityUtil`, `UserMapper`, interfaces `LoginTokenManager`, `LoginDecisionManager`, `LoginAttemptConsumer`
- Depends on: `repo`, `contract`, `common`
- Used by: `api`, `infrastructure`

**`infrastructure` (technical implementations):**
- Purpose: Implements service interfaces and handles technical concerns (JWT, filters, listeners, audit hooks).
- Location: `src/main/java/com/softropic/payam/security/infrastructure/`
- Contains: `JwtManagerImpl`, `JWTAuthenticationFilter`, `JWTAuthorizationFilter`, `SecondFactorLoginFilter`, `SecurityAdviceFilter`, `SessionRefreshFilter`, `SpringSecurityAuditorAware`, `AjaxLogoutSuccessHandler`, `FraudAwareAuthenticationManager`, `UnanimousAuthorizationManager`, `SecuredHttpEndpointGuard`
- Depends on: `service`, `repo`, `contract`, `common`
- Used by: `config` (wired in)

**`repo` (persistence leaf):**
- Purpose: JPA entities, Spring Data repositories, entity listeners. Nothing above this layer is imported here.
- Location: `src/main/java/com/softropic/payam/security/repo/`
- Contains: `User`, `LoginInfo`, `Authority`, `SecKey`, `Secret`, `Customer`, `Challenge`, `Address`, `UserRepository`, `LoginInfoRepository`, `SecKeyRepository`, `SecretRepository`, `PersistentTokenRepository`, `SecKeyEntityListener`
- Depends on: `contract` only
- Used by: `service`, `infrastructure`

**`common` (internal cross-cutting, per module):**
- Purpose: Intra-module utilities and events too coupled to live in the global `common` package.
- Location: `src/main/java/com/softropic/payam/security/common/`
- Contains: `CookieUtil`, `SecurityConstants`, `RequestMetadataProvider`, `RequestMetadata`, `ClientContextProvider`, `RequestIdProvider`, events `AuthEvent`, `PreAuthEvent`, `BadCredentialsEvent`, `FraudEvent`, `AuthenticationAction`
- Depends on: `contract` only
- Used by: `service`, `infrastructure`, `api`

**`config` (composition root):**
- Purpose: Spring bean wiring. The only location permitted to import from `infrastructure`, `service`, and `api` simultaneously.
- Location: `src/main/java/com/softropic/payam/security/config/`
- Contains: `SecurityConfiguration`, `CorsConfig`, `MvcConfig`, `AppEndpoints`, `SimpleGrantedAuthorityMixin`
- Depends on: all layers (exempt from the unidirectional rule)
- Used by: Spring container only

**`audit` (sub-module, pending migration):**
- Purpose: Audit trail storage; pre-dates the current convention and has not been fully migrated to `infrastructure/`.
- Location: `src/main/java/com/softropic/payam/security/audit/`
- Contains: `AuditLog` (entity), `AuditLogRepository`, `AccountChangeEventListener`, `SecurityAuditListener`, `AuditTrailMapper`, `LoggingFilter`
- Target: should migrate to `infrastructure/audit/`, `repo/`, and `contract/event/`

**Global `common` package:**
- Purpose: Shared utilities, base entities, and cross-domain types used by all modules.
- Location: `src/main/java/com/softropic/payam/common/`
- Contains: `BaseEntity`, `AbstractAuditingEntity`, persistence helpers, `MobilePaymentProvider`, payment/refund enums, validation annotations (`@Phone`, `@Name`, `@CamPhone`, etc.), `Success`/`Failure`/`ErrorDto` response wrappers, thread pool MDC decorators, REST client abstractions (`AbstractClient`)

**`email` module:**
- Purpose: Manages all email delivery with retry, persistence, and multi-provider support.
- Location: `src/main/java/com/softropic/payam/email/`
- Mirrors the same layer pattern: `contract/`, `service/`, `repo/`, `infrastructure/`, `config/`
- Key classes: `MailService`, `MailManager`, `EmailRetryScheduler` (scheduled retry via `SELECT FOR UPDATE SKIP LOCKED`), `SenderProvider` (round-robin SMTP), `EnvelopeEntity` (persisted delivery state), `AccountChangeEmailListener`

**Frontend (Quasar/Vue SPA):**
- Purpose: Single-page application compiled and embedded into the Spring Boot static resources at build time.
- Location: `src/frontend/src/`
- Key directories: `pages/` (route-level views), `stores/` (Pinia state), `api/` (Axios calls — `account.api.js`, `auth.api.js`, `profile.api.js`, `session.api.js`), `components/`, `composables/`, `layouts/`, `router/`, `boot/`, `i18n/`

## Data Flow

**Authentication (login) flow:**

1. POST `/authenticate` arrives and is intercepted by `JWTAuthenticationFilter` (before the controller layer)
2. Filter calls `loginTokenManager.ensureClientHasPreLoginId()` and publishes `PreAuthEvent`
3. `FraudAwareAuthenticationManager` delegates to `DaoAuthProvider`, which calls `LoadUserByUserNameService` to load a `Principal` from `UserRepository`
4. On success, `TwoFactorLoginService.processLogin()` creates a login reference; OTP is emailed via the `email` module
5. OTP verification hits `SecondFactorLoginFilter`; on success `JwtManagerImpl` issues JWT tokens as HTTP-only cookies
6. Subsequent requests pass through `JWTAuthorizationFilter`, which extracts and validates the JWT cookie, populates `SecurityContextHolder`
7. Controllers receive the request; services apply `@PreAuthorize` method-level checks using `SecurityUtil.getCurrentUserName()`

**Registration flow:**

1. POST `/v1/account/register` → `AccountResource.registerAccount()` → `AccountManagementFacade.registerAccount()`
2. `UserRegistrationService` creates user (inactive), persists via `UserRepository`
3. `AccountChangeEvent` is published → `AccountChangeEmailListener` receives it → `MailManager.send()` → `MailService.sendEmailFromTemplate()`
4. Email is persisted as `EnvelopeEntity` before delivery; on delivery failure `EmailRetryScheduler` retries via database lock

**Error handling flow:**

1. All controller exceptions are caught by `ApiAdvice` (`@RestControllerAdvice`)
2. Each exception type maps to an HTTP status and a localized `ErrorDto` with a `helpCode` (Sqids-encoded support ID) for correlation
3. Security exceptions additionally publish a `SecurityAlertEvent`
4. Filter-level exceptions are routed back to `ApiAdvice` via `HandlerExceptionResolver` (injected into `JWTAuthenticationFilter`)

**State Management:**
- No server-side HTTP session for authenticated state; all auth state is carried in JWT HTTP-only cookies
- `EntityStatus` enum (`ACTIVE`/`INACTIVE`) on `AbstractAuditingEntity` represents entity lifecycle state
- `AbstractAuditingEntity` captures `createdBy`, `createdDate`, `lastModifiedBy`, `lastModifiedDate`, `requestId`, `sessionId` automatically via JPA `AuditingEntityListener`
- Hibernate Envers (`@Audited`) provides entity change history

## Key Abstractions

**`Principal` (contract layer):**
- Purpose: The authenticated user identity used throughout the request lifecycle. Implements `UserDetails`.
- Examples: `src/main/java/com/softropic/payam/security/contract/Principal.java`
- Pattern: Built in `JWTAuthenticationFilter` from HTTP credentials; consumed by services via `SecurityUtil.getCurrentUserName()`

**`LoginTokenManager` (service interface):**
- Purpose: Defines JWT token creation, validation, and cookie management without coupling callers to JWT internals.
- Examples: `src/main/java/com/softropic/payam/security/service/LoginTokenManager.java` (interface), `src/main/java/com/softropic/payam/security/infrastructure/jwt/JwtManagerImpl.java` (implementation)
- Pattern: Interface-in-service, implementation-in-infrastructure

**`AbstractAuditingEntity` / `BaseEntity` (common persistence):**
- Purpose: All JPA entities extend these for TSID-based identity and automatic audit columns.
- Examples: `src/main/java/com/softropic/payam/common/persistence/BaseEntity.java`, `src/main/java/com/softropic/payam/common/persistence/AbstractAuditingEntity.java`
- Pattern: TSID (`@Tsid`) for primary keys; Envers `@Audited` for history tracking

**`Success` / `ErrorDto` (common message):**
- Purpose: Standardized response envelopes for all API responses.
- Examples: `src/main/java/com/softropic/payam/common/message/Success.java`, `src/main/java/com/softropic/payam/common/message/ErrorDto.java`
- Pattern: `Success` is a Java record; `ErrorDto` carries a `helpCode` for support correlation

**`AbstractClient` (common client):**
- Purpose: Base class for outbound HTTP client calls (e.g., MTN MoMo API).
- Examples: `src/main/java/com/softropic/payam/common/client/AbstractClient.java`
- Pattern: Configured via `ClientConfiguration` YAML properties with `defaultTcpConfig` anchor

## Entry Points

**`PayamApplication`:**
- Location: `src/main/java/com/softropic/payam/PayamApplication.java`
- Triggers: `java -jar` / Spring Boot Maven plugin
- Responsibilities: Bootstraps Spring context; enables `@EnableRetry`

**`AccountResource` (REST):**
- Location: `src/main/java/com/softropic/payam/security/api/AccountResource.java`
- Triggers: HTTP requests to `/v1/account/**`
- Responsibilities: Registration, activation, account retrieval, password reset

**`JWTAuthenticationFilter` (security filter):**
- Location: `src/main/java/com/softropic/payam/security/infrastructure/jwt/filter/JWTAuthenticationFilter.java`
- Triggers: POST `/authenticate`
- Responsibilities: Credential extraction, fraud-aware authentication, 2FA initiation, JWT issuance

**`JWTAuthorizationFilter` (security filter):**
- Location: `src/main/java/com/softropic/payam/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java`
- Triggers: Every secured HTTP request
- Responsibilities: JWT cookie validation, `SecurityContextHolder` population

**`EmailRetryScheduler` (scheduled):**
- Location: `src/main/java/com/softropic/payam/email/infrastructure/EmailRetryScheduler.java`
- Triggers: Spring `@Scheduled`
- Responsibilities: Retries failed email deliveries using `SELECT FOR UPDATE SKIP LOCKED` on `EnvelopeEntity`

## Error Handling

**Strategy:** Centralized `@RestControllerAdvice` with typed exception handlers; all exceptions surface a `helpCode` for user-facing support correlation.

**Patterns:**
- `ApiAdvice` at `src/main/java/com/softropic/payam/security/api/ApiAdvice.java` handles all controller and filter exceptions
- Filter-level exceptions are delegated back to `ApiAdvice` via `HandlerExceptionResolver` injected into filters
- Security exceptions additionally trigger `SecurityAlertEvent` publication
- `DataIntegrityViolationException` is caught and mapped to HTTP 400 with constraint name extraction
- All error responses include a `helpCode` (Sqids-encoded, non-sequential UUID hash) for log correlation

## Cross-Cutting Concerns

**Logging:** SLF4J with Logback; structured JSON via `logstash-logback-encoder`; distributed tracing via Micrometer + OpenTelemetry OTLP; Loki appender for log aggregation; `requestId` and `sessionId` stamped on every entity write via `RequestIdAuditEntityListener` and `SessionIdAuditEntityListener`

**Validation:** Bean Validation (Jakarta) annotations on DTOs with custom annotations (`@Phone`, `@Name`, `@CamPhone`, `@LangIso2`, `@LocalDateWindow`) in `src/main/java/com/softropic/payam/common/validation/`; validation failures produce structured `ErrorDto` with per-field `FieldErrorDto` entries

**Authentication:** Stateless JWT in HTTP-only cookies; 2FA via OTP emailed through the `email` module; rate limiting via Bucket4j (`RateLimitingService`); client ID whitelist enforcement via `ClientIdAccessDecisionManager`

---

*Architecture analysis: 2026-03-21*
