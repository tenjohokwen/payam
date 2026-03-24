# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-23)

**Core value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.
**Current focus:** Phase 2 complete (Transaction Core) — Phase 3 next

## Current Position

Phase: 3 of 10 (Orange Money Adapter) — In progress
Plan: 1 of 2 in phase (03-01 complete)
Status: In progress — 03-02 (OrangeMoneyPort service + Quartz poller) is next
Last activity: 2026-03-24 — Completed 03-01-PLAN.md (Orange adapter foundation: MobileMoneyPort, contract layer, OrangeMoneyClient, OrangeTokenService, Quartz DDL)

Progress: ███████░░░ ~39% (7 of ~18 plans)

## Performance Metrics

**Velocity:**
- Total plans completed: 5
- Average duration: 32.6 min
- Total execution time: ~2.7 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-multi-tenant-foundation | 3/3 | 153 min | 51 min |
| 02-transaction-core | 3/3 | 16 min | 5.3 min |
| 03-orange-money-adapter | 1/2 | 5 min | 5 min |

**Recent Trend:**
- Last 5 plans: 5 min, 4 min, 5 min, 6 min, 5 min avg
- Trend: Foundation/scaffold plans are fast (avg ~5 min) — well-defined patterns from prior phases

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Research confirmed: Spring Modulith replaces Kafka/RabbitMQ — durable events via PostgreSQL Event Publication Registry
- Research confirmed: Only 3 new Maven dependencies needed (spring-modulith-starter-jpa, spring-boot-starter-data-redis, spring-boot-starter-quartz)
- 01-01 decision: ApiKeyStatus is separate enum (ACTIVE/ROTATED/REVOKED) from EntityStatus — API key lifecycle is distinct
- 01-01 decision: tenant_status and key_status DDL columns used (not `status`) to avoid AbstractAuditingEntity.status clash
- 01-01 decision: JOIN FETCH k.tenant added to findValidKeyByHash — prevents LazyInitializationException in API key filter
- 01-01 decision: Flyway no-op FlywayMigrationStrategy removed from DataSourceConfig — V1 migration now runs on startup
- 01-02 decision: securityMatcher excludes /v1/account/** (AND-NOT matcher) — JWT chain retains user account management; required to keep SecurityFilterChainIT+SecurityIT green
- 01-02 decision: ApiKeyAuthenticationFilter NOT @Component — defined as @Bean in TenantSecurityConfig to prevent servlet container auto-registration; also has shouldNotFilter() bypass for account paths
- 01-02 decision: @Configuration("tenantAsyncConfig") on new AsyncConfig — Spring 6.2+ ConflictingBeanDefinitionException if two config classes share default name "asyncConfig"
- 01-02 decision: TenantFilterChainIT seeds JWT secret in @BeforeEach — SecurityAdviceFilter.addSecretToThread() runs on every request; main.sec must have secret row
- 01-03 decision: TenantAdminResource now takes two constructor args (TenantService, ApiKeyService) — both are @Service beans, Spring injects automatically
- 01-03 decision: tenantId path variable present for URL consistency; no DB ownership check in this plan (future security phase concern)
- 01-03 decision: EntityNotFoundException handler added to ApiAdvice → 404; without it JPA EntityNotFoundException hit Throwable catch-all → 500
- 02-01 decision: MobilePaymentProvider reused from common/payment package (MTN, ORANGE, NEXTTEL) — plan specified new enum in transaction/contract but common one already exists; reused to avoid duplication
- 02-01 decision: Transaction.txStatus has no public setter — applyTransition() is the only mutation point, enforcing state machine guards
- 02-01 decision: TransactionStateMachineIT creates tenant via TenantService (@BeforeEach) — TSID-based IDs preclude fixed numeric tenantId=1L
- 02-01 decision: payment_event_log extends BaseEntity only (not AbstractAuditingEntity) — append-only log table does not need audit columns
- 02-02 decision: @JdbcTypeCode(SqlTypes.JSON) required on String metadata field mapped to jsonb — @Column(columnDefinition="jsonb") alone does not add JDBC cast; PostgreSQL rejects varchar→jsonb without it
- 02-02 decision: Hash canonical string is pipe-delimited domain fields only (transactionId|eventType|statusFrom|statusTo|actor|previousHash); no timestamps or DB IDs — ensures hash reproducibility
- 02-02 decision: createdDate set inside create() factory but excluded from hash input — Instant.now() is non-deterministic and would break hash replay
- 02-03 decision: IdempotencyKey extends BaseEntity only — V2 DDL has no audit columns; AbstractAuditingEntity would cause schema-validation failure
- 02-03 decision: LedgerEntry uses @Builder not @SuperBuilder (no superclass) and @Immutable — Hibernate refuses dirty-check updates on append-only record
- 02-03 decision: IdempotencyService.store() uses delete-then-save for upsert — IdempotencyKey has no public setters; delete+save is clean for low-frequency update
- 02-03 decision: @ServiceConnection(name='redis') required on GenericContainer — Spring Boot cannot infer service type from untyped GenericContainer without name attribute
- 03-01 decision: OrangeModule.java is a plain marker class — spring-modulith not in pom.xml; @ApplicationModule unavailable; plain class serves as boundary documentation
- 03-01 decision: RestRequestInterceptor instantiated directly (new RestRequestInterceptor()) in OrangeMoneyClient constructor — concrete @Component with no-arg constructor; OrangeMoneyClient is @Bean not @Component, so direct instantiation is clean
- 03-01 decision: OrangeMoneyClient.pay() uses config.getPayUrl() (v1.0.1) not getBaseUrl() (v1.0.2) — Orange Pay endpoint is on different API version from other endpoints
- 03-01 decision: OrangeStatus.SUCCESSFULL has double-L — verbatim per Orange API response; not a typo

### Pending Todos

- Run `mvn resources:resources resources:testResources` before `mvn surefire:test` when bypassing lifecycle (frontend plugin blocks full lifecycle)
- Any new IT test class that makes HTTP requests must seed the JWT secret row in main.sec (or use @Sql with secData.sql)
- Any new IT test class that writes to main.transaction must delete from main.transaction in @AfterEach BEFORE deleting from main.tenant (FK constraint)
- Any new IT test class writing to main.payment_event_log must delete from main.payment_event_log in @AfterEach before main.transaction (no FK, but ordering matters for clean state)

### Blockers/Concerns

- Environment: `.mvn/wrapper/maven-wrapper.properties` missing — use system `mvn` not `./mvnw`
- Environment: Frontend plugin (`generate-resources` phase) broken due to missing quasar module — bypass with `mvn compiler:compile` or invoke goals directly
- Phase 3: Orange webhook HMAC header existence unconfirmed — verify with Orange partner before implementation
- Phase 4: MTN PUT callback confirmed in docs — verify in sandbox before relying on it
- Phase 9: Orange daily report format undocumented — requires partner verification before parser implementation
- Spring Security filter chain pattern: SecurityAdviceFilter is @Component — it runs for ALL requests via servlet container, not just JWT chain requests. Any @Component filter applies globally. When adding new IT tests, account for this.
- ApiAdvice exception handler priority: EntityNotFoundException is now mapped (→ 404). Any future JPA entity not-found scenarios will return 404 consistently. Check for conflicts before adding new @ExceptionHandler entries.
- Redis Testcontainer now active: 02-03 added GenericContainer(redis:7-alpine) to TestConfig — all future ITs will have Redis available automatically.

## Session Continuity

Last session: 2026-03-24T01:36:40Z
Stopped at: Completed 03-01-PLAN.md — Orange adapter foundation scaffold complete (MobileMoneyPort, all contracts, OrangeMoneyClient, OrangeTokenService, OrangeConfig, Quartz DDL, yaml config)
Resume file: None
