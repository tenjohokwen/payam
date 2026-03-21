# Coding Conventions

**Analysis Date:** 2026-03-21

## Language Split

This codebase has two codebases with separate conventions:
- **Backend:** Java 17 (Spring Boot) under `src/main/java/com/softropic/payam/`
- **Frontend:** JavaScript (Vue 3 + Quasar) under `src/frontend/src/`

---

## Backend (Java) Conventions

### Naming Patterns

**Classes:**
- PascalCase for all classes: `UserRegistrationService`, `JwtManagerImpl`, `RateLimitingAspect`
- Service classes suffixed with `Service`: `UserService`, `SecretService`, `LoginAttemptsService`
- Repository interfaces suffixed with `Repository`: `UserRepository`, `SecKeyRepository`
- Exception classes suffixed with `Exception`: `UserNotFoundException`, `JWTExpiredException`
- Filter classes suffixed with `Filter`: `JWTAuthorizationFilter`, `SessionRefreshFilter`
- DTO classes suffixed with `Dto` or `DTO` (mixed): `UserDto`, `ChangePasswordDto`
- Mapper interfaces suffixed with `Mapper`: `UserMapper`, `AuditTrailMapper`
- Config classes suffixed with `Config` or `Configuration`: `SecurityConfiguration`, `JwtConfiguration`

**Methods and Variables:**
- camelCase throughout: `findUserWithAuthoritiesByLogin`, `activateUser`, `completePasswordReset`
- Constants: SCREAMING_SNAKE_CASE: `SPRING_PROFILE_PRODUCTION`, `JWT_COOKIE_NAME`, `MAX_FAILED_CLIENT_ATTEMPTS`
- Boolean methods prefixed with `is`, `has`, or `can`: `isActivated()`, `hasAccountExpired()`, `canInitiatePasswordReset()`
- Repository query methods follow Spring Data naming: `findOneByLogin`, `findOneByEmailOrLogin`, `findInactivatedByActivationKey`

**Packages:**
- All lowercase: `com.softropic.payam.security.service`
- Feature-based sub-packages: `security`, `email`, `common`
- Within feature: `service`, `repo`, `infrastructure`, `config`, `contract`, `api`

### Code Style

**Formatting:**
- No explicit formatter config (Checkstyle/Spotless not detected); follows IntelliJ IDEA defaults
- `final` used extensively on method parameters and local variables: `final User user`, `final String email`
- Blank lines between logical sections inside methods

**Annotations:**
- Lombok `@Slf4j` for logging in service classes (provides `log` field)
- Lombok `@RequiredArgsConstructor` for constructor injection
- `@Service`, `@Transactional` at class level for services
- `@Transactional(readOnly = true)` on individual read methods to override class-level default
- `@PreAuthorize` with constants from `SecurityConstants.HAS_ANY_ROLE`

### Import Organization

**Order (observed pattern):**
1. Project classes
2. Lombok
3. Spring framework classes
4. Jakarta/Java standard library

**Static imports:** Used for assertions and constants: `import static org.assertj.core.api.Assertions.assertThat`

### Error Handling

**Exception hierarchy:**
- `ApplicationException` (base) → `SecException` → domain-specific exceptions
- `SecException` constructors accept `ErrorCode`, `Map<String,Object> logContext`, and `Throwable cause`
- Domain exceptions carry typed error codes: `SecurityError.USER_NOT_FOUND`, `SecurityError.TOO_MANY_REQUESTS`
- Specific exception types per business case: `UserNotFoundException`, `UserAccountLockedException`, `PasswordResetExpiredException`
- Throw domain exceptions from entity business methods, not from service layer
- Return `Optional<T>` from query methods when result may be absent; throw exceptions for mandatory lookups

Example:
```java
public User getUserWithAuthorities(final Long id) {
    return userRepository.findOneById(id)
            .orElseThrow(() -> new UserNotFoundException(id));
}
```

**Authentication exceptions:** Delegated to `HandlerExceptionResolver` via `AuthenticationExceptionHandler`.

### Logging

**Framework:** SLF4J via Lombok `@Slf4j` (provides `log` field)

**Patterns:**
- Debug level for operation details: `log.debug("Created Information for User: {}", newUser)`
- Warn level for security/rate-limit events: `LOGGER.warn("Rate limit exceeded for client: {}, key: {}", ...)`
- Passwords and sensitive data redacted in `toString()` methods: `"password\": \"[REDACTED]\"`
- `toString()` returns JSON-formatted string for structured logging

**Structured logging:** Logback configured to emit JSON to Loki (see `src/main/resources/config/logback-spring.xml`), including `traceId` and `spanId` from OpenTelemetry.

### Comments

**Javadoc:**
- All public service methods documented with `@param`, `@return`, and `@throws`
- Entity business methods fully documented

Example:
```java
/**
 * Activates a user account using the provided activation key.
 * This method can be called by an anonymous user.
 *
 * @param key the activation key
 * @return the activated user if found, empty otherwise
 */
```

**Inline comments:** Used sparingly for non-obvious logic. TODO comments present for known debt.

### Transaction Design

- `@Transactional` at class level in services; individual methods override with `readOnly = true`
- `TransactionTemplate` used in tests for explicit transaction management
- JPA lazy loading respected; `FETCH` entity graphs avoided (noted as causing issues with `@ElementCollection`)

### Entity Design

- JPA entities do not use Lombok (no `@Data` or `@Getter`/`@Setter` on entities) — manual getters/setters
- Entity equals/hashCode: `equals()` uses business key (e.g., `login`); `hashCode()` returns `getClass().hashCode()` to be stable
- Business methods on entities (rich domain model): `activate()`, `lock()`, `preparePasswordReset()`, `completePasswordReset()`
- Envers `@Audited` on `User` entity for audit trail

### MapStruct Mappers

- Mapper interfaces annotated with `@Mapper` (MapStruct)
- Files: `src/main/java/com/softropic/payam/security/service/UserMapper.java`, `src/main/java/com/softropic/payam/security/audit/api/AuditTrailMapper.java`

---

## Frontend (JavaScript/Vue) Conventions

### Naming Patterns

**Files:**
- Vue components: PascalCase — `LoginPage.vue`, `UpdateEmailDialog.vue`, `GlobalLoadingBar.vue`
- Composables: camelCase prefixed with `use` — `useErrorHandler.js`, `useLoading.js`, `useSession.js`
- API modules: camelCase suffixed with `.api.js` — `auth.api.js`, `account.api.js`
- Utility modules: camelCase — `errorHandler.js`
- Store files: camelCase suffixed with `-store.js` — `example-store.js`

**Functions and Variables:**
- camelCase: `handleLogin`, `initSession`, `setError`, `clearError`
- Event handlers prefixed with `handle`: `handleLogin`, `handleSubmit`
- Boolean refs: present-tense state names — `isSubmitting`, `isPwd`, `hasError`

### Code Style

**Formatting (Prettier):**
- No semicolons: `semi: false`
- Single quotes: `singleQuote: true`
- Print width: 100 characters

**Linting (ESLint):**
- `eslint.config.js` uses flat config format
- `@quasar/app-vite/eslint` recommended rules
- `eslint-plugin-vue` at "essential" level
- `prefer-promise-reject-errors` disabled
- Prettier skip-formatting integration

**Vue component style:**
- Composition API with `<script setup>` syntax throughout
- No `defineComponent()` wrapper used
- Template, script, and style sections in that order

### Import Organization

**Order (observed):**
1. Vue core imports: `import { ref, computed } from 'vue'`
2. Vue Router / plugins: `import { useRouter } from 'vue-router'`
3. i18n: `import { useI18n } from 'vue-i18n'`
4. Project API modules: `import { authApi } from 'src/api/auth.api'`
5. Project composables: `import { useErrorHandler } from 'src/composables/useErrorHandler'`

**Path aliases:** `src/` alias resolves to `src/frontend/src/` (Quasar convention)

### API Module Pattern

API functions grouped by domain in named export objects:
```javascript
export const authApi = {
  login(id, password, loginCode = 1) {
    return api.post('/authenticate', { id, password, loginCode });
  },
  logout() { ... }
};
```

### Composable Pattern

Composables encapsulate reactive state and expose readonly refs:
```javascript
export function useErrorHandler() {
  const error = ref(null);
  const hasError = computed(() => error.value !== null);
  // ...
  return {
    error: readonly(error),
    hasError,
    setError,
    clearError,
  };
}
```
- Internal mutable state, readonly external exposure
- Computed properties for derived state
- JSDoc-style comments with `@returns` typing

### Error Handling (Frontend)

- Errors from API calls caught in `try/catch` inside async handlers
- `useErrorHandler` composable wraps error state
- `parseApiError` utility in `src/frontend/src/utils/errorHandler.js` normalizes Axios errors
- Error keys (`errorKey`) used with i18n for translated messages
- Field-level errors supported via `hasFieldError(field)` / `getFieldError(field)`
- Pattern in every page:
```javascript
try {
  await authApi.login(...)
} catch (err) {
  setError(err);
} finally {
  isSubmitting.value = false;
}
```

### Validation

- Quasar form validation via `rules` prop on `q-input`
- Inline validation functions: `const required = val => !!val || t('validation.required')`
- `@submit.prevent` on `q-form`

### i18n

- All user-facing strings use `t('key')` from `useI18n()`
- Translation files: `src/frontend/src/i18n/en-US/index.js`, `src/frontend/src/i18n/fr-FR/index.js`

---

*Convention analysis: 2026-03-21*
