# Phase 13: Ledger Wiring + Webhook Access Control — Research

**Researched:** 2026-03-26
**Domain:** Spring Boot service wiring + Spring Security @PreAuthorize
**Confidence:** HIGH (all findings from direct source code inspection — no external libraries involved)

---

## Summary

Phase 13 closes two audit gaps found in the v1 milestone audit. Both fixes are small and targeted.

**Gap 1 — Ledger wiring (TX-05):** `LedgerService.postEntry()` exists and is fully tested in isolation (`LedgerServiceIT`) but has zero production callers. The fix is to call it from `WebhookTransitionService.applyFinalTransition()` immediately after `transactionRepository.save(tx)` when `target == TransactionStatus.SUCCESS`. The service, its signature, and its test infrastructure are all fully defined.

**Gap 2 — Access control on webhook delivery endpoint:** `WebhookDeliveryResource.getDeliveries()` at `GET /v1/webhooks/deliveries/{transactionId}` has no `@PreAuthorize`. The audit and ROADMAP both specify adding `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`. However, a critical architectural constraint exists: this endpoint is currently served by the **API key filter chain** (Order 1), not the JWT chain. Adding `@PreAuthorize` for a JWT role on a non-JWT endpoint requires careful consideration — see the Architecture Patterns section.

The IT test must cover: (1) ledger rows written after SUCCESS transition (new assertion in an extended `WebhookDoubleCheckIT` or standalone `LedgerWiringIT`), and (2) a ROLE_USER JWT returns 403 on the delivery endpoint (requires seeding admin/user rows and real `/authenticate` login flow, matching ReconciliationApiIT pattern).

**Primary recommendation:** Wire ledger call in one line inside the `SUCCESS` branch of `applyFinalTransition()`. For access control, move the endpoint to `/v1/admin/webhooks/deliveries/{transactionId}` so it falls in the JWT chain — OR add `@PreAuthorize` and update `TenantFilterChainIT` to reflect that a ROLE_USER JWT returns 403 (not 200) on the endpoint.

---

## Standard Stack

No new libraries needed. All required infrastructure exists.

### Core (already present)
| Component | File | Purpose |
|-----------|------|---------|
| `LedgerService` | `transaction/service/LedgerService.java` | Posts DEBIT+CREDIT pair to `ledger_entry` |
| `LedgerEntryRepository` | `transaction/repo/LedgerEntryRepository.java` | JPA repository with `findByTransactionId()` |
| `WebhookTransitionService` | `webhook/service/WebhookTransitionService.java` | Where the ledger call must be wired |
| `SecurityConstants.HAS_ADMIN_ROLE` | `security/common/util/SecurityConstants.java` | SpEL expression: `"hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN')"` |
| `WebhookDeliveryResource` | `webhook/api/WebhookDeliveryResource.java` | Where `@PreAuthorize` must be added |
| `AdminLogin` | `test/common/AdminLogin.java` | Shared helper: `loginAsAdmin(url, restTemplate)` → JWT cookies |

### Supporting (already present)
| Component | File | Purpose |
|-----------|------|---------|
| `TestConfig` | `test/config/TestConfig.java` | Provides Postgres + Redis Testcontainers, `@Import` into any IT |
| `TransactionTemplate` | Spring core | Used in `@BeforeEach`/`@AfterEach` for JDBC seeding within a transaction |
| `JdbcTemplate` | Spring core | Direct SQL inserts/deletes for test data |
| `WireMock` | via `@EnableWireMock` | Mocks Orange/MTN provider status APIs |

**Installation:** None required.

---

## Architecture Patterns

### Pattern 1: Ledger Wiring — Call Site and Transaction Scope

**What:** `LedgerService.postEntry()` must be called inside `WebhookTransitionService.applyFinalTransition()` when `target == TransactionStatus.SUCCESS`.

**Why this location:** `applyFinalTransition()` runs in `@Transactional(REQUIRES_NEW)`. `LedgerService.postEntry()` is itself `@Transactional`. When called from within an active transaction context (`REQUIRES_NEW` creates one), `postEntry()`'s inner `@Transactional` will join the outer transaction by default (`REQUIRED` propagation). This means the ledger rows commit atomically with the `transaction` row update — correct behaviour for double-entry bookkeeping.

**Transaction data available in call site:**
- `tx.getTransactionId()` — String, maps to `ledger_entry.transaction_id`
- `tx.getTenantId()` — Long, maps to `ledger_entry.tenant_id`
- `tx.getAmount()` — BigDecimal, maps to `ledger_entry.amount`
- `tx.getCurrency()` — String (3 chars), maps to `ledger_entry.currency`

**Exact insertion point in `applyFinalTransition()`:**

```java
// Source: WebhookTransitionService.java lines 72-101
tx.applyTransition(target);
transactionRepository.save(tx);   // ← existing line

// INSERT after save(), inside the SUCCESS branch:
if (target == TransactionStatus.SUCCESS) {
    ledgerService.postEntry(
        tx.getTransactionId(),
        tx.getTenantId(),
        tx.getAmount(),
        tx.getCurrency()
    );
}
```

The `WebhookTransitionService` constructor must be updated to inject `LedgerService`.

### Pattern 2: Access Control — Security Chain Routing

**What:** `/v1/webhooks/deliveries/{transactionId}` is served by the **API key chain** (`@Order(1)` in `TenantSecurityConfig`).

**Why this matters:** The API key chain authenticates using `X-Api-Key` header and sets a `TenantPrincipal` (not a JWT-based `UserDetails`). `@PreAuthorize("hasRole('ROLE_ADMIN')")` evaluates Spring Security `GrantedAuthority` on the authenticated principal. An API-key-authenticated request has no JWT roles, so the `hasRole()` check will always fail with 403 for API-key clients.

**Two valid approaches:**

**Option A (RECOMMENDED): Move endpoint to `/v1/admin/webhooks/deliveries/{transactionId}`**
- Change `@RequestMapping("/v1/webhooks")` to `@RequestMapping("/v1/admin/webhooks")` in `WebhookDeliveryResource`
- The `/v1/admin/**` path is explicitly excluded from the API key chain (`NegatedRequestMatcher` in `TenantSecurityConfig`)
- The JWT chain handles `/v1/admin/**` — `@PreAuthorize(HAS_ADMIN_ROLE)` works correctly there
- `TenantFilterChainIT` must update its path from `/v1/webhooks/deliveries/tx-123` to `/v1/admin/webhooks/deliveries/tx-123` (currently tests with API key — after move, tests should instead verify admin path requires JWT, not API key)
- `WebhookDeliveryIT` Test 3 must update its URL

**Option B: Keep path, apply `@PreAuthorize`, accept that API key requests return 403**
- Add `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` at class or method level on `WebhookDeliveryResource`
- API key authenticated tenants (`ROLE_TENANT_API`) will receive 403 — this may be intentional
- `TenantFilterChainIT` Test 1 currently asserts `200 OK` with X-Api-Key on this endpoint — must be updated to assert `403` or the test must be removed/replaced
- No path change needed

**ROADMAP spec says:** "ROLE_ADMIN (or authenticated tenant ownership) — a ROLE_USER JWT returns 403". This implies JWT-based access control. Option A aligns with the existing admin pattern (`ReconciliationResource` at `/v1/admin/reconciliation`).

**Existing admin pattern (ReconciliationResource):**
```java
// Source: ReconciliationResource.java line 42
@RestController
@RequestMapping("/v1/admin/reconciliation")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class ReconciliationResource { ... }
```

### Pattern 3: IT Test for Ledger Wiring

**What:** Verify that `applyFinalTransition()` with a SUCCESS outcome writes two `ledger_entry` rows.

**Best fit:** Extend `WebhookDoubleCheckIT` with an additional assertion (it already simulates the full callback→double-check→SUCCESS flow), or create a new `LedgerWiringIT`. The `WebhookDoubleCheckIT` pattern is the cleanest because it exercises the full async pipeline.

**Existing test flow (WebhookDoubleCheckIT pattern):**
1. `@BeforeEach`: seed `main.sec` JWT secret, stub Orange/MTN token endpoints, create tenant, reset circuit breakers
2. Helper: `createOrangeProcessingTransaction(payToken)` — JDBC insert of a PROCESSING transaction
3. Test: `orangeServer.stubFor(get(...))` returning `SUCCESSFULL`, `postOrangeCallback(payToken, "SUCCESSFULL")`, `Thread.sleep(300)`, assert `txStatus == SUCCESS`
4. Extended assertion: `ledgerEntryRepository.findByTransactionId(txId)` → size == 2, has DEBIT and CREDIT entries

**Required teardown additions:** `DELETE FROM main.ledger_entry` before `DELETE FROM main.transaction` (FK constraint: `ledger_entry.tenant_id → tenant.id`, and `ledger_entry.transaction_id` is not a FK but the ordering still matters for clean state).

### Pattern 4: IT Test for ROLE_USER → 403

**What:** A user with only ROLE_USER JWT receives 403 on `GET /v1/admin/webhooks/deliveries/{transactionId}` (assuming Option A path move).

**Pattern from ReconciliationApiIT:** Seed `main.sec`, `main.authority`, `main.user`, `main.user_authority`. Login via `POST /authenticate`. Use `SimpleClientHttpRequestFactory` (no retry). Assert 403 from a ROLE_USER login.

**ROLE_USER-only user seeding:** ReconciliationApiIT seeds the admin user `queb@yahoo.com` with both ROLE_USER and ROLE_ADMIN authorities. For the 403 test, a ROLE_USER-only user is needed. Options:
- Seed a separate user with only ROLE_USER authority (id: 5418719445932238328 is the ROLE_USER authority row)
- OR: login as the admin user but strip ROLE_ADMIN from the assertion — this doesn't work cleanly
- RECOMMENDED: seed a separate ROLE_USER-only user (different email/id)

**Credential fixture:** All existing tests use `queb@yahoo.com` / `admin*123!` with password hash `$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i`. A ROLE_USER test user needs its own distinct user row and password hash.

**Alternative simpler approach:** Use `noRetryRestTemplate` with no auth (or bogus auth) to verify the endpoint returns 401/403 without a valid admin JWT. This avoids needing a second user but doesn't precisely test "ROLE_USER gets 403 vs ROLE_ADMIN gets 200".

### Recommended Project Structure Change

```
webhook/api/
└── WebhookDeliveryResource.java   # Change @RequestMapping to /v1/admin/webhooks
```

No new files needed for production code. One new or extended IT test file.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| JWT test login | Custom JWT generation | Real `POST /authenticate` login flow (AdminLogin helper) |
| Ledger balance check | Custom SQL query | `ledgerEntryRepository.findByTransactionId()` (already exists) |
| Test user seeding | ORM entity save | Direct JDBC `INSERT` with known password hash (existing pattern) |
| Transaction teardown order | Arbitrary DELETE | FK-safe order: `ledger_entry` → `payment_event_log` → `webhook_delivery_log` → `transaction` → `tenant_api_key` → `tenant` → `user_authority` → `user` → `authority` → `sec` |

---

## Common Pitfalls

### Pitfall 1: LedgerService Self-Transactional Join

**What goes wrong:** Calling `ledgerService.postEntry()` from within `applyFinalTransition()` raises concerns about transaction propagation, but is actually correct.

**Why it is correct:** `applyFinalTransition()` runs in `REQUIRES_NEW` (confirmed). `postEntry()` is `@Transactional(REQUIRED)` by default. REQUIRED joins the existing REQUIRES_NEW transaction. Ledger rows commit atomically with the transaction row. No additional annotation needed on the `postEntry()` call site.

**Warning signs:** If `postEntry()` ever uses `REQUIRES_NEW` itself, it would create a nested transaction and commit independently — check the annotation. Currently it uses `@Transactional` (REQUIRED).

### Pitfall 2: Breaking TenantFilterChainIT

**What goes wrong:** `TenantFilterChainIT` tests `GET /v1/webhooks/deliveries/tx-123` with X-Api-Key and asserts 200 OK. If the endpoint moves to `/v1/admin/webhooks/deliveries/tx-123`, the old tests at the old path will get 404 or fail to match.

**How to avoid:** Update `TenantFilterChainIT` when changing the path. The 5 tests in `TenantFilterChainIT` use the webhook path as an arbitrary non-payment, non-admin endpoint to test API key chain routing. After path move, use a different `/v1/**` endpoint (e.g., `/v1/payments` — though initiating a real payment in that test context is complex). Simpler: keep the old tests as-is and just update the URL string to something else in `/v1/**` that still falls in the API key chain.

**Warning signs:** `TenantFilterChainIT` failing with 404 after path change.

### Pitfall 3: Missing `ledger_entry` Teardown

**What goes wrong:** IT tests that write ledger rows but don't delete them in `@AfterEach` cause FK violations in subsequent tenant deletion.

**Why it happens:** `ledger_entry.tenant_id` references `main.tenant(id)` with a FK constraint. Deleting tenant without first deleting ledger rows throws `DataIntegrityViolationException`.

**How to avoid:** Add `DELETE FROM main.ledger_entry` before `DELETE FROM main.tenant` in every `@AfterEach` block that triggers ledger writes.

### Pitfall 4: Thread Sleep Timing in Async Tests

**What goes wrong:** The double-check handler fires via `@TransactionalEventListener(AFTER_COMMIT)`, which is async. `Thread.sleep(300)` may not be sufficient on slow CI machines.

**How to avoid:** Use the same `Thread.sleep(500)` pattern used in `WebhookDeliveryIT`, or use Awaitility if timing issues appear. `WebhookDoubleCheckIT` uses 300ms; extending this test should use the same value but be aware of fragility.

### Pitfall 5: ROLE_USER-Only User Seeding Complexity

**What goes wrong:** The test fixture admin user `queb@yahoo.com` has ROLE_ADMIN + ROLE_USER. Using the same user for both the "admin gets 200" and "ROLE_USER gets 403" cases is impossible since both roles are assigned to the same user.

**How to avoid:** Either seed a second user with only ROLE_USER authority (different user id, email, password hash), or test the 403 case without auth (missing auth → 401/403) and accept that as sufficient for the access control verification.

---

## Code Examples

### Ledger Wiring — Constructor and Call Site

```java
// Source: WebhookTransitionService.java — existing constructor (lines 35-41)
public WebhookTransitionService(TransactionRepository transactionRepository,
                                EventLogService eventLogService,
                                WebhookDeliveryService webhookDeliveryService) { ... }

// Updated constructor (add LedgerService parameter):
public WebhookTransitionService(TransactionRepository transactionRepository,
                                EventLogService eventLogService,
                                WebhookDeliveryService webhookDeliveryService,
                                LedgerService ledgerService) { ... }

// Call site inside applyFinalTransition() after transactionRepository.save(tx):
if (target == TransactionStatus.SUCCESS) {
    ledgerService.postEntry(
        tx.getTransactionId(),
        tx.getTenantId(),
        tx.getAmount(),
        tx.getCurrency()
    );
}
```

### @PreAuthorize on WebhookDeliveryResource

```java
// Source: ReconciliationResource.java line 42 — exact same pattern to follow
@RestController
@RequestMapping("/v1/admin/webhooks")   // path moved to /v1/admin/**
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public class WebhookDeliveryResource { ... }
```

### LedgerEntryRepository Query Used in Tests

```java
// Source: LedgerEntryRepository.java line 9
List<LedgerEntry> findByTransactionId(String transactionId);

// Usage in assertion:
List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(txId);
assertThat(entries).hasSize(2);
assertThat(entries).extracting(LedgerEntry::getDirection)
    .containsExactlyInAnyOrder(LedgerDirection.DEBIT, LedgerDirection.CREDIT);
```

### AdminLogin Helper Usage

```java
// Source: AdminLogin.java line 28
// In @BeforeEach after seeding admin user rows:
adminCookies = AdminLogin.loginAsAdmin(url("/authenticate"), noRetryRestTemplate);

// Then use in request:
noRetryRestTemplate.exchange(
    url("/v1/admin/webhooks/deliveries/" + txId),
    HttpMethod.GET,
    new HttpEntity<>(adminCookies),
    new ParameterizedTypeReference<List<WebhookDeliveryLog>>() {});
```

### Standard JWT Secret + Authority + Admin User Seeding

```java
// Source: ReconciliationApiIT.java lines 110-151 — full seeding pattern
// 1. Seed main.sec (JWT secret)
jdbc.execute("INSERT INTO main.sec (id, ..., bus_id, value, version) VALUES ('659287191260154475', ...) ON CONFLICT DO NOTHING");
// 2. Seed ROLE_ADMIN authority (id: 6747751741842104908)
jdbc.execute("INSERT INTO main.authority (id, name, ...) VALUES (6747751741842104908, 'ROLE_ADMIN', ...) ON CONFLICT DO NOTHING");
// 3. Seed ROLE_USER authority (id: 5418719445932238328)
jdbc.execute("INSERT INTO main.authority (id, name, ...) VALUES (5418719445932238328, 'ROLE_USER', ...) ON CONFLICT DO NOTHING");
// 4. Seed admin user (queb@yahoo.com / admin*123!)
//    password hash: $2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i
jdbc.execute("INSERT INTO main.\"user\" (id=675373350208068096, login='queb@yahoo.com', ...) ON CONFLICT DO NOTHING");
// 5. Assign both roles to admin user
jdbc.execute("INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 5418719445932238328)");
jdbc.execute("INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 6747751741842104908)");
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No ledger caller | Wire in WebhookTransitionService | Phase 13 (this phase) | TX-05 gap closed |
| No @PreAuthorize on delivery endpoint | @PreAuthorize(HAS_ADMIN_ROLE) | Phase 13 (this phase) | Cross-tenant info disclosure closed |
| /v1/webhooks/deliveries | /v1/admin/webhooks/deliveries (proposed) | Phase 13 (this phase) | Moves to JWT chain; consistent with other admin endpoints |

---

## Open Questions

1. **Path change or annotation-only fix for webhook access control?**
   - What we know: The endpoint is currently in the API key chain. Adding `@PreAuthorize` for JWT roles to an API-key-served endpoint will break existing `TenantFilterChainIT` tests that assert 200 on `X-Api-Key` requests.
   - What's unclear: The ROADMAP says "ROLE_ADMIN (or authenticated tenant ownership)" but doesn't specify whether to move the path or keep it. The audit says "add @PreAuthorize" without clarifying the chain.
   - Recommendation: Move to `/v1/admin/webhooks/deliveries/{transactionId}` (Option A). This is consistent with `ReconciliationResource`, is automatically in the JWT chain, and requires updating 2 IT files (`TenantFilterChainIT` and `WebhookDeliveryIT` Test 3). Option A requires fewer workarounds than Option B.

2. **ROLE_USER-only user fixture for the 403 test**
   - What we know: The existing admin fixture has both ROLE_ADMIN and ROLE_USER. A true "ROLE_USER → 403" test requires a user with no ROLE_ADMIN.
   - What's unclear: Is it acceptable to test "no auth → 403" as a proxy for "ROLE_USER → 403"?
   - Recommendation: Seed a second user with only ROLE_USER for precision. Use a different user id (e.g. `675373350208068097`) and email (e.g. `user@test.com`) with any bcrypt password.

3. **Teardown order for ledger rows in extended WebhookDoubleCheckIT**
   - What we know: `WebhookDoubleCheckIT.tearDown()` deletes `payment_event_log` → `transaction` → `tenant_api_key` → `tenant` → `sec`. It does NOT delete `ledger_entry`.
   - What's unclear: After wiring, the SUCCESS test will write ledger rows. Without `DELETE FROM main.ledger_entry` before `DELETE FROM main.transaction`, the teardown may fail.
   - Recommendation: Add `DELETE FROM main.ledger_entry` as the first delete in the extended test's `@AfterEach`.

---

## Sources

### Primary (HIGH confidence)

All findings come from direct code inspection of the repository. No external sources consulted.

| File | What was checked |
|------|-----------------|
| `LedgerService.java` | Full `postEntry()` signature, parameters, transaction behavior |
| `LedgerEntry.java` | All fields, @Immutable, @Builder, column names and constraints |
| `LedgerEntryRepository.java` | `findByTransactionId()` method |
| `V4__ledger_schema.sql` | Complete `ledger_entry` schema — columns, types, constraints, indexes |
| `WebhookTransitionService.java` | Full `applyFinalTransition()` method, constructor, available `tx` fields |
| `WebhookDeliveryResource.java` | Current routing (`/v1/webhooks`), absence of `@PreAuthorize` |
| `SecurityConstants.java` | `HAS_ADMIN_ROLE = "hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN')"` |
| `TenantSecurityConfig.java` | API key chain scope: `/v1/**` EXCEPT `/v1/account/**` and `/v1/admin/**` |
| `ReconciliationResource.java` | Reference implementation of `@PreAuthorize(HAS_ADMIN_ROLE)` at class level |
| `LedgerServiceIT.java` | Existing isolated ledger test — pattern for JDBC seeding, teardown order |
| `WebhookDoubleCheckIT.java` | Full test infrastructure: WireMock setup, `createOrangeProcessingTransaction()`, `postOrangeCallback()`, sleep pattern |
| `WebhookDeliveryIT.java` | Pattern for ledger teardown context; Test 3 uses API key (needs updating if path moves) |
| `ReconciliationApiIT.java` | Reference IT pattern for real `/authenticate` login, JWT cookie forwarding, ROLE_ADMIN test |
| `TenantFilterChainIT.java` | Shows existing tests depend on `/v1/webhooks/deliveries/tx-123` being API-key accessible |
| `AdminLogin.java` | Shared test helper: `loginAsAdmin(url, restTemplate)` |
| `TransactionStatus.java` | SUCCESS is terminal state, PROCESSING→SUCCESS transition allowed |
| `Transaction.java` | `getTransactionId()`, `getTenantId()`, `getAmount()`, `getCurrency()` all available in transition service |
| `AppEndpoints.java` | `/v1/callbacks/orange` and `/v1/callbacks/mtn` are PUBLIC_ENDPOINTS; `/v1/webhooks/**` is not |
| `.planning/v1-MILESTONE-AUDIT.md` | Gap descriptions and proposed fixes |
| `.planning/STATE.md` | Prior decisions relevant to this phase (all listed above) |

---

## Metadata

**Confidence breakdown:**
- Ledger wiring (call site, method signature, transaction semantics): HIGH — all code directly read
- @PreAuthorize annotation syntax and placement: HIGH — ReconciliationResource is exact reference
- Security chain routing for the endpoint: HIGH — TenantSecurityConfig and TenantFilterChainIT both directly read
- IT test patterns (seeding, teardown, WireMock, login): HIGH — WebhookDoubleCheckIT and ReconciliationApiIT both directly read
- Recommendation to move to /v1/admin/webhooks: MEDIUM — architectural judgment based on security chain analysis; an alternative (keep path, update TenantFilterChainIT test expectations) also works

**Research date:** 2026-03-26
**Valid until:** No expiry — all findings are from the current codebase
