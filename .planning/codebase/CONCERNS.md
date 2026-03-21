# Codebase Concerns

**Analysis Date:** 2026-03-21

## Security Considerations

**Hardcoded Credentials in application.yaml:**
- Risk: Production `application.yaml` has a commented-out plaintext password next to the `${SPRING_MAIL_PASSWORD}` placeholder: `#BEQMN6SITHUDQEGIDWKZ`. If uncommented or copied incorrectly, real credentials are exposed in source control.
- Files: `src/main/resources/application.yaml` (line 73), `src/main/resources/application-dev.yaml` (line 73)
- Current mitigation: Actual secrets use env-var placeholders (`${SPRING_MAIL_PASSWORD}`). Dev profile uses `dev_*` fallback values.
- Recommendations: Remove all plaintext credential comments. Add git-secrets or detect-secrets pre-commit hook to prevent future leakage.

**All Actuator Endpoints Exposed (application.yaml):**
- Risk: `management.endpoints.web.exposure.include: "*"` in the main `application.yaml` exposes every actuator endpoint including `/manage/env`, `/manage/beans`, `/manage/heapdump`. The dev profile correctly narrows this to `health,info,env` — but the main profile is the fallback.
- Files: `src/main/resources/application.yaml` (line 126), `src/main/resources/application-dev.yaml` (lines 130-144)
- Current mitigation: Dev profile is more restrictive. Some actuator endpoints require ROLE_ADMIN.
- Recommendations: Restrict the base `application.yaml` to `health,info` and protect remaining endpoints. Never expose `heapdump`, `shutdown`, or `beans` without authentication.

**JMX Enabled in Main Profile:**
- Risk: `spring.jmx.enabled: true` in `application.yaml` exposes management beans over JMX, which can allow remote code execution if JMX is not properly firewalled.
- Files: `src/main/resources/application.yaml` (line 25)
- Current mitigation: Dev profile disables JMX (`spring.jmx.enabled: false`).
- Recommendations: Disable JMX in `application.yaml` by default; enable only where specifically needed with authentication.

**SSL Certificate Verification Disabled for MoMo Client:**
- Risk: `checkCertificate: false` in `defaultTcpConfig` means TLS certificate verification is disabled for the MoMo payment API client. This allows man-in-the-middle attacks against all payment API calls.
- Files: `src/main/resources/application.yaml` (line 147), `src/main/java/com/softropic/payam/common/client/TcpConfiguration.java`
- Current mitigation: Only applies to sandbox environment (`X-Target-Environment: sandbox`).
- Recommendations: Enable certificate checking (`checkCertificate: true`) before any production deployment. Create a separate production profile config.

**Session ID Not Validated Against JWT Claims:**
- Risk: The `getSessionId()` method in `RequestMetadataProvider` reads the JWT session cookie value but contains a TODO noting session ID validation against JWT claims is not yet implemented. Session fixation attacks are possible.
- Files: `src/main/java/com/softropic/payam/security/common/util/RequestMetadataProvider.java` (line 81)
- Current mitigation: Session cookie is tracked but not cryptographically bound to the JWT.
- Recommendations: Extract session ID from JWT claims and compare with cookie value in `getSessionId()`.

**CORS Allows All Methods and Headers:**
- Risk: Both `application.yaml` and `application-dev.yaml` set `allowed-methods: '*'` and `allowed-headers: '*'`. In production this may allow cross-origin requests with dangerous HTTP methods.
- Files: `src/main/resources/application.yaml` (lines 84-89)
- Current mitigation: Config is commented as "Just for dev"; allowed origins are still localhost-scoped.
- Recommendations: Create a production profile that lists explicit allowed methods (GET, POST, PUT, DELETE) and required headers only.

**Allowed Machine Clients Hardcoded in Config:**
- Risk: `allowed.clients: myClientId,hisClientId,herClientId,ourClientId` in both config files uses placeholder values. `ClientIdAccessDecisionManager` reads this list at startup and cannot change it without restarting the service.
- Files: `src/main/resources/application.yaml` (line 117), `src/main/java/com/softropic/payam/security/service/ClientIdAccessDecisionManager.java` (lines 29, 32)
- Current mitigation: The list is externalized to config rather than hardcoded in Java.
- Recommendations: Move allowed client list to database as noted in the TODO comments, so it can be managed at runtime without restarts.

**Fingerprint Cookie Missing HttpOnly/Secure Flags:**
- Risk: The browser fingerprint cookie (`fcookie`) is set via `document.cookie` in JavaScript without `HttpOnly` or `Secure` flags, making it readable by any JavaScript on the page and susceptible to theft over HTTP.
- Files: `src/frontend/src/boot/axios.js` (line 69)
- Current mitigation: Cookie is used for fraud detection only, not authentication.
- Recommendations: Set the cookie via the backend response with `HttpOnly` and `Secure` flags, or accept the limitation and document it.

---

## Tech Debt

**In-Memory Login Attempt Tracking (Not Multi-Node Ready):**
- Issue: `LoginAttemptsService` uses Guava in-memory `LoadingCache` for all login attempt tracking (by user, IP, and client). This state is local to each JVM instance.
- Files: `src/main/java/com/softropic/payam/security/service/LoginAttemptsService.java` (line 38)
- Impact: In a multi-node deployment, an attacker can bypass brute-force protection by distributing login attempts across application nodes. Each node sees only its own failure count.
- Fix approach: Replace in-memory caches with distributed cache (Redis/Hazelcast) or a database-backed approach as documented in the class-level TODO.

**In-Memory Client Blacklisting Not Persisted:**
- Issue: `blacklistClient()` in `LoginAttemptsService` stores blacklisted clients in a Guava `LoadingCache` with a 30-day expiry. Blacklisting is lost on restart and not shared between nodes.
- Files: `src/main/java/com/softropic/payam/security/service/LoginAttemptsService.java` (lines 183-191)
- Impact: Blacklisted clients regain access after application restart or in multi-node deployments.
- Fix approach: Persist blacklisted clients to the database as noted in the TODO comment.

**SMS Registration Strategy Is a Stub:**
- Issue: `SmsRegistrationStrategy` has no real implementation. Both `notifyNewUser()` and `notifyUserExists()` log a warning and return a random UUID. Phone-based registration cannot actually deliver activation codes.
- Files: `src/main/java/com/softropic/payam/security/api/registration/SmsRegistrationStrategy.java`
- Impact: Any user who registers with a phone number (`LoginIdType.PHONE`) will never receive an activation SMS, leaving their account stuck in inactive state.
- Fix approach: Integrate an SMS gateway (Twilio, AWS SNS, etc.) as described in the class-level Javadoc requirements list.

**`InputValidator.isValidName()` Not Used in Registration:**
- Issue: `isValidName()` is implemented and tested but has a TODO noting it "should be used in the registration process as well." User-submitted names are validated only by JPA annotations, not by the full name validation logic.
- Files: `src/main/java/com/softropic/payam/common/validation/InputValidator.java` (line 70)
- Impact: Names with reserved/blocked words, pure hyphens, or malformed Unicode may be accepted at registration.
- Fix approach: Call `InputValidator.isValidName()` within the registration request DTO validation or add it to `NameValidator`.

**`RequestIdProvider` and `RequestMetadataProvider` Overlap:**
- Issue: Two parallel classes handle request context propagation. `RequestMetadataProvider` calls `RequestIdProvider` internally and contains two TODOs to merge the two.
- Files: `src/main/java/com/softropic/payam/security/common/util/RequestMetadataProvider.java` (lines 41, 52), `src/main/java/com/softropic/payam/security/common/util/RequestIdProvider.java`
- Impact: Duplicated cleanup paths; risk of a future developer cleaning up one but not the other in async contexts.
- Fix approach: Consolidate `RequestIdProvider` into `RequestMetadataProvider` as the TODO comments prescribe.

**`TimeGuru` Static Utility Not Using Injected Clock:**
- Issue: `TimeGuru` is a static utility class with a TODO noting it should be moved to a `DateTimeService` if possible. It does not accept a `ClockProvider`, making time-based tests harder to write.
- Files: `src/main/java/com/softropic/payam/common/TimeGuru.java` (line 12)
- Impact: Code depending on `TimeGuru` cannot be tested with a controllable clock.
- Fix approach: Move time logic into an injectable `DateTimeService` that accepts `ClockProvider`.

**`CommonConfig` Not Using `ClockProvider`:**
- Issue: A TODO in `CommonConfig` notes it should "consider using clockProvider" but does not yet, creating inconsistency — some code uses `ClockProvider`, other code uses `LocalDateTime.now()` directly.
- Files: `src/main/java/com/softropic/payam/common/config/CommonConfig.java` (line 20)
- Impact: Time-dependent beans created in `CommonConfig` cannot be controlled in tests.
- Fix approach: Inject `ClockProvider` into `CommonConfig`.

**`DbSchemaChecker` Using `@PostConstruct` Instead of Constructor:**
- Issue: A TODO notes that schema-check logic in `@PostConstruct` should be moved to the parameterless constructor with the annotation removed.
- Files: `src/main/java/com/softropic/payam/common/persistence/DbSchemaChecker.java` (line 32)
- Impact: Minor: `@PostConstruct` works but is harder to test and creates a hidden initialization dependency.
- Fix approach: Move logic to the constructor and remove `@PostConstruct`.

**`RestTemplate` Used Instead of `RestClient`/`WebClient`:**
- Issue: `AbstractClient` constructs a synchronous `RestTemplate` directly (`new RestTemplate(new SimpleClientHttpRequestFactory())`). `RestTemplate` is in maintenance mode as of Spring 6.
- Files: `src/main/java/com/softropic/payam/common/client/AbstractClient.java` (line 49)
- Impact: No functional breakage now, but diverges from the Spring-recommended reactive/non-blocking `RestClient` pattern and will require migration eventually.
- Fix approach: Migrate to `RestClient` (synchronous, Spring 6.1+) or `WebClient` for reactive use cases.

**Duplicate Notification Guard Missing:**
- Issue: `AccountManagementFacade.registerAccount()` has a TODO noting the messaging module "should avoid sending duplicate notifications to the same user within 5 minutes." No deduplication guard is implemented.
- Files: `src/main/java/com/softropic/payam/security/api/AccountManagementFacade.java` (line 122)
- Impact: A user or attacker could trigger multiple activation emails/SMS within seconds by calling `/v1/account/register` repeatedly.
- Fix approach: Add a time-based deduplication check in `AccountManagementFacade` or within each `RegistrationNotificationStrategy`.

---

## Known Bugs

**User Not Assigned ROLE_USER on Registration (Open Issue):**
- Symptoms: Documented in `readme_checklist.md` — "When a user registers, he should be assigned the ROLE_USER authority."
- Files: `src/main/java/com/softropic/payam/security/service/UserRegistrationService.java` (line 44)
- Trigger: `authorityRepository.findOneByName(SecurityConstants.ROLE_USER).orElse(null)` returns `null` if the `ROLE_USER` row is absent from the `authority` table (e.g., on a fresh database without seed data). The `null` authority is added to the user's authority set and persisted, resulting in a user with no valid role.
- Workaround: Ensure the authority seed data is present in the database before registration.

**`LoginAttemptsService.isAllowed()` Fails Open on Cache Error:**
- Symptoms: Any `ExecutionException` from the Guava cache causes `isAllowed()` to return `true`, bypassing brute-force protection entirely.
- Files: `src/main/java/com/softropic/payam/security/service/LoginAttemptsService.java` (lines 131-134)
- Trigger: `ExecutionException` thrown by `cache.get()` on a cache loading error.
- Workaround: None. The fail-open is intentional but undocumented as a risk.

**`SecondFactorLoginFilter` Returns 200 Instead of Proper Status for 2FA:**
- Symptoms: A TODO marks the HTTP status code for the second-factor response as unresolved (`//TODO 201? 200? 202?`), suggesting the current 200 may not be semantically correct for all clients.
- Files: `src/main/java/com/softropic/payam/security/infrastructure/filter/SecondFactorLoginFilter.java` (line 101)
- Trigger: When 2FA login completes.
- Workaround: Frontend currently handles this as a 200, so it works in practice.

---

## Performance Bottlenecks

**Synchronous Email Sending with Circuit Breaker on Request Thread:**
- Problem: `MailManager.sendEmailSync()` is called by `EmailRetryScheduler` on a scheduler thread, but the `CircuitBreaker.run()` call inside is synchronous and blocking. In the scheduler's transaction, any slow SMTP provider holds a DB transaction open.
- Files: `src/main/java/com/softropic/payam/email/service/MailManager.java` (lines 58-112), `src/main/java/com/softropic/payam/email/infrastructure/EmailRetryScheduler.java`
- Cause: The circuit breaker and retry logic are synchronous within `@Transactional` context.
- Improvement path: Decouple SMTP send from the DB transaction; update status after send completes outside the transaction boundary.

**`FETCH EAGER` on `addresses` Element Collection:**
- Problem: `Customer.addresses` is declared `FetchType.EAGER` as an `@ElementCollection`. Every query loading a `Customer` or `User` entity will always join and load all addresses, even when only login or email is needed.
- Files: `src/main/java/com/softropic/payam/security/repo/Customer.java` (line 76)
- Cause: `@ElementCollection` defaults to `EAGER` in some contexts; here it is made explicit.
- Improvement path: Change to `FetchType.LAZY` and load addresses only when needed.

**Batch Size for DB Operations is 15:**
- Problem: `hibernate.jdbc.batch_size: 15` is a conservative batch size. For bulk operations (e.g., email envelope inserts), this results in many round trips.
- Files: `src/main/resources/application.yaml` (line 37)
- Cause: Conservative default.
- Improvement path: Profile bulk insert workloads and tune upward (50-100 is common for PostgreSQL).

---

## Fragile Areas

**`SecurityConfiguration.fraudAwareAuthenticationManager()` Missing `@Bean`:**
- Files: `src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java` (line 106)
- Why fragile: `fraudAwareAuthenticationManager()` is called directly by `filterChain()` as a plain method call rather than being a Spring bean. If Spring tries to proxy or instrument this method, the `@SuppressWarnings("PMD")` suppression suggests the author knows this is unusual. Spring Security filter chain wiring is complex; this manual wiring is easy to break when refactoring.
- Safe modification: Do not add `@Bean` without first understanding why it was omitted. Test the full auth chain in integration tests after any `SecurityConfiguration` changes.
- Test coverage: `SecurityIT.java` and `SecurityFilterChainIT.java` cover the chain, but coverage of the 2FA and refresh paths may be incomplete.

**`LoginAttemptsService` In-Memory State During Tests:**
- Files: `src/main/java/com/softropic/payam/security/service/LoginAttemptsService.java`
- Why fragile: Tests inject a `Ticker` to control time, but the caches are created in the constructor; if the Spring container caches a `@Service` singleton across tests, attempt counts from one test bleed into the next.
- Safe modification: Reset caches between integration tests via `unlockUser()` and `unblacklistClient()` or use `@DirtiesContext`.
- Test coverage: `LoginAttemptsServiceTest.java` covers unit behavior; no obvious integration-level reset between tests was found.

**`activation_key` Still Present in JWT `toString()` Output:**
- Files: `src/main/java/com/softropic/payam/security/repo/User.java` (line 336)
- Why fragile: `User.toString()` logs `activationKey` and `resetKey` in plaintext. Any code path that logs a `User` object leaks sensitive keys to the log output. `BodySanitizer` handles JSON request/response sanitization but does not cover entity `toString()` calls in logs.
- Safe modification: Override `toString()` to redact both `activationKey` and `resetKey`.
- Test coverage: No test verifies that `User.toString()` does not log sensitive fields.

**Duplicate `equals()` / `hashCode()` Contracts Across Entity Hierarchy:**
- Files: `src/main/java/com/softropic/payam/common/persistence/BaseEntity.java`, `src/main/java/com/softropic/payam/security/repo/Customer.java`, `src/main/java/com/softropic/payam/security/repo/User.java`
- Why fragile: Three different `equals()`/`hashCode()` strategies are in use across the entity hierarchy. `BaseEntity` uses `id`-based equality. `Customer` uses `nationalId` or name+DOB. `User` overrides again to use `login`. `hashCode()` returns `getClass().hashCode()` (constant) in `Customer` and `User`, but `Objects.hashCode(id)` in `BaseEntity`. Mixing entities across collections that use different equality definitions is unpredictable.
- Safe modification: Choose one equality contract per entity and document it. Consider using only `id`-based equality once IDs are guaranteed to be assigned before collection insertion.

---

## Scaling Limits

**PostgreSQL Connection Pool Assumes Max 4 Nodes:**
- Current capacity: `maximum-pool-size: 25` per application instance.
- Limit: PostgreSQL default is 100 connections. With 25 per node, the current config supports exactly 4 nodes. Adding a fifth node exhausts the PostgreSQL connection limit.
- Scaling path: Introduce PgBouncer connection pooler in front of PostgreSQL, or increase `max_connections` in PostgreSQL configuration.

**In-Memory Rate Limiting Not Shared Across Nodes:**
- Current capacity: `@RateLimited` aspect uses in-memory token buckets (see `RateLimitingService`).
- Limit: Each application instance tracks its own rate limit counts. In a multi-node setup, an attacker can bypass per-IP/per-key rate limits by distributing requests across nodes.
- Scaling path: Replace in-memory buckets with Redis-backed rate limiting (Bucket4j Redis or similar).

---

## Dependencies at Risk

**`axios ^1.2.1` Has Multiple Known Vulnerabilities:**
- Risk: As documented in `docs/owasp-violations.md`, axios 1.2.1 is vulnerable to SSRF, DoS, ReDoS, and CSRF.
- Impact: Frontend HTTP requests are exposed to these vulnerabilities; SSRF in particular could be exploited to make server-side requests to internal services.
- Migration plan: Upgrade to the latest stable axios release (1.7.x or newer). Run `npm audit` after upgrading to confirm resolution.

**Spring Boot 3.5.11 (Active; Watch for 3.6+ Advice):**
- Risk: The OWASP violations doc references CVE-2024-38807 for Spring Boot before 3.6.0. The current `pom.xml` uses `3.5.11` which is not yet 3.6.0.
- Impact: Signature forgery vulnerability in the Boot parent.
- Migration plan: Monitor Spring Boot 3.6.x release stability and plan upgrade. Review release notes for breaking changes.

**`jjwt 0.12.6` — Prefer 0.12.7+:**
- Risk: `docs/owasp-violations.md` notes jjwt 0.12.7 fixed CVE-2023-5072 (empty custom claims issue).
- Impact: Unexpected behavior in token validation with empty custom claims.
- Migration plan: Upgrade `io.jsonwebtoken:jjwt-*` to 0.12.7 or later in `pom.xml`.

---

## Missing Critical Features

**No Disposable Email Domain Blocking:**
- Problem: `InputValidator.isValidEmail()` uses Apache Commons `EmailValidator` which only checks format, not domain reputation. The class Javadoc explicitly references several disposable email detection services that are not yet integrated.
- Files: `src/main/java/com/softropic/payam/common/validation/InputValidator.java` (lines 104-111)
- Blocks: Registrations with throwaway email addresses can pollute the user base.

**No Duplicate Registration Guard (firstName + lastName + dateOfBirth):**
- Problem: `Customer` has a TODO noting a unique constraint on `firstName + lastName + dateOfBirth` should be added to prevent the same person from registering twice with a different email.
- Files: `src/main/java/com/softropic/payam/security/repo/Customer.java` (line 35)
- Blocks: A user can create multiple accounts for the same physical person, undermining identity verification.

**No Login History Tracking:**
- Problem: `User` entity has a TODO for a `lastLogins` collection (login time, session ID, explicit logout flag, IP metadata). No login history is currently persisted.
- Files: `src/main/java/com/softropic/payam/security/repo/User.java` (line 46)
- Blocks: Audit requirements, account security notifications ("new login detected"), and forensic investigation after suspicious activity.

**No `nationalId` Validation:**
- Problem: `Customer.nationalId` is a plain `String` with no length constraint, no format validation, and no uniqueness constraint at the database level.
- Files: `src/main/java/com/softropic/payam/security/repo/Customer.java` (line 81)
- Blocks: National ID-based duplicate account prevention and KYC verification flows.

---

## Test Coverage Gaps

**No Frontend Tests:**
- What's not tested: All Vue/Quasar components, composables, API modules, and the session manager plugin.
- Files: `src/frontend/` — `package.json` `test` script prints "No test specified" and exits.
- Risk: Session management logic (`src/frontend/src/plugins/sessionManager.js`), error handling (`src/frontend/src/composables/useErrorHandler.js`), and all API calls could regress silently.
- Priority: High — auth and session flows are security-critical.

**No Tests for `SecurityConfiguration` Filter Chain Wiring:**
- What's not tested: The manual `fraudAwareAuthenticationManager()` construction and the non-`@Bean` pattern.
- Files: `src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java`
- Risk: Refactoring the filter chain or adding new filters could silently break authentication without a failing test.
- Priority: High.

**No Tests for `SmsRegistrationStrategy`:**
- What's not tested: The entire SMS notification path for phone-based registration.
- Files: `src/main/java/com/softropic/payam/security/api/registration/SmsRegistrationStrategy.java`
- Risk: When SMS is implemented, there will be no regression baseline.
- Priority: Medium — implement when SMS integration is built.

**`AuthorizationFailureListener` Is Untested:**
- What's not tested: The `@SuppressWarnings("PMD")`-marked `AuthorizationFailureListener` contains a "TODO test this" comment.
- Files: `src/main/java/com/softropic/payam/security/infrastructure/listener/AuthorizationFailureListener.java` (line 28)
- Risk: Authorization failure events may not be handled or logged correctly.
- Priority: Medium.

---

*Concerns audit: 2026-03-21*
