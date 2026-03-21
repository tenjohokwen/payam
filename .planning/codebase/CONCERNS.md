# Codebase Concerns

**Analysis Date:** 2026-03-06

---

## Security Considerations

**Leaked credential hint in config files:**
- Risk: A commented-out password value `#BEQMN6SITHUDQEGIDWKZ` appears in the `spring.mail.password` line in both `application.yaml` (line 73) and `application-dev.yaml` (line 73). Even though it is a comment, it is committed to version control and represents a credential leak for the GMX mail account `blue-bone@gmx.de`.
- Files: `src/main/resources/application.yaml`, `src/main/resources/application-dev.yaml`
- Current mitigation: The active value uses `${SPRING_MAIL_PASSWORD}` env var interpolation; the comment is residual.
- Recommendations: Remove the comment from both files immediately. Rotate the GMX password if this repository has ever been pushed to a shared remote.

**nationalId stored as plain text:**
- Risk: `Customer.nationalId` is a plain `String` column (`@Size(max = 3)`) with no encryption, hashing, or `@Convert` annotation. National identification numbers are PII and may be subject to data-protection regulations (GDPR, etc.).
- Files: `src/main/java/com/softropic/payam/security/domain/Customer.java` (line 81)
- Current mitigation: None.
- Recommendations: Apply JPA `@Convert` with an `AttributeConverter` that encrypts the value at rest, or store a salted hash and only compare — never display — the raw value.

**TLS certificate verification disabled globally for outbound HTTP clients:**
- Risk: `checkCertificate: false` is set in the `defaultTcpConfig` anchor in both config files, which is the base config for the MoMo API client. `TcpConfiguration.checkCertificate` defaults to `true` in the Java class, but the YAML overrides it to `false`. This disables server certificate verification for outbound REST calls, making MitM attacks trivial in any environment where this profile is active.
- Files: `src/main/resources/application.yaml` (line 138), `src/main/resources/application-dev.yaml` (line 151), `src/main/java/com/softropic/payam/common/client/TcpConfiguration.java` (lines 13, 56-60)
- Current mitigation: Only affects sandbox MoMo endpoint today.
- Recommendations: Set `checkCertificate: true` (or remove the override) in production config. Keep `false` only in an isolated sandbox profile with a clear comment.

**Spring circular references enabled in production config:**
- Risk: `spring.main.allow-circular-references: true` is set in both `application.yaml` and `application-dev.yaml`. This suppresses a Spring Boot 2.6+ safeguard and hides design flaws that could cause unpredictable startup failures or proxy mis-wiring.
- Files: `src/main/resources/application.yaml` (line 24), `src/main/resources/application-dev.yaml` (line 24)
- Current mitigation: None.
- Recommendations: Identify the circular dependency causing this flag to be needed and break it. Remove the flag once resolved.

**Plaintext database credentials in dev config:**
- Risk: `application-dev.yaml` contains literal `username: postgres` / `password: postgres` (lines 44-45). If this profile is ever active in a shared or cloud environment, the database is trivially accessible.
- Files: `src/main/resources/application-dev.yaml` (lines 44-45)
- Current mitigation: Only used locally during development per convention.
- Recommendations: Use env var interpolation (`${DB_PASSWORD:postgres}`) consistently so the pattern is not accidentally promoted.

---

## Tech Debt

**SMS registration strategy is a complete stub:**
- Issue: `SmsRegistrationStrategy` is injected into `AccountManagementFacade` and registered as a Spring `@Component`, but both `notifyNewUser()` and `notifyUserExists()` contain only TODO comments and return a random UUID. Phone-based registration will silently succeed without actually sending any SMS.
- Files: `src/main/java/com/softropic/payam/security/api/registration/SmsRegistrationStrategy.java` (lines 54, 79)
- Impact: Users registering via phone number never receive an activation code. No error is raised; callers believe notification succeeded.
- Fix approach: Integrate an SMS gateway (Twilio, AWS SNS, etc.) and implement the two methods, or make the class throw `UnsupportedOperationException` so failures are explicit until then.

**MoMo payment integration configured but not implemented:**
- Issue: Full MoMo API endpoint configuration exists in both YAML files (5 endpoints: `requestToPay`, `getRequestToPayTransactionStatus`, `getAccountBalance`, `getBasicUserInfo`, `requestAuthToken`). `MomoError` error codes are defined in `common/client/exception`. `MobilePaymentProvider` enum (MTN, ORANGE, NEXTTEL) and `PaymentMethod` enum exist. However, there is no `MomoClient` class, no service that uses these configs, and no integration test for any payment flow.
- Files: `src/main/java/com/softropic/payam/common/client/exception/MomoError.java`, `src/main/java/com/softropic/payam/common/payment/MobilePaymentProvider.java`, `src/main/java/com/softropic/payam/common/payment/PaymentMethod.java`; config: `src/main/resources/application.yaml` (lines 140-176)
- Impact: Payment functionality is dead code. The `MOMO_SUBSCRIPTION_KEY` env var is required at startup but never consumed.
- Fix approach: Implement a `MomoClient extends AbstractClient` or explicitly remove the payment scaffolding if out of scope.

**In-memory login-attempt cache is not multi-node safe:**
- Issue: `LoginAttemptsService` uses Guava `LoadingCache` to track failed login attempts, blacklisted clients, and per-IP counters. The TODO on line 37 explicitly acknowledges this cannot be used in a multi-node deployment.
- Files: `src/main/java/com/softropic/payam/security/manager/LoginAttemptsService.java` (line 37)
- Impact: Brute-force protection evaporates the moment more than one instance is running (e.g., horizontal scaling or rolling deploys).
- Fix approach: Replace the in-memory cache with a distributed cache (Redis via Spring Cache abstraction) or a database-backed rate-limit table.

**`blacklistClient()` records but does not enforce:**
- Issue: `LoginAttemptsService.blacklistClient()` (line 142) is explicitly noted as "only recording." It populates a cache entry but `ClientIdAccessDecisionManager.isClientIdAllowed()` does not consult the blacklist.
- Files: `src/main/java/com/softropic/payam/security/manager/LoginAttemptsService.java` (line 142), `src/main/java/com/softropic/payam/security/manager/ClientIdAccessDecisionManager.java` (line 46)
- Impact: Clients can be blacklisted with no actual effect on access.
- Fix approach: Wire `LoginAttemptsService.isBlacklisted()` into `ClientIdAccessDecisionManager.isClientIdAllowed()`.

**Allowed machine clients hardcoded in config, not database:**
- Issue: `allowed.clients: myClientId,hisClientId,herClientId,ourClientId` in both YAML files. `ClientIdAccessDecisionManager` loads this list at startup. Any change requires a config change and redeploy. The TODO (line 29) acknowledges this should be in a DB.
- Files: `src/main/resources/application.yaml` (line 117), `src/main/resources/application-dev.yaml` (line 123), `src/main/java/com/softropic/payam/security/manager/ClientIdAccessDecisionManager.java` (line 29)
- Impact: Cannot revoke or add clients without redeployment.
- Fix approach: Persist allowed clients in a DB table and load them via a `@Refreshable` bean or Spring Cloud Config.

**Duplicate phone-number parsing logic:**
- Issue: The same `CamMobileValidator.validate()` to build-`PhoneNumber` entity pattern appears in both `UserMapper.stringToPhoneNumber()` and `UserProfileService.toPhoneNumber()`. The TODO in `AccountManagementFacade` (line 244) also calls this out.
- Files: `src/main/java/com/softropic/payam/security/core/mapper/UserMapper.java` (line 83), `src/main/java/com/softropic/payam/security/service/UserProfileService.java` (line 248), `src/main/java/com/softropic/payam/security/api/AccountManagementFacade.java` (line 244)
- Impact: Maintenance risk; a bug fix or validation change must be applied in multiple places.
- Fix approach: Extract into a single `PhoneNumberFactory` or `PhoneNumberConverter` utility class.

**`AccountChangeEventListener` couples audit package to email package:**
- Issue: The audit listener directly depends on email types. The TODO (line 29) notes this is a design flaw that should be corrected.
- Files: `src/main/java/com/softropic/payam/security/audit/listener/AccountChangeEventListener.java` (line 29)
- Impact: Audit cannot be used independently; testing the audit trail requires wiring in email infrastructure.
- Fix approach: Redesign `AccountChangeEvent` to carry only primitive/value data; let a separate email listener translate it into an email command.

**Hibernate DDL auto config risk:**
- Issue: `spring.jpa.generate-ddl: true` appears in `application.yaml` (line 27), which is the base config. `application-dev.yaml` further sets `hibernate.ddl-auto: create-drop`. While `application.yaml` has `ddl-auto: none`, having `generate-ddl: true` in a base config is a footgun.
- Files: `src/main/resources/application.yaml` (line 27), `src/main/resources/application-dev.yaml` (line 28)
- Impact: Misconfiguration risk: if a profile is missing the `ddl-auto: none` override the schema could be mutated or dropped in production.
- Fix approach: Set `generate-ddl: false` in the base `application.yaml`; override only in the dev profile.

---

## Null Returns in Production Code

The following production methods return raw `null` instead of `Optional` or throwing, which can cause `NullPointerException` at call sites:

**`ClaimsExtractorImpl.extractDbRefreshToken()`** — returns `null` if token is blank or claim is absent.
- File: `src/main/java/com/softropic/payam/security/jwt/api/ClaimsExtractorImpl.java` (lines 105, 109)
- Risk: Any caller that does not null-check will NPE at runtime.
- Fix approach: Change return type to `Optional<Long>`.

**`ClaimsExtractorImpl.extractUserNameSilently()`** — returns `null` via `orElse(null)`.
- File: `src/main/java/com/softropic/payam/security/jwt/api/ClaimsExtractorImpl.java` (line 114)
- Risk: Callers expecting a non-null username string will NPE.
- Fix approach: Return `Optional<String>` and update callers.

**`JWTAuthenticationFilter.toCreds()`** — returns `null` if `LoginIdType` code is exhausted.
- File: `src/main/java/com/softropic/payam/security/jwt/api/filter/JWTAuthenticationFilter.java` (line 180)
- Risk: `toCreds()` result is used immediately; a null leads to NPE in the login filter, which is a security-critical path.
- Fix approach: Add a `default` branch that throws `AuthenticationServiceException` or falls back to `LoginIdType.EMAIL`.

**`AuthenticationManagerSimulator.authenticate()`** — always returns `null` intentionally.**
- File: `src/main/java/com/softropic/payam/security/manager/AuthenticationManagerSimulator.java` (line 24)
- Risk: This is a timing-attack mitigation simulator, but any code path that dereferences the return value will NPE. There is no `@Nullable` annotation or Javadoc.
- Fix approach: Add `@Nullable` annotation and verify every call site handles null.

**`SecurityAuditListener.extractMessage()`** — returns `null` when exception is `null`.
- File: `src/main/java/com/softropic/payam/security/audit/listener/SecurityAuditListener.java` (line 130)
- Risk: `auditTrail.setMsg(null)` propagates a null into the audit DB column; depending on schema constraints this may cause an INSERT failure.
- Fix approach: Return an empty string or a default `"(no message)"`.

**`AccountManagementFacade` (line 240)** — returns `null` from phone-number builder when validator result is missing.
- File: `src/main/java/com/softropic/payam/security/api/AccountManagementFacade.java` (line 240)
- Risk: Null propagated to callers expecting a `PhoneNumber` entity.
- Fix approach: Return `Optional<PhoneNumber>` or throw a domain exception.

**`UserProfileService.toPhoneNumber()` and `formatAddress()`** — return `null` for blank/null input.
- File: `src/main/java/com/softropic/payam/security/service/UserProfileService.java` (lines 250, 290)
- Risk: Acceptable for optional fields, but undocumented; callers must know to null-check.
- Fix approach: Add `@Nullable` annotation or return `Optional<T>` for consistency.

---

## Missing Critical Features

**Email send-failure retry mechanism absent:**
- Problem: `MailService.sendEmailFromTemplate()` has a TODO (line 69) for handling send failures. The circuit-breaker in `MailManager` catches exceptions and marks rows `FAILED` in DB with `retry: true`, but there is no scheduled job to retry those rows.
- Blocks: Reliable email delivery in production.
- Files: `src/main/java/com/softropic/payam/email/service/MailService.java` (line 69), `src/main/java/com/softropic/payam/email/api/MailManager.java`

**Session ID not included in JWT claims:**
- Problem: `Principal` has a TODO noting the session ID should be added to claims (line 23). `RequestMetadataProvider` has a TODO to validate session ID is present in claims to prevent fraud (line 79).
- Blocks: Session-bound JWT validation and protection against token replay after logout.
- Files: `src/main/java/com/softropic/payam/security/exposed/Principal.java` (line 23), `src/main/java/com/softropic/payam/security/exposed/util/RequestMetadataProvider.java` (line 79)

**IP whitelist check never implemented:**
- Problem: `LoginAttemptsService` has a TODO (line 226): "also verify that ip is in whitelist."
- Blocks: IP-based access control for trusted machine clients.
- Files: `src/main/java/com/softropic/payam/security/manager/LoginAttemptsService.java` (line 226)

**`SecondFactorLoginFilter` response status unresolved:**
- Problem: The HTTP status returned after successful 2FA is left as a TODO with open question (`//TODO 201? 200? 202?`).
- Files: `src/main/java/com/softropic/payam/security/core/filter/SecondFactorLoginFilter.java` (line 101)
- Impact: API contract for 2FA completion is undefined and unstable.

---

## Test Coverage Gaps

**`UserProfileService` — no dedicated unit or integration tests:**
- What's not tested: `updateUserEmail()`, `updatePhone()`, `toggle2fa()`, `changePassword()`, `updatePostalAddress()`.
- Files: `src/main/java/com/softropic/payam/security/service/UserProfileService.java`
- Risk: Profile update logic, including `AccountChangeEvent` publishing and old-value capture, could regress silently.
- Priority: High

**`AccountManagementFacade` — no dedicated tests:**
- What's not tested: Password reset flow, registration dispatch to SMS vs email strategy, phone-number building.
- Files: `src/main/java/com/softropic/payam/security/api/AccountManagementFacade.java`
- Risk: Facade orchestrates multiple services; failures in delegation are invisible.
- Priority: High

**`SmsRegistrationStrategy` — no tests:**
- What's not tested: The stub silently returns a UUID. No test verifies that a warning is logged or that the channel type is `"SMS"`.
- Files: `src/main/java/com/softropic/payam/security/api/registration/SmsRegistrationStrategy.java`
- Priority: Low (stub), must be High once real implementation lands.

**`AuthorizationFailureListener` — explicitly untested:**
- What's not tested: The listener body has `//TODO test this` on line 28.
- Files: `src/main/java/com/softropic/payam/security/listener/AuthorizationFailureListener.java`
- Risk: Authorization-failure audit path is unverified.
- Priority: Medium

**`LoginInfoService` scenarios not tested:**
- What's not tested: `//TODO test the scenarios` on line 59.
- Files: `src/main/java/com/softropic/payam/security/service/LoginInfoService.java`
- Priority: Medium

---

## Fragile Areas

**`SecurityConfiguration` role-prefix assumption:**
- Files: `src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java` (line 94)
- Why fragile: A TODO notes that `hasAnyRole` is used with roles that do not carry the `ROLE_` prefix. If the Spring Security default behavior changes or roles are renamed, authorization checks will silently fail open or closed.
- Safe modification: Audit all `hasAnyRole` / `hasRole` calls and standardize prefix usage before adding new roles.
- Test coverage: Partial — `SecurityIT` and `SecurityFilterChainIT` exist but do not cover all role branches.

**`node_modules` committed to version control:**
- Files: `src/frontend/node_modules/` (407 subdirectories)
- Why fragile: The root `.gitignore` (line 65) contains `!**/src/frontend/node_modules/`, a negation that un-ignores `node_modules` for the entire repo, overriding the frontend's own `.gitignore` which correctly excludes it. As a result the full `node_modules` tree (~400 packages) is tracked by git, causing massive repo size, merge conflicts on dependency changes, and potential supply-chain risk from committed binaries.
- Safe modification: Remove the `!**/src/frontend/node_modules/` line from the root `.gitignore`. Run `git rm -r --cached src/frontend/node_modules` to stop tracking existing entries.

**`Customer.nationalId` used as equality key without unique DB constraint:**
- Files: `src/main/java/com/softropic/payam/security/domain/Customer.java` (line 81)
- Why fragile: `equals()` uses `nationalId` as the primary business key, but no `@Column(unique = true)` is declared, and the TODO on line 35 notes that a composite unique constraint on `(firstName, lastName, dateOfBirth)` is also missing. Duplicate customers can be inserted.
- Safe modification: Add a unique index on `national_id` via a Flyway migration before making the field mandatory.
- Test coverage: None for duplicate-prevention logic.

**`MailManager` `@Async` + `@EventListener` + `@Transactional` combination:**
- Files: `src/main/java/com/softropic/payam/email/api/MailManager.java` (lines 52-55)
- Why fragile: The three annotations interact in non-obvious ways. `@Async` moves execution off the calling thread, which means the `@Transactional` context from the event publisher is not inherited. The async thread opens its own transaction. If the outer transaction rolls back after publishing, the email may still be sent (or recorded as sent) for a rolled-back business operation.
- Safe modification: Adopt a transactional outbox pattern — write to `EnvelopeEntity` within the same transaction as the business operation; a scheduled poller sends emails — instead of direct async dispatch.

---

*Concerns audit: 2026-03-06*
