---
phase: 06-webhook-processing
plan: 01
subsystem: payments
tags: [webhook, orange-money, mtn-momo, redis, hmac, ip-whitelist, flyway, spring-mvc]

# Dependency graph
requires:
  - phase: 05-payment-orchestration
    provides: OrangeMoneyPort.processWebhook stub, MtnMoMoPort.processCallback stub, Redis via TestConfig
  - phase: 03-orange-money-adapter
    provides: OrangeMoneyConfig, OrangeWebhookPayload, OrangeMoneyPort
  - phase: 04-mtn-momo-adapter
    provides: MtnMoMoPort, MtnIpWhitelistInterceptor pattern, MtnWebConfig pattern
provides:
  - V8 Flyway migration adding webhook_url + webhook_secret to main.tenant
  - OrangeCallbackController POST /v1/callbacks/orange with IP whitelist + conditional HMAC-SHA256 + Redis dedup
  - OrangeIpWhitelistInterceptor + OrangeWebConfig (mirroring MTN pattern)
  - Redis dedup added to MtnMoMoPort.processCallback (webhook:mtn:{externalId}:{status}, 24h TTL)
  - OrangeCallbackControllerIT — 5 integration tests covering IP sandbox, dedup, HMAC rejection
affects:
  - 06-02 (webhook state transition — consumes OrangeCallbackController + MtnMoMoPort dedup foundation)
  - future-tenant-api (may surface webhook_url/webhook_secret via tenant admin endpoints)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Conditional HMAC-SHA256: enforced only when callbackHmacSecret non-blank; body re-serialized via ObjectMapper (not stream re-read — stream consumed by @RequestBody)"
    - "Redis dedup key design: webhook:{provider}:{token}:{createtime} — allows multiple status updates per payToken (Pitfall 8 guard)"
    - "IP whitelist interceptor pattern: @Component interceptor registered via WebMvcConfigurer.addPathPatterns to single callback path — does not affect JWT or other endpoints"
    - "No @Transactional on callback controller — return 200 to provider as fast as possible (P1.1)"

key-files:
  created:
    - src/main/resources/db/migration/V8__tenant_webhook_url.sql
    - src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java
    - src/main/java/com/softropic/payam/orange/web/OrangeIpWhitelistInterceptor.java
    - src/main/java/com/softropic/payam/orange/web/OrangeWebConfig.java
    - src/test/java/com/softropic/payam/webhook/OrangeCallbackControllerIT.java
  modified:
    - src/main/java/com/softropic/payam/tenant/repo/Tenant.java
    - src/main/java/com/softropic/payam/orange/config/OrangeMoneyConfig.java
    - src/main/resources/application.yaml
    - src/main/java/com/softropic/payam/security/config/AppEndpoints.java
    - src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java

key-decisions:
  - "OrangeCallbackController HMAC body computed via objectMapper.writeValueAsString(payload) — servlet input stream already consumed by @RequestBody deserialization; readAllBytes() would return empty"
  - "Redis dedup key includes createtime (not just payToken) — one payToken can receive multiple status transitions; createtime distinguishes each distinct event (Pitfall 8 guard)"
  - "No HMAC on MTN path — MTN API contract provides notifToken correlation + IP whitelist as authenticity mechanism; no HMAC header defined in MTN spec"
  - "callbackHmacSecret blank = sandbox mode (skip HMAC) — enables local development and sandbox testing without Orange partner credentials"
  - "StringRedisTemplate injected into MtnMoMoPort constructor — spring-boot-starter-data-redis already in pom.xml; no new dependency needed"

patterns-established:
  - "Callback controller pattern: no @Transactional, Redis dedup first, delegate to port, return 200 immediately"
  - "IP whitelist WebMvcConfigurer pattern: @Component interceptor + @Configuration WebMvcConfigurer registered via addPathPatterns to single path"
  - "Conditional security pattern: when config secret is blank/null, skip security check (sandbox mode); when set, enforce strictly"

# Metrics
duration: 6min
completed: 2026-03-24
---

# Phase 6 Plan 01: Webhook Reception Infrastructure Summary

**Orange POST callback endpoint with IP whitelist + conditional HMAC-SHA256 + Redis dedup, plus MTN dedup added to existing port, Flyway V8 migration for tenant webhook fields**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-24T09:15:41Z
- **Completed:** 2026-03-24T09:21:20Z
- **Tasks:** 3
- **Files modified:** 9 (5 created, 4 modified)

## Accomplishments
- V8 Flyway migration adds nullable webhook_url and webhook_secret columns to main.tenant
- POST /v1/callbacks/orange endpoint with three security layers: IP whitelist (OrangeIpWhitelistInterceptor), conditional HMAC-SHA256 (when callbackHmacSecret configured), Redis dedup (24h TTL keyed on payToken + createtime)
- MTN callback dedup added to MtnMoMoPort.processCallback via StringRedisTemplate injection
- OrangeCallbackControllerIT: 5 integration tests, all passing (happy path, dedup, different-createtime non-dedup, null payload, HMAC 401 rejection)

## Task Commits

Each task was committed atomically:

1. **Task 1: V8 migration + Tenant entity + Orange IP whitelist infrastructure** - `de92edf` (feat)
2. **Task 2: OrangeCallbackController + Redis dedup in MTN and Orange** - `144e9d4` (feat)
3. **Task 3: OrangeCallbackControllerIT** - `92d3531` (test)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `src/main/resources/db/migration/V8__tenant_webhook_url.sql` — adds webhook_url (VARCHAR 2048) and webhook_secret (VARCHAR 255) to main.tenant; nullable; no FK
- `src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java` — POST /v1/callbacks/orange; HMAC + dedup + port delegation; no @Transactional
- `src/main/java/com/softropic/payam/orange/web/OrangeIpWhitelistInterceptor.java` — mirrors MtnIpWhitelistInterceptor; CIDR + exact match; sandbox mode when whitelist empty
- `src/main/java/com/softropic/payam/orange/web/OrangeWebConfig.java` — registers interceptor for /v1/callbacks/orange only
- `src/test/java/com/softropic/payam/webhook/OrangeCallbackControllerIT.java` — 5 IT tests; no WireMock (inbound-only path)
- `src/main/java/com/softropic/payam/tenant/repo/Tenant.java` — getWebhookUrl() + getWebhookSecret() added
- `src/main/java/com/softropic/payam/orange/config/OrangeMoneyConfig.java` — callbackIpWhitelist (List) + callbackHmacSecret (String) added
- `src/main/resources/application.yaml` — orange.callback-ip-whitelist and orange.callback-hmac-secret keys added (both empty = sandbox mode)
- `src/main/java/com/softropic/payam/security/config/AppEndpoints.java` — /v1/callbacks/orange added to PUBLIC_ENDPOINTS
- `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` — StringRedisTemplate injected; processCallback adds dedup before existing logic

## Decisions Made
- HMAC body computed via `objectMapper.writeValueAsString(payload)` — servlet input stream consumed by @RequestBody; `request.getInputStream().readAllBytes()` would return empty byte array. Re-serializing the deserialized object is safe when Orange signs their own JSON payload structure.
- Redis dedup key includes createtime (`webhook:orange:{payToken}:{createtime}`) — a single payToken can receive multiple status transitions (PENDING, then SUCCESS). Including createtime distinguishes each distinct event, preventing legitimate second callbacks from being suppressed (Pitfall 8 guard).
- No HMAC on MTN path — intentional per MTN API design. MTN MoMo Collections API does not define an HMAC signature header; notifToken correlation + IP whitelist is MTN's authenticity mechanism.
- Blank callbackHmacSecret = sandbox mode — HMAC check skipped entirely when secret is blank/null. This matches the Orange partner documentation note that HMAC header existence is unconfirmed; sandbox testing should not require Orange partner credentials.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required. All new keys default to empty (sandbox mode).

## Next Phase Readiness
- OrangeCallbackController and MtnMoMoPort.processCallback are ready for Plan 06-02 to wire state transitions via double-check getTransactionStatus() pattern
- HMAC secret can be configured at any time via orange.callback-hmac-secret without code changes
- Orange IP whitelist can be configured via orange.callback-ip-whitelist without code changes
- No blockers for Phase 06-02

---
*Phase: 06-webhook-processing*
*Completed: 2026-03-24*
