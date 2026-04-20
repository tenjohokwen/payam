# Phase 45: PIN Add-Provider Fix — Research

**Researched:** 2026-04-20
**Domain:** Spring Boot service layer (upsert branch) + Vue 3/Quasar frontend (Add Provider dialog UX)
**Confidence:** HIGH — the gap is precisely identified in the milestone audit; all relevant source files read directly.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PIN-09 | Admin sees an optional PIN input field in the Add Provider dialog — same masked Quasar toggle pattern; no auto-mask timer (admin just entered the value) | Visual spec already satisfied by Phase 43. This phase closes the **functional** gap: PIN entered in the dialog must be encrypted and persisted atomically with the new PlatformConfig row, and the UI must reflect `pinConfigured` state after creation. |
</phase_requirements>

---

## Summary

Phase 45 is a targeted gap-closure for GAP-01 identified in the v8 milestone audit. The gap is narrow and precise: the `orElseGet` branch in `PlatformConfigService.update()` constructs a new `PlatformConfig` entity without calling `updatePin()`, silently discarding any PIN the admin typed in the Add Provider dialog. The admin sees `pinConfigured: false` in the response with no guidance that the PIN was dropped.

The fix has two parts. The **backend fix** is the primary correction: extend the `orElseGet` branch to accept the PIN parameter, encrypt it via `pinCryptopher`, and call `entity.updatePin(ciphertext)` before saving, when the PIN is non-blank. This makes the first-creation path symmetric with the update path. The **frontend fix** is a UX improvement: after `addProvider()` resolves, the `configs` array already receives the updated DTO (which now correctly returns `pinConfigured: true` when a PIN was set), so the provider card will reflect the correct state. The dialog currently shows a generic success notify — no additional change is needed for the card state, but the success message could be enriched to state whether a PIN was saved.

The change surface is minimal: one method in `PlatformConfigService`, one new unit-test case in `PlatformConfigServiceTest`, and one new integration-test case in `PlatformConfigAdminResourceIT`. The frontend `addProvider()` function in `PlatformConfigPage.vue` already passes the PIN correctly via `updatePlatformConfigFull(provider, msisdn, pin || undefined)` — no frontend change to the API call is needed. Only the success notification wording may be improved.

**Primary recommendation:** Fix the `orElseGet` branch in `PlatformConfigService.update()` to persist the PIN on first creation; add one unit test and one IT test; optionally improve the dialog success notification to mention PIN status.

---

## Standard Stack

No new dependencies. The fix operates entirely within the existing project stack.

### Core (already present)
| Component | Version | Purpose | Why Standard |
|-----------|---------|---------|--------------|
| `PlatformConfigService` | — | Service layer upsert logic | The single authoritative place for PlatformConfig mutation |
| `Cryptopher` (Jasypt/AES256) | — | PIN encryption | Already wired as `pinCryptopher` @Bean; same call used in the `map` branch |
| `PlatformConfig` entity | — | JPA entity with `updatePin(ciphertext)` | Existing mutation method; the `orElseGet` branch simply does not call it today |
| `PlatformConfigDto` | — | Request/response record | `@JsonInclude(NON_NULL)` + `@Pattern` validation already correct |
| `PlatformConfigAdminResourceIT` | — | Integration test class | Existing test harness with admin auth, DB seed/teardown; new test belongs here |
| `PlatformConfigServiceTest` | — | Unit test class (Mockito) | Existing mock-based test; new case for `orElseGet`+PIN belongs here |
| Vue 3 + Quasar (`PlatformConfigPage.vue`) | — | Frontend dialog | `addProvider()` already sends PIN correctly; only notification wording optional |

---

## Architecture Patterns

### The Gap: `orElseGet` Branch Does Not Call `updatePin`

The `update()` method uses `Optional.map(…).orElseGet(…)`. The `map` branch (existing row) already:
1. Calls `config.updateMsisdn(newMsisdn)`
2. Checks `StringUtils.isNotBlank(pin)` and, if true, calls `pinCryptopher.encrypt(pin)` then `config.updatePin(ciphertext)`
3. Saves via `platformConfigRepository.save(config)`

The `orElseGet` branch (new row) today:
1. Builds `PlatformConfig` via builder with `provider`, `platformMsisdn`, `status` — **no `pin`**
2. Calls `platformConfigRepository.save(newConfig)`
3. Returns `new PlatformConfigDto(upper, newMsisdn, false, null)` — `pinConfigured` hardcoded `false`

### Fix Pattern: Mirror the `map` Branch

```java
// Source: PlatformConfigService.java orElseGet branch — corrected pattern
.orElseGet(() -> {
    PlatformConfig newConfig = PlatformConfig.builder()
            .provider(upper)
            .platformMsisdn(newMsisdn)
            .status(com.softropic.payam.common.persistence.EntityStatus.ACTIVE)
            .build();
    if (StringUtils.isNotBlank(pin)) {
        String ciphertext = pinCryptopher.encrypt(pin);
        newConfig.updatePin(ciphertext);
    }
    platformConfigRepository.save(newConfig);
    // PIN-10: first-time row creation does not publish an event (existing rule unchanged)
    log.info("Platform config created", kv("provider", upper), kv("event", "platform_config_created"));
    return new PlatformConfigDto(upper, newMsisdn, newConfig.getPin() != null, null);
});
```

Key points:
- `pinCryptopher.encrypt(pin)` is the **same call** used in the `map` branch — no new import needed
- `newConfig.getPin() != null` correctly computes `pinConfigured` instead of the hardcoded `false`
- The Javadoc on `update()` must be updated to remove the false statement that "PIN cannot be set on initial creation via this code path"
- The event-suppression rule for new-row creation (PIN-10: no event on first-time creation) remains unchanged — the `orElseGet` branch still does not publish an event

### Frontend: No Functional Change Required

`addProvider()` in `PlatformConfigPage.vue` (line 237–269) already:
- Reads `newProvider.value.pin` and passes `pin || undefined` to `updatePlatformConfigFull()`
- Receives the `updated` DTO from the API response and pushes it to `configs.value`
- Initialises `pinValues.value[provider] = ''` (correct — field starts empty)

Once the backend fix lands, `updated.pinConfigured` will be `true` when a PIN was supplied, so the provider card will correctly display `pinConfigured: true` state without any Vue change.

**Optional UX improvement:** The success notify currently reads `${provider} configuration added`. It could be enriched to `${provider} configuration added${updated.pinConfigured ? ' (PIN set)' : ''}` to give the admin explicit confirmation without any structural change to the dialog.

### Anti-Patterns to Avoid

- **Do not add a new endpoint or API change.** The existing `PUT /v1/admin/platform-config/{provider}` handles both create and update — the fix is purely in the service layer branch logic.
- **Do not remove the event suppression rule for new-row creation.** PIN-10 explicitly states that first-time row creation does not fire `PlatformConfigChangedEvent`. The fix persists the PIN but does not change event semantics.
- **Do not change the `PlatformConfigDto` or `@Pattern` validation.** The regex `^$|^[a-zA-Z0-9]{4,8}$` is already correct and validated before the service is called.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| PIN encryption | Custom cipher | `pinCryptopher.encrypt(pin)` — existing `@Bean` | Same `Cryptopher` already used in the `map` branch; consistent at-rest encryption |
| `pinConfigured` flag | Manual boolean logic | `newConfig.getPin() != null` | Already the pattern used in `findAll()`, `findByProvider()`, and `map` branch |
| Test admin auth setup | New test infrastructure | Existing `seedAdminAndSecrets()` + `AdminLogin.loginAsAdmin()` in `PlatformConfigAdminResourceIT` | Copy the existing `@BeforeEach` pattern; no new test setup needed |

---

## Common Pitfalls

### Pitfall 1: Hardcoded `false` for `pinConfigured` in Return DTO
**What goes wrong:** The current `orElseGet` returns `new PlatformConfigDto(upper, newMsisdn, false, null)` — if the fix adds the PIN persistence but forgets to update the `false` literal, the response still claims `pinConfigured: false` even though the DB now has a PIN.
**How to avoid:** Compute `pinConfigured` from `newConfig.getPin() != null` — same as all other DTO construction sites in the service.

### Pitfall 2: Calling `updatePin` Before `save`
**What goes wrong:** The JPA entity is transient when built via `builder()`. `updatePin()` mutates the in-memory object; `save()` persists it. If the order is reversed (save then updatePin), the PIN update is never flushed.
**How to avoid:** Call `updatePin(ciphertext)` before `platformConfigRepository.save(newConfig)`, mirroring the `map` branch.

### Pitfall 3: Empty String PIN Triggers Encryption
**What goes wrong:** The frontend sends `undefined` for empty PIN (via `pin || undefined`) so the JSON body omits the `pin` field — Jackson deserialises it as `null`. But if the test or a direct API call sends `""`, `StringUtils.isNotBlank("")` returns `false`, correctly skipping encryption. No bug here, but tests must use `null` or `""` explicitly to confirm the no-PIN path.
**How to avoid:** The existing `StringUtils.isNotBlank(pin)` guard is correct; write the new test with both a PIN-provided case and a PIN-absent (null) case.

### Pitfall 4: Event Suppression Contract Must Not Change
**What goes wrong:** Adding PIN persistence to the `orElseGet` branch might tempt adding event publishing for "first creation with PIN" — but PIN-10 explicitly suppresses events on new-row creation entirely.
**How to avoid:** Do not add event publishing in `orElseGet`. The existing `// PIN-10: first-time row creation does not publish an event` comment is the authoritative rule; preserve it.

### Pitfall 5: Frontend Dialog Reset
**What goes wrong:** After `addProvider()` succeeds, the dialog resets `newProvider.value = { name: '', msisdn: '', pin: '' }` and `dialogPinVisible.value = false` (lines 258–259). This is already correct. If a developer adds PIN-state tracking to the dialog (e.g., countdown timer), they must also clean it up here.
**How to avoid:** Do not add a countdown timer to the dialog — PIN-09 explicitly states "no auto-mask timer" in the dialog. The existing simple `dialogPinVisible` boolean toggle is the correct pattern.

---

## Code Examples

### Backend Fix — `orElseGet` Branch (corrected)
```java
// Source: PlatformConfigService.java — orElseGet branch with PIN support
.orElseGet(() -> {
    PlatformConfig newConfig = PlatformConfig.builder()
            .provider(upper)
            .platformMsisdn(newMsisdn)
            .status(com.softropic.payam.common.persistence.EntityStatus.ACTIVE)
            .build();
    if (StringUtils.isNotBlank(pin)) {
        String ciphertext = pinCryptopher.encrypt(pin);
        newConfig.updatePin(ciphertext);
    }
    platformConfigRepository.save(newConfig);
    log.info("Platform config created", kv("provider", upper), kv("event", "platform_config_created"));
    return new PlatformConfigDto(upper, newMsisdn, newConfig.getPin() != null, null);
});
```

### New Unit Test Case — `PlatformConfigServiceTest`
```java
// Source: pattern from update_shouldCreateNewConfigIfNotFound
@Test
void update_shouldEncryptAndPersistPinOnNewRowCreation() {
    // Given — no existing row; PIN provided
    String provider = "MTN";
    String plainPin = "abcd";
    String ciphertext = "ENC(abcd)";
    when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.empty());
    when(pinCryptopher.encrypt(plainPin)).thenReturn(ciphertext);

    // When
    PlatformConfigDto result = platformConfigService.update(provider, "987654", plainPin);

    // Then
    assertThat(result.pinConfigured()).isTrue();
    assertThat(result.pin()).isNull();   // PIN-04: never returned in DTO
    verify(pinCryptopher).encrypt(plainPin);
    ArgumentCaptor<PlatformConfig> captor = ArgumentCaptor.forClass(PlatformConfig.class);
    verify(platformConfigRepository).save(captor.capture());
    assertThat(captor.getValue().getPin()).isEqualTo(ciphertext);
    verifyNoInteractions(eventPublisher);   // PIN-10: no event on new-row creation
}

@Test
void update_shouldCreateNewRowWithNoPinWhenPinIsBlank() {
    // Given
    when(platformConfigRepository.findByProvider("MTN")).thenReturn(Optional.empty());

    // When
    PlatformConfigDto result = platformConfigService.update("MTN", "987654", null);

    // Then
    assertThat(result.pinConfigured()).isFalse();
    verify(pinCryptopher, never()).encrypt(any());
}
```

### New Integration Test Case — `PlatformConfigAdminResourceIT`
```java
// New test: first-creation with PIN — pinConfigured=true in response (GAP-01 closure)
@Test
void putConfig_shouldPersistPinOnFirstCreation() {
    // The V17 seed inserts ORANGE and MTN rows, so true "new row" creation cannot be tested
    // against the seeded providers. The cleanDb() @AfterEach resets rows but does NOT delete them.
    // Instead, test a provider that does not exist in the seed — OR use the map branch to set PIN
    // on the already-existing ORANGE row (which starts with null pin from cleanDb reset).
    // Phase 42 tests already cover the "existing row, null pin -> first-time PIN" path in the map branch.
    // Phase 45 closure: prove that the orElseGet branch (when triggered) also persists PIN.
    // Since V17 seeds both ORANGE and MTN, we verify orElseGet indirectly by using a provider
    // not in the seed (e.g. "TESTPROVIDER") — OR we simply add a test using the existing ORANGE
    // row started fresh (null pin from cleanDb). The latter covers the real failure scenario.

    // Given — ORANGE row exists (V17 seed) with null pin (from cleanDb); this takes the map branch
    // but that is the path the Add Provider dialog actually calls for pre-seeded providers.
    // The orElseGet branch is reached when an admin manually adds a never-before-seen provider.
    // For completeness: verify that PUT with PIN on an existing null-pin row returns pinConfigured=true.
    // (The existing test putConfig_shouldReturn200AndAcceptValidPin already covers this.)
    //
    // For the true orElseGet path test — delete both seed rows and call PUT:
    transactionTemplate.execute(status -> {
        jdbcTemplate.execute("DELETE FROM main.platform_config");
        return 0;
    });

    // When — PUT with PIN for a provider that has no row
    ResponseEntity<Map> response = putConfig("ORANGE", "654321", "abcd");

    // Then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("pinConfigured", true);
    assertThat(response.getBody()).doesNotContainKey("pin");

    // And the PIN is actually stored — reveal it
    ResponseEntity<PinDto> pinResp = restTemplate.exchange(
            "/v1/admin/platform-config/ORANGE/pin",
            HttpMethod.GET,
            new HttpEntity<>(adminCookies),
            PinDto.class);
    assertThat(pinResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(pinResp.getBody().pin()).isEqualTo("abcd");
}

@Test
void putConfig_shouldCreateRowWithNoPinWhenPinFieldAbsent_orElseGetBranch() {
    // Given — delete all rows to force orElseGet
    transactionTemplate.execute(status -> {
        jdbcTemplate.execute("DELETE FROM main.platform_config");
        return 0;
    });

    // When — PUT with no pin (null body field)
    ResponseEntity<Map> response = putConfig("ORANGE", "654321", null);

    // Then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("pinConfigured", false);
}
```

### Optional Frontend Notification Enhancement — `PlatformConfigPage.vue`
```javascript
// In addProvider() — replace the success notify (line 261) with:
const pinMsg = updated.pinConfigured ? ' with PIN set' : ''
$q.notify({ type: 'positive', message: `${provider} configuration added${pinMsg}` })
```

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito (unit); Spring Boot Test + Testcontainers (IT) |
| Config file | `src/test/resources/` (inherits from project; IT uses `@ActiveProfiles("dev")`) |
| Quick run command | `mvn test -pl . -Dtest=PlatformConfigServiceTest -q` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PIN-09 | orElseGet branch persists PIN when non-blank | unit | `mvn test -Dtest=PlatformConfigServiceTest#update_shouldEncryptAndPersistPinOnNewRowCreation` | New — Wave 0 |
| PIN-09 | orElseGet branch creates row with no PIN when PIN blank | unit | `mvn test -Dtest=PlatformConfigServiceTest#update_shouldCreateNewRowWithNoPinWhenPinIsBlank` | New — Wave 0 |
| PIN-09 | E2E: PUT with PIN to new row returns pinConfigured=true; reveal confirms plaintext | IT | `mvn verify -Dtest=PlatformConfigAdminResourceIT` | New case — Wave 0 |
| PIN-09 | E2E: PUT with no PIN to new row returns pinConfigured=false | IT | `mvn verify -Dtest=PlatformConfigAdminResourceIT` | New case — Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=PlatformConfigServiceTest -q`
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full `mvn verify` green before verification

### Wave 0 Gaps
- [ ] New test method `update_shouldEncryptAndPersistPinOnNewRowCreation` in `PlatformConfigServiceTest.java`
- [ ] New test method `update_shouldCreateNewRowWithNoPinWhenPinIsBlank` in `PlatformConfigServiceTest.java`
- [ ] New test method `putConfig_shouldPersistPinOnFirstCreation` in `PlatformConfigAdminResourceIT.java`
- [ ] New test method `putConfig_shouldCreateRowWithNoPinWhenPinFieldAbsent_orElseGetBranch` in `PlatformConfigAdminResourceIT.java`

---

## Environment Availability

Step 2.6: SKIPPED — Phase 45 is a pure code and test change with no new external dependencies. All dependencies (PostgreSQL via Testcontainers, Spring Boot, Maven, Cryptopher/Jasypt) are already operational from previous phases.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `orElseGet` ignores `pin` parameter | `orElseGet` encrypts and persists `pin` when non-blank | Phase 45 | `pinConfigured: true` returned on first-creation with PIN; GAP-01 closed |

---

## Open Questions

1. **Should `DELETE FROM main.platform_config` in the IT test risk interfering with other ITs?**
   - What we know: `cleanDb()` in `@AfterEach` resets rows via `UPDATE … SET … = NULL` and deletes user/authority rows but does NOT delete the provider rows. The new IT test needs to delete provider rows to reach the `orElseGet` branch.
   - What's unclear: Whether any other IT in the same class relies on the provider rows existing in `@BeforeEach`.
   - Recommendation: Scope the `DELETE FROM main.platform_config` to a `try`/`finally` within the test body, and confirm that `cleanDb()` re-inserts via `UPDATE` (which fails harmlessly on zero rows) or augment `@BeforeEach` to re-seed if rows are missing. Alternatively, use a provider key that V17 does not seed (e.g., `"FAKEPROV"`) to force `orElseGet` without touching existing rows.

2. **Javadoc on `update()` is currently misleading**
   - What we know: The current Javadoc states "PIN cannot be set on initial creation via this code path; the orElseGet branch ignores the pin parameter — first-time PIN provisioning happens on a subsequent update once the row exists."
   - After the fix this is factually incorrect and should be removed/updated.
   - Recommendation: Update the Javadoc to reflect that the `orElseGet` branch now accepts and persists a PIN when one is provided.

---

## Sources

### Primary (HIGH confidence)
- `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java` — direct read of current `orElseGet` branch
- `src/frontend/src/pages/admin/PlatformConfigPage.vue` — direct read of `addProvider()` and dialog state
- `.planning/v8-MILESTONE-AUDIT.md` — GAP-01 precise diagnosis with file locations and fix options
- `src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java` — existing IT harness patterns
- `src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java` — existing unit test patterns
- `src/main/java/com/softropic/payam/platform/repo/PlatformConfig.java` — `updatePin()` mutation method
- `src/main/java/com/softropic/payam/platform/contract/PlatformConfigDto.java` — DTO shape and `@Pattern` validation
- `src/frontend/src/api/admin.api.js` — `updatePlatformConfigFull()` correctly omits empty PIN

### Secondary (MEDIUM confidence)
- None needed — the gap is fully characterised by direct code inspection.

---

## Metadata

**Confidence breakdown:**
- Gap diagnosis: HIGH — read directly from audit file and confirmed against source code
- Fix pattern: HIGH — the `map` branch is the direct template; the fix is symmetric
- Test patterns: HIGH — both test classes read directly; new cases follow established conventions
- Frontend impact: HIGH — `addProvider()` already passes PIN correctly; only notification wording is optional

**Research date:** 2026-04-20
**Valid until:** 2026-05-20 (stable — no fast-moving external dependencies)
