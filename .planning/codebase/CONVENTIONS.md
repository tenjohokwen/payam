# Coding Conventions

**Analysis Date:** 2026-03-06

## Language Overview

This is a dual-language codebase:
- **Backend:** Java 17 (Spring Boot 3.5) at `src/main/java/com/softropic/payam/`
- **Frontend:** JavaScript (Vue 3 + Quasar) at `src/frontend/src/`

---

## Java Conventions (Backend)

### Naming Patterns

**Files / Classes:**
- PascalCase for all class names: `LoginAttemptsService`, `JwtManagerImpl`, `UserRegistrationService`
- Suffix `Impl` for implementation classes: `JwtManagerImpl`, `TokenCreatorImpl`, `ClaimsExtractorImpl`
- Suffix `IT` for integration tests: `SecurityIT`, `UserServiceIT`, `RateLimitingAspectIT`
- Suffix `Test` for unit tests: `JwtManagerImplTest`, `LoginAttemptsServiceTest`
- Suffix `Filter` for servlet filters: `JWTAuthenticationFilter`, `SecondFactorLoginFilter`
- Suffix `Service` for business logic services: `UserService`, `PasswordResetService`
- Suffix `Repository` for JPA repositories: `UserRepository`, `LoginInfoRepository`
- Suffix `Config` for configuration beans: `SecurityConfiguration`, `DataSourceConfig`
- Suffix `Facade` for orchestrating services: `AccountManagementFacade`
- Suffix `Listener` for Spring event listeners: `AuthenticationSuccessListener`, `SendMailListener`
- Suffix `Dto` for data transfer objects: `UserDto`, `ChangePasswordDto`, `ErrorDto`

**Methods:**
- camelCase verbs: `loginSucceeded`, `loginFailed`, `isAllowed`, `blacklistClient`
- Getters/setters follow Java bean convention: `getLogin()`, `setActivated()`
- Boolean methods use `is` prefix: `isAllowed()`, `isActivated()`, `isLocked()`

**Constants:**
- SCREAMING_SNAKE_CASE static finals: `MAX_FAILED_CLIENT_ATTEMPTS`, `ADMIN_COOKIE`, `JWT_COOKIE_NAME`
- Grouped in dedicated constants classes: `src/main/java/com/softropic/payam/security/common/util/SecurityConstants.java`

**Variables:**
- camelCase: `loginAttemptsService`, `attemptsByClientUserCache`, `blacklistedClients`
- Prefix `final` local variables wherever value doesn't change (consistent pattern)

### Code Style

**Formatting:**
- No configured Spotless or Checkstyle detected; style is consistent but enforced by team convention
- Blank lines between logical sections within methods
- Aligned multi-line imports using static imports for constants (`import static com.softropic.payam.security.common.util.SecurityConstants.*`)

**`final` keyword:**
- Used extensively on local variables, method parameters, and fields: `final String errorCode`, `final ErrorDto errorDTO`
- Constructor parameters are `final`: `public LoginAttemptsService(final ClientIdAccessDecisionManager clientIdAccessDecisionMgr)`

**Annotations:**
- Lombok `@Slf4j` for logging (produces `log` field): `src/main/java/com/softropic/payam/security/manager/LoginAttemptsService.java`
- `@Service`, `@Repository`, `@RestController`, `@RestControllerAdvice` used appropriately
- Constructor injection preferred over field injection; `@Autowired` used on constructor when explicit wiring needed
- `@Transactional` on service methods; `@Sql` on test methods to load fixture data

### Import Organization

**Order (observed):**
1. Project imports (`com.softropic.payam.*`)
2. Framework imports (`org.springframework.*`, `org.junit.*`, `org.mockito.*`)
3. Java standard library (`java.*`, `javax.*`, `jakarta.*`)
4. Third-party libraries (`io.jsonwebtoken.*`, `com.google.*`)
5. Static imports last (`import static ...`)

### Error Handling

**Backend strategy — centralized `@RestControllerAdvice`:**
- All exceptions handled in `src/main/java/com/softropic/payam/security/ApiAdvice.java`
- Each exception type maps to an HTTP status via `@ResponseStatus`
- All errors return `ErrorDto` with a `helpCode` (Sqids-encoded UUID) for support traceability
- Security-related exceptions publish a `SecurityAlertEvent` via `ApplicationEventPublisher`
- Catch-all `Throwable` handler prevents unhandled stack traces leaking to clients
- Custom exception hierarchy: `SecException` → `AuthorizationException`, `JWTExpiredException`, `InvalidJWTDataException`, etc. at `src/main/java/com/softropic/payam/security/exposed/exception/`

**Error response shape:**
```java
// ErrorDto returned by all exception handlers
new ErrorDto(helpCode, new ErrorMsg(msgKey, message))
// Field errors added individually for validation failures
dto.add(fieldError.getObjectName(), field, new ErrorMsg(errorKey, message));
```

**Internationalized messages:**
- Error messages resolved via `MessageSource` using `msgKey` + locale from `RequestMetadataProvider.getClientInfo().getChosenLang()`
- Fallback to English default message string if key not found

### Logging

**Framework:** Logback via SLF4J; Logstash JSON encoder (`logstash-logback-encoder`) for structured output.

**Patterns:**
- Use Lombok `@Slf4j` to get `log` field; do not instantiate `Logger` manually
- Structured log entries via `entries(ctx)` from `net.logstash.logback.argument.StructuredArguments`
- Error logging always includes `SUPPORT_ID` (`helpCode`) in message
- Debug logging used for non-critical cache misses
- Warn logging for fraud/abuse events

```java
// Standard error log with structured context
log.error(fullMsg, entries(ctx), throwable);
// Warning for security events
log.warn("Fraud detection from client with the following metadata {}", metadata);
```

### Comments

**Javadoc:**
- Used on public service/interface methods describing purpose, parameters, return values
- Example at `src/main/java/com/softropic/payam/security/manager/LoginAttemptsService.java`: class-level Javadoc with `<ul>` lists explaining decision logic

**Inline TODO comments:**
- Format: `//TODO <description>` (no space after `//TODO` consistently)
- Used to flag multi-node readiness issues, incomplete features, and planned improvements
- 48 TODOs across 30 source files (not yet resolved)

### Module Design

**Package structure:** Feature-first within bounded context:
```
security/
  api/        # Controllers, DTOs, rate-limiting
  config/     # SecurityConfiguration, CorsConfig
  core/       # Filters
  domain/     # JPA entities (User, Customer, Authority)
  exposed/    # Public API contracts (Principal, exception types, util)
  jwt/api/    # JWT manager, token creator/validator
  manager/    # Login attempts, 2FA manager
  repository/ # Spring Data JPA repositories
  service/    # Business logic services
  listener/   # Spring event listeners
  audit/      # Audit trail (Hibernate Envers)
```

**Interface + Impl pattern:**
- Behavior defined on interface: `ClaimsExtractor`, `TokenCreator`, `TokenValidator`, `JwtSecretService`
- Implementation suffixed `Impl`: `ClaimsExtractorImpl`, `TokenCreatorImpl`, `TokenValidatorImpl`

**Exposed package convention:**
- `security/exposed/` contains types intended to be referenced by other modules (cross-module API boundary)
- Types here: `Principal`, exception types, `UserDto`, utility providers

---

## JavaScript / Vue Conventions (Frontend)

### Naming Patterns

**Files:**
- PascalCase for Vue components: `UpdateEmailDialog.vue`, `LoginPage.vue`, `SessionWarningDialog.vue`
- camelCase for composables with `use` prefix: `useErrorHandler.js`, `useSession.js`, `useLoading.js`
- camelCase with `.api.js` suffix for API modules: `auth.api.js`, `profile.api.js`, `session.api.js`
- camelCase for utilities: `errorHandler.js`

**Variables and functions:**
- camelCase for all variables and functions: `handleSubmit`, `dialogVisible`, `isSubmitting`, `clearError`
- Validation rule functions use concise camelCase names: `required`, `validEmail`, `minLen5`, `notSameEmail`

**Props/Emits:**
- Props use camelCase: `modelValue`, `currentEmail`
- Emits use kebab-case string events: `'update:modelValue'`, `'updated'`

### Code Style (Frontend)

**Formatting** (`.prettierrc.json` at `src/frontend/.prettierrc.json`):
- No semicolons (`"semi": false`)
- Single quotes (`"singleQuote": true`)
- Print width 100 characters (`"printWidth": 100`)

**Linting** (`src/frontend/eslint.config.js`):
- `eslint-plugin-vue` at `flat/essential` level
- Quasar recommended rules via `@quasar/app-vite/eslint`
- `no-debugger` is an error in production, off in development
- `prefer-promise-reject-errors` is disabled

**Vue component structure:**
- `<template>` → `<script setup>` (no `<style>` blocks observed)
- Composition API only via `<script setup>` syntax
- No TypeScript — pure JavaScript

### Import Organization (Frontend)

**Order observed in components:**
1. Vue core (`import { ref, watch } from 'vue'`)
2. Quasar (`import { useQuasar } from 'quasar'`)
3. Vue ecosystem (`import { useI18n } from 'vue-i18n'`, `import { useRouter } from 'vue-router'`)
4. Project API modules (`import { authApi } from 'src/api/auth.api'`)
5. Project composables (`import { useErrorHandler } from 'src/composables/useErrorHandler'`)

**Path aliases:**
- `src/` maps to `src/frontend/src/` (Quasar default alias)

### Error Handling (Frontend)

**Pattern — composable + utility:**
- `src/frontend/src/utils/errorHandler.js` — pure functions that parse Axios errors into structured objects
- `src/frontend/src/composables/useErrorHandler.js` — Vue reactive wrapper around `parseApiError`
- All components import `useErrorHandler()` and call `setError(err)` in catch blocks

```js
// In every form component
const { setError, clearError, hasError, errorMessage, helpCode, isValidationError,
        hasFieldError, getFieldError } = useErrorHandler()

async function handleSubmit() {
  clearError()
  isSubmitting.value = true
  try {
    await someApi.call(...)
  } catch (err) {
    setError(err)  // parses and stores structured error
  } finally {
    isSubmitting.value = false
  }
}
```

**Error display pattern in templates:**
```html
<!-- Field-level errors via Quasar q-input -->
:error="hasFieldError('fieldName')"
:error-message="getFieldError('fieldName')"

<!-- Global banner for non-validation errors -->
<q-banner v-if="hasError && !isValidationError" class="bg-negative text-white">
  {{ errorMessage }}
  <small v-if="helpCode">{{ t('error.helpCode') }}: {{ helpCode }}</small>
</q-banner>
```

### API Module Design

**Pattern — plain object with method properties:**
```js
export const authApi = {
  login(id, password, loginCode = 1) {
    return api.post('/authenticate', { id, password, loginCode })
  },
  logout() {
    return api.post('/api/logout')
  }
}
```

- Each domain has its own file: `auth.api.js`, `profile.api.js`, `account.api.js`, `session.api.js`
- All methods documented with JSDoc comments
- Methods return raw Axios promises (no `.then()` chaining inside the module)

### Composable Design

- Composables return named computed refs + functions
- Internal mutable state exposed as `readonly()` where mutation should be controlled
- Functions are documented with JSDoc including return type signatures

---

*Convention analysis: 2026-03-06*
