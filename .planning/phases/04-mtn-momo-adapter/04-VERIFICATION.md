---
phase: 04-mtn-momo-adapter
verified: 2026-03-24T04:30:00Z
status: passed
score: 5/5 must-haves verified
re_verification: false
---

# Phase 4: MTN MoMo Adapter Verification Report

**Phase Goal:** MTN MoMo provider adapter — OAuth2 lifecycle, RequestToPay, disbursement, account validation
**Verified:** 2026-03-24T04:30:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | RequestToPay initiates and receives async confirmation when customer approves | VERIFIED | `MtnMoMoPort.initiateMerchantPayment()` validates account, generates UUID, persists providerRef via `REQUIRES_NEW` before calling `mtnMoMoClient.requestToPay()`. Returns `ProviderResult.pending(referenceId, "PENDING")`. `MtnMoMoPortIT.request_to_pay_initiation_returns_pending_result_with_provider_ref` verifies stub returns 202, result is pending, UUID providerRef stored in DB. |
| 2 | OAuth2 access tokens are cached in Redis and refreshed automatically before expiry | VERIFIED | `MtnTokenService` uses `TOKEN_KEY = "mtn:token:cm"`, TTL 55 min, NX lock pattern. `getAccessToken()` checks Redis first, acquires NX lock on miss, fetches via `mtnMoMoClient.fetchCollectionToken()`, stores with TTL. `evict()` deletes the key. All three paths tested in `MtnTokenServiceIT` (3 tests pass). |
| 3 | MTN sends callbacks via HTTP PUT — received and processed correctly, not discarded (P1.4 fix) | VERIFIED | `MtnCallbackController` uses `@PutMapping("/v1/callbacks/mtn")` (not `@PostMapping`). Calls `mtnMoMoPort.processCallback(payload)` which logs + stores `financialTransactionId` in `REQUIRES_NEW`. Returns 200. `MtnMoMoPortIT.put_callback_endpoint_accepts_put_and_returns_200` confirms PUT returns 200. Note: full state-transition on receipt is Phase 6 scope per ROADMAP — the P1.4 fix (HTTP method correctness) is complete. |
| 4 | Account holder validation and balance query (merchant wallet) return correct responses | VERIFIED | `MtnMoMoClient.validateAccountHolder()` GETs `/v1_0/accountholder/MSISDN/{msisdn}/basicuserinfo`, catches `HttpClientException` with `404` to throw `MtnAccountInactiveException`. `MtnMoMoClient.getBalance()` GETs `/v1_0/account/balance` returning `AccountBalanceResponse`. `MtnMoMoPort.validateSubscriber()` returns `SubscriberStatus(true,...)` on 200, `SubscriberStatus(false,...)` on `MtnAccountInactiveException`. Tested by 2 IT tests (200 and 404 scenarios). |
| 5 | MTN callback requests from non-whitelisted IPs are rejected before processing | VERIFIED | `MtnIpWhitelistInterceptor` implements `HandlerInterceptor.preHandle()`, reads `X-Forwarded-For`, supports exact IP and octet-boundary CIDR matching (e.g. `/8`). Returns 403 + `false` when IP not in whitelist. Registered only for `/v1/callbacks/mtn` via `MtnWebConfig.addInterceptors()`. `application.yaml` pre-populates whitelist with `196.0.0.0/8`. Empty whitelist = sandbox mode (accept all, log warning). |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Status | Lines | Details |
|----------|--------|-------|---------|
| `src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java` | VERIFIED | 206 | Extends `AbstractClient`. All 7 operations: `fetchCollectionToken`, `fetchDisbursementToken`, `requestToPay`, `getRequestToPayStatus`, `validateAccountHolder`, `getBalance`, `disburse`. Null body for token POST (Pitfall 4 — no form encoding). `disburse()` uses `getDisbursementSubscriptionKey()` (Pitfall 5). |
| `src/main/java/com/softropic/payam/mtn/service/MtnTokenService.java` | VERIFIED | 66 | `TOKEN_KEY = "mtn:token:cm"`, `TTL = 55 min`, NX lock. `getAccessToken()` and `evict()` fully implemented. |
| `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` | VERIFIED | 194 | Implements `MobileMoneyPort`. All three interface methods: `initiateMerchantPayment`, `getTransactionStatus`, `validateSubscriber`. `processCallback()` is public (non-interface). `persistProviderRef(REQUIRES_NEW)` and `storeFinancialTxId(REQUIRES_NEW)` correctly scoped. |
| `src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java` | VERIFIED | 113 | Extends `QuartzJobBean`. Queries `PROCESSING` + `MTN` + cutoff. Uses `tx.getProviderRef()` (not `getPayToken()`). No `assertPayTokenFresh()`. Max-attempts guard at 15. |
| `src/main/java/com/softropic/payam/mtn/web/MtnCallbackController.java` | VERIFIED | 46 | `@PutMapping("/v1/callbacks/mtn")` — not `@PostMapping`. Returns `ResponseEntity.ok().build()`. |
| `src/main/java/com/softropic/payam/mtn/web/MtnIpWhitelistInterceptor.java` | VERIFIED | 76 | `HandlerInterceptor.preHandle()`. X-Forwarded-For aware. CIDR and exact-match. 403 on rejected. |
| `src/main/java/com/softropic/payam/mtn/web/MtnWebConfig.java` | VERIFIED | 27 | `addPathPatterns("/v1/callbacks/mtn")` only — does not affect other endpoints. |
| `src/main/java/com/softropic/payam/security/config/AppEndpoints.java` | VERIFIED | — | `PUBLIC_ENDPOINTS` includes `"/v1/callbacks/mtn"` (line 26). |
| `src/main/java/com/softropic/payam/mtn/config/MtnMoMoConfig.java` | VERIFIED | 70 | `@ConfigurationProperties(prefix = "mtn")`. All 9 fields including `callbackIpWhitelist` and nested `Poller`. |
| `src/main/resources/db/migration/V7__transaction_mtn_fields.sql` | VERIFIED | 5 | `ADD COLUMN IF NOT EXISTS mtn_financial_tx_id VARCHAR(255)` on `main.transaction`. |
| `src/main/resources/application.yaml` | VERIFIED | — | Top-level `mtn:` block (lines 270-285) with all config fields. `resilience4j.circuitbreaker.instances.mtn` (lines 232-238, `ignoreExceptions: MtnAccountInactiveException`). `resilience4j.retry.instances.mtn` (lines 249-256). |
| `src/main/java/com/softropic/payam/transaction/repo/Transaction.java` | VERIFIED | — | `mtnFinancialTxId` field with `@Column(name = "mtn_financial_tx_id")`, `setMtnFinancialTxId()` setter. |
| `src/test/java/com/softropic/payam/mtn/MtnTokenServiceIT.java` | VERIFIED | 76 | 3 tests: fetch+cache, cache-hit (0 MTN calls), evict. WireMock on `/token/` path. |
| `src/test/java/com/softropic/payam/mtn/MtnMoMoPortIT.java` | VERIFIED | 184 | 5 tests: validate active (200), validate inactive (404→false), requestToPay+providerRef persistence, PUT callback returns 200, getTransactionStatus returns SUCCESSFUL. `@TestPropertySource(mtn.callback-ip-whitelist=)` for sandbox mode. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `MtnMoMoClient.fetchCollectionToken()` | `config.getCollectionTokenUrl()` | POST null body, Basic auth | WIRED | Line 47: `makeHttpRequest(config.getCollectionTokenUrl(), HttpMethod.POST, null, ...)` |
| `MtnMoMoClient.requestToPay()` | `X-Reference-Id` header | Caller-generated UUID passed as `referenceId` param | WIRED | Line 82: `mtnHeaders(bearerToken, referenceId, ...)` sets `X-Reference-Id` |
| `MtnMoMoClient.disburse()` | `config.getDisbursementSubscriptionKey()` | Separate key from collection | WIRED | Line 171: `mtnHeaders(bearerToken, referenceId, config.getDisbursementSubscriptionKey())` |
| `MtnMoMoPort.initiateMerchantPayment()` | `persistProviderRef(REQUIRES_NEW)` | UUID stored before HTTP call | WIRED | Line 81: `persistProviderRef(cmd.transactionId(), referenceId)` before line 93 `mtnMoMoClient.requestToPay(...)` |
| `MtnStatusPollerJob.pollTransaction()` | `tx.getProviderRef()` | Stable merchant UUID as poll key | WIRED | Line 91: `mtnMoMoPort.getTransactionStatus(tx.getProviderRef())` |
| `MtnCallbackController` | `AppEndpoints.PUBLIC_ENDPOINTS` | `/v1/callbacks/mtn` in list | WIRED | AppEndpoints.java line 26 |
| `MtnIpWhitelistInterceptor` | `MtnWebConfig.addInterceptors()` | `.addPathPatterns("/v1/callbacks/mtn")` | WIRED | MtnWebConfig.java line 23 |
| `MtnTokenService.getAccessToken()` | `mtnMoMoClient.fetchCollectionToken()` | On cache miss with NX lock | WIRED | MtnTokenService.java line 43 |

### Requirements Coverage

| Requirement | Status | Notes |
|-------------|--------|-------|
| ADAPT-02 (MTN MoMo adapter) | SATISFIED | All must-haves verified. P1.4 (PUT method) closed. OAuth2 cache operational. IP whitelist enforced. |

### Anti-Patterns Found

None. No `TODO`, `FIXME`, `placeholder`, or empty-implementation patterns in any MTN source file.

One intentional partial implementation noted and documented:

- `MtnMoMoPort.processCallback()` logs + stores `financialTransactionId` but does not apply a state transition. This is the correct scope for Phase 4 — state transitions via callback are explicitly Phase 6 per ROADMAP. The P1.4 fix (callback received via HTTP PUT with 200, not dropped with 405) is complete.

### Human Verification Required

None required for automated structural verification. The following items could benefit from sandbox confirmation when available, but do not block phase goal assessment:

1. **CIDR matching correctness** — The `/8` CIDR matching uses octet-boundary arithmetic. Verified by code inspection but not exercised by the IT tests (which use empty whitelist = sandbox mode). A test with `196.x.x.x` IP would confirm the CIDR path works in production.
   - Expected: `196.1.2.3` passes, `197.0.0.1` returns 403.
   - Why human/sandbox: current IT tests use `mtn.callback-ip-whitelist=` (empty) to bypass whitelist; a separate targeted test would be needed.

2. **MTN sandbox PUT callback confirmation** — Research flag in ROADMAP notes "LIKELY — verify PUT callback in sandbox". The code is correct; sandbox end-to-end confirmation would close this flag.

### Gaps Summary

No gaps. All 5 phase must-haves are structurally verified at all three levels (exists, substantive, wired).

---

_Verified: 2026-03-24T04:30:00Z_
_Verifier: Claude (gsd-verifier)_
