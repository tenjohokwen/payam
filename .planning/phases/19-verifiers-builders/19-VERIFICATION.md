---
phase: 19-verifiers-builders
verified: 2026-03-27T00:00:00Z
status: passed
score: 12/12 must-haves verified
---

# Phase 19: Verifiers + Builders Verification Report

**Phase Goal:** Build verifier classes and test data builders for E2E tests.
**Verified:** 2026-03-27
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths — Plan 01 (Verifiers)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Every domain invariant can be asserted with a single-line verifier call | VERIFIED | InvariantVerifier exposes assertLedgerBalanced, assertNoDoubleCharge, assertTenantIsolation, assertLegalStateTransition, assertWebhookDoubleCheckFired, assertFraudEvaluatedBeforeProviderCall, assertAll — all single-call |
| 2 | Hash chain integrity is verified using the exact same SHA-256 canonical string as production | VERIFIED | HashChainVerifier.assertChainValid recomputes `DigestUtils.sha256Hex(txId + "\|" + eventType + "\|" + statusFrom + "\|" + statusTo + "\|" + actor + "\|" + previousHash)` — exact 6-field canonical string, null statusFrom rendered as literal "null" |
| 3 | N+1 query regressions are detectable via QueryCountVerifier wrapping SqlStatementHolder | VERIFIED | QueryCountVerifier.reset() calls SqlStatementHolder.initStatement() + QueryCountHolder.clear(); assertSelectCountAtMost uses QueryCountHolder.getGrandTotal().getSelect(); assertSelectCountExact uses SqlStatementHolder.getStatement().assertThatSelect().hasCount() |
| 4 | WireMock call counts are assertable without HTTP log parsing | VERIFIED | ProviderCallVerifier.assertMtnCallCount/assertOrangeCallCount/assertMtnGetCallCount/assertNoProviderCalls use mtnServer.verify(count, requestedFor(...)) — no log parsing |
| 5 | HMAC-SHA256 outbound webhook signature is verifiable using the exact same Mac algorithm as WebhookDeliveryService | VERIFIED | WebhookDeliveryVerifier.assertHmacSignatureCorrect uses Mac.getInstance("HmacSHA256") + org.apache.commons.codec.binary.Hex.encodeHexString — matches production |
| 6 | Tenant isolation is assertable across all tables and Redis namespaces in a single call | VERIFIED | TenantIsolationVerifier.assertNoDataLeaksToOtherTenant checks main.transaction, main.ledger_entry, main.idempotency_key, main.webhook_delivery_log, and Redis idempotency key namespace |

**Score:** 6/6 verifier truths verified

### Observable Truths — Plan 02 (Builders)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Test data for any payment scenario is constructable with deterministic builders via a single create(jdbcTemplate) call | VERIFIED | ReconciliationReportBuilder.create(jdbc) inserts report and discrepancy rows and returns reportId; ApiKeyBuilder.create(jdbc) inserts key and returns rawKey; TenantBuilder.create(tenantService, tenantRepository) delegates to service |
| 2 | All builders use deterministic fixed UUIDs seeded per test class with no shared mutable state | VERIFIED | DeterministicUuidFactory: `private long counter = 0` (instance field, not static); `new UUID(seed, counter++)` — deterministic per seed; reset() available for replay. PaymentRequestBuilder.withDeterministicIdempotencyKey(factory) wires factory.next() to idempotencyKey |
| 3 | TenantBuilder delegates tenant creation and API key hashing to TenantService (not hand-rolled SHA-256) | VERIFIED | TenantBuilder.create() calls tenantService.createTenant(name, environment) — no DigestUtils reference in TenantBuilder; API key hashing is handled entirely by TenantService → ApiKeyService |
| 4 | OrangeWebhookPayloadBuilder produces a createtime in yyyy-MM-dd HH:mm:ss format (WAT, no offset) | VERIFIED | DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); ZoneId.of("Africa/Douala"); default createtime = formatWat(Instant.now()); both withCreatetime(Instant) and withCreatetime(ZonedDateTime) apply the formatter without offset |
| 5 | FraudSignalBuilder can pre-seed velocity counter state via Redis key deletion | VERIFIED | resetVelocityCounters(StringRedisTemplate redis) iterates velocityResets list and calls redis.delete("fraud:velocity:" + signalName + ":" + identifier) — exact key prefix |
| 6 | ReconciliationReportBuilder inserts directly via JdbcTemplate for both report and discrepancy rows | VERIFIED | create(jdbc): INSERT INTO main.reconciliation_report; then for each DiscrepancyEntry: INSERT INTO main.reconciliation_discrepancy; returns TSID-generated reportId |

**Score:** 6/6 builder truths verified

**Overall Score:** 12/12 must-haves verified

### Required Artifacts

#### Verifiers (10 files)

| Artifact | Status | Details |
|----------|--------|---------|
| `e2e/verify/DatabaseVerifier.java` | VERIFIED | 73 lines; assertPaymentRow, assertEventCount, assertNoOrphanEvents, assertNoDuplicateIdempotencyKey; no Spring annotations |
| `e2e/verify/HashChainVerifier.java` | VERIFIED | 81 lines; DigestUtils.sha256Hex; exact canonical string; genesis/chain linkage assertions |
| `e2e/verify/InvariantVerifier.java` | VERIFIED | 191 lines; constructs all 7 delegate verifiers internally; assertAll chains ledger + double-charge + state-transition + hash chain |
| `e2e/verify/EventVerifier.java` | VERIFIED | 59 lines; assertEventCount, assertEventSequence (order-sensitive), assertEventPresent; queries main.payment_event_log only |
| `e2e/verify/LedgerVerifier.java` | VERIFIED | 97 lines; assertLedgerBalanced checks 2 rows, DEBIT/CUSTOMER_WALLET, CREDIT/PROVIDER_CLEARING, equal amounts; hardcoded account codes matching production LedgerService |
| `e2e/verify/ProviderCallVerifier.java` | VERIFIED | 61 lines; assertMtnCallCount, assertMtnGetCallCount, assertOrangeCallCount, assertNoProviderCalls; WireMock.verify() only |
| `e2e/verify/WebhookDeliveryVerifier.java` | VERIFIED | 103 lines; Awaitility.await().atMost(5, SECONDS); HMAC recomputation with Apache Hex; X-Payam-Signature header check |
| `e2e/verify/TenantIsolationVerifier.java` | VERIFIED | 77 lines; 4 table assertions + 1 Redis namespace assertion in single call |
| `e2e/verify/CacheVerifier.java` | VERIFIED | 98 lines; exact key prefixes: idempotency:, mtn:token:cm, orange:token:cm, fraud:velocity:; hasKey() only for velocity buckets |
| `e2e/verify/QueryCountVerifier.java` | VERIFIED | 64 lines; SqlStatementHolder.initStatement() + QueryCountHolder.clear() in reset(); Javadoc precondition note about spy activation |

#### Builders (8 files)

| Artifact | Status | Details |
|----------|--------|---------|
| `e2e/builder/DeterministicUuidFactory.java` | VERIFIED | 41 lines; instance-level counter; new UUID(seed, counter++); reset() |
| `e2e/builder/TenantBuilder.java` | VERIFIED | 82 lines; delegates to TenantService.createTenant; updates webhookUrl/Secret via TenantRepository if set; CreatedTenant record |
| `e2e/builder/ApiKeyBuilder.java` | VERIFIED | 75 lines; DigestUtils.sha256Hex(rawKey); random UUID credential; direct JDBC insert; DbUtil.generateDbRandom() for TSID PK |
| `e2e/builder/PaymentRequestBuilder.java` | VERIFIED | 94 lines; builds PaymentRequest; forOrange() sets Orange MSISDN; withDeterministicIdempotencyKey(factory) |
| `e2e/builder/MtnWebhookPayloadBuilder.java` | VERIFIED | 82 lines; forTransaction, asSuccessful, asFailed(reason); builds MtnCallbackPayload via setters |
| `e2e/builder/OrangeWebhookPayloadBuilder.java` | VERIFIED | 125 lines; WAT zone + format; createtime in props map; MAPPER.convertValue honours @JsonProperty mappings |
| `e2e/builder/FraudSignalBuilder.java` | VERIFIED | 88 lines; fraud:velocity: key prefix; redis.delete per entry; build() returns Map<String,String> |
| `e2e/builder/ReconciliationReportBuilder.java` | VERIFIED | 122 lines; TSID PK generation; inserts report + discrepancy rows; returns reportId |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| HashChainVerifier | DigestUtils.sha256Hex | Canonical: txId\|eventType\|statusFrom\|statusTo\|actor\|previousHash | WIRED | Exact string construction verified at lines 65-70 |
| WebhookDeliveryVerifier | main.webhook_delivery_log | JdbcTemplate query delivered=true + WireMock X-Payam-Signature header | WIRED | assertDelivered queries webhook_delivery_log; assertHmacHeaderPresent checks X-Payam-Signature matching "sha256=.*" |
| InvariantVerifier | DatabaseVerifier, LedgerVerifier, EventVerifier, HashChainVerifier, ProviderCallVerifier, WebhookDeliveryVerifier, TenantIsolationVerifier | Constructor-injected delegates; assertAll() calls each | WIRED | All 7 delegates constructed internally; assertAll invokes 4 of them; accessor methods expose remaining |
| CacheVerifier | StringRedisTemplate | hasKey() for all key types | WIRED | All 5 assertion methods use redis.hasKey(); no value reads on velocity buckets |
| TenantBuilder | TenantService.createTenant | Spring bean call; returns TenantCreationResult with rawKey | WIRED | Line 62: tenantService.createTenant(name, environment); no hand-rolled hashing |
| ApiKeyBuilder | ApiKeyAuthenticationFilter | DigestUtils.sha256Hex(rawKey) stored as key_hash | WIRED | Line 62: DigestUtils.sha256Hex(rawKey) — same algorithm as production filter |
| OrangeWebhookPayloadBuilder | OrangeCallbackController / OrangeTimeUtil | createtime field in yyyy-MM-dd HH:mm:ss WAT format | WIRED | DateTimeFormatter + Africa/Douala zone; no offset appended to string |
| FraudSignalBuilder | VelocityCheckService / Bucket4j | DEL fraud:velocity:{signal}:{identifier} resets bucket | WIRED | Line 71: redis.delete("fraud:velocity:" + entry[0] + ":" + entry[1]) |
| ReconciliationReportBuilder | main.reconciliation_report / main.reconciliation_discrepancy | Direct JDBC INSERT on both tables | WIRED | Lines 101-117: INSERT into both tables with TSID PKs; reportId returned |

### Anti-Patterns Found

No anti-patterns detected:
- No Spring annotations (@Component, @Service, @Repository, @Bean) in any verifier or builder
- No static mutable state in any builder (DeterministicUuidFactory.counter is an instance field)
- No placeholder or TODO content
- No empty method bodies
- No stub return values (null, {}, [])
- QueryCountVerifier has documented precondition about spy activation (informational, not a stub)

### Notable Observations

**assertAll signature extension:** The plan listed `assertAll(transactionId, tenantId, expectedStatus)` with 3 parameters. The implementation takes 4 parameters: `assertAll(transactionId, tenantId, idempotencyKey, expectedStatus)`. This is correct — `assertNoDoubleCharge` requires the idempotency key, so the 4-parameter signature is functionally necessary, not a regression.

**DatabaseVerifier method naming:** The plan listed `assertIdempotencyKeyPresent` as a DatabaseVerifier export. The actual method is `assertNoDuplicateIdempotencyKey(Long tenantId, String idempotencyKey)`. The name is more precise and the semantics match the must-have (idempotency uniqueness assertion). Not a gap.

**QueryCountVerifier uses dual backends:** `assertSelectCountAtMost` uses `QueryCountHolder` (datasource-proxy) and `assertSelectCountExact` uses `SqlStatementHolder`. Both are cleared by `reset()`. This is additive — the must-have for SqlStatementHolder wrapping is satisfied.

**Builder file count:** Plan objective text says "8 builder classes + 1 UUID factory" but `files_modified` lists exactly 8 files with DeterministicUuidFactory included among them. All 8 expected files are present.

---

_Verified: 2026-03-27_
_Verifier: Claude (gsd-verifier)_
