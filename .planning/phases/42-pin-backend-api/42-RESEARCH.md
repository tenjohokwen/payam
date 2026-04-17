# Phase 42: PIN Backend API - Research

**Researched:** 2026-04-18
**Domain:** Spring Boot REST API — encrypted field read/write, Bean Validation, service layer extension
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PIN-03 | PUT /v1/admin/platform-config/{provider} accepts optional `pin`; validates alphanumeric 4–8 chars (400 on failure); encrypts via Cryptopher; saves atomically with MSISDN in one transaction; empty/absent pin does not overwrite existing | Service layer update() extended with pin parameter; Bean Validation @Pattern on DTO; Cryptopher.encrypt() called only when pin non-blank |
| PIN-04 | GET /v1/admin/platform-config/{provider} returns `pinConfigured: boolean`; PIN value never returned | PlatformConfigDto gains pinConfigured field; findAll() and findByProvider() map pin != null → pinConfigured = true |
| PIN-05 | GET /v1/admin/platform-config/{provider}/pin returns decrypted plaintext PIN; 404 when none configured | New endpoint on PlatformConfigAdminResource; service method findPinByProvider() that throws ResourceNotFoundException when pin is null |
</phase_requirements>

---

## Summary

Phase 42 extends three existing components — `PlatformConfigDto`, `PlatformConfigService`, and `PlatformConfigAdminResource` — to implement PIN CRUD over the existing platform config endpoints. Phase 41 already delivered the `pin` column on `main.platform_config`, the `pin` field on the `PlatformConfig` entity, and the `pinEncryptionSecret` property on `PayamPlatformProperties`. Phase 42 wires those pieces to the API surface.

The implementation is an extension pattern, not a replacement: existing MSISDN handling is untouched except that `update()` gains a second optional parameter. The encryption utility (`Cryptopher`) is already used elsewhere (see `PermutedSecretKey`) so instantiation and usage patterns are established. The reveal endpoint for the PIN (`GET …/pin`) mirrors the existing `GET …/webhook-secret` endpoint in `TenantAdminResource` — same lazy-decrypt, dedicated sub-resource pattern.

The main design decision is how to inject a `Cryptopher` instance into `PlatformConfigService`. The correct approach is a `@Bean` factory method in `PlatformConfig` (the `@Configuration` class) that creates `Cryptopher` from `PayamPlatformProperties.getPinEncryptionSecret()`, following the precedent of `OrangeMoneyConfig` constructing its own collaborators. This avoids coupling the service to the properties class directly.

**Primary recommendation:** Wire Cryptopher as a Spring bean via PlatformConfig @Configuration; extend PlatformConfigDto with pinConfigured boolean; add updatePin to entity; extend service update() method; add findPinByProvider() service method; add GET /{provider}/pin endpoint to resource.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Jasypt AES256TextEncryptor | 3.x (already on classpath) | AES256 symmetric encryption/decryption | Already in use via Cryptopher; no new dependency needed |
| Jakarta Bean Validation | 3.x | @Pattern, @Size on DTO fields | Spring Boot default; already used throughout project |
| Spring Data JPA | 3.x | Dirty-checking on PlatformConfig entity | Already used; no new persistence code needed |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Cryptopher | project-internal | Thin wrapper around AES256TextEncryptor | Used directly in service — do NOT use AES256TextEncryptor directly |
| ResourceNotFoundException | project-internal | Produces HTTP 404 via ApiAdvice | Throw when pin is null on the reveal endpoint |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| @Bean Cryptopher in PlatformConfig @Configuration | Instantiate Cryptopher in PlatformConfigService constructor | @Bean is cleaner, testable; service constructor injection keeps the service focused |
| ResourceNotFoundException for null PIN | IllegalStateException | IllegalStateException maps to 409 Conflict in ApiAdvice — wrong status for "no PIN set" |

---

## Architecture Patterns

### Recommended Project Structure

No new packages needed. All changes are in existing files plus one new class.

```
platform/
├── api/
│   └── PlatformConfigAdminResource.java   # ADD: GET /{provider}/pin endpoint
├── config/
│   ├── PlatformConfig.java               # ADD: @Bean Cryptopher factory method
│   └── PayamPlatformProperties.java      # ALREADY has pinEncryptionSecret (Phase 41)
├── contract/
│   └── PlatformConfigDto.java            # CHANGE: add pinConfigured boolean field
├── repo/
│   └── PlatformConfig.java               # ADD: updatePin(String ciphertext) method
└── service/
    └── PlatformConfigService.java         # CHANGE: update() gains pin param; add findPinByProvider()
```

### Pattern 1: Cryptopher as @Bean

PlatformConfig.java (@Configuration) becomes the factory. This is the established pattern in the project (e.g., OrangeMoneyConfig registers Orange beans).

```java
// In PlatformConfig.java
@Bean
public Cryptopher pinCryptopher(PayamPlatformProperties props) {
    return new Cryptopher(props.getPinEncryptionSecret());
}
```

The Cryptopher constructor throws `EncryptionException(MISSING_SECRET)` when the secret is blank, so the application context fails to start if `PLATFORM_PIN_ENCRYPTION_SECRET` is not set. This is acceptable fail-fast behavior.

**Test implication:** Integration tests must set `payam.platform.pin-encryption-secret` to a non-blank value in test properties.

### Pattern 2: DTO Extension — pinConfigured boolean

`PlatformConfigDto` is currently a two-field record. PIN-04 requires adding `pinConfigured`. The record gains a third component. All construction sites must be updated.

```java
public record PlatformConfigDto(String provider, String platformMsisdn, boolean pinConfigured) {}
```

Construction sites to update:
- `PlatformConfigService.findAll()` — `new PlatformConfigDto(c.getProvider(), c.getPlatformMsisdn(), c.getPin() != null)`
- `PlatformConfigService.findByProvider()` — same
- `PlatformConfigService.update()` — same (return DTO after save reflects current pin state)
- `PlatformConfigServiceTest` — all test DTO constructions need the third arg

### Pattern 3: update() signature extension

The current signature `update(String provider, String newMsisdn)` must gain an optional pin parameter. The resource passes the DTO pin field. The service handles the null/blank guard.

```java
// Service
public PlatformConfigDto update(String provider, String newMsisdn, String pin) {
    // ...
    if (StringUtils.isNotBlank(pin)) {
        config.updatePin(cryptopher.encrypt(pin));
    }
    // ...
}
```

The resource passes `dto.pin()` which may be null — this is fine because the service guards it.

### Pattern 4: PIN reveal endpoint

Mirrors `GET /v1/admin/tenants/{tenantRef}/webhook-secret` exactly. Returns a dedicated DTO, not the full config DTO.

```java
@GetMapping("/{provider}/pin")
public ResponseEntity<PinDto> getPin(@PathVariable String provider) {
    return ResponseEntity.ok(platformConfigService.findPinByProvider(provider));
}
```

Service:
```java
@Transactional(readOnly = true)
public PinDto findPinByProvider(String provider) {
    String upper = provider.toUpperCase();
    PlatformConfig config = platformConfigRepository.findByProvider(upper)
        .orElseThrow(() -> new IllegalStateException("No config for provider: " + provider));
    if (config.getPin() == null) {
        throw new ResourceNotFoundException("No PIN configured for provider: " + provider, provider);
    }
    return new PinDto(cryptopher.decrypt(config.getPin()));
}
```

DTO:
```java
public record PinDto(String pin) {}
```

### Pattern 5: Bean Validation on PUT body

The PlatformConfigDto currently has no validation annotations (it's a plain record). PIN-03 requires 400 on invalid PIN format. The cleanest approach: add `@Pattern` and `@Size` (or just `@Pattern` with a regex that bounds length) to the `pin` field of the DTO, and add `@Valid` to the `@RequestBody` parameter on `update()`.

```java
public record PlatformConfigDto(
    String provider,
    String platformMsisdn,
    boolean pinConfigured,
    @Pattern(regexp = "^$|[a-zA-Z0-9]{4,8}",
             message = "pin|PIN must be alphanumeric and between 4 and 8 characters")
    String pin
) {}
```

**Critical note:** `pinConfigured` is a server-computed field (never sent by the client). The `pin` field is only meaningful on PUT requests (write path). The same DTO is used for GET responses — `pin` will be null on GET responses, and `pinConfigured` will be true/false. This dual-use is already the pattern in this project (PlatformConfigDto was always used for both GET and PUT). No change to the existing dual-use approach is needed.

**Regex choice:** `^$|[a-zA-Z0-9]{4,8}` allows blank (empty string means "no update") or exactly 4–8 alphanumeric chars. The `|` with `^$` means absent/empty is always valid and the service then skips encryption.

**Alternative:** Validate programmatically in the service instead of with annotations. This avoids the dual-use DTO concern. However, the project consistently uses `@Valid` + `@Pattern` for input constraints (see `TenantAdminResource` internal request records), so annotation-based validation is the standard pattern here.

### Anti-Patterns to Avoid

- **Using the full PlatformConfigDto for the PIN reveal endpoint:** The reveal endpoint must return only the PIN plaintext, not the full config. Use a dedicated `PinDto` — same pattern as `WebhookSecretDto`.
- **Encrypting on GET:** Never call `cryptopher.encrypt()` on read paths. Only decrypt on the reveal endpoint.
- **Exposing pin ciphertext in the standard GET response:** `PlatformConfigDto` must NEVER carry the `pin` field value from the entity. Only `pinConfigured` boolean is exposed.
- **Throwing IllegalStateException for null PIN:** ApiAdvice maps `IllegalStateException` → 409 Conflict. Use `ResourceNotFoundException` for "no PIN" to get 404.
- **Failing context startup when secret is blank in tests:** If the @Bean Cryptopher factory fails at startup due to a blank secret in CI/tests, add `payam.platform.pin-encryption-secret=test-aes-secret-for-unit-testing` to `src/test/resources/application.properties`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| AES256 encryption | Custom cipher | Cryptopher (wraps Jasypt AES256TextEncryptor) | Already in codebase, tested, handles blank guard |
| HTTP 404 for "not found" | IllegalStateException + custom handler | ResourceNotFoundException | ApiAdvice already maps it to 404; IllegalStateException maps to 409 |
| HTTP 400 for invalid input | Manual if/throw in service | @Pattern on DTO + @Valid on @RequestBody | ApiAdvice handles MethodArgumentNotValidException → 400 with field errors |

---

## Common Pitfalls

### Pitfall 1: Blank pinEncryptionSecret at context startup
**What goes wrong:** `Cryptopher` constructor throws `EncryptionException(MISSING_SECRET)` when secret is blank. The `@Bean` factory method fails, Spring context does not start.
**Why it happens:** `application.yaml` binds `pin-encryption-secret: ${PLATFORM_PIN_ENCRYPTION_SECRET:}` — empty default. In test environments the env var is not set.
**How to avoid:** Add `payam.platform.pin-encryption-secret=test-aes-secret-32chars` to `src/test/resources/application.properties` before writing any IT test.
**Warning signs:** `BeanCreationException: Error creating bean with name 'pinCryptopher'` in test logs.

### Pitfall 2: pinConfigured field breaks existing DTO construction sites
**What goes wrong:** Adding a third field to the `PlatformConfigDto` record breaks all existing `new PlatformConfigDto(...)` call sites with a compilation error.
**Why it happens:** Java records require all components in the canonical constructor.
**How to avoid:** Update `PlatformConfigService` (findAll, findByProvider, update — both map and orElseGet branches) and `PlatformConfigServiceTest` in the same plan step.
**Warning signs:** Compilation errors on `PlatformConfigDto(...)` constructor calls.

### Pitfall 3: Dual-use DTO — pin field appears in GET response JSON
**What goes wrong:** Jackson serializes all record components. The `pin` field (plaintext from PUT request) would appear as null in GET responses, which is harmless but potentially confusing. More importantly, if code accidentally populates `pin` from the entity, ciphertext leaks into GET /platform-config responses.
**Why it happens:** Same DTO for read and write.
**How to avoid:** In `findAll()` and `findByProvider()`, always construct `new PlatformConfigDto(provider, msisdn, pinConfigured, null)` — never pass `config.getPin()` (ciphertext).
**Warning signs:** GET /platform-config response body contains non-null `pin` field.

### Pitfall 4: update() MSISDN-only call breaks with 400 validation
**What goes wrong:** Existing callers (frontend, tests) send `{"provider":"ORANGE","platformMsisdn":"123"}` without a `pin` field. If @Pattern is applied to the DTO, `null` pin must be allowed by the regex.
**Why it happens:** @Pattern with a regex that requires `[a-zA-Z0-9]{4,8}` rejects null — Bean Validation skips null by default for `@Pattern` but validates empty strings as failures.
**How to avoid:** The regex `^$|[a-zA-Z0-9]{4,8}` allows empty string. Also: `@Pattern` skips null values by default in Bean Validation 3.x — null pin is valid, empty string pin is also valid with this regex.
**Warning signs:** Existing platform config update tests return 400 after adding validation.

### Pitfall 5: ResourceNotFoundException constructor mismatch
**What goes wrong:** `ResourceNotFoundException` has two constructors — one with `(msg, resourceName)` and one with `(msg, logContext, resourceName)`. Using the wrong one compiles but may not log context correctly.
**Why it happens:** Multiple constructors with similar signatures.
**How to avoid:** For the PIN 404 case, use `new ResourceNotFoundException("No PIN configured for provider: " + provider, provider)` — the two-arg constructor. No additional context needed.

---

## Code Examples

### Cryptopher @Bean factory

```java
// Source: project pattern from OrangeMoneyConfig + Cryptopher.java constructor
// In platform/config/PlatformConfig.java
@Bean
public Cryptopher pinCryptopher(PayamPlatformProperties props) {
    return new Cryptopher(props.getPinEncryptionSecret());
}
```

### Entity updatePin method

```java
// In platform/repo/PlatformConfig.java (same pattern as updateMsisdn)
public void updatePin(String ciphertext) {
    this.pin = ciphertext;
}
```

### Service update() with PIN

```java
// In PlatformConfigService.update()
if (StringUtils.isNotBlank(pin)) {
    String cipher = pinCryptopher.encrypt(pin);
    config.updatePin(cipher);
}
```

### Service findPinByProvider()

```java
@Transactional(readOnly = true)
public PinDto findPinByProvider(String provider) {
    String upper = provider.toUpperCase();
    PlatformConfig config = platformConfigRepository.findByProvider(upper)
        .orElseThrow(() -> new IllegalStateException("Platform config not found for provider: " + upper));
    if (config.getPin() == null) {
        throw new ResourceNotFoundException(
            "No PIN configured for provider: " + upper, upper);
    }
    return new PinDto(pinCryptopher.decrypt(config.getPin()));
}
```

### Resource GET /{provider}/pin endpoint

```java
// In PlatformConfigAdminResource.java
@GetMapping("/{provider}/pin")
public ResponseEntity<PinDto> getPin(@PathVariable String provider) {
    return ResponseEntity.ok(platformConfigService.findPinByProvider(provider));
}
```

### DTO with validation

```java
public record PlatformConfigDto(
    String provider,
    String platformMsisdn,
    boolean pinConfigured,
    @Pattern(
        regexp = "^[a-zA-Z0-9]{4,8}$|^$",
        message = "pin|PIN must be alphanumeric and 4–8 characters, or empty to keep existing PIN"
    )
    String pin
) {}
```

### Resource PUT update — add @Valid

```java
@PutMapping("/{provider}")
public ResponseEntity<PlatformConfigDto> update(
        @PathVariable String provider,
        @Valid @RequestBody PlatformConfigDto dto) {
    return ResponseEntity.ok(platformConfigService.update(provider, dto.platformMsisdn(), dto.pin()));
}
```

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers |
| Config file | `src/test/resources/application.properties` |
| Quick run command | `mvn test -pl . -Dtest=PlatformConfigServiceTest,PayamPlatformPropertiesTest -q` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PIN-03 | PUT accepts valid PIN, encrypts, saves atomically; invalid PIN → 400; empty PIN preserves existing | unit (service) + IT (resource) | `mvn test -Dtest=PlatformConfigServiceTest` | ✅ (service test exists, needs new cases) |
| PIN-03 | HTTP 400 on invalid PIN format | IT | `mvn failsafe:integration-test -Dit.test=PlatformConfigAdminResourceIT` | ❌ Wave 0 |
| PIN-04 | GET returns pinConfigured boolean, no PIN value | unit (service) + IT | `mvn test -Dtest=PlatformConfigServiceTest` | ✅ (needs new cases) |
| PIN-05 | GET /pin returns decrypted PIN; 404 when none | unit (service) + IT | `mvn test -Dtest=PlatformConfigServiceTest` | ✅ (needs new cases) |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=PlatformConfigServiceTest,PayamPlatformPropertiesTest`
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full `mvn verify` green before verification

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java` — covers PIN-03 HTTP validation (400 on invalid format), PIN-04 pinConfigured in GET, PIN-05 GET /pin 200 and 404
- [ ] Add `payam.platform.pin-encryption-secret=test-pin-secret-for-tests` to `src/test/resources/application.properties` — prevents `BeanCreationException` for `pinCryptopher` bean in all IT tests

---

## Environment Availability

Step 2.6: SKIPPED — this phase is pure Java/Spring code changes with no new external dependencies. PostgreSQL and Redis are already available via Testcontainers in `TestConfig`.

---

## Open Questions

1. **Cryptopher @Bean and blank secret on application startup in production**
   - What we know: `Cryptopher` throws `EncryptionException(MISSING_SECRET)` on blank. The `@Bean` factory will fail context startup if `PLATFORM_PIN_ENCRYPTION_SECRET` is not set in production.
   - What's unclear: Is this the desired fail-fast behavior, or should the bean be conditional (e.g., `@ConditionalOnProperty`)?
   - Recommendation: Fail fast is correct for production. In tests, provide a non-blank test value in `application.properties`. Document the env var requirement in the `application.yaml` comment.

2. **PlatformConfigDto pin field in GET response — Jackson null serialization**
   - What we know: Java records serialize all components via Jackson. `pin` will serialize as `null` in GET responses.
   - What's unclear: Whether the frontend (Phase 43) expects `pin: null` or the field to be absent.
   - Recommendation: Add `@JsonInclude(JsonInclude.Include.NON_NULL)` on the `pin` field or on the record class to suppress null fields in GET responses. This avoids frontend confusion and is a minor Jackson annotation.

---

## Sources

### Primary (HIGH confidence)
- Direct source code inspection of `PlatformConfigAdminResource.java`, `PlatformConfigService.java`, `PlatformConfig` entity, `Cryptopher.java`, `PlatformConfigDto.java`, `ApiAdvice.java`, `TenantAdminResource.java`, `ResourceNotFoundException.java`, `PayamPlatformProperties.java` — all read from project source tree
- `V24__platform_config_pin.sql` — confirms pin column and audit table already exist
- `PlatformConfigServiceTest.java` — confirms existing test structure to extend
- `TestConfig.java` + `AdminLogin.java` — confirms IT test infrastructure pattern

### Secondary (MEDIUM confidence)
- Bean Validation 3.x behavior for `@Pattern` on null — documented behavior: null is valid (constraint skipped), empty string is tested against regex

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already on classpath and in use
- Architecture: HIGH — all patterns have direct precedents in existing codebase (webhook-secret reveal, DTO validation, event publishing)
- Pitfalls: HIGH — derived from direct code analysis, not speculation

**Research date:** 2026-04-18
**Valid until:** Stable — no external dependencies; valid until codebase changes
