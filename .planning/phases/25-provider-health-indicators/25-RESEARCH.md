# Phase 25: Provider Health Indicators - Research

**Researched:** 2026-03-31
**Domain:** Spring Boot Actuator health indicators, Resilience4j, provider subscriber validation
**Confidence:** HIGH (all findings verified directly in the codebase)

---

## Summary

Phase 25 adds two custom Spring Boot `HealthIndicator` beans — one for Orange and one for MTN — that
verify each provider's platform MSISDN is active by calling the respective provider's subscriber
validation API on every health poll. Each indicator also reports the provider's Resilience4j circuit
breaker state as detail data.

Both provider port classes already expose `validateSubscriber(String msisdn)` with no `@CircuitBreaker`
annotation. `CircuitBreakerRegistry` is an auto-configured bean already used by `ProviderStatusResource`.
`PlatformConfigService.findAll()` supplies the configured MSISDNs from the DB. No new dependencies,
migrations, or infrastructure changes are required.

---

## Standard Stack

### Core
| Component | Type | Purpose | Why Standard |
|-----------|------|---------|--------------|
| `HealthIndicator` (Spring Actuator) | Interface | Custom health check | Spring Boot auto-discovery via `@Component`; no explicit registration needed |
| `OrangeMoneyPort.validateSubscriber()` | `com.softropic.payam.orange.service` | Live provider check | Existing method; no `@CircuitBreaker` — health check traffic is independent |
| `MtnMoMoPort.validateSubscriber()` | `com.softropic.payam.mtn.service` | Live provider check | Same; MTN 404 → `MtnAccountInactiveException` → `SubscriberStatus(false)` |
| `PlatformConfigService.findAll()` | `com.softropic.payam.platform.service` | Retrieve platform MSISDNs | Returns `List<PlatformConfigDto>` with `provider` + `platformMsisdn` |
| `CircuitBreakerRegistry` | Resilience4j bean | Get CB state for details | Same bean used by `ProviderStatusResource`; CB names: "orange" and "mtn" |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `HealthIndicator` interface | `AbstractHealthIndicator` | Abstract class adds `withDetail()` sugar, but the interface + `Health.Builder` is equally idiomatic and the plan code below uses it directly |
| Calling `validateSubscriber()` directly | Calling the provider client directly | Port method is the correct entry point — handles token acquisition, exception translation, MSISDN formatting |
| Separate health indicator per provider | Single combined indicator | Two separate indicators give independent UP/DOWN granularity per provider in the `/manage/health` response |

---

## Architecture

### Package
New package: `com.softropic.payam.health`

Cross-cutting concern — not inside `orange` or `mtn` packages, which contain port/adapter/config/contract
only. `health` sits at the payam root level, same as `admin`, `alert`, `fee`, `fraud`, etc.

### Recommended Project Structure
```
com.softropic.payam.health/
├── OrangePlatformHealthIndicator.java   # @Component, HealthIndicator
└── MtnPlatformHealthIndicator.java      # @Component, HealthIndicator
```

### Bean Naming → Health Key
Spring Boot strips the `HealthIndicator` suffix and lowercases the first character:
- `OrangePlatformHealthIndicator` → health key `orangePlatform`
- `MtnPlatformHealthIndicator` → health key `mtnPlatform`

Both will appear under `/manage/health` > `components` when `show-details: when-authorized` is in effect.

### HealthIndicator Contract
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrangePlatformHealthIndicator implements HealthIndicator {

    private final OrangeMoneyPort orangeMoneyPort;
    private final PlatformConfigService platformConfigService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public Health health() {
        // Force-create CB so it appears even before first payment (matches ProviderStatusResource pattern)
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("orange");
        String cbState = cb.getState().name();

        String msisdn = platformConfigService.findAll().stream()
                .filter(c -> "ORANGE".equals(c.provider()))
                .map(PlatformConfigDto::platformMsisdn)
                .findFirst()
                .orElse("");

        if (msisdn.isBlank()) {
            return Health.down()
                    .withDetail("reason", "platform MSISDN not configured")
                    .withDetail("circuitBreaker", cbState)
                    .build();
        }

        try {
            SubscriberStatus status = orangeMoneyPort.validateSubscriber(msisdn);
            Health.Builder builder = status.active() ? Health.up() : Health.down();
            return builder
                    .withDetail("msisdn", msisdn)
                    .withDetail("rawStatus", status.rawStatus())
                    .withDetail("circuitBreaker", cbState)
                    .build();
        } catch (Exception e) {
            log.warn("Orange platform health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("msisdn", msisdn)
                    .withDetail("error", e.getMessage())
                    .withDetail("circuitBreaker", cbState)
                    .build();
        }
    }
}
```

MTN version is identical in structure, using `MtnMoMoPort` and CB name `"mtn"`.

---

## Key Findings

### 1. validateSubscriber() has no @CircuitBreaker
Both `OrangeMoneyPort.validateSubscriber()` and `MtnMoMoPort.validateSubscriber()` have **no
`@CircuitBreaker` annotation**. This is correct: health check traffic is independent of the payment
circuit breaker. A health check calling a DOWN provider will throw or return `active=false`, which is
exactly the right DOWN signal for the health indicator. The CB state is reported as a detail field,
not a status driver.

### 2. MTN inactive path: exception translated to SubscriberStatus
`MtnMoMoPort.validateSubscriber()` catches `MtnAccountInactiveException` (thrown by client on 404)
and returns `SubscriberStatus(false, msisdn, "INACTIVE")`. The health indicator does **not** need to
catch `MtnAccountInactiveException` separately; it only needs to handle generic `Exception` for
network failures.

### 3. CircuitBreakerRegistry force-create pattern
From `ProviderStatusResource`: call `circuitBreakerRegistry.circuitBreaker("orange")` before reading
state to force-create it on first poll. Without this, the registry returns an empty set if no payments
have yet been processed since startup.

### 4. PlatformConfigDto structure
```java
public record PlatformConfigDto(String provider, String platformMsisdn) {}
```
`platformMsisdn` is an empty string `""` when not yet configured (seeded by V17 migration).
Use `.isBlank()` to detect the unconfigured case.

### 5. Port injection by concrete type
Two beans implement `MobileMoneyPort`: `OrangeMoneyPort` and `MtnMoMoPort`. Health indicators
must inject the **concrete class** (not the interface) to get the correct port without `@Qualifier`.

### 6. Actuator health config already correct
`application.yaml` already sets `show-details: when-authorized` + `roles: ROLE_ADMIN`.
Health detail data (MSISDN, rawStatus, CB state) will only be visible to admin users.
`/manage/health` is in `PUBLIC_MGMT_ENDPOINTS` (added in the observability chore commit) so the
endpoint itself is accessible to Prometheus/load-balancer probes; only the `components` detail block
is gated by auth.

---

## Anti-Patterns to Avoid

- **Putting health indicators in `orange.service` or `mtn.service`**: Those packages are for provider adapters. Cross-cutting actuator infrastructure belongs in `health`.
- **Using `@CircuitBreaker` on health() method**: Would cause health checks to contribute to payment circuit breaker failure counts. Health traffic must not affect CB state for payment traffic.
- **Caching SubscriberStatus in health indicator**: Requirements explicitly state "on every poll" — no caching.
- **Injecting `MobileMoneyPort` by interface without `@Qualifier`**: Two beans implement the interface; use the concrete type.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Health endpoint registration | Manual `@RequestMapping` | `@Component` + `HealthIndicator` — Spring Boot auto-discovers and aggregates |
| CB state aggregation | Manual Resilience4j API walk | `circuitBreakerRegistry.circuitBreaker("name").getState().name()` |
| MSISDN lookup | New `@Value` injection | `PlatformConfigService.findAll()` — already exists, DB-backed |

---

## Common Pitfalls

### Pitfall 1: Empty MSISDN causes provider API error
**What goes wrong:** Orange `getSubscriberInfo` with empty MSISDN returns a 400/422; MTN throws immediately.
**Fix:** Check `msisdn.isBlank()` before calling the port and return `Health.down()` with `"reason": "platform MSISDN not configured"`.

### Pitfall 2: @CircuitBreaker on health() trips payment CB
**What goes wrong:** Adding `@CircuitBreaker(name = "orange")` to `health()` means failed health checks count as failures against the payment circuit breaker. Payment traffic gets throttled because health poll latency tripped the CB.
**Fix:** Never annotate health indicator methods with circuit breaker — call `validateSubscriber()` bare.

### Pitfall 3: Lazy CB creation returns null state
**What goes wrong:** On a fresh app boot before any payment, `circuitBreakerRegistry.getAllCircuitBreakers()` is empty. Calling `.getState()` on a non-existent CB throws NPE.
**Fix:** Call `circuitBreakerRegistry.circuitBreaker("orange")` first — this force-creates it if it doesn't exist, then return the instance.

### Pitfall 4: Inject MobileMoneyPort interface — Spring throws NoUniqueBeanDefinitionException
**What goes wrong:** Two beans implement `MobileMoneyPort`. Spring can't pick one.
**Fix:** Inject by concrete type: `private final OrangeMoneyPort orangeMoneyPort`.

---

## Sources

### Primary (HIGH confidence)
- `com.softropic.payam.orange.service.OrangeMoneyPort` — `validateSubscriber()` signature, no `@CircuitBreaker`
- `com.softropic.payam.mtn.service.MtnMoMoPort` — `validateSubscriber()` signature, no `@CircuitBreaker`; MTN 404 → `MtnAccountInactiveException` caught, returns `SubscriberStatus(false)`
- `com.softropic.payam.common.payment.SubscriberStatus` — `record SubscriberStatus(boolean active, String msisdn, String rawStatus)`
- `com.softropic.payam.platform.service.PlatformConfigService` — `findAll()` returns `List<PlatformConfigDto>`
- `com.softropic.payam.platform.contract.PlatformConfigDto` — `record PlatformConfigDto(String provider, String platformMsisdn)`
- `com.softropic.payam.admin.api.ProviderStatusResource` — CB force-create pattern, registry injection
- `src/main/resources/application.yaml` (lines 145-165) — actuator config: `show-details: when-authorized`, `roles: ROLE_ADMIN`, `base-path: /manage`
- `com.softropic.payam.security.config.AppEndpoints` — `PUBLIC_MGMT_ENDPOINTS` includes `/manage/health`

**Research date:** 2026-03-31
**Valid until:** Stable (all findings from project source; no external library changes)
