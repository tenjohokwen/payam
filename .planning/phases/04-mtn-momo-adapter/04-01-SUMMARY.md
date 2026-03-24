---
phase: 04-mtn-momo-adapter
plan: 01
subsystem: payments
tags: [mtn, momo, rest-client, flyway, resilience4j, spring-configuration-properties]

# Dependency graph
requires:
  - phase: 03-orange-money-adapter
    provides: AbstractClient, OrangeMoneyClient structure used as template for MtnMoMoClient
provides:
  - MTN MoMo module boundary marker (MtnModule)
  - MtnMoMoConfig @ConfigurationProperties binding all MTN credentials and URLs
  - MtnConfig Spring @Configuration wiring the client @Bean
  - Full contract layer: MtnTransactionStatus enum, MtnCallbackPayload, 5 request/response DTOs, 2 exceptions
  - MtnMoMoClient HTTP client with all 7 MTN API operations
  - V7 Flyway migration adding mtn_financial_tx_id column to transaction table
  - application.yaml mtn top-level config block + Resilience4j circuitbreaker and retry instances
affects:
  - 04-02 (MtnMoMo service layer depends on all types from this plan)
  - 05-payment-orchestrator (MTN service contracts, callback payload)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "MTN uses null body for token POST (no form encoding) — contrast with Orange grant_type=client_credentials form body"
    - "Disbursement product uses separate subscription key and base URL from Collection product"
    - "validateAccountHolder: 404 response → MtnAccountInactiveException (not a status field in body)"
    - "@ConfigurationProperties bound via @EnableConfigurationProperties in config class (not @Component on config bean)"

key-files:
  created:
    - src/main/java/com/softropic/payam/mtn/MtnModule.java
    - src/main/java/com/softropic/payam/mtn/config/MtnMoMoConfig.java
    - src/main/java/com/softropic/payam/mtn/config/MtnConfig.java
    - src/main/java/com/softropic/payam/mtn/contract/MtnTransactionStatus.java
    - src/main/java/com/softropic/payam/mtn/contract/MtnCallbackPayload.java
    - src/main/java/com/softropic/payam/mtn/contract/dto/MtnTokenResponse.java
    - src/main/java/com/softropic/payam/mtn/contract/dto/RequestToPayRequest.java
    - src/main/java/com/softropic/payam/mtn/contract/dto/RequestToPayStatusResponse.java
    - src/main/java/com/softropic/payam/mtn/contract/dto/DisbursementRequest.java
    - src/main/java/com/softropic/payam/mtn/contract/dto/AccountBalanceResponse.java
    - src/main/java/com/softropic/payam/mtn/contract/dto/AccountHolderInfoResponse.java
    - src/main/java/com/softropic/payam/mtn/contract/exception/MtnApiException.java
    - src/main/java/com/softropic/payam/mtn/contract/exception/MtnAccountInactiveException.java
    - src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java
    - src/main/resources/db/migration/V7__transaction_mtn_fields.sql
  modified:
    - src/main/resources/application.yaml

key-decisions:
  - "MtnMoMoClient token POST sends null body — MTN returns 400 if form body is sent (Pitfall 4); unlike Orange no FormHttpMessageConverter needed"
  - "fetchDisbursementToken() is a separate method from fetchCollectionToken() — disbursement uses different product key and endpoint"
  - "disburse() uses getDisbursementSubscriptionKey() not getCollectionSubscriptionKey() — Pitfall 5: using wrong key returns 401"
  - "validateAccountHolder() catches HttpClientErrorException.NotFound and converts to MtnAccountInactiveException — MTN returns 404 (not body with status=INACTIVE) for inactive accounts"
  - "MtnMoMoClient constructor passes getCollectionBaseUrl() as super baseUrl — disbursement calls override URL explicitly via getDisbursementBaseUrl()"

patterns-established:
  - "mtnHeaders() private helper: Authorization Bearer + optional X-Reference-Id (null for GETs) + X-Target-Environment + Ocp-Apim-Subscription-Key + Content-Type"
  - "202 is the MTN success code for requestToPay and disburse — checked via is2xxSuccessful() which covers both 200 and 202"

# Metrics
duration: 3min
completed: 2026-03-24
---

# Phase 4 Plan 01: MTN MoMo Infrastructure Layer Summary

**MTN MoMo HTTP client (7 operations), full contract/DTO layer, V7 Flyway migration, and Resilience4j config — infrastructure foundation for the 04-02 service layer**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-24T03:35:12Z
- **Completed:** 2026-03-24T03:38:01Z
- **Tasks:** 2
- **Files modified:** 16

## Accomplishments
- Established com.softropic.payam.mtn.* package hierarchy mirroring the Orange adapter structure
- MtnMoMoClient with all 7 MTN API operations: fetchCollectionToken, fetchDisbursementToken, requestToPay, getRequestToPayStatus, validateAccountHolder, getBalance, disburse
- V7 migration adds mtn_financial_tx_id column to transaction table; application.yaml gains complete mtn config block + Resilience4j instances

## Task Commits

Each task was committed atomically:

1. **Task 1: Module marker, MtnMoMoConfig, MtnConfig, and contract types** - `2cce1ae` (feat)
2. **Task 2: MtnMoMoClient, V7 migration, and application.yaml additions** - `9bc1a27` (feat)

**Plan metadata:** (to be added after SUMMARY commit)

## Files Created/Modified
- `src/main/java/com/softropic/payam/mtn/MtnModule.java` - Spring Modulith boundary marker
- `src/main/java/com/softropic/payam/mtn/config/MtnMoMoConfig.java` - @ConfigurationProperties(prefix="mtn") binding all credentials, URLs, poller defaults
- `src/main/java/com/softropic/payam/mtn/config/MtnConfig.java` - @Configuration + @EnableConfigurationProperties + mtnMoMoClient @Bean
- `src/main/java/com/softropic/payam/mtn/contract/MtnTransactionStatus.java` - PENDING/SUCCESSFUL/FAILED enum
- `src/main/java/com/softropic/payam/mtn/contract/MtnCallbackPayload.java` - MTN PUT callback payload (externalId correlation key)
- `src/main/java/com/softropic/payam/mtn/contract/dto/MtnTokenResponse.java` - access_token, token_type, expires_in
- `src/main/java/com/softropic/payam/mtn/contract/dto/RequestToPayRequest.java` - amount, currency, externalId, payer (inner Party record), messages
- `src/main/java/com/softropic/payam/mtn/contract/dto/RequestToPayStatusResponse.java` - status, financialTransactionId, externalId, reason
- `src/main/java/com/softropic/payam/mtn/contract/dto/DisbursementRequest.java` - amount, currency, externalId, payee (inner Party record), messages
- `src/main/java/com/softropic/payam/mtn/contract/dto/AccountBalanceResponse.java` - availableBalance, currency
- `src/main/java/com/softropic/payam/mtn/contract/dto/AccountHolderInfoResponse.java` - name, given_name, family_name
- `src/main/java/com/softropic/payam/mtn/contract/exception/MtnApiException.java` - wraps unexpected MTN API errors
- `src/main/java/com/softropic/payam/mtn/contract/exception/MtnAccountInactiveException.java` - thrown when MTN returns 404 on accountholder info
- `src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java` - full HTTP client extending AbstractClient
- `src/main/resources/db/migration/V7__transaction_mtn_fields.sql` - ADD COLUMN mtn_financial_tx_id VARCHAR(255)
- `src/main/resources/application.yaml` - added mtn top-level block, resilience4j mtn circuitbreaker + retry instances

## Decisions Made
- **Null body for token POST:** MTN token endpoint rejects form body with 400 — MtnMoMoClient sends null body (unlike Orange which sends grant_type=client_credentials form). No FormHttpMessageConverter needed.
- **Separate disbursement token method:** fetchDisbursementToken() distinct from fetchCollectionToken() — uses different product key (getDisbursementSubscriptionKey()) and endpoint (getDisbursementTokenUrl()).
- **disburse() uses disbursementSubscriptionKey:** Not collection key — Pitfall 5 from research. Using collection key returns 401 on disbursement endpoints.
- **validateAccountHolder 404 handling:** MTN returns HTTP 404 for inactive accounts (not a JSON body with status). HttpClientErrorException.NotFound caught and converted to MtnAccountInactiveException.
- **Constructor baseUrl = collectionBaseUrl:** Disbursement calls build their own URL from getDisbursementBaseUrl() rather than relying on the super baseUrl.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required beyond environment variables already documented in application.yaml.

## Next Phase Readiness
- All types required by 04-02 (MtnMoMoService) are in place: MtnMoMoConfig, MtnMoMoClient, all DTOs, both exceptions
- V7 migration will run on next application startup; mtn_financial_tx_id column will be available
- Resilience4j mtn instances configured and ready for @CircuitBreaker/@Retry annotations in 04-02
- Blocker from STATE.md still applies: Phase 4 MTN PUT callback confirmed in docs — verify in sandbox before relying on it (pre-existing concern, not introduced here)

---
*Phase: 04-mtn-momo-adapter*
*Completed: 2026-03-24*
