---
phase: 42-pin-backend-api
plan: "01"
subsystem: platform-config
tags: [pin, encryption, dto, bean, foundation]
dependency_graph:
  requires: [41-pin-schema-encryption-config]
  provides: [PinDto, PlatformConfigDto-4arg, updatePin, pinCryptopher-bean, test-pin-secret]
  affects: [platform-config-service, platform-config-entity, platform-config-spring-config]
tech_stack:
  added: []
  patterns: [JsonInclude-NON_NULL-on-record, Pattern-errorKey-pipe-fallback, Cryptopher-bean-factory]
key_files:
  created:
    - src/main/java/com/softropic/payam/platform/contract/PinDto.java
  modified:
    - src/test/resources/application.properties
    - src/main/java/com/softropic/payam/platform/contract/PlatformConfigDto.java
    - src/main/java/com/softropic/payam/platform/repo/PlatformConfig.java
    - src/main/java/com/softropic/payam/platform/config/PlatformConfig.java
    - src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java
    - src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java
decisions:
  - regex ^$|^[a-zA-Z0-9]{4,8}$ to allow empty string (PIN-08 keep-existing) while enforcing 4-8 alphanumeric chars (PIN-03)
  - "@JsonInclude(NON_NULL) at record class level suppresses null pin from all GET JSON responses (PIN-04)"
  - pin always passed as null from service to avoid ciphertext leakage into API responses
  - pinCryptopher bean name derived from method name — Plan 02 autowires by type via @RequiredArgsConstructor
metrics:
  duration: "~13 minutes"
  completed: "2026-04-18T04:43:16Z"
  tasks_completed: 3
  files_changed: 6
  files_created: 1
---

# Phase 42 Plan 01: PIN Type + Config + Test Infrastructure Foundation Summary

PIN encryption type and configuration foundation: PinDto record, 4-arg PlatformConfigDto with NON_NULL JSON suppression, pinCryptopher Spring bean, updatePin entity mutator, and test-property wiring so every IT context starts cleanly.

## Objective Achieved

Laid the type, configuration, and test-infrastructure foundation that Plan 02 needs to wire PIN encryption, validation, and reveal endpoints. Zero API behavior changes in this plan — only contracts and wiring.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Add pin-encryption-secret to test props + create PinDto record | 9bba53d | application.properties, PinDto.java |
| 2 | Extend PlatformConfigDto, add updatePin to entity, register pinCryptopher @Bean | 85019b5 | PlatformConfigDto.java, PlatformConfig.java (entity), PlatformConfig.java (config) |
| 3 | Update PlatformConfigService DTO sites to 4-arg + extend test assertions | a7b6210 | PlatformConfigService.java, PlatformConfigServiceTest.java |

## Files Created

- `src/main/java/com/softropic/payam/platform/contract/PinDto.java` — Reveal-endpoint response DTO carrying decrypted plaintext PIN. Mirrors WebhookSecretDto pattern. Intentionally separate from PlatformConfigDto (PIN-04 — standard config DTO must never expose the PIN value).

## Files Modified

- `src/test/resources/application.properties` — Appended `payam.platform.pin-encryption-secret=test-pin-secret-for-tests`. Without this, Cryptopher constructor throws EncryptionException(MISSING_SECRET) causing BeanCreationException on every IT context startup.
- `src/main/java/com/softropic/payam/platform/contract/PlatformConfigDto.java` — Extended from 2-component to 4-component record: (provider, platformMsisdn, pinConfigured, pin). Class-level `@JsonInclude(NON_NULL)` suppresses null pin. `@Pattern` validates 4-8 alphanumeric chars or empty string.
- `src/main/java/com/softropic/payam/platform/repo/PlatformConfig.java` — Added `updatePin(String ciphertext)` mutator immediately after `updateMsisdn`. JPA dirty-checking persists the ciphertext on commit. Phase 41 pin field and @Column annotation unchanged.
- `src/main/java/com/softropic/payam/platform/config/PlatformConfig.java` — Added `@Bean Cryptopher pinCryptopher(PayamPlatformProperties props)` factory returning `new Cryptopher(props.getPinEncryptionSecret())`. Fail-fast: blank secret throws EncryptionException at context startup.
- `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java` — All 4 PlatformConfigDto construction sites updated from 2-arg to 4-arg. pin argument is always null (never config.getPin() — no ciphertext leakage). Method signatures unchanged (Plan 02 widens update() to 3 params).
- `src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java` — Three happy-path tests extended with `pinConfigured()` (false, since test builders don't set pin) and `pin()` (null) assertions. Throws-test unmodified.

## Decisions Made

| Decision | Rationale |
|----------|-----------|
| regex `^$|^[a-zA-Z0-9]{4,8}$` | Two anchored alternations: empty string allows PIN-08 "keep existing" semantics; second branch enforces PIN-03 4-8 alphanumeric constraint |
| `@JsonInclude(NON_NULL)` at record class level | Suppresses null pin from all GET JSON responses — any component that is null is omitted. PIN-04: standard config DTO must never expose PIN value |
| pin=null as fourth arg at all service DTO sites | Never pass `config.getPin()` or `c.getPin()` — that would leak AES ciphertext into API responses. Pitfall 3 from research |
| pinCryptopher bean name from method name | Spring derives bean name from method name; Plan 02 uses @RequiredArgsConstructor to inject by type into PlatformConfigService |
| No Cryptopher field in PlatformConfigService yet | Plan 02 adds the cryptopher field, findPinByProvider, and update() pin handling — adding it now would require not-yet-existing test collaborators |

## Plan 02 Dependencies Satisfied

- PinDto type available in `platform.contract` for the reveal endpoint response
- PlatformConfigDto.pin() accessor available for Plan 02's update() to read the inbound PIN value
- PlatformConfig.updatePin() available for the service to persist encrypted PINs via JPA dirty-checking
- pinCryptopher bean registered — Plan 02's PlatformConfigService can inject it via @RequiredArgsConstructor
- Test IT contexts will start cleanly — no BeanCreationException from blank secret

## Requirements Satisfied

- PIN-03: @Pattern validation on PlatformConfigDto.pin enforces 4-8 alphanumeric chars or empty
- PIN-04: @JsonInclude(NON_NULL) + pin=null on all read paths ensures PIN never appears in GET responses
- PIN-05: pinCryptopher @Bean wired from PayamPlatformProperties.getPinEncryptionSecret()

## Verification Results

- `mvn -o compile -q` exits 0
- `mvn -o test-compile -q` exits 0
- `mvn -o test -Dtest=PlatformConfigServiceTest,PayamPlatformPropertiesTest` exits 0 (4 tests pass)
- All 4 PlatformConfigDto construction sites use 4-arg form with null as 4th arg
- No 2-arg PlatformConfigDto constructions remain

## Deviations from Plan

None — plan executed exactly as written. Tasks 2 and 3 were committed separately (one commit per task) as required.

## Known Stubs

None — this plan adds infrastructure/types only; no data-wiring stubs.

## Self-Check: PASSED
