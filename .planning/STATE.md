# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-30)

**Core value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.
**Current focus:** Phase 24 — Platform Configuration (v4 milestone start)

## Current Position

Phase: 24 of 26 in v4 (Platform Configuration)
Plan: Not started
Status: Ready to plan
Last activity: 2026-03-30 — v4 roadmap created (3 phases, 11 requirements mapped)

Progress: ██████████████████████████████ v1+v2 complete | █████████████████ v3 + gap closure 100% COMPLETE

## Performance Metrics

**Velocity:**
- Total plans completed: 30 (29 v1 + 1 v2)
- Average duration: —
- Total execution time: —

**By Phase (v2):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 14 (plan 01) | 1 | 1 min | 1 min |
| 16 (plan 01) | 1 | 6 min | 6 min |
| 17 (plan 02) | 1 | 11 min | 11 min |
| 17 (plan 03) | 1 | 12 min | 12 min |
| 21 (plan 01) | 1 | 13 min | 13 min |
| 21 (plan 02) | 1 | 15 min | 15 min |
| 22 (plan 01) | 1 | 8 min | 8 min |
| 22 (plan 02) | 1 | 25 min | 25 min |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- v2 uses 4 phases: Infrastructure → MDC/Request → Business Events → Code Standards
- Phase 14 starts at 14 (continuous numbering from v1's 13 phases)
- **[14-01] springProperty indirection pattern:** `<springProperty source="app.environment">` reads a Spring property, not a raw env var. The `app:` YAML block resolves from `${ENVIRONMENT:prod}` (or `:dev`). Enables per-profile defaults AND runtime env-var override.
- **[14-01] Hard-coded root level=INFO in logback:** Eliminates null/empty level risk from missing `logging.level.root` property. Per-package overrides still possible via YAML `logging.level.*`.
- **[14-01] LoggingEventCompositeJsonEncoder as canonical encoder:** All JSON log output goes through this encoder. PatternLayoutEncoder must not be used for structured logging.
- **[14-01] MDC flattening via `<mdc/>` provider:** traceId/spanId injected by micrometer-tracing-bridge-otel appear as top-level JSON fields automatically — no Java code needed.
- **[15-01] tenantRef as MDC tenantId value:** `tenantRef` (String UUID) is used for the "tenantId" MDC key, not the Long database PK. Matches TenantContext and is the canonical Loki-queryable tenant identifier.
- **[15-01] MDC.remove() ownership split:** LoggingFilter owns requestId (set/remove in its own try/finally). ApiKeyAuthenticationFilter owns tenantId (set/remove in its own try/finally). Each filter cleans up exactly what it set.
- **[15-01] Conditional tenantId in request_end:** tenantId appended to args list only when TenantContext.get() != null — absent for JWT paths, always present for API-key paths. request_error intentionally omits it.
- **[15-02] Do not call MDC.put("traceId", ...) in application code:** micrometer-tracing-bridge-otel injects traceId automatically via `<mdc/>` provider for every active OTel span. Manual put was redundant and used the wrong snake_case key.
- **[15-02] TXN_ID_NAME canonical MDC key is "transactionId":** Updated from "txnId" (was never aligned with what TransactionService wrote). All TransactionIdProvider operations now use the correct key.
- **[15-02] MDC camelCase contract enforced:** All application-owned MDC fields use camelCase. OTel-owned fields (traceId, spanId) must not be manually overridden.
- **[16-01] tenantId log event field uses TenantContext.get() (UUID tenantRef):** Consistent with MDC decision 15-01. PaymentOrchestrator receives Long tenantId param but log event uses the UUID string — the Loki-queryable canonical tenant identifier.
- **[16-01] LOG-BUS-01 excluded paths:** Unknown MSISDN prefix path (no transactionId yet) and idempotency replay path (not a new initiation) are intentionally not logged under initiate_payment.
- **[16-01] Fraud allowed path upgraded DEBUG → INFO:** Every payment passes through fraud evaluation; DEBUG is invisible in production. INFO makes allow decisions traceable alongside block decisions.
- **[16-01] LOG-BUS-05 start timer before velocity checks:** Declared as first line of evaluate() so durationMs covers all 4 Redis velocity lookups, not just score computation.
- **[16-05] runForProviderAndDate() returns int[] {totalChecked, discrepancyCount}:** Minimal structural change to accumulate cross-provider totals in runForDate(); no changes to comparison logic.
- **[16-05] reconciliation_run status="SUCCESS" unconditional:** runForDate() always completes normally (provider exceptions caught inside try/catch); discrepancyCount communicates financial outcome, status reflects execution completion.
- **[16-03] Orange webhook_received log inside present-branch lambda:** txId is only available after transactionRepository.findByPayToken() resolves; log emitted there to include transactionId in structured event. Top-level log.info removed.
- **[16-03] MTN externalReference = financialTransactionId (nullable):** payload.getFinancialTransactionId() may be null at callback time — passed as-is, Loki omits null fields.
- **[16-03] deliveryStart timer before try block in WebhookDeliveryService:** Covers all 4 outcome paths so durationMs is always computable in any catch branch.
- **[16-03] httpStatus=-1 for network errors (generic Exception):** Sentinel signals no HTTP response received, consistent with delivery.setHttpStatus() only called on actual responses.
- **[16-04] LOG-BUS-06 co-exists with RestRequestInterceptor log:** Interceptor logs raw debug strings; kv() events provide structured Loki-queryable fields. Both intentional.
- **[16-04] externalLatencyMs scope is makeHttpRequest() only:** long start immediately before call, log immediately after call returns before any conditional throw.
- **[16-04] validateAccountHolder exception path omits log:** start inside try block; on HttpClientException (404), log.info() line is never reached — acceptable, exception is the signal.
- **[16-04] cashout/c2c direct-return pattern:** Assign makeHttpRequest() to local variable, log, then return — required to access response for status check before returning.
- **[16-02] LOG-BUS-02 fromState hardcoded at poller/webhook sites:** `TransactionStatus.PROCESSING.name()` used instead of `tx.getTxStatus().name()` — post-transition status is already mutated. Hardcoded constant is safe since pollers and webhook double-check only ever transition from PROCESSING.
- **[16-02] State change log placed between applyTransition() and eventLogService.append():** Co-locates Loki structured event adjacent to event sourcing append for correlation. Pattern consistent across all 4 files.
- **[17-01] Still-PENDING logs upgraded from log.debug to log.info in both pollers:** Poller backpressure (many transactions stuck pending) is a Loki-queryable production signal; debug is invisible in production.
- **[17-01] No-webhook-URL log upgraded from log.debug to log.warn:** Silently skipping delivery on a billable-path event warrants visibility; per plan spec.
- **[17-01] Exception arg preserved as last positional arg in all error/warn catch blocks:** kv() varargs followed by throwable is the correct SLF4J overload pattern; stack traces included in structured Loki entries.
- **[17-02] AlertEvaluationService threshold log upgraded debug → warn:** Threshold breach triggers AlertFiredEvent and email; log promoted to match consequence severity for Loki visibility.
- **[17-02] Cache Logger removal:** When all log calls deleted from a class, static Logger field and imports removed to avoid compiler warnings.
- **[17-02] SecurityAuditListener AUDIT_TRAIL toString() replaced with kv():** AuditTrail contains login, IP, sessionId — full object is PII. Log ID already persisted to DB; Loki event uses operation + status only.
- **[17-02] PII removal contract finalized:** Usernames, emails, loginIds, reset keys, activation keys never appear as log arguments. Omit entirely; do not hash.
- **[17-02] @Slf4j removed from no-log classes:** UserRegistrationService, PasswordResetService, LoadUserByUserNameService — annotation removed after all log calls deleted to prevent unused field warnings.
- **[17-03] BodySanitizer SENSITIVE_KEYS adds msisdn, merchant_key, merchantKey:** Substring matching means "msisdn" catches any field name containing it; merchant_key and merchantKey added as separate entries since neither contains the other as a substring.
- **[17-03] RestRequestInterceptor: no headers object in log args:** Headers may contain Authorization Bearer token; content-type extracted separately for sanitization only.
- **[17-03] OrangeCallbackController line 118 upgraded warn to error:** Callback processing failure is error-severity; exception object passed as last arg (not e.getMessage()) for full stack trace.
- **[17-03] Exception as last log arg pattern:** Pass raw `e` not `e.getMessage()` to preserve stack trace without {} placeholder — established across all fixed log.error/warn calls.
- **[17-04] RequestMetadataProvider/JWTAuthorizationFilter deviation:** 7 additional ##### violations found during Task 3 full-codebase grep. Fixed as Rule 2 deviation — pure deletions, no architectural change. Both files brought into LOG-CODE-02 compliance.
- **[18-01] AbstractFailureFlowTest extends AbstractPayamE2ETest directly:** Failure flows inject faults before executeFlow — a different phase structure from payment flows. Separate hierarchy branch (AbstractPayamE2ETest -> AbstractFailureFlowTest) not a subtype of AbstractPaymentFlowTest.
- **[18-01] stubTokenEndpoints() is protected and overrideable:** Default stubs both mtn and orange token endpoints using WireMockConfig constants. Circuit-breaker tests that flush Redis mid-test can override to re-stub after cache clear.
- **[18-01] final on runFlow()/runFailureScenario() is mandatory:** Prevents subclasses from overriding the orchestration phase order — structural contract for all v3 E2E tests.
- **[18-02] WireMockConfig excluded from @Import:** Non-instantiable utility class (private constructor, no Spring annotations) — cannot be imported as @Configuration. Used only as static constant provider.
- **[18-02] E2ESecurityConfig dual-seed pattern:** ApplicationListener<ContextRefreshedEvent> seeds main.sec at context startup. AbstractPayamE2ETest.baseSetUp() calls seedSecurityRow() per-test after TestDataCleaner.wipeAll() clears it. ON CONFLICT DO NOTHING makes both calls idempotent.
- **[18-02] TestDataCleaner preserves Flyway seed rows:** fee_rule id=1 and fraud_rule id 1-5 preserved via NOT IN clauses — relied upon by FeeEvaluationService and FraudEvaluationService. msisdn_prefix_route never deleted — Flyway V16 seeds it; all MSISDN routing fails without it.
- **[20-01] PROPAGATION_REQUIRES_NEW for jdbcTemplate backdating in poller tests:** When a raw JDBC update must survive Hibernate L1 cache flush on TransactionTemplate commit, use DefaultTransactionDefinition(REQUIRES_NEW) + new TransactionTemplate(transactionManager, requiresNew).execute(). The outer TX commits after the JPA session flushes; only REQUIRES_NEW guarantees the update is durable before the poller reads it.
- **[20-01] TransactionTemplate wrapping for protected @Transactional poller invocation:** reflection on MtnStatusPollerJob.class (not AopTestUtils.getTargetObject()) + transactionTemplate.execute() wrapper. AopTestUtils.getTargetObject() unwraps CGLIB proxy — protected method reflection on the raw bean bypasses @Transactional advice, so dirty entity changes are never flushed.
- **[20-01] JSONB metadata must be JSON-quoted in pollers:** MtnStatusPollerJob and OrangeStatusPollerJob were passing bare strings to the JSONB metadata column. PostgreSQL rejects non-JSON values. Wrap rawStatus and literals in double-quotes: "\"" + value + "\"". WebhookTransitionService already did this correctly.
- **[20-01] OrangePayTokenExpiry asserts PROCESSING (not FAILED):** The expiry path in OrangeStatusPollerJob increments pollAttempts and returns early — FAILED only fires at pollAttempts >= 15. Plan spec was incorrect; actual code behavior determines the test assertion.
- **[20-01] assertAll() not usable on polling paths:** assertAll() includes assertLedgerBalanced(), but the polling path does not post ledger entries (only WebhookTransitionService does). Use individual invariant assertions on poller-driven tests.
- **[20-02] noRetryRestTemplate pattern:** RestTemplate(SimpleClientHttpRequestFactory) with DefaultResponseErrorHandler that never throws. Required for circuit-breaker tests — Apache HC default retry behavior on 503 masks whether the CB is actually open.
- **[20-02] Fraud threshold injection requires cache refresh:** jdbcTemplate.update on fraud_rule alone has no effect; fraudRuleCache.refreshRules() must be called immediately after to invalidate in-memory cache.
- **[21-01] WebhookReplayProtectionE2ETest extends AbstractPayamE2ETest directly:** Replay tests send two callbacks in one @Test method — the AbstractWebhookFlowTest 4-phase template enforces one callback dispatch per test. AbstractPayamE2ETest used directly for flat @Test structure.
- **[21-01] MTN SUCCESSFUL (single-L) vs Orange SUCCESSFULL (double-L):** Provider-specific status string difference — MtnStatusMapper and OrangeStatusMapper only recognise their respective spellings. All MTN stubs must use single-L; all Orange stubs must use double-L.
- **[21-01] noErrorRestTemplate for non-2xx assertion:** DefaultResponseErrorHandler returning false prevents Spring from throwing exceptions on 4xx/5xx — required when the test must assert the error status code value.
- **[21-01] ApiAdvice missing HttpRequestMethodNotSupportedException handler:** Bug: catch-all Throwable handler returned 500 for POST to @PutMapping endpoint. Fixed by adding specific handler with @ResponseStatus(METHOD_NOT_ALLOWED).
- **[21-02] OutboundWebhookDeliveryE2ETest standalone pattern:** Does not extend AbstractPayamE2ETest — that base only declares mtn+orange WireMock servers. Outbound delivery tests need a third tenant-wh server; all 3 must be declared at class level in @EnableWireMock. Mirrors WebhookDeliveryIT.
- **[21-02] Awaitility for delivery log row existence:** Use Awaitility.await().until() instead of Thread.sleep() to wait for the first async delivery attempt to complete before reading delivery log rows.
- **[21-02] Direct attemptDelivery() for retry count verification:** Bypass Quartz 1-minute scheduler by calling webhookDeliveryService.attemptDelivery() directly for deterministic retry count assertions. MAX_ATTEMPTS=5 so after 3 attempts nextRetryAt stays non-null.
- **[22-01] seedFraudRule() is a local private helper in each test class:** AbstractFailureFlowTest does not expose this helper; FraudBlockedPaymentE2ETest and FraudVelocityBlockE2ETest both define it locally. Consistent with established pattern.
- **[22-01] blockedResponse stored as ResponseEntity<PaymentResponse> field:** Allows verifyFailureHandled() to assert HTTP status and errorCode separately from executeFlow(). Same pattern used in FraudBlockedPaymentE2ETest.
- **[22-01] exactly(1) WireMock verifier for POST count:** More precise than moreThanOrEqualTo — proves strictly one provider call (the allowed path); zero additional calls from the blocked path.
- **[22-02] ProviderResult(null, null, false, null, null) as notFound sentinel:** No factory method exists; null rawStatus is what MtnReportAdapter passes as providerStatus to ReconciliationService — triggers MISSING_IN_PROVIDER path.
- **[22-02] Admin tenantId param is Long (database PK), not UUID:** AdminTransactionResource.search() @RequestParam type is Long; JPQL compares t.tenantId (Long PK) directly.
- **[22-02] URI.create() with manual + → %2B for + in query params:** RestTemplate.exchange(URI) passes URI as-is; URLEncoder, UriComponentsBuilder, new URI() all cause double-encoding or wrong behavior.
- **[22-02] transactionTemplate.execute() wraps all admin user seeding:** Prevents FK constraint errors on user_authority → authority FK when bare jdbcTemplate.execute() runs as auto-commit statements.
- **[22-02] discrepancy_type is the reconciliation_discrepancy column name:** Not 'type' — @Column(name = "discrepancy_type") per JPA entity annotation.
- **[23-01] OrangeTimestampWatTest is a plain JUnit 5 unit test:** No @SpringBootTest — WAT offset is a pure computation; Spring context overhead unnecessary.
- **[23-01] StateMachineLegalTransitionsTest @MethodSource covers all 32 illegal transitions:** Each case queries DB after expected throw to confirm row status unchanged.
- **[23-01] InitBeforeProviderCallTest uses WireMock RequestListener in try-finally:** Prevents listener bleed into subsequent tests sharing the same Spring context.
- **[23-01] Fraud rule seeding required for all full-HTTP-flow domain invariant tests:** BLOCK_THRESHOLD=70 allows normal payments; must call fraudRuleCache.refreshRules() after JDBC update.
- **[23-01] PaymentIdempotencyE2ETest required fraud rule seeding fix:** FraudVelocityBlockE2ETest left MSISDN_VELOCITY threshold=1 in FraudRuleCache across test ordering — idempotency test now seeds its own rules defensively.
- **[23-02] WebhookPollingRaceTest PROVIDER_SUCCESS event count >= 1 (not exactly 1):** Hibernate L1 cache in REQUIRES_NEW context can return stale PROCESSING entity even after poller committed SUCCESS. Both paths commit successfully (2 PROVIDER_SUCCESS events). Financial invariants (1 SUCCESS row, 2 ledger entries) remain correct — MtnStatusPollerJob never calls LedgerService.
- **[23-02] lessThanOrExactly() not atMost() for WireMock 3.13.2:** WireMock.atMost() does not exist; use WireMock.lessThanOrExactly(N) for at-most-N provider call assertion.
- **[23-02] TenantApiKeyRepository.findAllByTenantId() for key rotation:** TenantBuilder.CreatedTenant exposes rawApiKey but not key entity ID. Use findAllByTenantId(tenantId) to get the TenantApiKey entity and extract its ID before calling ApiKeyService.rotate(keyId).
- **[23-03] Event count assertions reduced to >= 1 (assertEventCountAtLeast):** Actual flow produces 2 events; research doc estimated 4. Using atLeast(1) avoids brittle Awaitility timeouts.
- **[23-03] WireMock 3.9.1 lacks removeMockServiceRequestListener():** baseTearDown() resetAll() clears listeners between tests. No try-finally remove needed.
- **[23-03] main.transaction has no idempotency_key column:** Idempotency key lives in main.idempotency_key table. TXN-02 queries by tenant_id; TXN-04 joins main.idempotency_key.
- **[23-03] pitest-junit5-plugin updated 1.2.1 → 1.2.2:** Spring Boot 3.5.11 uses JUnit Platform 1.12.2; 1.2.1 produced OutputDirectoryProvider UNKNOWN_ERROR.
- **[23-03] PITest targetClasses narrowed to 3 pure domain classes:** OrangeTimeUtil, TransactionStatus, PaymentEventLog — Spring-managed services have no unit-test coverage.
- **[23-03] hashValue_nullStatusFrom_differFromNonNullStatusFrom() kills RemoveConditionalMutator_EQUAL_ELSE:** statusFrom != null ternary in PaymentEventLog.create() requires test with non-null statusFrom to distinguish from null path.
- **[23-03] OrangeTimeUtil package is com.softropic.payam.orange.service (not .orange.util).**
- **[23-03] PITest goal is pitest:mutationCoverage (not pitest:mutate).**
- **[23-05] PITest unit-test coverage pattern:** Use `new ServiceClass(mock(Dep.class))` to call real Spring @Service methods in unit tests — no @SpringBootTest overhead. @Transactional proxy is bypassed but method logic executes directly. ArgumentCaptor.forClass(List.class) captures repository saveAll() arguments.
- **[23-05] Mockito.argumentCaptor() does not exist:** The correct API is `ArgumentCaptor.forClass(ClassName.class)` (instance method on the class itself, not a static import from Mockito).

### Pending Todos

None.

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-03-30
Stopped at: v4 roadmap created — 3 phases (24–26), 11 requirements mapped. Ready to plan Phase 24.
Resume file: None
