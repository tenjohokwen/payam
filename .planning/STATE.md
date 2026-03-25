# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-23)

**Core value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.
**Current focus:** Phase 11 (Fee Exposure) — In progress. Plan 11-01 complete.

## Current Position

Phase: 11 of 11 (Fee Exposure) — In progress
Plan: 1 of 1 done (11-01 complete)
Status: Phase 11 Plan 1 complete — fee fields exposed in API response and webhook payload
Last activity: 2026-03-25 — Completed 11-01: feeAmount+feeRuleId in PaymentResponse; feeAmount in OutboundWebhookPayload; PaymentOrchestratorIT 8/8, WebhookDeliveryIT 3/3

Progress: ████████████████████████ ~100% (27 of ~27 plans)

## Performance Metrics

**Velocity:**
- Total plans completed: 26
- Average duration: 22 min
- Total execution time: ~7.1 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-multi-tenant-foundation | 3/3 | 153 min | 51 min |
| 02-transaction-core | 3/3 | 16 min | 5.3 min |
| 03-orange-money-adapter | 4/4 | 42 min | 10.5 min |
| 04-mtn-momo-adapter | 2/2 | 14 min | 7 min |
| 05-payment-orchestration | 2/2 | 38 min | 19 min |
| 06-webhook-processing | 3/3 | 43 min | 14 min |
| 07-fraud-engine | 2/2 | 53 min | 26.5 min |
| 08-admin-dashboard | 3/3 | 50 min | 16 min |
| 09-reconciliation | 2/2 | 43 min | 21 min |
| 10-operational-hardening | 4/4 | ~100 min | ~25 min |
| 11-fee-exposure | 1/1 | 27 min | 27 min |

**Recent Trend:**
- Last 5 plans: 11 min, 3 min, 25 min, 23 min, 30 min avg
- Trend: Infrastructure plans fast (3-6 min); service+test plans 10-30 min

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
- 03-02 decision: @CircuitBreaker fallbackMethod removed — Resilience4j calls fallback for ALL exceptions (not just circuit-open), swallowing SubscriberInactiveException; CallNotPermittedException propagates to Phase 5 orchestration layer
- 03-02 decision: AbstractClient.messageConverters() only has JSON — OrangeMoneyClient adds FormHttpMessageConverter for x-www-form-urlencoded token POST
- 03-02 decision: @ConfigureWireMock baseUrlProperties cannot override full-URL properties (orange.token-url has path); test application.properties derives token-url as ${orange.base-url}/token
- 03-02 decision: cashout/C2C stubs have no @CircuitBreaker — unconditionally-throwing stubs don't need circuit-breaking; fallbacks swallowed UnsupportedOperationException in tests
- 03-02 decision: Circuit breaker ignoreExceptions for SubscriberInactiveException and PayTokenExpiredException — domain validation exceptions should not count as circuit failures
- 03-03 decision: getCreatetimeAsInstant() is the sole designated call site for OrangeTimeUtil.parseOrangeTimestamp() — Phase 5/6 consumers must use this method, not the raw String getter (P5.1)
- 03-03 decision: No @JsonIgnore needed on getCreatetimeAsInstant() — @JsonIgnoreProperties(ignoreUnknown=true) prevents Jackson from trying to deserialize derived getters
- 03-04 decision: assertPayTokenFresh() catch block placed BEFORE max-attempts check — expired token skips poll; incrementPollAttempts() still fires to prevent infinite looping on stale token
- 03-04 decision: PayTokenExpiredException NOT re-thrown from pollTransaction() — adapter lacks PaymentCommand context; re-initiation is Phase 5 PaymentOrchestrator responsibility (ROADMAP SC-4)
- 04-01 decision: MtnMoMoClient token POST sends null body — MTN returns 400 if form body is sent (Pitfall 4); unlike Orange no FormHttpMessageConverter needed
- 04-01 decision: fetchDisbursementToken() is a separate method — disbursement uses different product key (getDisbursementSubscriptionKey()) and endpoint (getDisbursementTokenUrl())
- 04-01 decision: disburse() uses getDisbursementSubscriptionKey() not getCollectionSubscriptionKey() — Pitfall 5: wrong key returns 401
- 04-01 decision: validateAccountHolder() catches HttpClientErrorException.NotFound → MtnAccountInactiveException — MTN returns 404 (not status field in body) for inactive accounts
- 04-01 decision: MtnMoMoClient constructor passes getCollectionBaseUrl() as super baseUrl; disbursement calls build full URL via getDisbursementBaseUrl()
- 04-02 decision: @TestPropertySource(properties = "mtn.callback-ip-whitelist=") required in MtnMoMoPortIT — application.yaml has 196.0.0.0/8 which rejects 127.0.0.1; empty list triggers sandbox mode (accept all)
- 04-02 decision: MtnMoMoClient.validateAccountHolder() catches HttpClientException (not HttpClientErrorException.NotFound) — RestRequestInterceptor converts all 4xx to HttpClientException before RestTemplate error handling fires
- 04-02 decision: MtnStatusPollerJob uses tx.getProviderRef() not payToken — MTN providerRef is stable UUID, no expiry; no assertPayTokenFresh() guard needed (Orange-specific)
- 05-01 decision: PaymentOrchestrator.initiate() has no @Transactional — holding DB connection during outbound HTTP exhausts connection pool (P1.1/P8.1); TransactionTemplate used for discrete DB operations
- 05-01 decision: Three applyTransition() calls required — state machine path INITIATED->AUTH_PENDING->AUTHORIZED->PROCESSING; direct INITIATED->PROCESSING throws IllegalStateTransitionException
- 05-01 decision: CallNotPermittedException caught before HttpClientException — wrong order silently swallows circuit-open events as generic provider errors
- 05-01 decision: @CircuitBreaker stays on OrangeMoneyPort and MtnMoMoPort (not on orchestrator) — circuit breakers placed in Phases 3/4; orchestrator catches CallNotPermittedException
- 05-01 decision: PAYMENT_ALREADY_PROCESSING returns HTTP 202 — duplicate in-flight is semantically accepted; 4xx would cause clients to unnecessarily retry
- 05-01 decision: MsisdnRouter uses hardcoded prefix rules — config-driven prefix table is Phase 10 hardening concern (RESEARCH.md Pitfall 3 mitigation)
- 04-02 decision: Transaction.setProviderRef() and setMtnFinancialTxId() added as package-accessible setters — needed by MtnMoMoPort.persistProviderRef() and storeFinancialTxId()
- 05-02 decision: noRetryRestTemplate (SimpleClientHttpRequestFactory) required for circuit breaker test — Apache HTTP Client 5 auto-retries 503, retry reuses RESERVED idempotency key → PAYMENT_ALREADY_PROCESSING (202) masks the 503
- 05-02 decision: PaymentOrchestrator.applyFailed() wraps error.name() in JSON quotes for jsonb metadata column — bare strings are invalid JSON and cause DataIntegrityViolationException
- 05-02 decision: circuitBreakerRegistry.circuitBreaker("mtn").transitionToOpenState() used after 10 failures to guarantee circuit-open state before 503 assertion
- 06-01 decision: HMAC body computed via objectMapper.writeValueAsString(payload) — servlet input stream already consumed by @RequestBody; readAllBytes() returns empty; re-serialization of deserialized object is correct approach
- 06-01 decision: Redis dedup key for Orange includes createtime (webhook:orange:{payToken}:{createtime}) — same payToken can receive multiple status transitions; createtime distinguishes each event (Pitfall 8 guard)
- 06-01 decision: No HMAC on MTN inbound callbacks — intentional per MTN API contract; notifToken correlation + IP whitelist is MTN's authenticity mechanism
- 06-01 decision: blank callbackHmacSecret = sandbox mode for Orange — skip HMAC check entirely; enables local/sandbox testing without Orange partner credentials
- 06-02 decision: @Transactional(REQUIRES_NEW) on WebhookTransitionService.applyFinalTransition — @TransactionalEventListener(AFTER_COMMIT) fires in afterCompletion phase with no active transaction; REQUIRED propagation throws TransactionRequiredException; REQUIRES_NEW creates fresh independent transaction
- 06-02 decision: WebhookTransitionService extracted as separate @Service — @Transactional self-invocation (WebhookDoubleCheckHandler calling its own method) bypasses Spring AOP CGLIB proxy; separate bean ensures proxy is invoked
- 06-02 decision: TransactionTemplate.execute() wraps publishEvent in OrangeMoneyPort and MtnMoMoPort — publishEvent without enclosing transaction never triggers @TransactionalEventListener(AFTER_COMMIT)
- 06-03 decision: enqueue() sets nextRetryAt=null on INSERT — findPendingForRetry uses WHERE nextRetryAt <= :now, so null rows are not matched by Quartz job; prevents race condition between inline first delivery attempt and Quartz pickup of just-inserted row
- 06-03 decision: attemptDeliveryInternal() extracted as private shared method — called from enqueue() (first attempt) and public attemptDelivery() (Quartz retries); avoids code duplication
- 06-03 decision: WebhookConfig.noRetryRestTemplate @Bean in main config; TestConfig.restTemplate @Primary — resolves NoUniqueBeanDefinitionException when both beans exist in test context (HttpTestClient has unqualified @Autowired RestTemplate)
- 06-03 decision: HttpStatusCodeException caught before generic Exception in attemptDeliveryInternal — captures httpStatus from HTTP error responses for delivery log queryability
- 07-01 decision: VelocityCheckService uses LettuceConnectionFactory.getHostName()/getPort() for RedisClient — getNativeClient() cast unreliable when @ServiceConnection reconfigures factory for Testcontainer
- 07-01 decision: FraudScoringService.evaluate() has dual block: direct velocity block (any exceeded velocity = immediate block) + score-based block (weighted sum >= BLOCK_THRESHOLD); direct block enforces must-have truths
- 07-01 decision: Fraud rules seeded via JDBC in IT @BeforeEach — dev profile create-drop wipes Flyway seed data; ON CONFLICT DO UPDATE avoids TSID re-generation on explicit IDs
- 07-01 decision: FraudSignal enum values match signal_name DB strings exactly — getSignalName() returns the enum name; zero translation layer between Java and DB
- 07-01 decision: BLOCK_THRESHOLD stored as fraud_rule row (threshold=70) — DB-configurable without restart; default=70 if rule not found (fail-safe)
- 07-02 decision: setRiskScore() and setDeviceFingerprint() are public (not package-private) — PaymentOrchestrator is in payment.service; Transaction is in transaction.repo; package-private is inaccessible cross-package; existing setProviderRef() pattern is also public
- 07-02 decision: MSISDN_VELOCITY used for velocity block IT test — ForwardedHeaderFilter (in SecurityConfiguration) strips Forwarded header before RequestMetadataProvider reads it; IP injection via headers unreliable; MSISDN is stable bucket key not dependent on header
- 07-02 decision: JDBC threshold update wrapped in transactionTemplate.execute() before fraudRuleCache.refreshRules() — ensures DB commit visible to Spring Data JPA query inside refreshRules()
- 07-02 decision: @NotAudited on Transaction.riskScore and Transaction.deviceFingerprint — V10 migration adds columns to main table only; Envers _AUD table lacks them; @NotAudited excludes from revision tracking to prevent schema validation errors
- 08-01 decision: NegatedRequestMatcher(OrRequestMatcher(/v1/account/**, /v1/admin/**)) replaces single-path exclusion — /v1/admin/** must not be intercepted by API-key chain or admin JWT requests return 401
- 08-01 decision: adminSearch JPQL uses ORDER BY in query text, not PageRequest sort — PageRequest.of(page, size) without sort preserves the intended DESC ordering
- 08-01 decision: statusFrom null-safe in EventLogEntryDto mapping — PaymentEventLog.statusFrom is nullable (genesis INITIATED event has no prior status); direct .name() call throws NPE
- 08-02 decision: @EnableScheduling already in AsyncConfig (email.config) — no new config class needed; @Scheduled on PaymentMetricsService.pushMetrics() works with existing scheduling infrastructure
- 08-02 decision: providerStart declared before outer try block; inner try/finally wraps only port.initiateMerchantPayment() — ensures latency recorded on any provider call outcome without interfering with outer catch blocks
- 08-02 decision: recordSuccess() called after idempotencyService.store() — plan spec placement; ensures payment is fully committed before counter increments
- 08-03 decision: No frontend requiresAdmin guard on admin routes — backend returns 403/401 for non-ROLE_ADMIN callers; nav items visible to all authenticated users; backend enforcement is sufficient for this phase
- 08-03 decision: v-for over store.providerLatencyMs object renders provider latency cards dynamically — no hardcoded ORANGE/MTN names; future providers render automatically
- 08-03 decision: /v1/payments proxy rule added alongside /v1/admin in quasar.config.js — plan specified both rules; /v1/payments was previously missing from devServer proxy
- 09-01 decision: DiscrepancyType has no MISSING_IN_PAYAM — neither Orange nor MTN expose batch listing API; only Payam-side transactions can be reconciled against provider
- 09-01 decision: OrangeReportAdapter catches ALL exceptions including CallNotPermittedException — UNCONFIRMED resilience; circuit-open is expected failure mode
- 09-01 decision: ProviderReportPort.provider() default method used for EnumMap wiring — each adapter self-declares its provider key
- 09-01 decision: OrangeReportAdapter does NOT call OrangeTimeUtil.parseOrangeTimestamp — PayResponse has no createtime field; P5.1 WAT compliance documented with comment
- 09-01 decision: Per-provider exception isolation in ReconciliationService.runForDate() — one provider failure must not abort reconciliation for other providers
- 09-02 decision: ReconciliationApiIT uses real /authenticate login flow — seeds admin user, POSTs credentials, extracts Set-Cookie, forwards on admin requests; avoids hand-crafted JWT issues
- 09-02 decision: FilterRegistrationBean(setEnabled=false) added to TenantSecurityConfig — prevents ApiKeyAuthenticationFilter from running as servlet-registered filter on ALL requests; filter now only runs within tenantApiKeyFilterChain scope
- 09-02 decision: /v1/admin/** added to ApiKeyAuthenticationFilter shouldNotFilter bypass list — defence-in-depth alongside securityMatcher exclusion already in TenantSecurityConfig
- 09-02 decision: TenantFilterChainIT updated to use /v1/webhooks/deliveries/tx-123 instead of /v1/admin/tenants — admin paths now excluded from API-key chain scope
- 09-02 decision: @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE) must be retained on ReconciliationResource — JWT chain only requires any authenticated user for /v1/**; @PreAuthorize enforces ROLE_ADMIN/ROLE_LTD_ADMIN specifically
- 10-03 decision: TlsStartupAssertion uses Environment.getProperty("client.momo.tcp-config.check-certificate") — OrangeMoneyConfig and MtnMoMoConfig have no tcpConfig field; the flag is in the legacy client.momo.tcpConfig YAML node (defaultTcpConfig anchor); RESEARCH.md line 203 had an incorrect assertion about config structure
- 10-03 decision: ProviderStatusResource force-creates "orange" and "mtn" CBs before getAllCircuitBreakers() — Resilience4j lazy CB creation means getAllCircuitBreakers() returns empty set if no payments processed yet (Pitfall 5)
- 10-03 decision: AuditResource.verifyChain() returns 404 with valid:false on exception — covers both not-found and unexpected errors; avoids leaking existence information
- 10-03 decision: OperationalIT seeds hash-chain data via EventLogService.append() — guarantees correct chain hashing without reproducing canonical string logic in test code; payment_event_log.transaction_id is NOT a FK so no transaction row needed

### Pending Todos

- Run `mvn resources:resources resources:testResources` before `mvn surefire:test` when bypassing lifecycle (frontend plugin blocks full lifecycle)
- Any new IT test class that makes HTTP requests must seed the JWT secret row in main.sec (or use @Sql with secData.sql)
- Any new IT test class that writes to main.transaction must delete from main.transaction in @AfterEach BEFORE deleting from main.tenant (FK constraint)
- Any new IT test class writing to main.payment_event_log must delete from main.payment_event_log in @AfterEach before main.transaction (no FK, but ordering matters for clean state)
- Any new IT test class writing to main.webhook_delivery_log must delete from main.webhook_delivery_log in @AfterEach before main.transaction (FK constraint: webhook_delivery_log.tenant_id → tenant.id)
- New Orange IT tests: orange.pay-url and orange.token-url must both resolve to WireMock — use baseUrlProperties = {"orange.base-url", "orange.pay-url"} and token-url via test application.properties
- MTN IT tests: mtn.collection-base-url and mtn.disbursement-base-url must both resolve to WireMock; collection-token-url and disbursement-token-url also need stubs
- MTN IT tests: mtn.target-environment must be set to "sandbox" in test properties (already the default in application.yaml)
- MTN IT tests: mtn.callback-ip-whitelist MUST be overridden to empty in @TestPropertySource — application.yaml default (196.0.0.0/8) rejects 127.0.0.1 test requests

### Blockers/Concerns

- Apache HTTP Client 5 retry pattern: httpclient5:5.5.2 (test scope) auto-retries 503 via HttpRequestRetryExec. Any test asserting on 503 responses MUST use SimpleClientHttpRequestFactory (not TestRestTemplate) to avoid retry masking. See PaymentOrchestratorIT.noRetryRestTemplate pattern.
- Environment: `.mvn/wrapper/maven-wrapper.properties` missing — use system `mvn` not `./mvnw`
- Environment: Frontend plugin (`generate-resources` phase) broken due to missing quasar module — bypass with `mvn compiler:compile` or invoke goals directly
- WAT timestamp consumption pattern: all Orange createtime consumers in Phase 5/6 MUST call OrangeWebhookPayload.getCreatetimeAsInstant() — never raw getCreatetime() for timestamp arithmetic (P5.1)
- Pre-existing failure: `SecurityFilterChainIT.testSecuredEndpointRequiresAuth` — pre-existing failure unrelated to Phase 3; do not count as regression
- Phase 3: Orange webhook HMAC header existence unconfirmed — verify with Orange partner before implementation
- Phase 4: MTN PUT callback confirmed in docs — verify in sandbox before relying on it
- Phase 9: Orange daily report format undocumented — requires partner verification before parser implementation
- Spring Security filter chain pattern: SecurityAdviceFilter is @Component — it runs for ALL requests via servlet container, not just JWT chain requests. Any @Component filter applies globally. When adding new IT tests, account for this.
- ApiAdvice exception handler priority: EntityNotFoundException is now mapped (→ 404). Any future JPA entity not-found scenarios will return 404 consistently. Check for conflicts before adding new @ExceptionHandler entries.
- Redis Testcontainer now active: 02-03 added GenericContainer(redis:7-alpine) to TestConfig — all future ITs will have Redis available automatically.
- Resilience4j circuit breaker pattern: @CircuitBreaker WITHOUT fallbackMethod — fallback is called for ALL exceptions not just circuit-open; for domain exceptions (SubscriberInactiveException) use ignoreExceptions config or remove fallback entirely
- Quartz + @Transactional on executeInternal: QuartzJobBean.execute() is final, Spring AOP cannot proxy it; @Transactional on executeInternal works because it's called by execute() from the Spring-managed bean
- RestRequestInterceptor pattern: converts ALL 4xx/5xx to HttpClientException at interceptor level; any new client code catching Spring HttpClientErrorException subtypes will silently not handle errors — must catch HttpClientException and check getHttpStatusCode() instead
- Orange HMAC body MUST use objectMapper.writeValueAsString(payload) — NOT request.getInputStream().readAllBytes() which returns empty (servlet stream already consumed by @RequestBody)
- Orange callback dedup key must include createtime (not just payToken) — Pitfall 8: same payToken can receive multiple status transitions; separate them by createtime
- No HMAC on MTN inbound — MTN API contract uses notifToken + IP whitelist; any plan adding HMAC to MTN path would be incorrect
- @TransactionalEventListener(AFTER_COMMIT) pattern: fires in afterCompletion phase with no active transaction; any @Transactional method called from handler MUST use REQUIRES_NEW propagation and MUST be on a separate Spring bean (self-invocation bypasses AOP proxy)
- TransactionTemplate wrapper for publishEvent: when calling publishEvent from a non-transactional context, wrap in TransactionTemplate.execute() to provide transaction boundary for @TransactionalEventListener(AFTER_COMMIT) to fire
- Quartz delivery race condition pattern: enqueue() sets nextRetryAt=null on INSERT — Quartz job uses WHERE nextRetryAt <= :now so null rows are excluded; only failed-attempt rows with scheduled nextRetryAt are picked up by Quartz; inline delivery in enqueue() handles first attempt promptly
- noRetryRestTemplate dual-context: WebhookConfig defines it as @Bean in main config; HttpStatusCodeException must be caught before generic Exception to capture HTTP status codes from 4xx/5xx responses
- Fraud rule seed data NOT present at test startup — dev profile create-drop wipes Flyway seed data; any fraud IT test MUST seed rules in @BeforeEach via JDBC (see FraudScoringServiceIT/FraudEngineIT pattern)
- FraudScoringService.evaluate() dual block: direct velocity block fires first (any exceeded velocity), then score-based block; BLOCK_THRESHOLD=70 is score threshold, not velocity threshold
- ForwardedHeaderFilter strips Forwarded header in IT tests — SecurityConfiguration registers ForwardedHeaderFilter; it removes/rewrites RFC 7239 Forwarded header before RequestMetadataProvider reads it; IP injection via Forwarded header doesn't work in tests; use MSISDN/tenantId-based signals for velocity testing (see FraudEngineIT pattern)
- FRAUD-01 COMPLETE: velocity checks, risk scoring, and device fingerprinting all wired into POST /v1/payments; fraud fires before provider dispatch; risk_score queryable from main.transaction
- ADMIN-01 COMPLETE: /v1/admin/** excluded from API-key chain via NegatedRequestMatcher(OrRequestMatcher); GET /v1/admin/transactions and GET /v1/admin/transactions/{id}/events serve JWT+ROLE_ADMIN callers
- ADMIN-02 COMPLETE: PaymentMetricsService registers payment.success/failed/fraud-blocked Counters and payment.provider.latency Timer; SSE stream at GET /v1/admin/metrics/stream; PaymentOrchestrator records all outcome paths with try/finally provider latency timing
- Any new /v1/admin/** endpoint is automatically JWT-protected (NegatedRequestMatcher exclusion already in TenantSecurityConfig); no further security config changes needed for new admin routes
- RECON-01 COMPLETE: Daily Quartz job at 02:00 UTC; ProviderReportPort with MTN+Orange adapters; ReconciliationService comparison engine; V12 schema; ReconciliationJobIT 2/2 pass
- RECON-02 COMPLETE: Three admin REST endpoints under /v1/admin/reconciliation; ReconciliationExportService (CSV+JSON); ReconciliationPage.vue; ReconciliationApiIT 5/5 pass
- Orange reconciliation path: OrangeReportAdapter does NOT use OrangeTimeUtil — PayResponse has no createtime field; P5.1 WAT guard is OrangeWebhookPayload-only
- ReconciliationService provider isolation: each provider loop in separate try/catch; one provider API failure never aborts the other provider's reconciliation loop
- OPS-03 COMPLETE: TlsStartupAssertion + GET /v1/admin/providers/status + GET /v1/admin/audit/hash-chain/{txId}; OperationalIT 5/5 pass
- TlsStartupAssertion dev-profile guard: assertion skips when "dev" profile active; in non-dev throws AppSetupException if client.momo.tcp-config.check-certificate=false; OrangeMoneyConfig/MtnMoMoConfig have NO tcpConfig field — use Environment.getProperty() not config.getTcpConfig()
- ProviderStatusResource Pitfall 5 guard: always call circuitBreakerRegistry.circuitBreaker("orange") and circuitBreakerRegistry.circuitBreaker("mtn") before getAllCircuitBreakers() — CBs are lazily created and won't appear in the set until force-created or first used
- payment_event_log.transaction_id is NOT a FK to transaction.transaction_id — it is a plain VARCHAR column; PaymentEventLog rows can be seeded without a corresponding main.transaction row
- IT test real-login pattern: ReconciliationApiIT.loginAsAdmin() seeds admin user/authority rows, POSTs /authenticate, extracts Set-Cookie, forwards cookies on admin requests — exercises full JWT filter chain
- FilterRegistrationBean(setEnabled=false) pattern: use this in any @Configuration that defines a OncePerRequestFilter @Bean to prevent Spring Boot auto-registration with servlet container

- 11-01 decision: Array holders BigDecimal[]{ZERO} and Long[]{null} capture fee values from transactionTemplate lambda — lambda locals must be effectively-final; array reference is final while array contents are mutable
- 11-01 decision: cachedResponse (not cached) used in idempotency replay — 'cached' already declared as Optional<CachedResponse> in same method scope; compiler error without rename
- 11-01 decision: WebhookDeliveryLog.feeAmount nullable (no Flyway migration needed for dev create-drop tests); null-guarded to ZERO in enqueue() and attemptDeliveryInternal()
- 11-01 decision: fee_rule JDBC seed requires rule_name column (NOT NULL) — add 'TEST-FIXED-50' literal to any new fee_rule JDBC INSERT in tests
- OPS-01 COMPLETE: fee rules configurable via POST /v1/admin/fees without restart; FeeEvaluationService evaluates FEE_FIXED and FEE_PERCENTAGE; fee_amount stored on transaction row (idempotency-safe via Pitfall 2 pattern)
- OPS-02 COMPLETE (gap closed in 10-04): CALLBACK_ANOMALY metric now computes failed/received ratio from real Micrometer counters; OrangeCallbackController and MtnCallbackController both instrument callback.received.total and callback.failed.total; AlertRuleIT 5/5 including test_callbackAnomalyAlertFires
- 10-01 decision: FeeRuleCache uses volatile List (not AtomicReference) — simpler; list replacement is atomic on 64-bit JVMs
- 10-01 decision: Dev create-drop schema has no version column — fee_rule/msisdn_prefix_route test seeds must omit version (same as FraudEngineIT fraud_rule seeds)
- 10-01 decision: test_globalFeeAppliedToPayment deletes seed row id=1 before asserting 50 XAF — multiple global rules cause first-match ambiguity; clean-state ensures deterministic evaluation
- 10-01 decision: MsisdnRouter constructor-injected MsisdnPrefixRouteCache — breaking from no-arg; Spring-managed dependency for DB-backed routing
- 10-01 decision: FeeRuleAdminResource PUT uses delete-then-save — FeeRule fully immutable; @SuperBuilder copy-override produces correct updated entity without custom @Modifying JPQL
- MSISDN routing now DB-backed (MsisdnPrefixRouteCache with Cameroon seeded prefixes in V16); hardcoded fallback retained with WARN log (Pitfall 4)
- 10-02 decision: AlertRule.metricName stored as plain String not enum — allows new metric names (e.g. CALLBACK_ANOMALY) via DB row without code change or restart
- 10-02 decision: V15 DDL excludes version column — AbstractAuditingEntity has no @Version; dev profile create-drop creates table from entity first, then Flyway CREATE TABLE IF NOT EXISTS is a no-op; version in seed INSERT causes PSQLException
- 10-02 decision: AlertFiredEventCaptor registered via @TestConfiguration inner class CaptorConfig — static inner @Component not scanned by SpringBootTest; @TestConfiguration + @Bean is the correct registration pattern for test-only listeners
- 10-02 decision: alertRuleCache.refresh() called explicitly in tests after JDBC insert — @Scheduled cache doesn't reload between test method JDBC inserts; without explicit refresh, evaluate() uses stale cache and test rule is not visible
- 10-02 decision: AlertNotificationListener uses String.format("%.4f") for decimal log output — SLF4J {} is positional substitution, not printf format specifier; {:.4f} prints literally
- OPS-02 COMPLETE: alert rules threshold-configurable via /v1/admin/alerts without restart; AlertEvaluationService reads Micrometer counters (no DB per evaluation); MINIMUM_SAMPLE_SIZE=10 guard prevents false alarms at startup; AlertRuleIT 4/4

## Session Continuity

Last session: 2026-03-25
Stopped at: Completed 11-01 — fee exposure (feeAmount+feeRuleId in PaymentResponse, feeAmount in OutboundWebhookPayload). PaymentOrchestratorIT 8/8, WebhookDeliveryIT 3/3. Phase 11 Plan 1 complete.
Resume file: None
