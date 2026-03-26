# Phase 14: Logging Infrastructure - Research

**Researched:** 2026-03-26
**Domain:** Logback JSON logging, logstash-logback-encoder, OpenTelemetry MDC injection
**Confidence:** HIGH

---

## Summary

Phase 14 replaces the existing broken logging configuration with a correct, production-ready
JSON logging pipeline. The current `logback-spring.xml` has three problems: it uses a plain
pattern encoder instead of `LoggingEventCompositeJsonEncoder`, it ships a file appender and a
Loki4j network appender that must be removed, and the file itself lives at
`classpath:config/logback-spring.xml` which Spring Boot does NOT auto-discover (the
`logging.config` property that would activate it is commented out in both YAML files).

The fix is straightforward: replace the entire `logback-spring.xml` with one that uses
`LoggingEventCompositeJsonEncoder` on a single `ConsoleAppender`. The required library
(`logstash-logback-encoder`) is already declared in `pom.xml` at version 8.1. The OpenTelemetry
MDC injection (`traceId`, `spanId`) is already wired — `micrometer-tracing-bridge-otel` is in
the POM and `management.tracing.sampling.probability: 1.0` is set — so no new dependencies are
needed.

**Primary recommendation:** Replace `src/main/resources/config/logback-spring.xml` with a
`LoggingEventCompositeJsonEncoder` stdout-only configuration and activate it via
`logging.config: classpath:config/logback-spring.xml` in `application.yaml`.

---

## Standard Stack

### Core

| Library | Version in POM | Purpose | Why Standard |
|---------|----------------|---------|--------------|
| `net.logstash.logback:logstash-logback-encoder` | 8.1 (already present) | Provides `LoggingEventCompositeJsonEncoder` and all JSON providers | De-facto standard for JSON Logback in Spring Boot; maintained; works with Jackson 2 (Spring Boot 3.x) |
| `io.micrometer:micrometer-tracing-bridge-otel` | managed by Boot 3.5.11 (already present) | Bridges Micrometer Observation API to OTel; injects `traceId`/`spanId` into MDC automatically | Replaces Sleuth; supported by Spring Boot 3.x |
| `io.opentelemetry:opentelemetry-exporter-otlp` | managed by Boot (already present) | Exports trace data to Tempo/Alloy via OTLP | Matches the LGTM stack target |

### Supporting (already present — no additions required)

| Library | Purpose |
|---------|---------|
| `spring-boot-starter-actuator` | Exposes `management.tracing.*` auto-configuration that activates MDC injection |
| `micrometer-registry-prometheus` | Metrics endpoint for Prometheus scraping |

### Version decision: stay on 8.1, do not upgrade to 9.0

logstash-logback-encoder 9.0 (released Oct 2023) migrated to Jackson 3 and introduced breaking
changes. Spring Boot 3.5.x ships with Jackson 2.21.x. Upgrading to 9.0 would create a Jackson
version conflict. The current version 8.1 is correct and stable for this project.

### Loki4j dependency

`com.github.loki4j:loki-logback-appender:1.6.0` is present in the POM. The requirements
(LOG-INF-02) mandate stdout-only; the Docker/K8s log pipeline (Alloy) picks up from stdout.
The Loki4j dependency is no longer needed once the appender is removed from config. However,
removing it from the POM is a separate concern — the phase only needs to stop using it in
`logback-spring.xml`. Removing the Maven dependency is a safe optional cleanup.

### Installation (no new dependencies needed)

The `pom.xml` already contains everything required. No `mvn` changes for this phase.

---

## Architecture Patterns

### Recommended File Structure

```
src/main/resources/
├── config/
│   └── logback-spring.xml     # REPLACE entirely (this is the one change)
├── application.yaml           # MODIFY: uncomment logging.config line
└── application-dev.yaml       # MODIFY: uncomment logging.config line
```

### Pattern: Single ConsoleAppender with LoggingEventCompositeJsonEncoder

**What:** One appender writes JSON lines to stdout. No file appender. No network appender.
Docker/K8s collects stdout. Alloy/Promtail ships to Loki.

**When to use:** Always in containerized environments (Docker, Kubernetes). This is the LOG-INF-02 requirement.

**How `LoggingEventCompositeJsonEncoder` works:**
No providers are configured by default. Each provider is listed explicitly inside `<providers>`.
The element name for each provider is the short name without a `Provider` suffix (e.g.,
`<timestamp/>` not `<timestampProvider/>`).

**Verified provider XML element names** (confirmed from logstash-logback-encoder test resources
and documentation):

```
<timestamp/>          — ISO-8601 timestamp
<logLevel/>           — log level string
<threadName/>         — thread name
<loggerName/>         — logger class name
<message/>            — log message text
<mdc/>                — ALL MDC keys as top-level JSON fields (this is how traceId/spanId appear)
<arguments/>          — structured arguments from StructuredArguments.kv()
<stackTrace/>         — exception stack trace if present
<pattern>             — custom static fields (service, environment, version)
  <pattern>{ ... }</pattern>
</pattern>
```

**Reference logback-spring.xml** (replacing the existing file):

```xml
<configuration>

    <springProperty name="appName"     source="spring.application.name" defaultValue="payam"/>
    <springProperty name="environment" source="ENVIRONMENT"             defaultValue="dev"/>
    <springProperty name="appVersion"  source="APP_VERSION"             defaultValue="unknown"/>

    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp/>
                <logLevel/>
                <threadName/>
                <loggerName/>
                <message/>
                <mdc/>
                <arguments/>
                <stackTrace/>
                <pattern>
                    <pattern>
                        {
                          "service":     "${appName}",
                          "environment": "${environment}",
                          "version":     "${appVersion}"
                        }
                    </pattern>
                </pattern>
            </providers>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>

</configuration>
```

**Notes on `<springProperty>`:**
- `source` for environment variables uses the variable name directly (e.g., `ENVIRONMENT`),
  not `spring.config.*`. Spring Boot's `<springProperty>` can resolve environment variables
  when the source matches an env var name.
- Alternatively, define the values as Spring properties (`ENVIRONMENT` → `app.environment`) in
  application.yaml and reference those. The pattern above using env vars directly is the
  simplest and matches how Docker/K8s deployments pass configuration.

### Pattern: Activating the config file

The `logging.config` line is commented out in both YAML files:

```yaml
# current state (both application.yaml and application-dev.yaml):
#logging.config: classpath:config/logback-spring.xml
```

It must be uncommented. Spring Boot does NOT auto-discover files in `classpath:config/`.
Only `classpath:logback-spring.xml` (root) is auto-discovered.

**Options (choose one):**
1. Uncomment `logging.config: classpath:config/logback-spring.xml` in application.yaml — keeps
   current file location.
2. Move `logback-spring.xml` to `src/main/resources/` (root) and remove `logging.config` —
   relies on auto-discovery.

**Recommendation:** Option 1 (uncomment the property). The `config/` subdirectory is a
reasonable organizational choice; explicit is safer than implicit.

### Anti-Patterns to Avoid

- **Using `PatternLayoutEncoder` for JSON output:** The current config does this. Pattern-based
  JSON is fragile — message text with quotes or newlines will break JSON validity. Use
  `LoggingEventCompositeJsonEncoder` instead.
- **File appenders in production:** Breaks the Docker/K8s log pipeline. Logs go to a file
  inside the container; Alloy never sees them.
- **Network appenders (Loki4j) as primary path:** If the Loki endpoint is unreachable, logs
  are lost. Stdout is always available.
- **Using `LogstashEncoder` instead of `LoggingEventCompositeJsonEncoder`:** `LogstashEncoder`
  includes opinionated default fields. The requirements specify `LoggingEventCompositeJsonEncoder`
  with explicit providers for full control.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON log serialization | Custom string concatenation or pattern-based JSON | `LoggingEventCompositeJsonEncoder` | Handles quoting, escaping, newlines, Unicode in message/exception text |
| Trace correlation fields | Manual `MDC.put("traceId", ...)` calls | `micrometer-tracing-bridge-otel` auto-injection | The bridge automatically puts `traceId` and `spanId` into MDC on every traced request |
| Service identity fields | Environment filter or Spring bean | `<springProperty>` + `<pattern>` provider | Built into logback-spring.xml; zero runtime code |

**Key insight:** The entire Phase 14 implementation is configuration-only. No Java code changes are required. All three requirements are satisfied through XML configuration.

---

## Common Pitfalls

### Pitfall 1: logback-spring.xml not loading (currently happening)

**What goes wrong:** Application starts with default Spring Boot text logging; JSON never appears.
**Why it happens:** The file is at `classpath:config/logback-spring.xml` but `logging.config` is
commented out. Spring Boot's auto-discovery only checks the classpath root.
**How to avoid:** Uncomment `logging.config: classpath:config/logback-spring.xml` in
`application.yaml` (and `application-dev.yaml`).
**Warning signs:** No JSON in console output; logs look like standard Spring Boot text format.

### Pitfall 2: traceId/spanId not appearing as top-level JSON fields

**What goes wrong:** `traceId` and `spanId` are missing or appear nested inside an `mdc` object.
**Why it happens:** The `<mdc/>` provider in `LoggingEventCompositeJsonEncoder` writes ALL MDC
entries as top-level JSON fields by default. This is the correct behavior — no extra config needed.
If they appear nested, the encoder is not `LoggingEventCompositeJsonEncoder` or the `<mdc/>`
provider is missing.
**How to avoid:** Use `<mdc/>` inside `<providers>`. Confirm `micrometer-tracing-bridge-otel`
is on the classpath and `management.tracing.sampling.probability: 1.0` is set (already done).
**Warning signs:** Loki query `| json | traceId=~".+"` returns no results.

### Pitfall 3: MDC key names — traceId vs trace_id

**What goes wrong:** Code searches for `trace_id` but OTel injects `traceId`.
**Why it happens:** OTel uses camelCase MDC keys. Sleuth used `X-B3-TraceId`. Micrometer
Tracing bridge-otel uses `traceId` and `spanId`.
**How to avoid:** Always reference `%X{traceId}` and `%X{spanId}` (camelCase).
**Source:** Spring Boot official tracing docs, confirmed HIGH confidence.

### Pitfall 4: JSON breaking due to multi-line exception stack traces

**What goes wrong:** Log line is split across lines; JSON parser fails.
**Why it happens:** Naive pattern encoder outputs literal newlines in exceptions. This breaks
JSON parsers that expect one JSON object per line.
**How to avoid:** `LoggingEventCompositeJsonEncoder` + `<stackTrace/>` provider handles this
correctly — the stack trace is serialized as a JSON string (escaped newlines) or array.

### Pitfall 5: logstash-logback-encoder 9.0 Jackson 3 conflict

**What goes wrong:** Build fails or runtime errors due to Jackson version mismatch.
**Why it happens:** Version 9.0 requires Jackson 3; Spring Boot 3.5.x uses Jackson 2.21.x.
**How to avoid:** Stay on version 8.1 (already in POM). Do not upgrade.

### Pitfall 6: `<springProperty>` source for environment variables

**What goes wrong:** `${environment}` always resolves to the `defaultValue`.
**Why it happens:** `<springProperty source="ENVIRONMENT">` works when `ENVIRONMENT` is an OS
environment variable or Spring property. If the source key is wrong, the default is used silently.
**How to avoid:** Verify the env var name matches. Alternatively map them in `application.yaml`:
```yaml
app:
  environment: ${ENVIRONMENT:dev}
  version: ${APP_VERSION:unknown}
```
and use `source="app.environment"` in `<springProperty>`.

---

## Code Examples

### Complete logback-spring.xml (verified pattern)

```xml
<configuration>

    <springProperty name="appName"     source="spring.application.name" defaultValue="payam"/>
    <springProperty name="environment" source="app.environment"         defaultValue="dev"/>
    <springProperty name="appVersion"  source="app.version"             defaultValue="unknown"/>

    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp/>
                <logLevel/>
                <threadName/>
                <loggerName/>
                <message/>
                <mdc/>
                <arguments/>
                <stackTrace/>
                <pattern>
                    <pattern>
                        {
                          "service":     "${appName}",
                          "environment": "${environment}",
                          "version":     "${appVersion}"
                        }
                    </pattern>
                </pattern>
            </providers>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>

</configuration>
```

If env vars are injected at OS level (`ENVIRONMENT`, `APP_VERSION`), use:
```xml
<springProperty name="environment" source="ENVIRONMENT"  defaultValue="dev"/>
<springProperty name="appVersion"  source="APP_VERSION"  defaultValue="unknown"/>
```

### application.yaml change (uncomment one line)

```yaml
# BEFORE (broken — file is never loaded):
#logging.config: classpath:config/logback-spring.xml

# AFTER (correct):
logging.config: classpath:config/logback-spring.xml
```

This change must be applied in BOTH `application.yaml` and `application-dev.yaml`.

### Expected JSON output per log line

```json
{
  "@timestamp": "2026-03-26T10:33:21.123Z",
  "level": "INFO",
  "thread_name": "http-nio-9990-exec-1",
  "logger_name": "com.softropic.payam.SomeService",
  "message": "Payment processed",
  "traceId": "803b448a0489f84084905d3093480352",
  "spanId": "3425f23bb2432450",
  "service": "payam",
  "environment": "prod",
  "version": "1.0.0"
}
```

`traceId` and `spanId` appear as top-level fields because the `<mdc/>` provider flattens all
MDC entries into the root JSON object.

---

## What Needs to Be Replaced / Modified

### File 1: `src/main/resources/config/logback-spring.xml` — REPLACE entirely

**Remove:**
- `PatternLayoutEncoder` on CONSOLE appender
- `ROLLING_FILE` appender (writes to `/var/log/payam/spring.log`)
- `LOKI` appender (Loki4j network push appender)
- `<appender-ref ref="LOKI"/>` and `<appender-ref ref="ROLLING_FILE"/>` from `<root>`

**Add:**
- Single `ConsoleAppender` with `LoggingEventCompositeJsonEncoder` as documented above

### File 2: `src/main/resources/application.yaml` — MODIFY

Uncomment line 130:
```yaml
logging.config: classpath:config/logback-spring.xml
```

Also consider adding service identity properties if using Spring property sources:
```yaml
app:
  environment: ${ENVIRONMENT:dev}
  version: ${APP_VERSION:unknown}
```

### File 3: `src/main/resources/application-dev.yaml` — MODIFY

Same change as application.yaml — uncomment `logging.config`.

### Optional: POM cleanup

Remove `com.github.loki4j:loki-logback-appender` from `pom.xml` since it will no longer be
used. This is safe but not strictly required by the phase requirements.

---

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| Loki4j network push appender | stdout → Alloy → Loki | More resilient; no log loss if Loki unreachable |
| Pattern encoder for JSON | `LoggingEventCompositeJsonEncoder` | Correct JSON escaping; works with multiline exceptions |
| Spring Cloud Sleuth MDC | Micrometer Tracing + OTel bridge (MDC keys: `traceId`, `spanId`) | Spring Boot 3.x standard; no additional config needed |
| File appender + rotation | None (Docker/K8s manages lifecycle) | Container-native; no disk management needed |

---

## Open Questions

1. **Tomcat access log (application.yaml lines 8-11)**
   - What we know: `server.tomcat.accesslog.enabled: true` writes to `/usr/local/var/ledger/payam_access.ledger` using a pattern format. This is a separate log from the application log.
   - What's unclear: Whether this should also be migrated to JSON or is out of scope for Phase 14. The requirements (LOG-INF-01 through LOG-INF-03) reference `logback-spring.xml` specifically, suggesting Tomcat access log is out of scope.
   - Recommendation: Treat as out of scope for Phase 14. File it for a separate task if needed.

2. **`<springProperty>` for env var sourcing**
   - What we know: `<springProperty source="ENVIRONMENT">` resolves OS environment variables in logback-spring.xml when Spring Boot loads it.
   - What's unclear: Exact resolution order when the env var is absent. The `defaultValue` attribute provides fallback.
   - Recommendation: Add `app.environment` and `app.version` properties to application.yaml (with `${ENVIRONMENT:dev}` interpolation) so both env-var and Spring property source paths work correctly.

3. **`loki-logback-appender` removal from POM**
   - What we know: Removing the Maven dependency is safe since the appender will not be referenced in any XML config.
   - What's unclear: Whether any Java code directly imports Loki4j classes (unlikely but should be verified).
   - Recommendation: Grep for `loki4j` imports before removing from POM.

---

## Recommended Plan Structure

Phase 14 is a single-concern configuration change. One plan is sufficient:

**Plan 14-01: Replace logback-spring.xml and activate logging config**

Tasks:
1. Replace `src/main/resources/config/logback-spring.xml` with `LoggingEventCompositeJsonEncoder` config
2. Uncomment `logging.config` in `application.yaml`
3. Uncomment `logging.config` in `application-dev.yaml`
4. Add `app.environment` and `app.version` to `application.yaml` (service identity property sources)
5. (Optional) Remove `loki-logback-appender` from `pom.xml`
6. Verify: start application, confirm every log line is valid JSON with required fields

---

## Sources

### Primary (HIGH confidence)

- Spring Boot official tracing docs — MDC key names (`traceId`, `spanId`), automatic injection
  https://docs.spring.io/spring-boot/reference/actuator/tracing.html
- Spring Boot official logging docs — `logback-spring.xml` auto-discovery path, `logging.config` property
  https://docs.spring.io/spring-boot/how-to/logging.html
- logstash-logback-encoder GitHub releases — version history (latest is 9.0 with Jackson 3; 8.1 is last Jackson 2 version)
  https://github.com/logfellow/logstash-logback-encoder/releases
- logstash-logback-encoder GitHub test resources — verified provider XML element names
  https://github.com/logfellow/logstash-logback-encoder/blob/main/src/test/resources/logback-test.xml
- Project `requirements/logging.md` — official logging standard document, includes reference configuration

### Secondary (MEDIUM confidence)

- WebSearch: Jackson 3 in Spring Boot — Spring Boot 3.5.x uses Jackson 2.21.x; Jackson 3 arrives in Spring Boot 4.0
  (confirmed by Spring Boot release notes references in search results)
- WebSearch: Micrometer Tracing MDC key names confirmed `traceId` and `spanId` (camelCase)
  across multiple sources matching official Spring Boot docs

### Tertiary (LOW confidence — not used for prescriptive recommendations)

- Medium articles on LoggingEventCompositeJsonEncoder configuration examples

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — library versions confirmed from POM + official releases
- Architecture patterns: HIGH — provider names confirmed from library test resources + official docs
- OTel MDC injection: HIGH — confirmed from Spring Boot official tracing documentation
- File location issue: HIGH — confirmed from Spring Boot official logging documentation
- Pitfalls: HIGH (versions, file location) / MEDIUM (springProperty env var behavior)

**Research date:** 2026-03-26
**Valid until:** 2026-09-26 (stable ecosystem; logstash-logback-encoder 8.x and Spring Boot 3.5.x are mature)
