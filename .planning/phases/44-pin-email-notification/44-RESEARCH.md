# Phase 44: PIN Email Notification — Research

**Researched:** 2026-04-18
**Domain:** Spring application events, transactional email notification, Thymeleaf templates
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PIN-10 | `PlatformConfigChangedEvent` carries `msisdnChanged` (boolean), `pinChanged` (boolean), and `changedBy` (String) — event fires only when MSISDN changed OR PIN changed; first-time PIN creation (was null) does not fire an event | Event record already exists as `PlatformConfigChangedEvent`; must be widened from 3-param to 5-param record. Service `update()` method already computes `oldMsisdn` and knows whether `pin` arg is non-blank and whether `config.getPin()` was null before the update — all diff logic already present in `PlatformConfigService.update()`. |
| PIN-11 | Email states provider name, which field(s) changed (MSISDN / PIN / both), admin username who made the change, and timestamp — PIN value never in email | `PlatformConfigEmailListener.onConfigChanged()` already passes a `data` map to the Thymeleaf `PLATFORM_CONFIG_CHANGED` template. Template `platformConfigChanged.html` extends (not replaces) existing template. `changedBy` is resolved via `SecurityUtil.getCurrentUserName()`, which reads `UserDetails.getUsername()` from `SecurityContextHolder` — this is populated for every admin JWT request. |

</phase_requirements>

---

## Summary

Phase 44 is a targeted enrichment of infrastructure that already exists. `PlatformConfigChangedEvent` is a Java record with three components (`provider`, `oldMsisdn`, `newMsisdn`). This phase widens it to five components, adding `msisdnChanged` (boolean), `pinChanged` (boolean), and `changedBy` (String). The caller, `PlatformConfigService.update()`, already has all state needed to compute those values — it holds `oldMsisdn`, the incoming `pin` argument, and the entity's current `pin` before the update.

The listener (`PlatformConfigEmailListener`) already dispatches the event into the `MailManager` pipeline via `publisher.publishEvent(envelope)`. `MailManager.sendEmailFromTemplate()` is annotated `@TransactionalEventListener(phase = AFTER_COMMIT)` — so post-commit delivery is already guaranteed by the existing infrastructure. No architectural change is required: only the event record, the service publishing site, the listener data map, and the Thymeleaf HTML template need touching.

The fire-suppression rule ("not fired when a PIN is set for the first time, was null before") means the service must snapshot `config.getPin()` **before** calling `config.updatePin()` and must suppress the event entirely when neither MSISDN nor PIN changed. The existing code fires the event unconditionally on every `update()` call for an existing row — that must become conditional.

**Primary recommendation:** Widen the event record, add conditional publish logic in `PlatformConfigService.update()`, update the listener data map, and extend the Thymeleaf template. Single plan is sufficient; no new beans or infrastructure is needed.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Framework (application events) | already on classpath | `ApplicationEventPublisher.publishEvent()` + `@EventListener` | Project-wide pattern; used in all notification listeners |
| Thymeleaf | already on classpath | HTML email template rendering | Used for all 15+ existing email templates in this project |
| Spring Security `SecurityContextHolder` | already on classpath | Resolve admin username via `SecurityUtil.getCurrentUserName()` | Existing utility bean; same pattern used throughout service layer |

### No New Dependencies
All required capabilities are already in the classpath. Phase 44 introduces no new library dependencies.

---

## Architecture Patterns

### Recommended Project Structure

No new files or packages are needed. Changes are confined to:

```
src/main/java/.../platform/contract/event/PlatformConfigChangedEvent.java  ← widen record
src/main/java/.../platform/service/PlatformConfigService.java               ← conditional publish
src/main/java/.../email/infrastructure/listener/PlatformConfigEmailListener.java ← extend data map
src/main/resources/mails/platformConfigChanged.html                         ← extend template
src/main/resources/i18n/messages_en.properties                              ← no new keys needed
```

### Pattern 1: Widening a Java Record Event

**What:** Java records are immutable; widening means replacing the existing `record` with a new signature. All construction sites must be updated.

**Current signature:**
```java
// Source: src/main/java/.../platform/contract/event/PlatformConfigChangedEvent.java
public record PlatformConfigChangedEvent(String provider, String oldMsisdn, String newMsisdn) {}
```

**New signature:**
```java
public record PlatformConfigChangedEvent(
    String provider,
    String oldMsisdn,
    String newMsisdn,
    boolean msisdnChanged,
    boolean pinChanged,
    String changedBy
) {}
```

There are exactly **two construction sites** in `PlatformConfigService.update()` — one for the existing-row `.map()` branch and one for the new-row `.orElseGet()` branch. The `orElseGet` branch creates a brand-new config row and currently fires the event unconditionally; per PIN-10 this branch must NOT fire the event at all (because it is the first-time creation path and neither MSISDN nor PIN "changed").

### Pattern 2: Conditional Event Publishing in PlatformConfigService.update()

**What:** The existing `.map()` branch fires the event unconditionally. The new logic must:
1. Snapshot `oldPin = config.getPin()` before any mutation.
2. Compute `msisdnChanged = !Objects.equals(oldMsisdn, newMsisdn)`.
3. Compute `pinChanged = StringUtils.isNotBlank(pin) && oldPin != null`.
   - `StringUtils.isNotBlank(pin)` — the pin arg is non-blank (admin sent a new PIN value).
   - `oldPin != null` — a PIN was already stored (not first-time creation).
   - When `oldPin == null` and `pin` is non-blank it is first-time PIN creation — per PIN-10, this does NOT set `pinChanged = true`.
4. Suppress the event entirely when `!msisdnChanged && !pinChanged`.
5. Obtain `changedBy` via `SecurityUtil.getCurrentUserName()` — injected into the service.

**Example for `.map()` branch:**
```java
String oldMsisdn = config.getPlatformMsisdn();
String oldPin    = config.getPin();              // snapshot BEFORE mutation

config.updateMsisdn(newMsisdn);
if (StringUtils.isNotBlank(pin)) {
    config.updatePin(pinCryptopher.encrypt(pin));
}
platformConfigRepository.save(config);

boolean msisdnChanged = !Objects.equals(oldMsisdn, newMsisdn);
boolean pinChanged    = StringUtils.isNotBlank(pin) && oldPin != null;

if (msisdnChanged || pinChanged) {
    String changedBy = securityUtil.getCurrentUserName();
    eventPublisher.publishEvent(new PlatformConfigChangedEvent(
        upper, oldMsisdn, newMsisdn, msisdnChanged, pinChanged, changedBy));
}
```

The `orElseGet` (first-time row creation) branch: remove the `eventPublisher.publishEvent(...)` call entirely — that path creates a new row with whatever MSISDN is given, which is not a "change" in the audit sense.

### Pattern 3: Listener Data Map Extension

**What:** `PlatformConfigEmailListener.onConfigChanged()` currently puts `provider`, `oldMsisdn`, and `newMsisdn` into the Thymeleaf data map. Extend it with the new event fields.

```java
data.put("provider",       event.provider());
data.put("oldMsisdn",      event.oldMsisdn() != null ? event.oldMsisdn() : "");
data.put("newMsisdn",      event.newMsisdn());
data.put("msisdnChanged",  event.msisdnChanged());
data.put("pinChanged",     event.pinChanged());
data.put("changedBy",      event.changedBy() != null ? event.changedBy() : "unknown");
data.put("changedAt",      Instant.now(ClockProvider.getClock()).toString());
```

The `changedAt` timestamp is already available via `ClockProvider.getClock()` — same pattern used in `TenantLifecycleEmailListener`.

### Pattern 4: Thymeleaf Template Extension

**What:** The existing `platformConfigChanged.html` shows MSISDN old/new. Phase 44 extends it (does NOT replace) per STATE.md. Required content: provider name, which field(s) changed, admin username, timestamp. PIN value must never appear.

```html
<!-- Source: src/main/resources/mails/platformConfigChanged.html (extended) -->
<p>Hello,</p>

<p>Platform configuration for provider <strong th:text="${map.provider}">PROVIDER</strong>
has been updated.</p>

<table>
    <tr th:if="${map.msisdnChanged}">
        <td><strong>MSISDN changed:</strong></td>
        <td><span th:text="${map.oldMsisdn}">—</span> → <span th:text="${map.newMsisdn}">—</span></td>
    </tr>
    <tr th:if="${map.pinChanged}">
        <td><strong>PIN changed:</strong></td>
        <td>PIN has been updated (value not shown for security)</td>
    </tr>
    <tr>
        <td><strong>Changed by:</strong></td>
        <td th:text="${map.changedBy}">—</td>
    </tr>
    <tr>
        <td><strong>Timestamp:</strong></td>
        <td th:text="${map.changedAt}">—</td>
    </tr>
</table>

<p>If this change was not authorized, please investigate immediately.</p>

<p th:text="#{email.closing}">Thanks, the Payam team</p>
```

### Anti-Patterns to Avoid

- **PIN value in the data map (even as ciphertext):** The `update()` method has access to the ciphertext stored in `config.getPin()`. Never put it in the event record or the data map. The PIN-changed signal is conveyed solely by the `pinChanged` boolean.
- **Publishing from `orElseGet` branch:** The first-time row creation path must not publish a changed event. PIN-10 explicitly excludes first-time PIN creation.
- **Resolving `changedBy` inside the listener:** The `SecurityContextHolder` is cleared before `AFTER_COMMIT` listeners fire in an async context. Resolve `changedBy` inside the service method (inside the transaction, on the request thread) and embed it in the event record.
- **Class-level `@Transactional` on PlatformConfigService and `@EventListener` interaction:** The existing `@EventListener` on `PlatformConfigEmailListener` is correct — `MailManager` handles `AFTER_COMMIT` via `@TransactionalEventListener`. Do not change the listener annotations.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Post-commit email delivery | Custom AFTER_COMMIT hook | Existing `MailManager.sendEmailFromTemplate()` with `@TransactionalEventListener(AFTER_COMMIT)` | Already implemented; handles retry, circuit breaker, envelope persistence |
| Admin username resolution | Manual JWT parsing | `SecurityUtil.getCurrentUserName()` | Already an injectable `@Component`; reads `UserDetails.getUsername()` from `SecurityContextHolder` on the request thread |
| Email rendering | String concatenation | Thymeleaf template with `${map.X}` expressions | Project-wide pattern for all 15+ notification emails |
| Email send failure isolation | Try/catch in listener | Existing circuit breaker + retry in `MailManager` | MailManager already wraps send in `CircuitBreaker` with `RetryTemplate` |

---

## Common Pitfalls

### Pitfall 1: Resolving changedBy in the Listener (Wrong Thread)
**What goes wrong:** `SecurityContextHolder` is thread-local. The `MailManager` listener runs in the `@Async("sendMailPool")` thread pool after commit. `SecurityContextHolder.getContext()` returns an empty context on that thread, giving `null` for the username.
**Why it happens:** `@TransactionalEventListener` + `@Async` combination runs outside the original request thread.
**How to avoid:** Resolve `securityUtil.getCurrentUserName()` inside `PlatformConfigService.update()` — on the request thread, inside the transaction — and carry the result in the event record as `changedBy`.
**Warning signs:** `changedBy` is always `null` or "unknown" in every test run.

### Pitfall 2: Firing the Event When Neither Field Changed
**What goes wrong:** Admin calls PUT with the same MSISDN and an empty PIN. The event fires and an email is sent, confusing the admin.
**Why it happens:** The current code fires unconditionally on every `update()` call for an existing row.
**How to avoid:** Guard with `if (msisdnChanged || pinChanged)` before publishing.

### Pitfall 3: Treating First-Time PIN Creation as a PIN Change
**What goes wrong:** `pinChanged` is set to `true` when `oldPin == null` and a PIN is supplied, violating PIN-10.
**Why it happens:** Naively checking only `StringUtils.isNotBlank(pin)`.
**How to avoid:** `pinChanged = StringUtils.isNotBlank(pin) && oldPin != null`. Snapshot `oldPin` before calling `config.updatePin()`.

### Pitfall 4: Exposing PIN Ciphertext in the Event or Data Map
**What goes wrong:** Developer adds `config.getPin()` to the event for debugging, which flows into the email template.
**Why it happens:** Convenient during development; not noticed in code review.
**How to avoid:** Never pass PIN ciphertext or plaintext outside the service method. The event record carries only booleans for PIN-related state.

### Pitfall 5: `orElseGet` Branch Still Publishing
**What goes wrong:** A new provider is added via PUT; the admin receives a "MSISDN changed" notification even though no prior value existed.
**Why it happens:** Current code has `eventPublisher.publishEvent(...)` in the `orElseGet` branch.
**How to avoid:** Remove the `publishEvent` call from `orElseGet` entirely in this phase.

---

## Code Examples

### SecurityUtil Injection Pattern (existing, HIGH confidence)
```java
// Source: SecurityUtil is a @Component — inject via @RequiredArgsConstructor
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class PlatformConfigService {
    private final PlatformConfigRepository platformConfigRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Cryptopher pinCryptopher;
    private final SecurityUtil securityUtil;  // add this field
    ...
}
```

### ClockProvider for Timestamp in Listener (existing pattern, HIGH confidence)
```java
// Source: TenantLifecycleEmailListener.java — identical pattern
data.put("changedAt", Instant.now(ClockProvider.getClock()).toString());
```

---

## Runtime State Inventory

> This is not a rename/refactor phase. Omit.

---

## Environment Availability

> Step 2.6: SKIPPED — this phase is purely code changes within the existing Spring Boot project. No new external tools, services, CLIs, or runtimes are required.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + AssertJ (Maven Failsafe for ITs) |
| Config file | `pom.xml` (maven-failsafe-plugin; `*IT.java` pattern) |
| Quick run command | `mvn test -pl . -Dtest=PlatformConfigServiceTest` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| PIN-10 | Event carries `msisdnChanged`, `pinChanged`, `changedBy`; suppressed when no change; suppressed on first-time PIN creation | unit | `mvn test -Dtest=PlatformConfigServiceTest` | ✅ (extend existing `PlatformConfigServiceTest`) |
| PIN-10 | Event not fired when MSISDN unchanged and PIN empty | unit | same | ✅ extend |
| PIN-10 | Event not fired on first-time PIN creation (oldPin was null) | unit | same | ✅ extend |
| PIN-11 | Email data map contains `changedBy`, `changedAt`, `msisdnChanged`, `pinChanged`, no PIN value | unit | `mvn test -Dtest=PlatformConfigEmailListenerTest` | ❌ Wave 0 |
| PIN-11 | Email delivered after commit (not on rollback) | integration | `mvn verify -Dit.test=PlatformConfigAdminResourceIT` | ✅ extend existing IT |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=PlatformConfigServiceTest,PlatformConfigEmailListenerTest`
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/email/infrastructure/listener/PlatformConfigEmailListenerTest.java` — unit test for listener data map (PIN-11); does not currently exist (only `MailManagerIT` exists; no dedicated listener unit test)

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `PlatformConfigChangedEvent(provider, oldMsisdn, newMsisdn)` | Widen to add `msisdnChanged`, `pinChanged`, `changedBy` | Phase 44 | All construction sites in `PlatformConfigService` must be updated |
| Event fired unconditionally in `.map()` branch | Conditional fire: only when `msisdnChanged || pinChanged` | Phase 44 | Eliminates spurious notifications |
| `orElseGet` branch fires event | `orElseGet` branch does not fire event | Phase 44 | Correct per PIN-10 |

---

## Open Questions

1. **`changedBy` when running in a non-request context (e.g., tests)**
   - What we know: `SecurityUtil.getCurrentUserName()` returns `null` when `SecurityContextHolder` is empty (e.g., in a unit test where no authentication is set).
   - What's unclear: Whether integration tests with `AdminLogin.loginAsAdmin()` correctly populate `SecurityContextHolder` in the service layer during the PUT call.
   - Recommendation: Null-safe fallback in the service: `String changedBy = securityUtil.getCurrentUserName(); if (changedBy == null) changedBy = "unknown";`. The IT already uses real JWT login so `SecurityContextHolder` will be populated.

2. **Whether the `orElseGet` path should emit a different "created" event**
   - What we know: PIN-10 explicitly says the event is not fired when a PIN is set for the first time (was null). The roadmap says Phase 44 enriches the event, not that it adds new events.
   - What's unclear: Whether admins want a notification when a brand-new provider row is created.
   - Recommendation: Out of scope per PIN-10. Remove event publishing from `orElseGet` entirely.

---

## Sources

### Primary (HIGH confidence)
- Direct source code inspection: `PlatformConfigChangedEvent.java`, `PlatformConfigEmailListener.java`, `PlatformConfigService.java`, `PlatformConfigAdminResource.java`, `MailManager.java`, `SecurityUtil.java`, `TenantLifecycleEmailListener.java`, `platformConfigChanged.html`, `PlatformConfigAdminResourceIT.java`
- `REQUIREMENTS.md` — PIN-10 and PIN-11 specification
- `STATE.md` — confirmed: `platformConfigChanged.html` Thymeleaf template exists; Phase 44 extends it, does not replace it; `@EventListener` on `PlatformConfigEmailListener` is the correct pattern

### Secondary (MEDIUM confidence)
- Spring Framework docs (training data, confirmed by existing codebase patterns): `@TransactionalEventListener(AFTER_COMMIT)`, `SecurityContextHolder` thread-locality, Java record widening rules

### Tertiary (LOW confidence)
- None

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in use in this project; no new dependencies
- Architecture: HIGH — all patterns directly sourced from existing production code in this repo
- Pitfalls: HIGH — derived from direct reading of the existing `update()` method and known Spring Security threading model

**Research date:** 2026-04-18
**Valid until:** 2026-05-18 (stable domain; no external ecosystem dependency)
