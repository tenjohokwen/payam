# Testing Patterns

**Analysis Date:** 2026-03-06

## Overview

Testing is Java-only. The Vue/Quasar frontend has no test files. All tests live under `src/test/java/com/softropic/payam/`.

---

## Test Framework

**Runner:**
- JUnit 5 (Jupiter) — via `spring-boot-starter-test` (Spring Boot 3.5)

**Assertion Library:**
- AssertJ 3.24.2 — primary assertions: `assertThat(...)`, `assertThatThrownBy(...)`, `assertThatCode(...)`
- JSON-Unit AssertJ 4.1.0 — for JSON assertions (available but not observed in reviewed tests)

**Mocking:**
- Mockito (via `spring-boot-starter-test`) — `@Mock`, `MockitoAnnotations.openMocks(this)`, `MockedStatic`

**Database:**
- Testcontainers with PostgreSQL 14.18 — real database per test class
- datasource-proxy (`net.ttddyy:datasource-proxy:1.10`) — optional SQL query recording/assertion
- `@Sql` annotation — loads fixture SQL scripts before each test

**Test Data:**
- Instancio 2.10.0 — random object generation: `Instancio.create(User.class)`
- `instancio.properties` at `src/test/resources/instancio.properties` with `bean.validation.enabled=true`

**Async Assertions:**
- Awaitility 4.2.0 — used in integration tests to wait for async email delivery: `await().until(...)`

**Run Commands:**
```bash
./mvnw test                   # Unit tests only
./mvnw verify                 # Unit + integration tests (failsafe plugin)
./mvnw test -pl . -Dtest=LoginAttemptsServiceTest   # Single test class
```

---

## Test File Organization

**Location:** All tests co-located with test package mirroring the main package structure:
```
src/test/java/com/softropic/payam/
├── config/
│   ├── TestConfig.java               # Shared @TestConfiguration (Postgres container, mocks)
│   ├── CustomPostgresContainer.java
│   └── ApplicationNoSecurity.java
├── security/
│   ├── SecurityIT.java               # Full HTTP integration tests
│   ├── SecurityFilterChainIT.java
│   ├── jwt/api/
│   │   ├── JwtManagerImplTest.java   # Unit test
│   │   └── filter/
│   │       ├── JWTAuthenticationFilterTest.java
│   │       └── JWTAuthorizationFilterTest.java
│   ├── manager/
│   │   └── LoginAttemptsServiceTest.java   # Unit test
│   ├── api/
│   │   ├── util/InputValidatorTest.java
│   │   └── ratelimit/
│   │       ├── RateLimitingServiceTest.java  # Unit test
│   │       └── RateLimitingAspectIT.java     # Integration test
│   ├── service/
│   │   ├── UserServiceIT.java
│   │   └── PasswordResetIT.java
│   ├── secret/SecretServiceIT.java
│   └── repo/UserRepositoryIT.java
└── utils/
    ├── DbCleaner.java
    ├── TestMailManager.java           # In-memory MailManager stub
    ├── MockServletInputStream.java
    ├── KeyGen.java
    └── sql/                          # SQL query assertion utilities
        ├── SqlQuery.java
        ├── SqlStatementHolder.java
        ├── QueryRecorderListener.java
        ├── EntityFetchAsserter.java
        └── matcher/
            ├── AtLeast.java
            ├── Times.java
            ├── CountStrategy.java
            └── CountStrategyFactory.java
```

**Naming:**
- Unit tests: `{ClassUnderTest}Test.java` — e.g., `LoginAttemptsServiceTest.java`
- Integration tests: `{ClassName}IT.java` — e.g., `SecurityIT.java`, `UserServiceIT.java`
- `IT` suffix triggers maven-failsafe-plugin for integration test lifecycle

---

## Test Structure

**Unit Test Pattern:**

```java
class LoginAttemptsServiceTest {

    private LoginAttemptsService loginAttemptsService;

    @BeforeEach
    void setUp() {
        defaultDecisionVoter = new ClientIdAccessDecisionManager(List.of());
        loginAttemptsService = new LoginAttemptsService(defaultDecisionVoter, Ticker.systemTicker());
        resetTestRequestMetadataProvider();
    }

    @AfterEach
    void tearDown() {
        resetTestRequestMetadataProvider();
    }

    @Test
    void givenClientUser_whenMaxAttemptsExceeded_thenBlocked() {
        simulateFailedLogins(MAX_FAILED_CLIENT_ATTEMPTS - 1);
        assertLoginAllowed(true, "Allowed before reaching client-user max attempts");

        simulateFailedLogins(1);
        assertLoginAllowed(false, "Blocked after reaching client-user max attempts");
    }
}
```

**Test method naming convention:** `given{Context}_when{Action}_then{Outcome}` (BDD style):
- `givenClientUser_whenMaxAttemptsExceeded_thenBlocked`
- `givenFailedAttempts_whenLoginSucceeds_thenAttemptsClearedAndLoginAllowed`
- `givenAttemptsMade_whenCacheExpires_thenAttemptCountResets`

Some tests use plain descriptive names:
- `createUser`, `activateUser`, `testPasswordReset`, `lockUserAccount`

**Spring Integration Test Pattern:**

```java
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
public class SecurityIT {

    @LocalServerPort
    int randomServerPort;

    @Autowired
    private HttpTestClient httpTestClient;

    @AfterEach
    void tearDown() {
        template.execute(status -> {
            jdbcTemplate.execute("delete from main.sec");
            jdbcTemplate.execute("delete from main.user");
            // ... other tables
            return 0;
        });
    }
}
```

---

## Mocking

**Framework:** Mockito via `MockitoAnnotations.openMocks(this)` (no `@ExtendWith(MockitoExtension.class)` — manual init used)

**Instance mock pattern:**

```java
@Mock
private SecretService secretService;

@BeforeEach
void setUp() {
    MockitoAnnotations.openMocks(this);
    when(secretService.fetchSecret(anyString(), anyString())).thenReturn(secret);
}

@AfterEach
void tearDown() {
    reset(secretService);
}
```

**Static mock pattern (for `RequestMetadataProvider` which uses ThreadLocal):**

```java
try (MockedStatic<RequestMetadataProvider> mockedStatic = mockStatic(RequestMetadataProvider.class)) {
    mockedStatic.when(RequestMetadataProvider::getClientInfo).thenReturn(mockMetadata);
    // assertions within the try block
}
```

**What to mock:**
- Infrastructure services (`SecretService`, `MailManager`) in unit tests
- Static utility providers that read from ThreadLocal (`RequestMetadataProvider`)
- HTTP context objects (`HttpServletRequest`, `HttpServletResponse`) via Spring's `MockHttpServletRequest`/`MockHttpServletResponse`

**What NOT to mock:**
- Real service classes under test — construct directly with test-specific constructor
- Database access in integration tests — use real Testcontainers PostgreSQL instead
- `MailManager` in integration tests — replaced with `TestMailManager` stub via `@TestConfiguration`

---

## Shared Test Infrastructure

**`TestConfig.java` at `src/test/java/com/softropic/payam/config/TestConfig.java`:**
- `@TestConfiguration(proxyBeanMethods = false)`
- Spins up a `PostgreSQLContainer` using `postgres:14.18` image with `@ServiceConnection`
- Registers `TestMailManager` as `@Primary` `MailManager` bean when `enable.test.mail=true`
- Registers `EntityFetchAsserter` for custom SQL query count assertions
- Registers optional `spyDataSource` for slow query logging when `log.database.spy=true`

**`TestMailManager.java` at `src/test/java/com/softropic/payam/utils/TestMailManager.java`:**
- In-memory implementation of `MailManager` interface
- Stores sent envelopes by `helpCode` for assertion in tests
- Used with Awaitility: `await().until(() -> testMailManager.getEnvelope(helpCode) != null)`

**`TestRequestMetadataProvider.java` at `src/test/java/com/softropic/payam/security/exposed/util/TestRequestMetadataProvider.java`:**
- Thread-local metadata provider for unit tests that need to simulate HTTP request context
- Set values: `TestRequestMetadataProvider.setUserName(...)`, `setBrowserCookie(...)`, `setIpAddress(...)`

**`TestClockProvider` (`src/main/java/com/softropic/payam/common/TestClockProvider.java`):**
- Allows freezing or controlling the application clock in tests
- Used in `JwtManagerImplTest` to test token expiry scenarios: `TestClockProvider.setSystemClock()`

---

## Fixtures and Factories

**SQL Fixture files at `src/test/resources/sql/`:**
- `createSchema.sql` — schema creation (run once via Testcontainers `withInitScript`)
- `secData.sql` — security configuration seed data
- `userData.sql` — user records including `me@yahoo.com`, `queb@yahoo.com` (admin), `locked@yahoo.com`, `not-activated@yahoo.com`
- `authorityData.sql` — authority records (`ROLE_USER`, `ROLE_ADMIN`)
- `account.sql` — account-specific seed data
- `cleanup.sql`, `dropAllTables.sql` — teardown helpers

**SQL loading in tests:**
```java
// Class-level default
@Sql({SecurityIT.SEC_DATA_SQL_PATH})

// Method-level override to load additional data
@Test
@Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
void createUser() { ... }
```

**In-code factory methods:**
```java
// Integration tests build test users inline
private UserDto getUserData(boolean otpEnabled) {
    final UserDto userDto = new UserDto();
    userDto.setEmail("figu@yahoo.com");
    userDto.setPassword("admin*123!");
    // ...
    return userDto;
}
```

**Instancio random generation:**
```java
// Random object generation for fields not relevant to the test
final User user = Instancio.create(User.class);
user.setId(null);
user.setEmail("me@yahoo.com"); // override specific fields
```

---

## Database Teardown Pattern

All integration test classes manually clean dependent tables in `@AfterEach` using `TransactionTemplate`:

```java
@AfterEach
void tearDown() {
    template.execute(status -> {
        jdbcTemplate.execute("delete from main.sec");
        jdbcTemplate.execute("delete from main.user_addresses");
        jdbcTemplate.execute("delete from main.user_authority");
        jdbcTemplate.execute("delete from main.authority");
        jdbcTemplate.execute("delete from main.user");
        jdbcTemplate.execute("delete from main.audit_log");
        return 0;
    });
}
```

Delete order respects FK constraints (child tables before parent).

---

## Parameterized Tests

JUnit 5 parameterized tests used for scenario variation:

```java
// Enum-sourced parameters
@ParameterizedTest
@EnumSource(value = Credentials.class,
            names = {"INVALID_EMAIL", "ARBITRARY_EMAIL", "INVALID_PASSWORD"})
void loginWithWrongCredentials(Credentials credentials) throws JsonProcessingException { ... }

// Method-sourced parameters
@ParameterizedTest
@MethodSource("provideTokenScenarios")
void someTokenTest(Arguments args) { ... }
```

Test data enums defined as inner classes within the test class (e.g., `Credentials` enum in `SecurityIT`).

---

## Coverage

**Requirements:** No coverage threshold configured (no JaCoCo or Surefire coverage config detected).

**View Coverage:**
```bash
./mvnw test jacoco:report   # if JaCoCo were configured
```

---

## Test Types

**Unit Tests (`*Test.java`):**
- Scope: Single class under test, all dependencies mocked or constructed directly
- Spring context: NOT loaded
- Database: NOT used
- Examples: `LoginAttemptsServiceTest`, `RateLimitingServiceTest`, `JwtManagerImplTest`

**Integration Tests (`*IT.java`):**
- Scope: Full Spring context with real database via Testcontainers
- Spring context: Full `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- Database: PostgreSQL 14.18 in Docker via Testcontainers
- HTTP: Real HTTP calls using `HttpTestClient` (wrapper around Spring's `RestTemplate`)
- Lifecycle: Run by maven-failsafe-plugin during `verify` phase
- Examples: `SecurityIT`, `UserServiceIT`, `RateLimitingAspectIT`

**E2E Tests:** Not present.

**Frontend Tests:** Not present (no Vitest, Cypress, or Playwright configuration found).

---

## Common Patterns

**Async Testing:**
```java
// Wait for email to be delivered asynchronously
await().until(() -> testMailManager.getEnvelope(helpCode) != null);
final Envelope envelope = testMailManager.getEnvelope(helpCode);
final String activationKey = (String) envelope.data().get("activationKey");
```

**Exception Testing:**
```java
// Test for specific exception + HTTP status
assertThatThrownBy(() -> httpTestClient.makeHttpRequest(uri, HttpMethod.POST, body, headers, Map.class))
    .isInstanceOf(HttpClientErrorException.class)
    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);

// Test for exception + response body content
assertThatThrownBy(() -> ...)
    .isInstanceOf(HttpClientErrorException.class)
    .satisfies(e -> {
        HttpClientErrorException ex = (HttpClientErrorException) e;
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponseBodyAsString()).contains("security.accLocked");
    });

// Test that no exception is thrown
assertThatCode(() -> testService.limitedMethod()).doesNotThrowAnyException();
```

**Security Context Setup for Integration Tests (non-HTTP path):**
```java
private void initSecurityContext() {
    final Principal principal = createPrincipal();
    var token = new UsernamePasswordAuthenticationToken(
        principal.getUsername(), null, principal.getAuthorities());
    token.setDetails(principal);
    SecurityContextHolder.setContext(new SecurityContextImpl(token));
}

// Always clear in @AfterEach
SecurityContextHolder.clearContext();
```

**Time Control:**
```java
// Use FakeTicker to control Guava cache expiry
FakeTicker fakeTicker = new FakeTicker();
LoginAttemptsService expiringService = new LoginAttemptsService(defaultDecisionVoter, fakeTicker);
fakeTicker.advance(4, TimeUnit.HOURS);
fakeTicker.advance(1, TimeUnit.MINUTES);
```

---

*Testing analysis: 2026-03-06*
