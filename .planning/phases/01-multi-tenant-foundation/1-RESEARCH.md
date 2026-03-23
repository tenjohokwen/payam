# Phase 1: Multi-Tenant Foundation - Research

**Researched:** 2026-03-23
**Domain:** Multi-tenancy, API key authentication, tenant isolation, Spring Security filter chains
**Confidence:** HIGH — all claims verified against existing codebase, Spring Security official docs, and authoritative sources

---

## Summary

Phase 1 introduces a Tenant domain model, API key authentication filter chain, and per-tenant data isolation.
The research reveals that most building blocks already exist in the codebase: the security module provides
`SecKey`/`SecKeyService` for encrypted key storage, `PermutedSecretKey` for at-rest obfuscation,
`AbstractAuditingEntity` for auditing, and `SecurityConfiguration` showing the exact Spring Security 6 pattern
for adding filter chains with `@Order`. The work in Phase 1 is: (a) defining the `Tenant` entity and Flyway
schema, (b) adding a new `@Order(1)` `SecurityFilterChain` for API key authentication scoped to `/v1/**`
paths, (c) injecting `tenantId` into a `TenantContext` ThreadLocal and enforcing it at the query layer, and
(d) implementing key rotation with a time-bounded grace period.

Phase 1 is the foundation that every subsequent phase queries by `tenantId`. The critical decision is where
tenant isolation is enforced: application-level (`WHERE tenant_id = ?` in every repository) is the correct
choice for this stack — not PostgreSQL RLS, which requires a separate DB role and adds configuration complexity
that is not justified when Hibernate can enforce isolation reliably with a compile-time constraint approach.

**Primary recommendation:** Add an `@Order(1)` `SecurityFilterChain` scoped to `/v1/**` that authenticates
via API key (a custom `OncePerRequestFilter`), sets `TenantContext`, and is completely independent of the
existing JWT chain. Enforce tenant isolation at the repository layer with `tenantId` in every query.

---

## Standard Stack

The project already has all necessary dependencies for Phase 1. No new dependencies are required.

### Core (already in pom.xml)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-starter-security` | 3.5.11 (managed) | API key filter chain, `@Order`, `securityMatcher` | Already present; Spring Security 6 supports multiple `SecurityFilterChain` beans natively |
| `spring-boot-starter-data-jpa` | 3.5.11 (managed) | `Tenant` entity, `TenantApiKey` entity, repositories | Already present; JPA is the persistence mechanism in the project |
| `flyway-database-postgresql` | managed | Schema migrations for `tenant` and `tenant_api_key` tables | Already present and configured (`defaultSchema: main`) |
| `hibernate-envers` | 6.6.14.Final | Audit trail on tenant and API key changes | Already present and in use (`@Audited` on `User`, `SecKey`) |
| `commons-codec` | 1.19.0 | `DigestUtils.sha256Hex` for API key hashing | Already present |
| `jasypt` | 1.9.3 | AES-256 at-rest encryption via existing `Cryptopher` utility | Already present; used by `PermutedSecretKey` |
| `commons-lang3` | 3.20.0 | `RandomStringUtils.secure()` for key generation | Already present |

### Key Secret Storage Pattern (existing)

The project already has `SecKey` + `SecKeyService` + `PermutedSecretKey` for storing encrypted, permuted
secret keys. API key secrets for tenants follow this same pattern rather than a new one.

### Not Yet in pom.xml

Redis (`spring-boot-starter-data-redis`) is not yet a dependency. Phase 1 does not require Redis —
idempotency key scope is only referenced in Success Criterion 5, which is a schema constraint, not
a runtime lookup (that belongs to Phase 2). Phase 1 uses PostgreSQL for all state.

**No new dependencies need to be added for Phase 1.**

---

## Architecture Patterns

### Recommended Project Structure for Phase 1

```
src/main/java/com/softropic/payam/
├── tenant/                        # NEW module for Phase 1
│   ├── api/
│   │   └── TenantAdminResource.java        # POST /v1/admin/tenants
│   ├── contract/
│   │   ├── TenantDto.java
│   │   ├── ApiKeyDto.java                  # response on key creation/rotation
│   │   └── TenantStatus.java               # ACTIVE, SUSPENDED
│   ├── service/
│   │   ├── TenantService.java              # create tenant, provision key
│   │   └── ApiKeyService.java              # authenticate, rotate, revoke
│   ├── repo/
│   │   ├── Tenant.java                     # @Entity
│   │   ├── TenantRepository.java
│   │   ├── TenantApiKey.java               # @Entity
│   │   └── TenantApiKeyRepository.java
│   └── config/
│       └── TenantSecurityConfig.java       # @Order(1) SecurityFilterChain
│
└── security/                      # EXISTING — extend where noted
    └── common/util/
        └── TenantContext.java     # NEW — ThreadLocal holder for tenantId
```

### Pattern 1: Dual SecurityFilterChain (API Key + JWT)

**What:** Spring Security 6 supports multiple `@Bean SecurityFilterChain` instances. Each is scoped
to a URL pattern via `securityMatcher()`. The `FilterChainProxy` picks the first chain whose
matcher matches the request URL. A chain with `@Order(1)` runs first.

**When to use:** When tenant API calls (`/v1/**`) must authenticate via API key while the existing
admin/internal flows authenticate via JWT. The chains must never overlap on URL patterns.

**Pattern — existing chain (JWT, must be updated to exclude `/v1/**`):**

The existing `SecurityConfiguration.filterChain()` has no `securityMatcher`, so it matches ALL
requests. This must be updated: it must either explicitly match `/api/**` and `/manage/**`, or the
new API key chain must use a `securityMatcher("/v1/**")` so that requests to `/v1/**` are claimed
by `@Order(1)` before the existing chain processes them.

```java
// Source: Spring Security 6 official docs + existing SecurityConfiguration.java pattern
// TenantSecurityConfig.java — NEW file in tenant/config/
@Configuration
@Order(1)   // takes priority over the existing filterChain (which has no @Order == lowest priority)
public class TenantSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain tenantApiKeyFilterChain(HttpSecurity http,
                                                       ApiKeyAuthenticationFilter apiKeyFilter) throws Exception {
        http
            .securityMatcher("/v1/**")               // only tenant API paths
            .csrf(AbstractHttpConfigurer::disable)   // API clients, not browsers
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz ->
                authz.anyRequest().authenticated())
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

**Critical:** The existing `SecurityConfiguration.filterChain()` bean has no `@Order` annotation,
which means it defaults to the lowest priority. Once `TenantSecurityConfig` registers an `@Order(1)`
chain with `securityMatcher("/v1/**")`, requests to `/v1/**` will be handled exclusively by the new
chain. The existing chain only processes requests that do not match `/v1/**`.

### Pattern 2: `OncePerRequestFilter` for API Key Authentication

```java
// Source: verified against existing JWTAuthorizationFilter.java in this project
// ApiKeyAuthenticationFilter.java — in tenant/config/ or infrastructure/
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rawKey = request.getHeader(API_KEY_HEADER);
        if (rawKey == null) {
            // 401 — no key presented
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        // Delegate to service — returns authenticated principal or throws
        TenantPrincipal principal = apiKeyService.authenticate(rawKey);
        TenantContext.set(principal.getTenantId());

        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();  // CRITICAL: prevent cross-request leakage
        }
    }
}
```

### Pattern 3: TenantContext ThreadLocal

```java
// Source: well-established pattern for Spring servlet stack (non-reactive)
// Note: this project uses spring-boot-starter-web (servlet), not WebFlux, so ThreadLocal is safe.
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String get() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();  // use remove(), not set(null), to avoid memory leaks
    }
}
```

### Pattern 4: API Key Entity Schema

The API key must store a hashed version of the raw key, not the raw key. The raw key is shown
exactly once on creation and never stored in plaintext.

```sql
-- Flyway: V2__tenant_schema.sql  (V1 is reserved for existing security schema)
-- Adjust version numbers after confirming existing Flyway baseline version

CREATE TABLE main.tenant (
    id              BIGSERIAL PRIMARY KEY,
    tenant_ref      VARCHAR(36) UNIQUE NOT NULL,  -- external identifier, UUID
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by      VARCHAR(50),
    created_date    TIMESTAMP,
    last_modified_by VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id      VARCHAR(255),
    session_id      TEXT
);

CREATE TABLE main.tenant_api_key (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES main.tenant(id),
    key_hash        VARCHAR(64) NOT NULL,         -- SHA-256 hex of raw key
    key_prefix      VARCHAR(8)  NOT NULL,         -- first 8 chars for identification (non-secret)
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    environment     VARCHAR(10) NOT NULL DEFAULT 'LIVE', -- LIVE or SANDBOX
    expires_at      TIMESTAMP,                    -- NULL = no expiry
    rotated_at      TIMESTAMP,                    -- set when key is rotated out; still valid until grace expires
    created_by      VARCHAR(50),
    created_date    TIMESTAMP,
    last_modified_by VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id      VARCHAR(255),
    session_id      TEXT
);

CREATE INDEX idx_tenant_api_key_hash ON main.tenant_api_key(key_hash);
CREATE INDEX idx_tenant_api_key_tenant_id ON main.tenant_api_key(tenant_id);
```

### Pattern 5: Tenant Isolation Enforcement

Enforce `tenantId` at the repository level, not in service methods. Every repository that holds
tenant-scoped data adds a query parameter from `TenantContext`.

```java
// Example: any future repository for tenant-scoped data
// Source: standard Spring Data JPA derived query pattern
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // TenantContext.get() is read by the service caller — NOT directly in the query annotation
    // The service passes tenantId explicitly:
    List<Payment> findAllByTenantId(String tenantId);

    Optional<Payment> findByIdAndTenantId(Long id, String tenantId);
}

// Service enforces isolation:
public Payment getPayment(Long paymentId) {
    String tenantId = TenantContext.get();  // extracted from filter
    return paymentRepository.findByIdAndTenantId(paymentId, tenantId)
        .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
}
```

### Anti-Patterns to Avoid

- **Missing `TenantContext.clear()` in `finally` block:** Servlet container reuses threads. Failure to
  clear ThreadLocal causes tenant leakage between requests. Always clear in `finally`.
- **No `@Order` on existing JWT chain:** If the existing `SecurityConfiguration.filterChain` receives
  no `@Order`, it has `Integer.MAX_VALUE` precedence (lowest priority) by default in Spring Security.
  This is correct — but verify it; an `@Order` annotation on the existing bean must not conflict.
- **Overlapping `securityMatcher` patterns:** If both chains match `/v1/**`, only `@Order(1)` fires.
  The existing chain should NOT explicitly claim `/v1/**`.
- **Authenticating via raw key comparison:** Never compare the raw presented key against the stored
  value directly. Hash the presented key with SHA-256 and compare against `key_hash`. This prevents
  exposure if the `tenant_api_key` table is read by an attacker.
- **Using `set(null)` instead of `remove()` on ThreadLocal:** `set(null)` leaves the ThreadLocal
  entry in the thread's map; `remove()` removes it entirely. Use `remove()`.

---

## Don't Hand-Roll

Problems that look simple but have existing solutions in this codebase or standard libraries:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Encrypted key storage | Custom encryption logic | Existing `Cryptopher` + `PermutedSecretKey` | Already proven; AES-256 via Jasypt; permutation obfuscates key at rest |
| API key hashing | Custom hash | `DigestUtils.sha256Hex(rawKey)` (commons-codec, already imported) | Standard, constant-time safe via comparison, no new dependency |
| Secure random key generation | `Math.random()` or `UUID.randomUUID()` | `SecureRandom` + `Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)` | `UUID` is not a cryptographic primitive; `SecureRandom` is |
| Multiple filter chains | Modifying existing `SecurityConfiguration` to handle both JWT and API key | `@Order(1)` `SecurityFilterChain` with `securityMatcher("/v1/**")` | Spring Security 6 is designed for this; do not add API key logic to the JWT chain |
| Tenant context propagation | Passing `tenantId` as a method parameter through every call | `TenantContext` ThreadLocal cleared in filter `finally` | Parameter drilling is brittle; ThreadLocal is the established Spring MVC pattern |
| Audit trail on tenant entities | Custom audit tables | `AbstractAuditingEntity` + `@Audited` (Hibernate Envers) | Already in use across all existing entities |
| Idempotency key namespace scoping | Application-level string concatenation | Composite unique index on `(tenant_id, idempotency_key)` in Phase 2's schema | Database constraint is authoritative; application logic alone is not |

---

## Common Pitfalls

### Pitfall 1: TenantContext Not Cleared After Request

**What goes wrong:** Requests complete without clearing `TenantContext`. The thread returns to the
pool with a stale `tenantId`. The next request on that thread reads the wrong tenant's ID before
setting its own.

**Why it happens:** Forgetting the `finally` block, or clearing only on the happy path.

**How to avoid:** Always wrap `chain.doFilter(request, response)` in try/finally and call
`TenantContext.clear()` in `finally`. See Pattern 2 above.

**Warning signs:** Integration tests that run multiple tenants sequentially produce cross-contaminated
results on the second tenant.

---

### Pitfall 2: Overlapping SecurityFilterChain Patterns

**What goes wrong:** The new `@Order(1)` chain does not use `securityMatcher("/v1/**")`, so it
matches ALL requests. The JWT chain never fires. Admin login breaks.

**Why it happens:** Omitting `securityMatcher()` defaults to matching every request.

**How to avoid:** Every chain except the fallback (lowest priority) must declare an explicit
`securityMatcher`. Only one chain should have no `securityMatcher` (the catch-all).

**Warning signs:** `SecurityFilterChainIT` tests for JWT authentication start failing after adding
the new chain.

---

### Pitfall 3: API Key Stored or Logged in Plaintext

**What goes wrong:** The raw API key is stored in the database, or printed in logs during debugging.
A DB read or log access compromises all tenant keys.

**Why it happens:** Convenience during development; forgetting that the key is like a password.

**How to avoid:** Store only `SHA-256` hash. Return the raw key to the admin exactly once on creation
(never store it). Apply log sanitization (the existing `BodySanitizer` pattern) to filter `X-Api-Key`
header from access logs.

**Warning signs:** The `tenant_api_key` table has a `raw_key` column, or the Tomcat access log
pattern includes `X-Api-Key` header values.

---

### Pitfall 4: Grace-Period Keys Not Constrained by Time

**What goes wrong:** Rotated keys have `status = 'ROTATED'` but no `rotated_at` timestamp.
The authentication service must decide "is this grace period still valid?" but has no timestamp to
compare against.

**Why it happens:** The grace period concept is added as a status flag but the timestamp is omitted.

**How to avoid:** Store `rotated_at` on the row when a key is rotated. Authentication query fetches
keys where `status = 'ACTIVE'` OR (`status = 'ROTATED'` AND `rotated_at > NOW() - INTERVAL '24 hours'`).

**Warning signs:** `ApiKeyService.authenticate()` accepts rotated keys indefinitely.

---

### Pitfall 5: Flyway Version Collision

**What goes wrong:** Phase 1 creates `V1__tenant_schema.sql`, but existing Flyway migrations use
`V1` as a baseline. Flyway throws `FlywayException: Found more than one migration with version 1`.

**Why it happens:** The `baseline-on-migrate: true` config marks the existing schema as V1 without
a real V1 migration file. Adding an explicit V1 file creates a conflict.

**How to avoid:** Check the current Flyway version with `SELECT version FROM main.flyway_schema_history
ORDER BY installed_rank DESC LIMIT 1`. Use the next available version number. The schema was
created externally (test SQL: `createSchema.sql` creates `main` schema only), so the first real
Flyway migration is likely V1 or needs to start at V2. Verify before naming migration files.

**Warning signs:** Application fails to start with Flyway version conflict on first boot after
adding migration files.

---

### Pitfall 6: TenantContext Lost in @Async / TaskExecutor

**What goes wrong:** A service method annotated `@Async` runs on a pool thread. `TenantContext.get()`
returns `null` because ThreadLocal does not cross thread boundaries.

**Why it happens:** ThreadLocal is per-thread. `@Async` dispatches to a new thread from the executor.

**How to avoid:** Register a `TaskDecorator` on the `AsyncTaskExecutor` that captures the current
`tenantId` before dispatch and restores it within the async thread's scope. Phase 1 may not use
`@Async` immediately, but this pitfall will appear in later phases if the decorator is not in place.

**Pattern:**
```java
// Source: tech.asimio.net Propagating-data-to-Async-Threads
public class TenantContextTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable task) {
        String tenantId = TenantContext.get();
        return () -> {
            try {
                TenantContext.set(tenantId);
                task.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
```

---

## Code Examples

Verified patterns drawn from existing code in this project and official sources.

### Hashing a Raw API Key for Storage

```java
// Source: commons-codec DigestUtils (already in pom.xml as commons-codec 1.19.0)
import org.apache.commons.codec.digest.DigestUtils;

public String hashApiKey(String rawKey) {
    return DigestUtils.sha256Hex(rawKey);
}
```

### Generating a Secure API Key

```java
// Source: java.security.SecureRandom + java.util.Base64 (standard JDK)
// Produces a 32-byte (256-bit) URL-safe key, ~43 characters
import java.security.SecureRandom;
import java.util.Base64;

public String generateApiKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    // Example output: "gY4xKp2mNzL3eFbRqT8HwVsJoD1cUiA0-XnPk7YhQMs"
}
```

### Authentication Query with Grace Period

```java
// Source: derived from SecKeyRepository.findTopByBusIdAndStatusOrderByCreatedDateDesc pattern
// in this project + grace period pattern from WebSearch verification
@Query("""
    SELECT k FROM TenantApiKey k
    WHERE k.keyHash = :keyHash
      AND (k.status = 'ACTIVE'
           OR (k.status = 'ROTATED'
               AND k.rotatedAt > :graceDeadline))
    """)
Optional<TenantApiKey> findValidKeyByHash(
    @Param("keyHash") String keyHash,
    @Param("graceDeadline") Instant graceDeadline
);

// Caller:
public TenantApiKey authenticate(String rawKey) {
    String hash = DigestUtils.sha256Hex(rawKey);
    Instant graceDeadline = Instant.now().minus(Duration.ofHours(24));
    return tenantApiKeyRepository.findValidKeyByHash(hash, graceDeadline)
        .orElseThrow(() -> new AuthenticationException("Invalid or expired API key"));
}
```

### Registering the Tenant API Key Filter Chain

```java
// Source: existing SecurityConfiguration.java + Spring Security 6 official docs (architecture.html)
// Place in tenant/config/TenantSecurityConfig.java
@Configuration
@EnableConfigurationProperties(TenantProperties.class)
public class TenantSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain tenantApiKeyFilterChain(HttpSecurity http,
                                                        ApiKeyAuthenticationFilter apiKeyFilter)
                                                        throws Exception {
        http
            .securityMatcher("/v1/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

### Tenant Entity (abbreviated)

```java
// Source: existing AbstractAuditingEntity pattern in this project
@Audited
@Entity
@Table(name = "tenant", schema = "main")
public class Tenant extends AbstractAuditingEntity {

    @Column(name = "tenant_ref", unique = true, nullable = false)
    private String tenantRef;   // UUID assigned at creation

    @Column(nullable = false)
    private String name;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL)
    private List<TenantApiKey> apiKeys = new ArrayList<>();
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `WebSecurityConfigurerAdapter.configure(HttpSecurity)` | `@Bean SecurityFilterChain` + `@Order` | Spring Security 5.7 / Spring Boot 3.x | Adapter is removed in Boot 3.x; the functional bean style is mandatory |
| Separate Maven module for multi-tenant config | Single Spring application, separate `@Configuration` class | N/A | This project is already a single module; no multi-module Maven setup needed |
| PostgreSQL RLS for tenant isolation | Application-level `tenantId` in every query | Ongoing preference | RLS requires a second DB role, bypasses schema owner (JPA migrations user), and makes integration tests harder; application-level enforcement is simpler and testable |
| Storing raw API keys | Storing SHA-256 hash only | Industry standard since at least 2020 | Database exposure cannot compromise keys |

**Deprecated/outdated approaches that must not be used:**
- `WebSecurityConfigurerAdapter`: removed in Spring Boot 3.x; already not used in this project.
- `HttpSecurity.antMatcher()` (singular): replaced by `securityMatcher()` in Spring Security 6.
- `HttpSecurity.authorizeRequests()`: replaced by `authorizeHttpRequests()` in Spring Security 6.
  The existing code already uses `authorizeHttpRequests()`; the new chain must also use it.

---

## Open Questions

1. **Current Flyway baseline version**
   - What we know: `baseline-on-migrate: true` and `defaultSchema: main` are set. The test resource
     `createSchema.sql` only creates `CREATE SCHEMA IF NOT EXISTS main;`.
   - What's unclear: Whether any Flyway migration files already exist in `src/main/resources/db/migration/`
     (no such directory was found during research; Flyway may be using `baseline-on-migrate` to skip
     an empty migration history).
   - Recommendation: Run `SELECT version FROM main.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1`
     in the dev DB before naming migration files. If no history exists, start at `V1`.

2. **`allowed.clients` config vs. tenant API key auth**
   - What we know: `SecurityConfiguration` reads `${allowed.clients}` (comma-separated list) and
     uses `ClientIdAccessDecisionManager` for client ID whitelist enforcement on the JWT chain.
   - What's unclear: Whether tenant API key holders are also subject to the `allowed.clients` check,
     or whether that check is only for JWT-authenticated sessions.
   - Recommendation: The new `@Order(1)` chain should bypass `ClientIdAccessDecisionManager` entirely.
     That manager is wired into the JWT authorization flow. The tenant chain has its own
     `ApiKeyAuthenticationFilter` which handles identity verification directly.

3. **`TenantPrincipal` or reuse of existing `Principal`**
   - What we know: Existing `Principal` extends `org.springframework.security.core.userdetails.User`
     and carries `businessId` (the user's DB id), not a `tenantId`.
   - What's unclear: Whether downstream phases need a `TenantPrincipal` with a `tenantId` field, or
     whether tenant identity should be stored only in `TenantContext` (ThreadLocal).
   - Recommendation: Create a minimal `TenantPrincipal` that implements `UserDetails` and carries
     `tenantId` + granted authority `ROLE_TENANT`. This integrates cleanly with Spring Security's
     `SecurityContextHolder` and allows method security annotations to work.

---

## Sources

### Primary (HIGH confidence)

- Existing codebase: `SecurityConfiguration.java`, `JWTAuthorizationFilter.java`, `SecKeyService.java`,
  `PermutedSecretKey.java`, `AbstractAuditingEntity.java`, `AppEndpoints.java` — all read directly
- Existing `pom.xml` — dependency versions verified by reading the file
- Spring Security official docs — architecture.html (verified via WebSearch result pointing to
  `https://docs.spring.io/spring-security/reference/servlet/architecture.html`)

### Secondary (MEDIUM confidence)

- WebFetch: https://blog.boottechsolutions.com/2025/01/13/spring-security-6-multiple-securityfilterchain-instances/
  — confirmed `@Order` + `securityMatcher()` pattern for Spring Security 6
- WebFetch: https://tech.asimio.net/2024/09/12/Propagating-data-to-Async-Threads-with-ThreadLocalTargetSource-TaskDecorator-Spring-Boot.html
  — confirmed `TaskDecorator` pattern for ThreadLocal propagation to async threads
- WebSearch: "API key rotation grace period dual validity Spring Security" — confirmed dual-status
  query pattern (`ACTIVE` OR `ROTATED + rotated_at within grace window`)

### Tertiary (LOW confidence — noted for awareness, not relied upon for implementation)

- WebSearch: "PostgreSQL row level security tenant isolation Spring Data JPA 2025" — RLS pattern
  considered and rejected (see Architecture Patterns rationale above)

---

## Metadata

**Confidence breakdown:**

| Area | Level | Reason |
|------|-------|--------|
| Standard stack | HIGH | All dependencies verified in pom.xml; no new additions needed |
| Filter chain pattern | HIGH | Verified against existing SecurityConfiguration + official Spring Security 6 docs |
| API key entity schema | HIGH | Follows existing AbstractAuditingEntity/SecKey pattern exactly |
| Key rotation grace period | MEDIUM | Confirmed by WebSearch sources; no official Spring Security doc covers this specific scenario |
| Flyway version number | LOW | Existing migration history not confirmed; Open Question #1 must be resolved before writing migration files |
| TenantContext ThreadLocal safety | HIGH | Project uses servlet stack (spring-boot-starter-web), not WebFlux; ThreadLocal is appropriate here |

**Research date:** 2026-03-23
**Valid until:** 2026-06-01 (Spring Security 6 filter chain APIs are stable; no breaking changes expected in this window)
