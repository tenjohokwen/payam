---
phase: 03-orange-money-adapter
plan: "01"
subsystem: payments
tags: [orange-money, resilience4j, quartz, redis, http-client, jackson, spring-config-properties]

# Dependency graph
requires:
  - phase: 02-transaction-core
    provides: Transaction state machine, IdempotencyService, LedgerEntry — service layer patterns and Redis Testcontainer config
  - phase: 01-multi-tenant-foundation
    provides: AbstractClient, RestRequestInterceptor, BaseEntity, common/payment/MobilePaymentProvider enum
provides:
  - MobileMoneyPort strategy interface (contract for Phase 5 provider routing)
  - Full Orange contract layer (2 enums, OrangeWebhookPayload, 7 DTOs, 3 exceptions)
  - OrangeMoneyConfig @ConfigurationProperties binding for orange.* yaml
  - OrangeMoneyClient extending AbstractClient with 7 HTTP endpoint methods
  - OrangeTokenService with Redis NX+EX token caching (55-min TTL, soft lock)
  - OrangeConfig @Configuration registering OrangeMoneyClient as Spring bean
  - V5__quartz_schema.sql Flyway DDL (11 Quartz JDBC store tables in main schema)
  - Resilience4j circuit breaker + retry config for Orange in application.yaml
  - Quartz JDBC store config in application.yaml
affects:
  - 03-02-orange-money-adapter (OrangeMoneyPort service + Quartz poller implementation)
  - 05-payment-orchestration (MobileMoneyPort used for provider routing)
  - 06-webhook-handling (OrangeWebhookPayload type used for inbound webhook deserialization)

# Tech tracking
tech-stack:
  added:
    - spring-boot-starter-quartz (Quartz JDBC store scheduler)
    - wiremock-spring-boot 4.0.9 (test scope — for Plan 03-02 HTTP mock tests)
  patterns:
    - MobileMoneyPort strategy pattern — each provider adapter implements the interface; Phase 5 routes by MobilePaymentProvider enum
    - AbstractClient extension — OrangeMoneyClient constructed via @Bean factory in OrangeConfig, NOT @Component; avoids double registration
    - OrangeConfig @EnableConfigurationProperties — @ConfigurationProperties class not self-registering; registered explicitly via @EnableConfigurationProperties on @Configuration
    - Redis NX+EX soft lock for token refresh — same pattern as IdempotencyService; prevents multi-node token stampede
    - Plain DTO convention — no Lombok on DTOs; explicit getters; @JsonIgnoreProperties(ignoreUnknown=true) on all Orange DTOs

key-files:
  created:
    - src/main/java/com/softropic/payam/common/payment/MobileMoneyPort.java
    - src/main/java/com/softropic/payam/common/payment/PaymentCommand.java
    - src/main/java/com/softropic/payam/common/payment/ProviderResult.java
    - src/main/java/com/softropic/payam/common/payment/SubscriberStatus.java
    - src/main/java/com/softropic/payam/orange/OrangeModule.java
    - src/main/java/com/softropic/payam/orange/config/OrangeMoneyConfig.java
    - src/main/java/com/softropic/payam/orange/config/OrangeConfig.java
    - src/main/java/com/softropic/payam/orange/contract/OrangeTransactionType.java
    - src/main/java/com/softropic/payam/orange/contract/OrangeStatus.java
    - src/main/java/com/softropic/payam/orange/contract/OrangeWebhookPayload.java
    - src/main/java/com/softropic/payam/orange/contract/dto/SubscriberInfoResponse.java
    - src/main/java/com/softropic/payam/orange/contract/dto/MerchantInfoResponse.java
    - src/main/java/com/softropic/payam/orange/contract/dto/PayRequest.java
    - src/main/java/com/softropic/payam/orange/contract/dto/PayResponse.java
    - src/main/java/com/softropic/payam/orange/contract/dto/CashoutRequest.java
    - src/main/java/com/softropic/payam/orange/contract/dto/C2CRequest.java
    - src/main/java/com/softropic/payam/orange/contract/dto/OrangeTokenResponse.java
    - src/main/java/com/softropic/payam/orange/contract/exception/OrangeApiException.java
    - src/main/java/com/softropic/payam/orange/contract/exception/PayTokenExpiredException.java
    - src/main/java/com/softropic/payam/orange/contract/exception/SubscriberInactiveException.java
    - src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java
    - src/main/java/com/softropic/payam/orange/service/OrangeTokenService.java
    - src/main/resources/db/migration/V5__quartz_schema.sql
  modified:
    - pom.xml (added spring-boot-starter-quartz, wiremock-spring-boot)
    - src/main/resources/application.yaml (added quartz, resilience4j, orange config sections)

key-decisions:
  - "OrangeModule.java is a plain marker class — spring-modulith is not in the pom.xml; @ApplicationModule annotation unavailable; plain class serves as boundary documentation"
  - "RestRequestInterceptor instantiated directly (new RestRequestInterceptor()) in OrangeMoneyClient constructor — it has a no-arg @Autowired constructor and is a @Component; instantiating directly avoids Spring circular dependency; OrangeMoneyClient is a @Bean not @Component"
  - "OrangeMoneyClient.pay() uses config.getPayUrl() (v1.0.1 base) not config.getBaseUrl() (v1.0.2) — Orange Pay endpoint uses different API version than other endpoints"
  - "OrangeStatus.SUCCESSFULL has double-L — this is not a typo; Orange API returns 'SUCCESSFULL' in their status field"
  - "fetchToken() sends credentials as Basic base64(consumerKey:consumerSecret) with form body grant_type=client_credentials — standard OAuth2 client_credentials flow"

patterns-established:
  - "Provider port pattern: MobileMoneyPort interface in common/payment/ is the stable contract; adapters (orange, mtn) implement it; Phase 5 routes by MobilePaymentProvider enum without knowing concrete types"
  - "ProviderResult factory methods: ProviderResult.pending(), .success(), .failure() — adapters return these; orchestration layer inspects pending flag for polling decisions"
  - "Orange HTTP client: OrangeMoneyClient extends AbstractClient; all methods return typed DTOs or ResponseEntity<Map>; throw OrangeApiException on non-2xx"
  - "Token caching with soft lock: Redis NX+EX 10s lock prevents token stampede; 200ms wait then direct fetch on contention — prevents indefinite wait"

# Metrics
duration: 5min
completed: 2026-03-24
---

# Phase 3 Plan 01: Orange Money Adapter Foundation Summary

**MobileMoneyPort strategy interface, full Orange contract layer (DTOs/enums/exceptions), OrangeMoneyClient extending AbstractClient, OrangeTokenService with Redis NX+EX caching, OrangeConfig bean wiring, Quartz V5 Flyway DDL, and Resilience4j yaml config**

## Performance

- **Duration:** ~5 min
- **Started:** 2026-03-24T01:32:02Z
- **Completed:** 2026-03-24T01:36:40Z
- **Tasks:** 4 (Task 1, Task 2, Task 3a, Task 3b)
- **Files modified:** 25 created + 2 modified = 27 total

## Accomplishments

- MobileMoneyPort strategy interface established as the Phase 5 routing contract — stable, no Spring annotations, ready for provider adapters and orchestration
- Complete Orange module scaffold: all contracts, DTOs, client, token service, and config wiring — Plan 03-02 can implement business logic against stable types immediately
- V5 Quartz Flyway DDL with all 11 canonical JDBC store tables and indexes — Quartz poller from Plan 03-02 will persist jobs here
- Resilience4j circuit breaker + retry configured for orange instance — Plan 03-02 service layer can annotate @CircuitBreaker(name="orange") immediately

## Task Commits

Each task was committed atomically:

1. **Task 1: Maven dependencies, Quartz Flyway V5 DDL, application.yaml config** - `f47bd18` (chore)
2. **Task 2: MobileMoneyPort interface + common payment types** - `daf24e0` (feat)
3. **Task 3a: Orange contract layer — enums, DTOs, exceptions, OrangeMoneyConfig** - `c740c63` (feat)
4. **Task 3b: OrangeMoneyClient, OrangeTokenService, OrangeConfig bean wiring** - `1401e06` (feat)

## Files Created/Modified

- `pom.xml` - Added spring-boot-starter-quartz + wiremock-spring-boot 4.0.9 (test)
- `src/main/resources/application.yaml` - Added quartz JDBC store, resilience4j orange circuit breaker + retry, orange client properties
- `src/main/resources/db/migration/V5__quartz_schema.sql` - Quartz JDBC DDL (11 tables + indexes) in main schema
- `src/main/java/com/softropic/payam/common/payment/MobileMoneyPort.java` - Strategy interface with 3 methods
- `src/main/java/com/softropic/payam/common/payment/PaymentCommand.java` - Command record (transactionId, msisdn, amount, provider, idempotencyKey)
- `src/main/java/com/softropic/payam/common/payment/ProviderResult.java` - Result record with pending/success/failure factories
- `src/main/java/com/softropic/payam/common/payment/SubscriberStatus.java` - Subscriber validation result record
- `src/main/java/com/softropic/payam/orange/OrangeModule.java` - Module boundary marker
- `src/main/java/com/softropic/payam/orange/config/OrangeMoneyConfig.java` - @ConfigurationProperties(prefix="orange") with Poller nested class
- `src/main/java/com/softropic/payam/orange/config/OrangeConfig.java` - @Configuration @EnableConfigurationProperties registering OrangeMoneyClient @Bean
- `src/main/java/com/softropic/payam/orange/contract/OrangeTransactionType.java` - Enum: MP, CASHOUT, C2C, IC2C
- `src/main/java/com/softropic/payam/orange/contract/OrangeStatus.java` - Enum: INITIATED, PENDING, SUCCESSFULL (double-L), FAILED, EXPIRED
- `src/main/java/com/softropic/payam/orange/contract/OrangeWebhookPayload.java` - Inbound webhook DTO with @JsonIgnoreProperties
- `src/main/java/com/softropic/payam/orange/contract/dto/` - 7 DTOs: SubscriberInfoResponse, MerchantInfoResponse, PayRequest, PayResponse, CashoutRequest, C2CRequest, OrangeTokenResponse
- `src/main/java/com/softropic/payam/orange/contract/exception/` - 3 exceptions: OrangeApiException, PayTokenExpiredException, SubscriberInactiveException
- `src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java` - HTTP client extending AbstractClient; 7 methods fully implemented
- `src/main/java/com/softropic/payam/orange/service/OrangeTokenService.java` - Redis NX+EX token cache with soft lock and contention retry

## Decisions Made

- **OrangeModule is a plain marker class** — spring-modulith not in pom.xml; @ApplicationModule unavailable; class serves as boundary documentation and can be upgraded later without logic changes.
- **RestRequestInterceptor instantiated directly** — `new RestRequestInterceptor()` in OrangeMoneyClient constructor; it has a no-arg @Autowired constructor; OrangeMoneyClient is a @Bean not @Component, so injecting the @Component interceptor by constructor avoids needing OrangeConfig to have an extra dependency.
- **pay() uses config.getPayUrl() not getBaseUrl()** — Orange Pay endpoint is on v1.0.1 API, while all other endpoints are on v1.0.2; different base URLs required.
- **OrangeStatus.SUCCESSFULL has double-L** — Orange API returns "SUCCESSFULL" (two L's) in their status response; this is verbatim per Orange docs, not a typo.

## Deviations from Plan

None — plan executed exactly as written. The only minor adaptation was using a plain marker class for OrangeModule.java instead of @ApplicationModule (spring-modulith not available), which is consistent with the plan's note to "follow established codebase patterns exactly."

## Issues Encountered

None — all 4 tasks compiled clean on first attempt. The `RestRequestInterceptor` is concrete with a no-arg constructor, confirming the plan's guidance that it could be used directly.

## User Setup Required

None — no external service configuration required for this plan. Orange API credentials are already wired via environment variables (`ORANGE_CONSUMER_KEY`, `ORANGE_CONSUMER_SECRET`) with test defaults in application.yaml.

## Next Phase Readiness

- Plan 03-02 can proceed immediately: OrangeMoneyClient and OrangeTokenService are ready for service-layer business logic
- OrangeMoneyPort service (implementing MobileMoneyPort) and Quartz poller job can be added against these stable types
- @CircuitBreaker(name="orange") and @Retry(name="orange") annotations can be applied in Plan 03-02 service methods
- Concern: `spring-cloud-starter-circuitbreaker-resilience4j` already exists in pom.xml — Resilience4j is already available, no additional dependency needed for Plan 03-02 annotations

---
*Phase: 03-orange-money-adapter*
*Completed: 2026-03-24*
