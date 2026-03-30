# Phase 24: Platform Configuration - Research

**Researched:** 2026-03-30
**Domain:** Spring Boot configuration management, REST API, Vue/Quasar admin UI, Spring Mail
**Confidence:** HIGH (all findings verified directly in the codebase)

---

## Summary

Phase 24 introduces a `platform_config` table that stores the platform-owned MSISDNs for Orange
and MTN, exposes them via a new admin REST endpoint, and sends an email notification when either
value changes. None of this infrastructure exists today — MSISDNs are not currently stored in the
database or referenced by the provider adapters at runtime.

The admin UI is a Quasar/Vue 3 SPA (not Thymeleaf). All existing admin pages follow the same
pattern: a `@RestController` in `com.softropic.payam.admin.api`, a new `.vue` page under
`src/frontend/src/pages/admin/`, a new API function in `src/frontend/src/api/admin.api.js`, and a
new route in `src/frontend/src/router/routes.js`. The email infrastructure (MailManager, MailService,
Envelope, EmailTemplate) is fully operational; adding a new email type means adding one enum entry,
one Thymeleaf HTML template, and a subject key in `messages.properties`. The notification email
target address comes from an application-config property (`payam.platform.notification-email`) — it
is never stored in the database or editable via the UI.

**Primary recommendation:** Store MSISDNs in a single-row `platform_config` table (V17 migration),
expose them through a new `PlatformConfigAdminResource` at `/v1/admin/platform-config`, and send
the notification by publishing an `Envelope` event through the existing `MailManager` pipeline.

---

## Standard Stack

### Core
| Component | Version/Type | Purpose | Why Standard |
|-----------|-------------|---------|--------------|
| Spring Boot `@ConfigurationProperties` | Spring Boot 3.x | Bind `payam.platform.*` from YAML | Existing pattern in `OrangeMoneyConfig`, `MtnMoMoConfig` |
| Spring Data JPA + Hibernate | Existing | Persist `platform_config` entity | All data access uses JPA |
| Flyway migration | V17 | Create `platform_config` table | Highest current migration is V16 |
| `@RestController` + `@PreAuthorize(HAS_ADMIN_ROLE)` | Existing Spring MVC | Admin REST endpoint | Exact pattern used by all admin resources |
| `MailManager.sendEmailFromTemplate` | Existing | Send notification email | Full retry/CB/persistence pipeline already wired |
| Vue 3 + Quasar (SPA) | Existing | Admin UI page | All admin pages use this stack; no Thymeleaf in pages |

### Supporting
| Component | Version/Type | Purpose | When to Use |
|-----------|-------------|---------|-------------|
| `EmailTemplate` enum + Thymeleaf HTML template | Existing | New `PLATFORM_CONFIG_CHANGED` email type | Required for MailService to resolve the template |
| `@Value("${payam.platform.notification-email}")` | Spring | Inject notification address into listener/service | Address is config-only, not DB-backed |
| `ApplicationEventPublisher` | Spring | Decouple config change trigger from email dispatch | Same pattern as `AccountChangeEmailListener` |
| Lombok `@RequiredArgsConstructor` / `@Slf4j` | Existing | Constructor injection and logging | Used throughout the codebase |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| DB table for MSISDNs | YAML properties only | DB gives admin-editable persistence without restart; YAML would require app restart for changes |
| Spring `ApplicationEventPublisher` for email | Direct `MailManager` call in service | Event bus matches existing `AccountChangeEmailListener` pattern; service should not know about email |
| Single-row `platform_config` table | Two separate tables (one per provider) | Single table is simpler; one row per provider via `provider` column is the cleanest extension point |

---

## Architecture Patterns

### Recommended Project Structure

```
com.softropic.payam.platform/
├── api/
│   └── PlatformConfigAdminResource.java   # GET + PUT /v1/admin/platform-config
├── contract/
│   ├── PlatformConfigDto.java             # Request/response body
│   └── event/
│       └── PlatformConfigChangedEvent.java # Spring ApplicationEvent
├── repo/
│   ├── PlatformConfig.java                # JPA entity
│   └── PlatformConfigRepository.java      # Spring Data JPA
├── service/
│   └── PlatformConfigService.java         # Business logic + event publish
└── config/
    └── PayamPlatformProperties.java       # @ConfigurationProperties(prefix="payam.platform")

email/infrastructure/listener/
└── PlatformConfigEmailListener.java       # @EventListener, builds Envelope, publishes to MailManager

resources/
├── db/migration/V17__platform_config_schema.sql
├── mails/platformConfigChanged.html       # Thymeleaf template
└── i18n/messages.properties              # Add email.platform_config_changed.title key
```

### Pattern 1: Single-Row Configuration Table
**What:** One row per provider in `platform_config`, keyed by a `provider` VARCHAR column.
**When to use:** When there are exactly two providers (ORANGE, MTN) with independent MSISDNs.
**Example schema:**
```sql
-- V17__platform_config_schema.sql
CREATE TABLE IF NOT EXISTS main.platform_config (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    provider            VARCHAR(20)  NOT NULL UNIQUE,  -- 'ORANGE' | 'MTN'
    platform_msisdn     VARCHAR(20)  NOT NULL
);

INSERT INTO main.platform_config (id, version, provider, platform_msisdn, status)
VALUES
    (1, 0, 'ORANGE', '', 'ACTIVE'),
    (2, 0, 'MTN',    '', 'ACTIVE')
ON CONFLICT DO NOTHING;
```

### Pattern 2: Admin REST Endpoint (existing pattern)
**What:** `@RestController` with `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`, mapping to
`/v1/admin/platform-config`. GET returns both provider configs; PUT accepts a provider + new MSISDN.
**When to use:** All admin data endpoints follow this pattern. Security is enforced via
`@PreAuthorize` at controller level, matching `AdminTransactionResource`, `ProviderStatusResource`,
`AlertRuleAdminResource`, etc.

```java
// Source: existing AdminTransactionResource.java pattern
@RestController
@RequestMapping("/v1/admin/platform-config")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class PlatformConfigAdminResource {

    private final PlatformConfigService platformConfigService;

    @GetMapping
    public ResponseEntity<List<PlatformConfigDto>> getAll() {
        return ResponseEntity.ok(platformConfigService.findAll());
    }

    @PutMapping("/{provider}")
    public ResponseEntity<PlatformConfigDto> update(
            @PathVariable String provider,
            @RequestBody @Valid PlatformConfigDto dto) {
        return ResponseEntity.ok(platformConfigService.update(provider, dto.platformMsisdn()));
    }
}
```

### Pattern 3: Event-Driven Email (existing pattern)
**What:** Service publishes a `PlatformConfigChangedEvent` via `ApplicationEventPublisher`. A
dedicated `@EventListener` builds an `Envelope` and calls `publisher.publishEvent(envelope)`.
`MailManager.sendEmailFromTemplate` listens for `Envelope` via `@TransactionalEventListener`.
**When to use:** Exactly how `AccountChangeEmailListener` triggers notification emails.

```java
// Source: AccountChangeEmailListener.java pattern
@Slf4j
@Component
public class PlatformConfigEmailListener {

    private final ApplicationEventPublisher publisher;
    private final String notificationEmail;

    public PlatformConfigEmailListener(
            ApplicationEventPublisher publisher,
            @Value("${payam.platform.notification-email}") String notificationEmail) {
        this.publisher = publisher;
        this.notificationEmail = notificationEmail;
    }

    @EventListener
    public void onConfigChanged(PlatformConfigChangedEvent event) {
        Recipient recipient = new Recipient();
        recipient.setEmail(notificationEmail);
        recipient.setLangKey("en");

        Map<String, Object> data = new HashMap<>();
        data.put("provider", event.provider());
        data.put("oldMsisdn", event.oldMsisdn() != null ? event.oldMsisdn() : "");
        data.put("newMsisdn", event.newMsisdn());

        Envelope envelope = new Envelope(
                List.of(recipient),
                EmailTemplate.PLATFORM_CONFIG_CHANGED,
                Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
                data,
                UUID.randomUUID().toString()
        );

        publisher.publishEvent(envelope);
    }
}
```

### Pattern 4: Frontend Admin Page (existing Quasar pattern)
**What:** A new `.vue` page under `src/frontend/src/pages/admin/`, a new entry in `admin.api.js`,
and a new route child in `routes.js` under the `admin` parent.
**When to use:** All admin pages follow this pattern. There is no Thymeleaf for admin pages.

### Anti-Patterns to Avoid
- **Storing notification-email in the database:** Out of scope per requirements; keep it in
  `application.yaml` as `payam.platform.notification-email` only.
- **Calling MailManager directly from PlatformConfigService:** This couples the service to email.
  Publish an event, let the listener handle it (matches existing pattern).
- **Creating a Thymeleaf MVC controller for the admin page:** The admin UI is a Vue/Quasar SPA.
  All admin pages are REST-backed single-page routes, not server-rendered HTML.
- **Using a new `@ConfigurationProperties` bean for the MSISDNs themselves:** MSISDNs must be
  DB-persisted and admin-editable at runtime. Only the notification-email target is config-file-only.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Email send + retry + circuit breaker | Custom SMTP client | Existing `MailManager` + `MailService` | Full CB/retry/persistence/async pipeline already wired |
| Admin auth enforcement | Manual token check | `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` | Spring Security method security with `@EnableMethodSecurity` |
| Property binding for notification address | Manual `Environment.getProperty()` | `@Value("${payam.platform.notification-email}")` | Simple scalar binding; no class needed |
| Schema versioning | Manual DDL | Flyway V17 migration | All schema changes go through Flyway |

**Key insight:** The email and security infrastructure is mature and fully operational. Every new
admin feature in this codebase reuses the same three-layer pattern (config → service → event) rather
than building anything custom.

---

## Common Pitfalls

### Pitfall 1: Missing Flyway migration version
**What goes wrong:** Creating a migration file with version V17 or higher when V16 is the current
highest. If two uncoordinated branches both create V17 the build fails.
**Why it happens:** Developer doesn't check existing migration files.
**How to avoid:** Verify `src/main/resources/db/migration/` — highest is V16. Next safe version is V17.
**Warning signs:** `FlywayException: Found more than one migration with version 17` at startup.

### Pitfall 2: EmailTemplate enum name does not match template file name
**What goes wrong:** `MailService.sendEmailFromTemplate` derives the Thymeleaf template name from
the enum constant using `CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, ...)`. If the
enum is `PLATFORM_CONFIG_CHANGED`, the resolved template name is `platformConfigChanged`. The
file must be `src/main/resources/mails/platformConfigChanged.html`.
**Why it happens:** Mismatch between enum naming and Guava CaseFormat output.
**How to avoid:** Verify: `PLATFORM_CONFIG_CHANGED` → `platformConfigChanged`. Match exactly.
**Warning signs:** `TemplateInputException` at email send time.

### Pitfall 3: Event published before transaction commits
**What goes wrong:** If `PlatformConfigService.update()` publishes the `PlatformConfigChangedEvent`
inside a `@Transactional` boundary, and the listener calls `MailManager.sendEmailFromTemplate`
which is `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`, the email is sent even if the
outer transaction rolls back.
**Why it happens:** Spring's `@TransactionalEventListener` fires after commit — this is actually
the correct behaviour for the email listener. The risk is the reverse: publishing the event
outside any transaction means `AFTER_COMMIT` never fires.
**How to avoid:** Publish the `PlatformConfigChangedEvent` inside the same `@Transactional` method
that saves the entity. `MailManager.sendEmailFromTemplate` already handles `AFTER_COMMIT` correctly.
**Warning signs:** Email never sent despite successful save; or email sent on rollback.

### Pitfall 4: No `payam.platform.notification-email` in application.yaml
**What goes wrong:** `@Value("${payam.platform.notification-email}")` injection fails at startup
with `IllegalArgumentException: Could not resolve placeholder`.
**Why it happens:** New config key not added to `application.yaml` and `application-dev.yaml`.
**How to avoid:** Add the property to both YAML files. Use an empty string or test address as
default: `payam.platform.notification-email: ${PLATFORM_NOTIFICATION_EMAIL:admin@example.com}`.
**Warning signs:** `BeanCreationException` at application startup.

### Pitfall 5: Frontend route not under the `admin` parent
**What goes wrong:** The new `/admin/platform-config` route bypasses the meta guard because it
is added as a top-level route instead of a child of the `admin` parent in `routes.js`.
**Why it happens:** Copy-paste error when adding the route.
**How to avoid:** Add it as a child of the `{ path: 'admin', meta: { requiresAuth: true }, children: [...] }`
block, following the pattern of `ReconciliationPage` and `TransactionSearchPage`.

---

## Code Examples

Verified patterns from existing source files:

### Enum + Template File Naming (verified in MailService.java:73)
```java
// MailService.java — how template name is derived:
// CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, emailTemplate.name())
// PLATFORM_CONFIG_CHANGED → "platformConfigChanged"
// File must be: src/main/resources/mails/platformConfigChanged.html
```

### EmailTemplate enum entry (verified in EmailTemplate.java)
```java
// Add to EmailTemplate.java:
PLATFORM_CONFIG_CHANGED("email.platform_config_changed.title"),
```

### @ConfigurationProperties pattern (verified in OrangeMoneyConfig.java)
```java
// PayamPlatformProperties.java
@ConfigurationProperties(prefix = "payam.platform")
public class PayamPlatformProperties {
    private String notificationEmail;
    public String getNotificationEmail() { return notificationEmail; }
    public void setNotificationEmail(String notificationEmail) { this.notificationEmail = notificationEmail; }
}
```

### Admin API Module pattern (verified in admin.api.js)
```javascript
// Add to admin.api.js:
getPlatformConfig() {
  return api.get('/v1/admin/platform-config')
},
updatePlatformConfig(provider, platformMsisdn) {
  return api.put(`/v1/admin/platform-config/${provider}`, { platformMsisdn })
},
```

### Frontend route addition (verified in routes.js)
```javascript
// Add inside the admin children array:
{
  path: 'platform-config',
  component: () => import('pages/admin/PlatformConfigPage.vue'),
  meta: { requiresAuth: true },
},
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| MSISDN hardcoded in application.yaml | DB-backed runtime-configurable | This phase (V17) | Admin can update without restart |
| No notification email for config changes | `payam.platform.notification-email` via event pipeline | This phase | Audit trail of MSISDN changes |

**What does NOT exist yet and must be created:**
- `platform_config` table (V17 migration)
- `PlatformConfig` JPA entity
- `PlatformConfigRepository`
- `PlatformConfigService`
- `PlatformConfigAdminResource`
- `PlatformConfigDto`
- `PlatformConfigChangedEvent`
- `PlatformConfigEmailListener`
- `EmailTemplate.PLATFORM_CONFIG_CHANGED` enum entry
- `platformConfigChanged.html` Thymeleaf template
- Subject key in `messages.properties`
- `payam.platform.notification-email` in `application.yaml` and `application-dev.yaml`
- `PlatformConfigPage.vue` in the admin frontend
- Route entry in `routes.js`
- API functions in `admin.api.js`

---

## Open Questions

1. **Initial MSISDN values**
   - What we know: The migration must seed rows for ORANGE and MTN. Empty string is valid for sandbox.
   - What's unclear: Should the seed use empty string or a real placeholder MSISDN?
   - Recommendation: Seed with empty string `''` and document that the admin must set values before
     live traffic. This avoids baking in a real phone number in the migration.

2. **Validation rules for MSISDN**
   - What we know: Orange uses national format (no country code, e.g. `692954629`); MTN accepts
     full format with country code but without `+` (e.g. `237692954629`). The existing
     `CamMobileValidatorTest` and `OrangeMoneyPort.stripCountryCode()` handle subscriber MSISDNs.
   - What's unclear: What format is expected for the *platform's own* MSISDN in each provider's API?
     The `OrangeMoneyPort.buildPayRequest()` does not currently use a platform MSISDN (it uses
     `consumerKey` as `merchant_key`). The `CashoutRequest.msisdnFrom` would be the platform MSISDN.
   - Recommendation: Accept the MSISDN as-is (plain VARCHAR) for now. The phase scope is storage
     and notification only; format validation for actual usage is deferred.

3. **`@Transactional` on the email listener vs. `@EventListener`**
   - What we know: `AccountChangeEmailListener.handleAccountChange` uses `@Transactional` +
     `@EventListener` (not `@TransactionalEventListener`). `MailManager.sendEmailFromTemplate` uses
     `@TransactionalEventListener(AFTER_COMMIT)`. The `AccountChangeEmailListener` publishes an
     `Envelope` event, which `MailManager` then processes.
   - What's unclear: Whether `PlatformConfigEmailListener` should also be `@Transactional` or not.
   - Recommendation: Match the `AccountChangeEmailListener` pattern exactly: `@EventListener` +
     `@Transactional` on the platform config listener, publishing an `Envelope` to `MailManager`.

---

## Sources

### Primary (HIGH confidence)
- Direct reading of `src/main/resources/db/migration/V1__tenant_schema.sql` through `V16__msisdn_prefix_route_schema.sql` — confirms V17 is next
- Direct reading of `src/main/java/com/softropic/payam/email/service/MailService.java` — confirms Guava CaseFormat template name derivation
- Direct reading of `src/main/java/com/softropic/payam/email/contract/EmailTemplate.java` — confirms how to add new template
- Direct reading of `src/main/java/com/softropic/payam/email/service/MailManager.java` — confirms `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`
- Direct reading of `src/main/java/com/softropic/payam/email/infrastructure/listener/AccountChangeEmailListener.java` — confirms event-driven email pattern
- Direct reading of `src/main/java/com/softropic/payam/email/contract/Envelope.java` — confirms record shape
- Direct reading of `src/main/java/com/softropic/payam/admin/api/AdminTransactionResource.java` — confirms admin endpoint pattern
- Direct reading of `src/main/java/com/softropic/payam/admin/api/ProviderStatusResource.java` — confirms `@PreAuthorize(HAS_ADMIN_ROLE)` pattern
- Direct reading of `src/main/java/com/softropic/payam/security/common/util/SecurityConstants.java` — confirms `HAS_ADMIN_ROLE` constant
- Direct reading of `src/main/resources/application.yaml` — confirms no `payam.platform` exists yet; confirms Spring Mail is configured
- Direct reading of `src/frontend/src/router/routes.js` — confirms route structure
- Direct reading of `src/frontend/src/api/admin.api.js` — confirms API module pattern
- Direct reading of `src/frontend/src/layouts/MainLayout.vue` — confirms nav drawer pattern
- Direct reading of `src/main/java/com/softropic/payam/orange/config/OrangeMoneyConfig.java` — confirms `@ConfigurationProperties` pattern
- Direct reading of `src/main/java/com/softropic/payam/mtn/config/MtnMoMoConfig.java` — confirms `@ConfigurationProperties` pattern
- Direct reading of `.planning/codebase/CONVENTIONS.md` — confirms naming and package conventions

### Secondary (MEDIUM confidence)
- None required; all findings from direct codebase inspection.

### Tertiary (LOW confidence)
- None.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all components verified as existing in the codebase
- Architecture: HIGH — every pattern verified against at least one existing file
- Pitfalls: HIGH — derived from reading the exact code paths involved (MailService:73 for template
  naming, application.yaml for missing property risk)
- Frontend structure: HIGH — routes.js and admin.api.js read directly

**Research date:** 2026-03-30
**Valid until:** Stable for the foreseeable future (no external library changes; all findings are
from the project's own source files)
