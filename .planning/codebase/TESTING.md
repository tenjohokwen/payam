# Testing Patterns

**Analysis Date:** 2026-03-21

## Test Framework

**Runner:**
- JUnit 5 (Jupiter), managed via `spring-boot-starter-test` (Spring Boot BOM)
- Config: no separate config file; test execution driven by Maven Surefire/Failsafe

**Assertion Library:**
- AssertJ 3.24.2 (`assertj-core`) — primary assertion library for all tests
- `json-unit-assertj` 4.1.0 — JSON-aware assertions
- JUnit 5 `Assertions` used in some older unit tests (prefer AssertJ)

**Mocking:**
- Mockito (version managed by Spring Boot BOM)
- `MockedStatic` available for static method mocking
- `guava-testlib` 33.4.8-jre — provides `FakeTicker` for time-sensitive cache tests

**Async:**
- Awaitility 4.2.0 — polling assertions for async/event-driven flows

**Test Data:**
- Instancio 2.10.0 (`instancio-core`) — random object generation
- Config: `src/test/resources/instancio.properties` (`bean.validation.enabled=true`)
- SQL fixture files in `src/test/resources/sql/`

**Run Commands:**
```bash
mvn test                  # Unit tests only (*Test.java)
mvn verify                # Unit + integration tests (*IT.java)
mvn test -pl .            # Run from project root
```

## Test File Organization

**Location:** Co-located under `src/test/java/` mirroring the `src/main/java/` package structure.

**Naming:**
- `*Test.java` — unit tests; run by Maven Surefire
- `*IT.java` — integration tests; run by Maven Failsafe (require Docker for Testcontainers)

**Directory layout:**
```
src/test/
├── java/com/softropic/payam/
│   ├── config/
│   │   ├── TestConfig.java                   # Shared @TestConfiguration for integration tests
│   │   ├── CustomPostgresContainer.java       # UTC-pinned PostgreSQL container wrapper
│   │   └── ApplicationNoSecurity.java
│   ├── utils/
│   │   ├── TestMailManager.java               # In-memory MailManager stub
│   │   ├── DbCleaner.java                     # Programmatic table-delete helper
│   │   ├── MockServletInputStream.java
│   │   └── sql/
│   │       ├── EntityFetchAsserter.java        # JPA lazy/eager load assertion helper
│   │       ├── QueryRecorderListener.java      # datasource-proxy listener (SQL capture)
│   │       ├── SqlStatementHolder.java         # ThreadLocal SQL statement store
│   │       ├── Statement.java
│   │       ├── SqlQuery.java / SelectQuery.java / InsertQuery.java / ...
│   │       └── matcher/
│   │           ├── AtLeast.java
│   │           ├── Times.java
│   │           └── CountStrategyFactory.java
│   ├── common/
│   │   └── TransactionExceptionSimulator.java
│   ├── security/
│   │   ├── SecurityIT.java
│   │   ├── SecurityFilterChainIT.java
│   │   ├── api/
│   │   │   ├── AccountManagementFacadeIT.java
│   │   │   ├── AdminLoginResourceTest.java
│   │   │   └── ratelimit/
│   │   │       ├── RateLimitingAspectIT.java
│   │   │       └── RateLimitingServiceTest.java
│   │   ├── service/
│   │   │   ├── UserServiceIT.java
│   │   │   ├── UserProfileServiceIT.java
│   │   │   ├── PasswordResetIT.java
│   │   │   ├── LoginInfoServiceIT.java
│   │   │   ├── LoginAttemptsServiceTest.java
│   │   │   └── SecretServiceIT.java
│   │   ├── repo/
│   │   │   └── UserRepositoryIT.java
│   │   └── infrastructure/jwt/
│   │       ├── JwtManagerImplTest.java
│   │       └── filter/
│   │           ├── JWTAuthenticationFilterTest.java
│   │           └── JWTAuthorizationFilterTest.java
│   └── email/
│       └── infrastructure/
│           ├── MailManagerIT.java
│           ├── EmailRetrySchedulerIT.java
│           ├── EmailRetrySchedulerTest.java
│           └── MailManagerResilienceTest.java
└── resources/
    ├── instancio.properties
    └── sql/
        ├── createSchema.sql      # Init script run once by Testcontainers
        ├── authorityData.sql
        ├── userData.sql
        ├── secData.sql
        ├── account.sql
        ├── initTestData.sql
        ├── cleanup.sql
        └── dropAllTables.sql
```

## Integration Test Structure

All integration tests share a common setup pattern:

```java
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@Sql({UserServiceIT.SEC_DATA_SQL_PATH})          // class-level: runs before each test
class UserServiceIT {

    public static final String SEC_DATA_SQL_PATH      = "/sql/secData.sql";
    public static final String AUTHORITY_SQL_PATH     = "/sql/authorityData.sql";
    public static final String USER_DATA_SQL_PATH     = "/sql/userData.sql";

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        template.execute(status -> {
            jdbcTemplate.execute("delete from main.sec");
            jdbcTemplate.execute("delete from main.user_authority");
            jdbcTemplate.execute("delete from main.authority");
            jdbcTemplate.execute("delete from main.user");
            return 0;
        });
    }

    @Test
    @Sql({AUTHORITY_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})  // method-level overrides or adds
    void someTest() { ... }
}
```

**Key rules for `@Sql` ordering:**
- Always load in dependency order: `authorityData.sql` → `userData.sql` → `secData.sql`
- `authorityData.sql` must come first because `user_authority` has a FK to `authority`
- Class-level `@Sql` (e.g., `@Sql({SEC_DATA_SQL_PATH})`) runs before every test method
- Method-level `@Sql` runs additionally for tests that need more data
- Cleanup uses either programmatic `jdbcTemplate.execute("delete from ...")` in `@AfterEach`, or `@Sql(scripts = "/sql/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)`

## Testcontainers PostgreSQL Setup

The container is declared as a Spring bean in `TestConfig.java` using `@ServiceConnection` (Spring Boot 3 auto-wiring):

```java
// src/test/java/com/softropic/payam/config/TestConfig.java
@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer(@Value("${spring.application.name}") String dbName) {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.18"))
                .withDatabaseName(dbName)
                .withPassword("postgres")
                .withUsername("postgres")
                .withInitScript("sql/createSchema.sql");   // runs DDL once at startup
    }
}
```

`CustomPostgresContainer` (`src/test/java/com/softropic/payam/config/CustomPostgresContainer.java`) adds UTC timezone pinning:
```java
public class CustomPostgresContainer extends PostgreSQLContainer<CustomPostgresContainer> {
    public CustomPostgresContainer(final DockerImageName dockerImageName) {
        super(dockerImageName);
        this.withEnv("TZ", "UTC");
        this.withEnv("PGTZ", "UTC");
    }
}
```

`TestConfig` is imported into every integration test via `@Import(TestConfig.class)`. The container is shared across the test run via Spring's application context cache.

## Unit Test Structure

Unit tests have no Spring context. Dependencies are injected manually or via Mockito annotations:

```java
// Pattern 1: MockitoAnnotations (most common for filter/service tests)
@BeforeEach
void setUp() {
    MockitoAnnotations.openMocks(this);
    // manually construct the class under test
    filter = new JWTAuthorizationFilter(daoAuthProvider, eventPublisher, ...);
}

// Pattern 2: @ExtendWith(MockitoExtension.class) (used in EmailRetrySchedulerTest)
@ExtendWith(MockitoExtension.class)
class EmailRetrySchedulerTest {
    @Mock
    private EnvelopeEntityRepository envelopeEntityRepository;
    @InjectMocks
    private EmailRetryScheduler scheduler;
}
```

**Structure convention:**
```java
@Test
void descriptiveMethodName_whenCondition_thenExpectedBehavior() {
    // Setup
    when(dependency.method(...)).thenReturn(...);

    // Action
    result = subjectUnderTest.doSomething();

    // Verification
    assertThat(result)...;
    verify(dependency).method(...);
}
```

## Mockito Patterns

**Standard mock with `when/then`:**
```java
when(loginTokenManager.extractPrincipal(request)).thenReturn(principal);
when(userDetails.isEnabled()).thenReturn(true);
doThrow(new RuntimeException("SMTP down")).when(mailManager).sendEmailSync(argThat(e -> sendId.equals(e.sendId())));
```

**Verification with `verify`:**
```java
verify(eventPublisher).publishEvent(any(PreAuthEvent.class));
verify(filterChain).doFilter(request, response);
verify(loginTokenManager, times(2)).extractPrincipal(request);
verify(loadUserByUserNameService, never()).loadUserByUsername("testuser");
verifyNoInteractions(mailManager);
```

**`ArgumentCaptor` — capture and inspect passed arguments:**
```java
// From EmailRetrySchedulerTest and JWTAuthenticationFilterTest
ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
verify(mailManager, times(2)).sendEmailSync(captor.capture());
assertThat(captor.getAllValues().stream().map(Envelope::sendId).toList())
        .containsExactlyInAnyOrder(sendId1, sendId2);

ArgumentCaptor<AuthEvent> authEventCaptor = ArgumentCaptor.forClass(AuthEvent.class);
verify(eventPublisher, times(2)).publishEvent(authEventCaptor.capture());
List<AuthEvent> allValues = authEventCaptor.getAllValues();
assertThat(authEventCaptor.getValue().getAction()).isEqualTo(AuthenticationAction.SUCCESSFUL_AUTHENTICATION);
```

**`mockStatic` — mock static utility classes:**
```java
// From RateLimitingAspectIT and JwtManagerImplTest
try (MockedStatic<RequestMetadataProvider> mockedStatic = mockStatic(RequestMetadataProvider.class)) {
    mockedStatic.when(RequestMetadataProvider::getClientInfo).thenReturn(mockMetadata);

    assertThatCode(() -> testService.limitedMethod()).doesNotThrowAnyException();
    assertThatThrownBy(() -> testService.limitedMethod())
            .isInstanceOf(AuthorizationException.class)
            .extracting(e -> ((AuthorizationException) e).getErrorCode())
            .isEqualTo(TOO_MANY_REQUESTS);
}
```

**`@Spy` usage:**
```java
@Spy
private UserDetails userDetails = principal;
```

## Security Context Setup

Integration tests that exercise secured service methods must populate the `SecurityContext` manually. Both a regular user and an admin variant are used:

```java
// From UserServiceIT — helper methods called in test body or @BeforeEach
private void initSecurityContext() {
    final Principal principal = new Principal.Builder()
            .username("me@yahoo.com")
            .password("$2a$10$...")
            .authorities(Set.of(new Authority("ROLE_USER")))
            .displayName("Genie")
            .businessId("586920556720583008")
            .enabled(true).otpEnabled(true)
            .phone(phone).build();

    var token = new UsernamePasswordAuthenticationToken(
            principal.getUsername(), null, principal.getAuthorities());
    token.setDetails(principal);
    SecurityContextHolder.setContext(new SecurityContextImpl(token));
}

private void initAdminSecurityContext() {
    // same pattern with ROLE_USER + ROLE_ADMIN authorities
}

@AfterEach
void tearDown() {
    SecurityContextHolder.clearContext();   // always clear after each test
}
```

Unit tests that test filter behavior clear the context manually:
```java
@BeforeEach void setUp()  { SecurityContextHolder.clearContext(); }
@AfterEach  void tearDown() { SecurityContextHolder.clearContext(); }
```

## Asynchronous Testing with Awaitility

Used when email dispatch is event-driven (Spring `@TransactionalEventListener` / `@EventListener`):

```java
// From PasswordResetIT, MailManagerIT, SecurityIT
import static org.awaitility.Awaitility.await;

await().until(() -> testMailManager.getEnvelope(helpCode) != null);
Envelope envelope = testMailManager.getEnvelope(helpCode);
assertThat(envelope.data().get("resetKey")).isNotBlank();
```

`await()` uses default poll interval and timeout (Awaitility defaults). No custom `pollInterval` or `atMost` is configured in existing tests — the default 10-second timeout applies.

## TestMailManager

`src/test/java/com/softropic/payam/utils/TestMailManager.java` is the in-memory email stub. It is activated via the property `enable.test.mail=true` in `TestConfig`:

```java
@Bean
@Primary
@ConditionalOnProperty(name = "enable.test.mail", havingValue = "true")
public MailManager mailManager() {
    return new TestMailManager();
}
```

`TestMailManager` overrides `sendEmailSync` and `sendEmailFromTemplate` to store envelopes in a `ConcurrentHashMap<String, Envelope>` keyed by `sendId`. It also registers an `@EventListener` (not `@TransactionalEventListener`) so it captures envelopes synchronously regardless of transaction state.

```java
TestMailManager testMailManager = (TestMailManager) mailManager;  // cast after @Autowired MailManager
await().until(() -> testMailManager.getEnvelope(sendId) != null);
Envelope received = testMailManager.getEnvelope(sendId);
testMailManager.clear();   // call in @BeforeEach if needed between tests
```

## EntityFetchAsserter

`src/test/java/com/softropic/payam/utils/sql/EntityFetchAsserter.java` — wraps JPA `PersistenceUnitUtil` to assert lazy vs eager loading of entity associations. Registered as a bean in `TestConfig`:

```java
@Bean
public EntityFetchAsserter createAsserter(EntityManagerFactory emf) {
    return new EntityFetchAsserter(emf);
}
```

Usage pattern:
```java
@Autowired
private EntityFetchAsserter entityFetchAsserter;

entityFetchAsserter.assertThat(user)
        .isLazyLoaded("addresses")
        .isEagerlyLoaded("authorities");
```

Note: `PersistenceUnitUtil#isLoaded` has known accuracy limitations (see TODO comments in the class). Prefer this for smoke-level fetch checks rather than precise initialization guarantees.

## QueryRecorderListener and SqlStatementHolder

`src/test/java/com/softropic/payam/utils/sql/QueryRecorderListener.java` implements `net.ttddyy.dsproxy.listener.QueryExecutionListener`. It intercepts SQL after execution and routes each statement into a thread-local `Statement` object via `SqlStatementHolder`.

Activated by setting `log.database.spy=true` and building the proxy DataSource in `TestConfig`:
```java
@Bean
@ConditionalOnProperty(name="log.database.spy", havingValue="true")
DataSource spyDataSource(HikariConfig hikariConfig) {
    return ProxyDataSourceBuilder.create(dataSource)
            .name("DS-Proxy")
            .listener(chainListener)   // includes QueryRecorderListener
            .countQuery()
            .build();
}
```

`SqlStatementHolder` uses a `ThreadLocal<Statement>`. In tests, call `SqlStatementHolder.initStatement()` before the action and then use the `Statement` / `SelectQuery` / `InsertQuery` APIs to assert what SQL was issued.

## Parameterized Tests

**`@ValueSource` — simple value arrays:**
```java
// From CamMobileValidatorTest
@ParameterizedTest
@ValueSource(strings = {"690022002", "+237698684749", "655684749"})
void validateOrange(String phone) {
    final PhoneNumberDto phoneNumber = CamMobileValidator.validate(phone);
    assertThat(phoneNumber.getProvider()).isEqualTo(Provider.ORANGE);
}
```

**`@EnumSource` with inner enum — data-driven API tests:**
```java
// From SecurityIT — enum is declared as a static inner class of the test
@Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
@ParameterizedTest
@EnumSource(value = Credentials.class, names = {"INVALID_EMAIL", "ARBITRARY_EMAIL", "INVALID_PASSWORD"})
void loginWithWrongCredentials(Credentials credentials) throws JsonProcessingException {
    assertThatThrownBy(() -> httpTestClient.makeHttpRequest(uri, POST, credentials.getBody(), ...))
            .isInstanceOf(HttpClientErrorException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
}

enum Credentials {
    INVALID_EMAIL(Map.of(EMAIL, "Mike", PASSWORD, "Thomson")),
    ARBITRARY_EMAIL(Map.of(EMAIL, "walters@yahoo.com", PASSWORD, "Thomson")),
    INVALID_PASSWORD(Map.of(EMAIL, "me@yahoo.com", PASSWORD, PASSWORD));

    private final Map<String, Object> body;
    Credentials(Map<String, Object> body) { this.body = body; }
    public Map<String, Object> getBody() { return this.body; }
}
```

**`@MethodSource` — stream of `Arguments` for multi-parameter cases:**
```java
// From JwtManagerImplTest
@ParameterizedTest
@MethodSource("claimsData")
void testIsTokenFixed_userAgentMismatch(Map<String, Object> claims, Boolean expectedOutcome) {
    String token = buildTokenWithCustomClaims(claims, ...);
    assertThat(jwtManager.isTokenFixed(mockRequest)).isEqualTo(expectedOutcome);
}

private static Stream<Arguments> claimsData() {
    return Stream.of(
            Arguments.of(expectedClaims, Boolean.FALSE),
            Arguments.of(userAgentMismatch, Boolean.TRUE),
            Arguments.of(clientIdMismatch, Boolean.TRUE),
            Arguments.of(sessionIdMismatch, Boolean.TRUE)
    );
}
```

## Time-Sensitive Testing

Tests involving cache TTL use Guava's `FakeTicker` injected as the `Ticker` dependency:

```java
// From LoginAttemptsServiceTest
FakeTicker fakeTicker = new FakeTicker();
LoginAttemptsService expiringService = new LoginAttemptsService(defaultDecisionVoter, fakeTicker);

simulateFailedLogins(expiringService, MAX_FAILED_CLIENT_ATTEMPTS - 1);
assertLoginAllowed(expiringService, true, "Allowed before cache expiry");

fakeTicker.advance(4, TimeUnit.HOURS);
fakeTicker.advance(1, TimeUnit.MINUTES);

simulateFailedLogins(expiringService, 1);
assertLoginAllowed(expiringService, true, "Allowed after cache expiry and 1 new attempt");
```

JWT expiry tests use `TestClockProvider` (production `ClockProvider` has a test-only reset method):
```java
// From JwtManagerImplTest
@BeforeEach
void setUp() {
    TestClockProvider.setSystemClock();
}
```

## Database Cleanup Strategies

Three cleanup approaches are used; choose based on test class needs:

**1. Programmatic `@AfterEach` with `JdbcTemplate`** (most common in service ITs):
```java
@AfterEach
void tearDown() {
    template.execute(status -> {
        jdbcTemplate.execute("delete from main.sec");
        jdbcTemplate.execute("delete from main.user_addresses");
        jdbcTemplate.execute("delete from main.user_authority");
        jdbcTemplate.execute("delete from main.authority");
        jdbcTemplate.execute("delete from main.user");
        return 0;
    });
}
```

**2. `@Sql` after-test cleanup script:**
```java
@Sql(scripts = "/sql/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
```

**3. `DbCleaner` component** (`src/test/java/com/softropic/payam/utils/DbCleaner.java`) — injected bean that deletes all known tables in FK-safe order. Used by `SecretServiceIT`:
```java
@Autowired
private DbCleaner dbCleaner;

@AfterEach
void cleanup() {
    dbCleaner.cleanDb();
}
```

`@Transactional` on the test class is used only in `PasswordResetIT` — it rolls back after each test automatically. Avoid combining `@Transactional` on the class with `@Sql` cleanup scripts, as the transaction wrapping interferes with `AFTER_TEST_METHOD` execution phase.

## Test Types

**Unit Tests (`*Test.java`):**
- No Spring context
- All dependencies mocked via Mockito or manually constructed
- Use `MockitoAnnotations.openMocks(this)` in `@BeforeEach` or `@ExtendWith(MockitoExtension.class)`
- Examples: `LoginAttemptsServiceTest`, `EmailRetrySchedulerTest`, `JwtManagerImplTest`, `RateLimitingServiceTest`, `JWTAuthorizationFilterTest`

**Integration Tests (`*IT.java`):**
- Full Spring Boot context (`@SpringBootTest(webEnvironment = RANDOM_PORT)`)
- PostgreSQL via Testcontainers (`@Import(TestConfig.class)`)
- `@ActiveProfiles("dev")` on every integration test
- Data loaded via `@Sql`; cleaned up in `@AfterEach`
- Examples: `UserServiceIT`, `PasswordResetIT`, `SecurityIT`, `MailManagerIT`

**HTTP-Level Integration Tests:**
- Use `@LocalServerPort` + `HttpTestClient` (autowired Spring bean wrapping `RestTemplate`)
- `SecurityIT` and `SecurityFilterChainIT` exercise actual HTTP requests including cookies, CORS, and JWT headers

## Coverage

**Requirements:** None enforced (no Jacoco minimum thresholds detected in `pom.xml`).

**Gaps to be aware of:**
- No E2E test framework (no Selenium / Playwright)
- No contract tests
- `EntityFetchAsserter` has acknowledged accuracy limitations (see TODO comments in the class)

---

*Testing analysis: 2026-03-21*
