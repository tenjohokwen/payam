# Phase 31: Tenant REST API Surface - Research

**Researched:** 2026-04-07
**Domain:** Spring Boot REST controller layer — tenant lifecycle endpoints on existing service layer
**Confidence:** HIGH

## Summary

Phase 31 adds 9 HTTP endpoints to the existing `TenantAdminResource` (plus a new `TenantQueryService` for read operations). The service layer is entirely complete — `TenantService` has `updateName`, `updateEmail`, `updateWebhookUrl`, `suspend`, `reactivate`, and `regenerateWebhookSecret`; `ApiKeyService` has `generateAndStore`, `rotate`, and `revoke`. No new domain logic is required. This phase is purely a mapping from HTTP surface to service calls.

The existing `TenantAdminResource` provides the pattern: method-level `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` on every handler, plain Java records as request/response DTOs defined as inner types, and `@Valid`/`@RequestBody` for body validation. The existing `AdminTransactionResource` provides the pagination pattern: `Page<SummaryDto>` returned from a query service that wraps `PageRequest.of(page, size)`.

Three new artifacts are needed: (1) a `TenantQueryService` with `findAll` (paginated, status-filtered) and `findByTenantRef` (full detail with keys grouped by environment), (2) new DTO types for these responses, and (3) 9 new handler methods on `TenantAdminResource`. The webhook secret reveal endpoint (`WSEC-03`) is a dedicated `GET` that returns the plaintext `webhookSecret` field already stored on the `Tenant` entity — it must never appear in the standard detail response.

**Primary recommendation:** Add `TenantQueryService` as a `@Transactional(readOnly=true)` `@Service` in `tenant/service/`, add response DTOs in `tenant/contract/`, extend `TenantAdminResource` with 9 new handler methods each annotated `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` at method level, and add a `findAll(TenantStatus, Pageable)` query on `TenantRepository`.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TENT-05 | `GET /v1/admin/tenants` — paginated, status-filtered list | Pagination pattern from `AdminTransactionResource`; needs `TenantRepository.findAll(TenantStatus, Pageable)` |
| TENT-06 | `GET /v1/admin/tenants/{tenantRef}` — full detail (name, email, webhookUrl, status, keys by env); `webhookSecret` absent | Read `Tenant` + `findAllByTenantId`; new `TenantDetailDto` without secret field |
| TENT-10 | `PATCH /v1/admin/tenants/{tenantRef}/name` | Calls `TenantService.updateName`; returns 204 or updated detail |
| TENT-02 | `PATCH /v1/admin/tenants/{tenantRef}/email` | Calls `TenantService.updateEmail` |
| TENT-03 | `PATCH /v1/admin/tenants/{tenantRef}/webhook-url` | Calls `TenantService.updateWebhookUrl` |
| TENT-04 | `POST /v1/admin/tenants/{tenantRef}/suspend` — atomically revokes all keys | Calls `TenantService.suspend` (already atomic via bulk JPQL update) |
| TENT-07 | `POST /v1/admin/tenants/{tenantRef}/reactivate` — response includes `rawKey` for new PROD key | Calls `TenantService.reactivate`; returns `ApiKeyDto` with rawKey |
| TENT-08 | `POST /v1/admin/tenants/{tenantRef}/webhook-secret` — regenerates secret | Calls `TenantService.regenerateWebhookSecret`; no body needed; returns 204 or new secret |
| WSEC-03 | `GET /v1/admin/tenants/{tenantRef}/webhook-secret` — returns plaintext secret (never in standard detail) | Reads `tenant.getWebhookSecret()` directly; new dedicated endpoint only |
</phase_requirements>

---

## Standard Stack

### Core (all already in project)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Web MVC | (project version) | `@RestController`, `@GetMapping`, `@PatchMapping`, `@PostMapping`, `@RequestParam`, `@PathVariable` | Already used throughout |
| Spring Security | (project version) | `@PreAuthorize`, `@EnableMethodSecurity` | Already active; admin endpoints use this |
| Spring Data JPA | (project version) | `JpaRepository`, `Page<T>`, `Pageable`, `PageRequest` | Used in `AdminTransactionQueryService` |
| jakarta.validation | (project version) | `@Valid`, `@NotBlank`, `@Size`, `@Email`, `@Pattern` | Used in existing `CreateTenantRequest` records |

### No new dependencies required
All libraries needed are already on the classpath. No `pom.xml` changes needed.

---

## Architecture Patterns

### Existing TenantAdminResource Pattern (method-level PreAuthorize)
The existing `TenantAdminResource` uses **method-level** `@PreAuthorize`. This is intentional — STATE.md documents that class-level `@PreAuthorize` breaks `@ExceptionHandler` in `ApiAdvice`. Every new handler MUST use method-level `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`.

```java
// CORRECT — method level
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public TenantCreationResponse createTenant(@Valid @RequestBody CreateTenantRequest request) { ... }

// WRONG — do NOT use class-level @PreAuthorize on TenantAdminResource
// (breaks @ExceptionHandler in ApiAdvice)
```

Note: `AdminTransactionResource` and `PlatformConfigAdminResource` DO use class-level `@PreAuthorize` — this is a controller-specific constraint for `TenantAdminResource` only (it already has mixed existing handlers without class-level).

### Pagination Pattern (from AdminTransactionResource)
```java
// In TenantQueryService (new)
@Transactional(readOnly = true)
public Page<TenantSummaryDto> findAll(TenantStatus status, int page, int size) {
    Pageable pageable = PageRequest.of(page, size);
    // status == null → return all; status != null → filter
    return tenantRepository.findAll(status, pageable).map(this::toSummary);
}

// In TenantAdminResource
@GetMapping
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public ResponseEntity<Page<TenantSummaryDto>> list(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    TenantStatus statusEnum = status != null ? TenantStatus.valueOf(status) : null;
    return ResponseEntity.ok(tenantQueryService.findAll(statusEnum, page, size));
}
```

### Detail Response Pattern
The TENT-06 detail response must include keys grouped by environment. `findAllByTenantId(Long tenantId)` already exists on `TenantApiKeyRepository` and returns all keys (ACTIVE, ROTATED, REVOKED). The detail DTO should expose all keys so the UI can display them. `webhookSecret` must NOT appear.

```java
// TenantDetailDto — webhookSecret absent by design
public record TenantDetailDto(
    Long id,
    String tenantRef,
    String name,
    String email,
    String webhookUrl,
    TenantStatus tenantStatus,
    List<ApiKeySummaryDto> keys  // all keys regardless of status
) {}

// ApiKeySummaryDto — no rawKey field (rawKey is one-time only on creation/rotation)
public record ApiKeySummaryDto(
    Long id,
    String keyPrefix,
    ApiKeyEnvironment environment,
    ApiKeyStatus keyStatus
) {}
```

### PATCH Endpoints Pattern
TENT-10, TENT-02, TENT-03 are single-field update endpoints. Two valid conventions in this codebase: return `204 No Content` (simpler, no mapping needed) or return `200` with updated detail. The simplest convention matching the existing `revokeKey` DELETE (which returns `204 No Content`) is to use `204` for PATCH operations.

```java
@PatchMapping("/{tenantRef}/name")
@ResponseStatus(HttpStatus.NO_CONTENT)
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public void updateName(@PathVariable String tenantRef,
                       @Valid @RequestBody UpdateNameRequest req) {
    tenantService.updateName(tenantRef, req.name());
}

public record UpdateNameRequest(@NotBlank @Size(max = 255) String name) {}
```

### Suspend/Reactivate Pattern
```java
@PostMapping("/{tenantRef}/suspend")
@ResponseStatus(HttpStatus.NO_CONTENT)
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public void suspend(@PathVariable String tenantRef) {
    tenantService.suspend(tenantRef);
}

@PostMapping("/{tenantRef}/reactivate")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public ApiKeyDto reactivate(@PathVariable String tenantRef) {
    ApiKeyService.ApiKeyAndRawKey result = tenantService.reactivate(tenantRef);
    return new ApiKeyDto(
        result.entity().getId(),
        result.entity().getKeyPrefix(),
        result.entity().getEnvironment(),
        result.rawKey()  // rawKey shown once — PROD key for reactivated tenant
    );
}
```

### Webhook Secret Endpoints Pattern
Two separate endpoints for TENT-08 and WSEC-03:
```java
// TENT-08: regenerate (POST — mutates state)
@PostMapping("/{tenantRef}/webhook-secret")
@ResponseStatus(HttpStatus.NO_CONTENT)
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public void regenerateWebhookSecret(@PathVariable String tenantRef) {
    tenantService.regenerateWebhookSecret(tenantRef);
}

// WSEC-03: retrieve (GET — read-only, separate from standard detail)
@GetMapping("/{tenantRef}/webhook-secret")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public WebhookSecretDto getWebhookSecret(@PathVariable String tenantRef) {
    return tenantQueryService.getWebhookSecret(tenantRef);
}

public record WebhookSecretDto(String webhookSecret) {}
```

### TenantQueryService Structure
New service class in `tenant/service/` alongside `TenantService`:
```java
@Service
@Transactional(readOnly = true)
public class TenantQueryService {
    private final TenantRepository tenantRepository;
    private final TenantApiKeyRepository keyRepository;

    // findAll: paginated, optional status filter
    // findByTenantRef: full detail (name, email, webhookUrl, status, all keys)
    // getWebhookSecret: read webhookSecret field (used by WSEC-03 endpoint only)
}
```

### TenantRepository Query Needed
`TenantRepository` currently has only `findByTenantRef` and `findByTenantRefAndTenantStatus`. Phase 31 needs a paginated query with optional status filter. Two options:
1. JPQL query with conditional: `findAll(Pageable)` (no filter) + `findByTenantStatus(TenantStatus, Pageable)` (filtered)
2. Spring Data JPA `findByTenantStatus(TenantStatus status, Pageable pageable)` — derived method, zero boilerplate

Recommendation: add derived method `findByTenantStatus(TenantStatus, Pageable)` to `TenantRepository`, and call `tenantRepository.findAll(pageable)` when status is null.

### Recommended Project Structure
No structural changes. All new files go into existing packages:
```
src/main/java/com/softropic/payam/tenant/
├── api/
│   └── TenantAdminResource.java      -- add 9 new methods + inner request/response records
├── contract/
│   ├── TenantSummaryDto.java         -- new (list view: id, tenantRef, name, status)
│   ├── TenantDetailDto.java          -- new (detail: all fields except webhookSecret)
│   ├── ApiKeySummaryDto.java         -- new (id, keyPrefix, environment, keyStatus; no rawKey)
│   └── WebhookSecretDto.java         -- new (webhookSecret only; returned from WSEC-03)
├── service/
│   └── TenantQueryService.java       -- new (read-only query service)
└── repo/
    └── TenantRepository.java         -- add findByTenantStatus(TenantStatus, Pageable)
```

### Anti-Patterns to Avoid
- **Class-level `@PreAuthorize` on `TenantAdminResource`:** Breaks `@ExceptionHandler` in `ApiAdvice` (STATE.md confirmed). Every handler must have its own annotation.
- **Exposing `webhookSecret` in `TenantDetailDto`:** TENT-06 success criterion says `webhookSecret` is absent. Only `WebhookSecretDto` via the dedicated GET endpoint may contain it.
- **Returning `rawKey` in `TenantDetailDto` or `ApiKeySummaryDto`:** The `rawKey` field on `ApiKeyDto` is only valid at creation/rotation time. The summary DTO for list/detail views must not include `rawKey`.
- **Loading `tenant.getApiKeys()` from the lazy collection:** `apiKeys` is `LAZY`. Query `keyRepository.findAllByTenantId(tenant.getId())` instead to avoid N+1 or `LazyInitializationException`.
- **`IllegalStateException` reaching the client as 500:** `TenantService.reactivate` can throw `IllegalStateException` from `generateAndStore` if an ACTIVE PROD key already exists (tenant already has a key). The endpoint should either catch this and return `409 Conflict`, or the service should be adjusted. The `ApiAdvice` has no handler for `IllegalStateException` — it falls to the `defaultErrorHandler` which returns 500. A `@ExceptionHandler(IllegalStateException.class)` should be added to `ApiAdvice`, or the controller should catch it.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Pagination | Manual offset/limit SQL | Spring Data `Page<T>` + `Pageable` | Page metadata (total, hasNext) included automatically |
| Optional status filter | Complex conditional JPQL | Two derived repo methods (`findAll(Pageable)` + `findByTenantStatus(TenantStatus, Pageable)`) | Zero boilerplate; Spring Data generates the query |
| 404 for unknown tenantRef | Custom check | `EntityNotFoundException` in `TenantService.findTenantOrThrow` | Already thrown; `ApiAdvice.entityNotFoundExceptionHandler` maps it to HTTP 404 |
| Validation | Manual null checks | `@Valid` + `@NotBlank` / `@Email` / `@Size` / `@Pattern` on request records | `MethodArgumentNotValidException` is already handled → 400 |

**Key insight:** The service layer is complete. All state transitions, atomicity guarantees, and validation logic already exist. This phase is purely HTTP mapping.

---

## Common Pitfalls

### Pitfall 1: LazyInitializationException on apiKeys collection
**What goes wrong:** Calling `tenant.getApiKeys()` in `TenantQueryService` after the `@Transactional(readOnly=true)` session has closed.
**Why it happens:** `Tenant.apiKeys` is `@OneToMany(fetch=LAZY)`. Accessing it outside the session boundaries triggers the exception.
**How to avoid:** Call `keyRepository.findAllByTenantId(tenant.getId())` to load keys explicitly within the same transaction.
**Warning signs:** `LazyInitializationException: failed to lazily initialize a collection of role com.softropic.payam.tenant.repo.Tenant.apiKeys` in logs.

### Pitfall 2: 409 vs 500 when reactivating a tenant that already has an active PROD key
**What goes wrong:** `TenantService.reactivate()` calls `apiKeyService.generateAndStore()` which throws `IllegalStateException("Active key already exists for environment: PROD")`. `ApiAdvice` has no handler for `IllegalStateException` — falls to `defaultErrorHandler` → HTTP 500.
**Why it happens:** A tenant could theoretically have an active PROD key if reactivated twice. The `IllegalStateException` is a business-rule violation, not a server error.
**How to avoid:** Add `@ExceptionHandler(IllegalStateException.class)` to `ApiAdvice` mapping to HTTP 409 Conflict, OR check for existing active key before calling service.
**Warning signs:** Integration tests expecting 409 receive 500 instead.

### Pitfall 3: webhookSecret leaking into TenantDetailDto
**What goes wrong:** Developer adds `tenant.getWebhookSecret()` to `TenantDetailDto` for convenience.
**Why it happens:** `Tenant` entity has `getWebhookSecret()` and it's natural to include all fields.
**How to avoid:** `TenantDetailDto` record definition must omit `webhookSecret`. `WebhookSecretDto` is the ONLY dto that contains it. Review DTO constructor call during code review.
**Warning signs:** Test asserting `webhookSecret` is absent from `GET /v1/admin/tenants/{ref}` fails.

### Pitfall 4: Using class-level @PreAuthorize on TenantAdminResource
**What goes wrong:** Adding `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` at class level breaks `@ExceptionHandler` routing in `ApiAdvice`.
**Why it happens:** Spring Security's AOP interception interacts poorly with `@ExceptionHandler` when security annotations are at the class level in this codebase (confirmed in STATE.md).
**How to avoid:** Every new handler method must carry its own `@PreAuthorize` annotation. Do not add class-level annotation.
**Warning signs:** Integration tests for non-admin calls receive 403 but error response body is missing or malformed.

### Pitfall 5: Pagination parameter type mismatch with TenantStatus
**What goes wrong:** Accepting `status` as a `@RequestParam TenantStatus` directly causes a 400 when the param is omitted and `required=false`.
**Why it happens:** Spring tries to bind `null` string to enum; with `required=false` the parameter becomes a `null` String that is then parsed.
**How to avoid:** Accept as `String status` with `@RequestParam(required=false)`, then convert: `status != null ? TenantStatus.valueOf(status) : null`. Wrap in try/catch or let `MethodArgumentTypeMismatchException` handle invalid values (already mapped to 400 in ApiAdvice).

---

## Code Examples

### Existing rotate pattern (reference for new endpoints)
```java
// Source: TenantAdminResource.java
@PostMapping("/{tenantId}/keys/{keyId}/rotate")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public ApiKeyDto rotateKey(@PathVariable Long tenantId, @PathVariable Long keyId) {
    ApiKeyService.ApiKeyAndRawKey result = apiKeyService.rotate(keyId);
    return new ApiKeyDto(
        result.entity().getId(),
        result.entity().getKeyPrefix(),
        result.entity().getEnvironment(),
        result.rawKey()
    );
}
```

### Existing pagination pattern (AdminTransactionResource)
```java
// Source: AdminTransactionResource.java + AdminTransactionQueryService.java
@GetMapping
public ResponseEntity<Page<TransactionSummaryDto>> search(
        @RequestParam(required = false) String transactionId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(
        queryService.search(transactionId, ..., page, size)
    );
}

// In service:
Pageable pageable = PageRequest.of(page, size);
return transactionRepository.adminSearch(..., pageable).map(this::toSummary);
```

### EntityNotFoundException → 404 (ApiAdvice)
```java
// Source: ApiAdvice.java lines 361-366
@ExceptionHandler(EntityNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public ErrorDto entityNotFoundExceptionHandler(final EntityNotFoundException enfe) {
    final String defaultMsg = "The requested entity could not be found";
    return logErrorAndReturnDTO(enfe, defaultMsg, "generic.notFound");
}
// TenantService.findTenantOrThrow() throws EntityNotFoundException for unknown tenantRef
// → automatically returns 404 with ErrorDto — no controller-level catch needed
```

### Test infrastructure pattern (TenantAdminResourceIT)
```java
// Source: TenantAdminResourceIT.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class TenantAdminResourceIT {
    @BeforeEach void setUp() {
        // Insert sec row (JWT key), authority rows, admin user → use AdminLogin.loginAsAdmin()
    }
    @AfterEach void tearDown() {
        // DELETE FROM idempotency_key, tenant_api_key, tenant, sec
    }
    // Tests use RestTemplate with adminCookies HttpHeaders
    // Error cases use assertThatThrownBy → HttpClientErrorException
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No TenantQueryService | Add TenantQueryService in Phase 31 | Phase 31 (new) | Clean separation of read vs write |
| TenantDto (id, tenantRef, name, status only) | TenantDetailDto (adds email, webhookUrl, keys) | Phase 31 (new) | TENT-06 detail response |
| No paginated tenant list | Page<TenantSummaryDto> from GET /v1/admin/tenants | Phase 31 (new) | TENT-05 |

**Existing, completed (do not change):**
- `TenantService`: all 6 mutation operations complete — `createTenant`, `updateName`, `updateEmail`, `updateWebhookUrl`, `suspend`, `reactivate`, `regenerateWebhookSecret`
- `ApiKeyService`: `generateAndStore`, `rotate`, `revoke`, `authenticate` — complete
- `webhookSecret`: stored plaintext in `tenant.webhook_secret` column (V8 migration) — correct, no encryption needed
- `TenantAdminResource`: `createTenant` (POST), `rotateKey` (POST /{tenantId}/keys/{keyId}/rotate), `revokeKey` (DELETE /{tenantId}/keys/{keyId}) — 3 methods already exist

---

## Open Questions

1. **Response body for PATCH endpoints (204 vs 200)**
   - What we know: `revokeKey` returns 204 No Content; `rotateKey` returns 200 with body
   - What's unclear: Should `updateName`, `updateEmail`, `updateWebhookUrl` return 204 or 200 with updated detail?
   - Recommendation: Use 204 No Content for PATCH — simpler, consistent with `revokeKey`. The UI can refresh state via a subsequent GET detail call.

2. **Response body for `POST /webhook-secret` (TENT-08)**
   - What we know: `tenantService.regenerateWebhookSecret` returns the new secret String
   - What's unclear: Should the regenerate response return the new secret, or should the caller be required to call `GET /webhook-secret` to retrieve it?
   - Recommendation: Return 204 No Content from POST. The secret is retrieved separately via `GET /webhook-secret` (WSEC-03). This enforces the separation between "trigger regeneration" and "retrieve secret" — matches the one-time-reveal pattern.

3. **`IllegalStateException` mapping (409 vs 500)**
   - What we know: `ApiKeyService.generateAndStore` throws `IllegalStateException` when an ACTIVE key already exists
   - What's unclear: Is this scenario reachable in production via `POST /reactivate`? (Yes — if called on an already-active tenant that somehow kept its PROD key)
   - Recommendation: Add `@ExceptionHandler(IllegalStateException.class)` → HTTP 409 Conflict to `ApiAdvice`. This makes the error surfaceable to the UI with a meaningful status code.

4. **Idempotency for suspend/reactivate**
   - What we know: `TenantService.suspend` sets `SUSPENDED` on any tenant (even if already `SUSPENDED`); `TenantService.reactivate` will throw `IllegalStateException` if called on an ACTIVE tenant with an existing PROD key
   - Recommendation: Document that `POST /suspend` is idempotent (safe to call multiple times). `POST /reactivate` is NOT idempotent (throws on second call). Integration tests must cover both.

---

## Environment Availability

Step 2.6: SKIPPED — Phase 31 is purely code additions (new REST handlers, service, DTOs). No external tools, databases, or CLIs beyond the existing Maven/Java project are needed.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + AssertJ |
| Config file | none — Spring Boot auto-configures |
| Quick run command | `./mvnw test -Dtest="TenantAdminResourceIT" -q` |
| Full suite command | `./mvnw test -q` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TENT-05 | GET /v1/admin/tenants returns Page with status filter | integration | `./mvnw test -Dtest="TenantAdminResourceIT#listTenants*" -q` | Wave 0 gap |
| TENT-06 | GET /v1/admin/tenants/{ref} returns detail without webhookSecret | integration | `./mvnw test -Dtest="TenantAdminResourceIT#getTenantDetail*" -q` | Wave 0 gap |
| TENT-10 | PATCH /name returns 204 and persists change | integration | `./mvnw test -Dtest="TenantAdminResourceIT#updateName*" -q` | Wave 0 gap |
| TENT-02 | PATCH /email returns 204 and persists change | integration | `./mvnw test -Dtest="TenantAdminResourceIT#updateEmail*" -q` | Wave 0 gap |
| TENT-03 | PATCH /webhook-url returns 204 and persists change | integration | `./mvnw test -Dtest="TenantAdminResourceIT#updateWebhookUrl*" -q` | Wave 0 gap |
| TENT-04 | POST /suspend returns 204; all keys REVOKED | integration | `./mvnw test -Dtest="TenantAdminResourceIT#suspend*" -q` | Wave 0 gap |
| TENT-07 | POST /reactivate returns ApiKeyDto with non-null rawKey | integration | `./mvnw test -Dtest="TenantAdminResourceIT#reactivate*" -q` | Wave 0 gap |
| TENT-08 | POST /webhook-secret returns 204 and secret is regenerated | integration | `./mvnw test -Dtest="TenantAdminResourceIT#regenerateWebhookSecret*" -q` | Wave 0 gap |
| WSEC-03 | GET /webhook-secret returns plaintext secret; absent from detail | integration | `./mvnw test -Dtest="TenantAdminResourceIT#getWebhookSecret*" -q` | Wave 0 gap |

### Sampling Rate
- **Per task commit:** `./mvnw test -Dtest="TenantAdminResourceIT" -q`
- **Per wave merge:** `./mvnw test -q`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
All tests are new — `TenantAdminResourceIT` exists but only covers `rotateKey`, `revokeKey`, and `rotateKey_unknownKeyId_returns404`. All 9 requirement-covering test methods must be added as part of Phase 31 implementation (inline with each handler, TDD style).

---

## Sources

### Primary (HIGH confidence)
- Direct source code inspection: `TenantAdminResource.java`, `TenantService.java`, `ApiKeyService.java`, `TenantRepository.java`, `TenantApiKeyRepository.java`, `Tenant.java`, `TenantApiKey.java`
- Direct source code inspection: `ApiAdvice.java` — exception handler mappings
- Direct source code inspection: `AdminTransactionResource.java` + `AdminTransactionQueryService.java` — pagination pattern
- Direct source code inspection: `TenantAdminResourceIT.java`, `TenantServiceIT.java` — test infrastructure pattern
- Direct source code inspection: `SecurityConstants.java`, `TenantSecurityConfig.java`, `SecurityConfiguration.java` — auth setup
- STATE.md — confirmed: service layer complete; class-level @PreAuthorize breaks @ExceptionHandler; webhook_secret plaintext; Phase 30 complete

### Secondary (MEDIUM confidence)
- None needed — all findings verified from source.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — verified from live source files
- Architecture: HIGH — all patterns extracted directly from existing working code
- Pitfalls: HIGH — pitfalls derived from actual constraints in source code (`IllegalStateException` not in `ApiAdvice`, lazy collection in `Tenant`, STATE.md `@PreAuthorize` note)
- Test patterns: HIGH — test infrastructure copied from existing `TenantAdminResourceIT`

**Research date:** 2026-04-07
**Valid until:** 2026-05-07 (stable Spring project; no fast-moving dependencies)
