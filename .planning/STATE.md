# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-27)

**Core value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.
**Current focus:** Phase 19 — Verifiers + Test Data Builders (v3 E2E Test Suite)

## Current Position

Phase: 19 of 23 (Verifiers + Test Data Builders)
Plan: Not started
Status: Ready to plan
Last activity: 2026-03-27 — Phase 18 (Test Infrastructure) complete — 5/5 must-haves verified

Progress: ██████████████████████████████ v1+v2 complete | ██░░░░░░░░ v3 ~17%

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

### Pending Todos

None.

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-03-27
Stopped at: Completed 18-02-PLAN.md — six E2E test infrastructure config classes in com.softropic.payam.config
Resume file: None
