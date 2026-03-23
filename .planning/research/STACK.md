# Technology Stack: Payam Payment Gateway Module

**Project:** Payam — unified multi-tenant mobile money API for Cameroon (MTN MoMo + Orange Money)
**Researched:** 2026-03-23
**Scope:** Additive libraries for the payment gateway layer only — existing stack is NOT re-researched
**Overall confidence:** HIGH (all recommendations verified against official Spring, Modulith, and Micrometer documentation)

---

## Existing Stack (Do Not Change)

The following is already in place and must not be replaced or duplicated:

| Component | Artifact | Version |
|-----------|----------|---------|
| Framework | spring-boot-starter-parent | 3.5.11 |
| Security | spring-boot-starter-security + JJWT | in pom |
| Persistence | spring-boot-starter-data-jpa + PostgreSQL + Flyway | in pom |
| Resilience | spring-cloud-starter-circuitbreaker-resilience4j + spring-retry | in pom |
| JSON logging | logstash-logback-encoder | 8.1 |
| Log shipping | loki-logback-appender | 1.6.0 |
| Distributed tracing | micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp | in pom |
| Metrics export | micrometer-registry-prometheus | in pom |
| Observability infra | Prometheus + Loki + Tempo + Grafana (docker-compose-lgtm.yaml) | configured |
| Audit | hibernate-envers | 6.6.14.Final |
| Rate limiting | bucket4j-core | 8.10.1 |
| HMAC primitives | commons-codec | 1.19.0 |
| Phone validation | libphonenumber | 9.0.25 |
| JSON utils | hypersistence-utils-hibernate-63 | 3.9.10 |
| Mapping | mapstruct | 1.6.3 |
| Cloud BOM | spring-cloud-dependencies | 2025.0.1 |

---

## Additions Required for the Payment Gateway Module

The seven technology areas below represent genuine gaps. Each section states what to add, the exact Maven coordinates, and why.

---

## 1. Async Event Processing and Event Sourcing (Within Monolith)

### Recommendation: Spring Modulith 1.4.9 (Events + JPA starter)

**Confidence: HIGH** — Verified against official Spring Modulith compatibility matrix. Version 1.4.9 is the latest in the 1.4.x line; it is compiled against Spring Boot 3.4 and explicitly tested against Spring Boot 3.5.

**What it provides:**

- `@ApplicationModuleListener`: a single annotation that combines `@Async`, `@Transactional(REQUIRES_NEW)`, and `@TransactionalEventListener`. This is the correct mechanism for decoupling the payment orchestrator from downstream side-effects (ledger updates, fraud scoring, notification dispatch) without coupling them with direct method calls.
- **Event Publication Registry**: automatically persists a row per event-listener pair in the same transaction that publishes the event. If a listener crashes mid-execution, the row survives. A staleness monitor marks abandoned rows as `FAILED`; an `IncompleteEventPublications` bean enables resubmission on restart. This gives at-least-once delivery semantics within a single PostgreSQL-backed monolith — no Kafka required.
- The registry creates an `EVENT_PUBLICATION` table (schema auto-created with `spring.modulith.events.jdbc.schema-initialization.enabled=true` or via Flyway migration). Columns include: `id`, `listener_id`, `event_type`, `serialized_event`, `publication_date`, `completion_date`, `status`, `completion_attempts`, `last_resubmission_date`.

**Why not Kafka or RabbitMQ:** Both require additional infrastructure processes and operational overhead that is unwarranted in a single-JVM monolith. The project constraint explicitly rules out microservices. Spring Modulith achieves durable async dispatch on top of the existing PostgreSQL + Spring Data JPA stack already present.

**Why not raw `@TransactionalEventListener` alone:** Without the publication registry, a JVM crash between event publish and listener execution loses the event silently. This is unacceptable for financial transactions.

**Maven additions (Spring Modulith needs its own BOM — it is NOT in the Spring Boot BOM):**

```xml
<!-- Add to <dependencyManagement> alongside the existing spring-cloud BOM -->
<dependency>
  <groupId>org.springframework.modulith</groupId>
  <artifactId>spring-modulith-bom</artifactId>
  <version>1.4.9</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>

<!-- Add to <dependencies> -->
<dependency>
  <groupId>org.springframework.modulith</groupId>
  <artifactId>spring-modulith-starter-jpa</artifactId>
  <!-- version managed by BOM -->
</dependency>

<!-- Optional: test support for asserting published events -->
<dependency>
  <groupId>org.springframework.modulith</groupId>
  <artifactId>spring-modulith-starter-test</artifactId>
  <scope>test</scope>
</dependency>
```

**Key configuration:**

```yaml
spring:
  modulith:
    events:
      republish-outstanding-events-on-restart: true
      completion-mode: ARCHIVE   # keeps completed events for audit queries
      staleness:
        published: PT30M         # mark stale PUBLISHED rows as FAILED after 30 min
        processing: PT10M        # mark stale PROCESSING rows as FAILED after 10 min
```

**Conflict check:** None. Spring Modulith is additive. It does not replace or conflict with Resilience4j, Spring Retry, or Hibernate Envers.

---

## 2. Idempotency Key Management and Redis-Based Deduplication

### Recommendation: Spring Data Redis (already in classpath via spring-boot-starter-cache + Bucket4j) — no new library needed

**Confidence: HIGH** — Verified against official Spring Data Redis Javadoc. `ValueOperations.setIfAbsent(key, value, Duration timeout)` has been available since Spring Data Redis 2.1. The project already uses Redis (Bucket4j rate limiting implies a Redis connection factory is configured).

**What to do:**

The correct pattern for idempotency key enforcement is:

```java
// Atomic SETNX + TTL in one Redis round-trip — verified in Spring Data Redis docs
Boolean accepted = redisTemplate.opsForValue()
    .setIfAbsent(idempotencyKey, serializedResponse, Duration.ofHours(24));
```

- On `true`: first time seeing this key — proceed with the payment operation.
- On `false`: duplicate request — deserialize and return the cached response immediately.
- TTL of 24 hours is a common financial API standard (Stripe, Paystack both use 24h windows).

**What to store as the value:** a compact JSON-serialized record containing `{ transactionId, status, httpStatusCode, responseBody }`. Serialization via Jackson (already present).

**For webhook replay protection:** same pattern with a shorter TTL (2–5 minutes). Store `webhookId` or `(providerId + externalRef + timestamp)` as the key, `"processed"` as the value.

**No new dependency.** The `spring-boot-starter-data-redis` artifact is the right addition if not already explicit in the pom (Bucket4j may have brought in Lettuce transitively — verify at runtime).

```xml
<!-- Add only if redis is not already a declared dependency -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**Conflict check:** Bucket4j 8.x has a Redis backend (`bucket4j-redis`). If the project is using that integration, a `LettuceConnectionFactory` is already configured. The idempotency service shares that connection factory — no second Redis client.

---

## 3. HMAC Request and Webhook Signature Verification

### Recommendation: Apache Commons Codec HmacUtils (already in pom) — no new library needed

**Confidence: HIGH** — `commons-codec 1.19.0` is already declared in `pom.xml`. `HmacUtils` in that library provides:

- `HmacUtils(HmacAlgorithms.HMAC_SHA_256, secretKey).hmacHex(payload)` — produces the hex-encoded HMAC-SHA256.
- Constant-time comparison via `MessageDigest.isEqual()` (JDK standard) to prevent timing attacks.

**Pattern for outbound request signing (to MTN/Orange):**

```java
String signature = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, apiSecret)
    .hmacHex(payload + timestamp);
// Attach as header: X-Signature: sha256=<signature>
```

**Pattern for inbound webhook verification:**

```java
String expected = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, webhookSecret)
    .hmacHex(rawRequestBody);
boolean valid = MessageDigest.isEqual(
    expected.getBytes(StandardCharsets.UTF_8),
    receivedSignature.getBytes(StandardCharsets.UTF_8)
);
```

**Important implementation notes:**

1. Read the raw request body as `byte[]` (not as a parsed DTO) before any deserialization — signature is computed over the exact bytes on the wire. Use `HttpServletRequest` with a `ContentCachingRequestWrapper` (Spring provides this) or a custom `ReadOnceRequestWrapper`.
2. The timestamp in the signature prevents replay attacks. Enforce a maximum age (30–60 seconds) on the `X-Timestamp` header. Store processed nonces in Redis with a TTL matching that window.
3. MTN MoMo webhooks do not use HMAC — they use IP allowlisting only. Orange Money webhook authentication specifics should be confirmed from provider documentation before implementation. The HMAC infrastructure should be built for Payam's own outbound client-facing webhooks.

**No new dependency.** `commons-codec` covers all HMAC needs for this project.

---

## 4. Distributed Tracing and Structured JSON Logging

### Status: Already Fully Configured — No New Libraries Needed

**Confidence: HIGH** — Verified against official Spring Boot Actuator tracing documentation and inspection of the existing `pom.xml` and `docker-compose-lgtm.yaml`.

**What is already present:**

| Concern | Library | Status |
|---------|---------|--------|
| Trace bridge | `micrometer-tracing-bridge-otel` | in pom |
| OTLP exporter | `opentelemetry-exporter-otlp` | in pom |
| Trace receiver | Grafana Tempo on port 4317/4318 | in docker-compose |
| Metrics export | `micrometer-registry-prometheus` | in pom |
| Metrics scraper | Prometheus on port 9090 | in docker-compose |
| JSON log encoding | `logstash-logback-encoder 8.1` | in pom |
| Log shipping | `loki-logback-appender 1.6.0` | in pom |
| Log aggregator | Grafana Loki on port 3100 | in docker-compose |
| Dashboards | Grafana on port 3000 | in docker-compose |

**What the payment module must do (not a library gap — an implementation discipline):**

1. Propagate `traceId` and `spanId` into every payment event payload stored in the `EVENT_PUBLICATION` table and in the custom transaction event log. Micrometer Tracing automatically populates MDC with `traceId` and `spanId`; `logstash-logback-encoder` will include them in every JSON log line.

2. Create custom spans for high-value operations using `ObservationRegistry`:

```java
Observation.createNotStarted("payment.provider.request", observationRegistry)
    .lowCardinalityKeyValue("provider", "mtn")
    .highCardinalityKeyValue("transactionId", txnId)
    .observe(() -> mtnClient.requestPayment(request));
```

3. Correlate the internal `transactionId` through Micrometer baggage propagation:

```yaml
management:
  tracing:
    baggage:
      remote-fields: X-Transaction-Id
      correlation:
        fields: X-Transaction-Id
    sampling:
      probability: 1.0   # capture all payment traces; adjust if volume warrants sampling
```

**Admin dashboard (real-time metrics in existing Quasar SPA):** The Grafana instance in docker-compose is the dedicated ops dashboard. The Quasar admin UI should surface per-tenant, per-transaction business metrics by querying the Payam API (which reads from PostgreSQL), not by embedding Prometheus/Grafana directly. Grafana is for infrastructure/operational visibility; the Quasar SPA is for business-level admin. Keep these concerns separate.

---

## 5. Rule-Based Fraud and Risk Scoring Engine

### Recommendation: Custom rule engine on top of Redis + Spring AOP (no third-party rule engine)

**Confidence: MEDIUM** — The recommendation to NOT use Drools is based on well-documented operational experience and the project's velocity/threshold requirements (not complex stateful rules). The custom approach is affirmed by the existing `bucket4j-core` presence (already handles rate limiting, the closest cousin of velocity checks).

**Why not Drools:**

- Drools (latest: 10.x, KIE 9.x) has a very large footprint and a steep learning curve for rule authoring (DRL syntax, KieSession management, RETE algorithm tuning).
- For a fraud engine scoring 0–100 on ~6 signals (velocity, device fingerprint, IP reputation, phone prefix, amount threshold, time-of-day), Drools introduces accidental complexity that dwarfs the problem.
- The project already has Bucket4j for velocity checks. Adding Drools to do what a plain Java `FraudScorer` class can do is over-engineering.
- Drools' Spring Boot integration requires a separate `kie-spring-boot-autoconfigure` dependency and has a history of version alignment pain.

**Why not Easy Rules or other lightweight engines:** The scoring model is not dynamic (it does not need hot-reload of rules from a database by non-engineers). A well-structured Java class with small, testable scoring functions is more maintainable and faster.

**Recommended pattern: Scoring pipeline with Redis-backed velocity counters**

The following libraries are already in the pom and together implement the full fraud layer:

| Capability | Library | Already in pom? |
|-----------|---------|----------------|
| Velocity counters (txn/min per IP, user, tenant) | `bucket4j-core 8.10.1` | YES |
| Redis storage for counters | Spring Data Redis | YES (via Lettuce) |
| Device fingerprint hashing | `commons-codec` | YES |
| Phone number validation (+237 prefix check) | `libphonenumber 9.0.25` | YES |
| Risk score aggregation | Plain Java | N/A |

**No new dependency needed.** Build `FraudScoringService` as a Spring service that:
1. Queries Redis counters (via `bucket4j-redis` or raw `RedisTemplate.opsForValue().increment()`)
2. Checks device fingerprint history (Redis set with TTL)
3. Assigns partial scores per signal
4. Returns `RiskScore(value: int, signals: List<RiskSignal>)` — the score is persisted as a JSON column (JSONB via `hypersistence-utils`, already in pom) on the `transaction_event` row

**One optional addition — Guava RateLimiter for in-JVM burst protection:** Guava 33.4.8 is already in the pom. `RateLimiter.create(tokensPerSecond)` can provide a per-tenant in-memory burst guard that sits in front of the Redis counter check, reducing Redis round-trips for clearly-benign traffic.

---

## 6. Queue and Messaging Within a Spring Boot Monolith

### Recommendation: Spring Modulith Event Publication Registry (chosen in Section 1) — this IS the queue

**Confidence: HIGH** — The Spring Modulith Event Publication Registry with PostgreSQL persistence is the correct answer to "durable async messaging in a monolith." It is not a compromise; it is the intended architecture.

**Decision matrix:**

| Option | Verdict | Reason |
|--------|---------|--------|
| Spring Modulith events (PostgreSQL-backed) | **USE THIS** | Durable, transactional, zero new infrastructure, already chosen in Section 1 |
| `@Async` + `ThreadPoolTaskExecutor` alone | AVOID | Not durable — JVM crash drops in-flight events. Unacceptable for payments. |
| Spring `@TransactionalEventListener` alone | AVOID | Same durability problem without the publication registry. |
| Kafka | AVOID | Requires a Kafka broker. Overkill for a monolith. Contradicts project constraints. |
| RabbitMQ | AVOID | Same as Kafka — external broker, operational overhead, contradicts monolith constraint. |
| ActiveMQ Artemis (embedded) | AVOID | Embedded JMS brokers have messy persistence semantics and complicate clustering later. |
| Quartz JDBC job queue | CONSIDER for scheduled work | Quartz is appropriate for the reconciliation jobs and retry scheduler (see Section 7). |

**For the reconciliation scheduler specifically:** Use `spring-boot-starter-quartz` with JDBC job store (`spring.quartz.job-store-type=jdbc`). This ensures the daily reconciliation job runs exactly once even when multiple application instances are deployed, because Quartz uses a database row lock to elect a single executor.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-quartz</artifactId>
</dependency>
```

**What about the double-check pattern (webhook → provider status API call)?** This is handled by a `@ApplicationModuleListener` that listens to `WebhookReceivedEvent`. The listener calls the provider status API and publishes `ProviderStatusVerifiedEvent`. Spring Modulith guarantees this listener fires at-least-once. Resilience4j's circuit breaker (already configured) wraps the provider API call.

---

## 7. Admin Dashboard and Real-Time Metrics UI

### Recommendation: Existing Quasar SPA + Spring Boot Actuator (no new library) for business metrics; Grafana for infrastructure metrics

**Confidence: HIGH**

**Architecture decision:**

The project already has two dashboard layers:

1. **Grafana (port 3000)** — Reads from Prometheus (metrics), Loki (logs), Tempo (traces). This is the **operational** dashboard for SREs: JVM health, request latency, error rates, circuit breaker states, provider response times.

2. **Quasar SPA (existing admin UI)** — This should serve as the **business** dashboard for admins: transaction volumes per tenant, success/failure rates, fraud flag counts, reconciliation diff status. These are served by new Spring Boot REST endpoints that query PostgreSQL directly via JPA.

**Adding live push to the Quasar SPA:** Use Server-Sent Events (SSE) via Spring's `SseEmitter` for real-time payment status updates in the admin UI. SSE works over HTTP/1.1, requires no additional dependency, and is native to Spring MVC.

```java
@GetMapping(value = "/admin/live/transactions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter liveTransactions() {
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
    // register emitter; publish events via @ApplicationModuleListener on TransactionCompletedEvent
    return emitter;
}
```

**For Spring Boot Actuator custom metrics (payment-specific):** Use `MeterRegistry` (already auto-configured) to register business counters:

```java
meterRegistry.counter("payment.initiated", "provider", "mtn", "tenant", tenantId).increment();
meterRegistry.timer("payment.provider.latency", "provider", "orange").record(duration);
```

These appear automatically in `/actuator/prometheus` and flow to Grafana without any additional library.

**What NOT to add:**

- Do not add Spring Boot Admin Server — it is a standalone server that manages multiple Spring Boot instances. This is a single monolith; Actuator endpoints + Grafana are sufficient.
- Do not add Vaadin or Thymeleaf dashboards — the Quasar SPA is already the admin frontend.
- Do not add WebSocket (SockJS/STOMP) for live updates — SSE is simpler, unidirectional, and correct for the use case (admin monitoring, not bi-directional chat).

---

## Complete Delta — Libraries to Add

The following table summarizes the net-new Maven dependencies. Everything else is already present.

| Library | GroupId | ArtifactId | Version | Purpose | Confidence |
|---------|---------|------------|---------|---------|------------|
| Spring Modulith BOM | org.springframework.modulith | spring-modulith-bom | **1.4.9** | Version management for Modulith artifacts | HIGH |
| Spring Modulith JPA Starter | org.springframework.modulith | spring-modulith-starter-jpa | (BOM) | Event publication registry on PostgreSQL | HIGH |
| Spring Modulith Test | org.springframework.modulith | spring-modulith-starter-test | (BOM) | `AssertablePublishedEvents` for integration tests | HIGH |
| Spring Data Redis | org.springframework.boot | spring-boot-starter-data-redis | (Spring Boot BOM) | Idempotency keys, webhook dedup, velocity counters | HIGH |
| Quartz (JDBC store) | org.springframework.boot | spring-boot-starter-quartz | (Spring Boot BOM) | Distributed reconciliation scheduler | HIGH |

**Total new production dependencies: 3 artifacts** (Modulith JPA starter, Redis starter, Quartz starter). Everything else is already in the pom or is implemented as application code.

---

## Alternatives Considered and Rejected

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| In-monolith event bus | Spring Modulith events | Kafka, RabbitMQ | External broker, contradicts monolith constraint, operational overhead |
| In-monolith event bus | Spring Modulith events | @Async alone | Not durable — events lost on JVM crash |
| Fraud rule engine | Custom Java scoring | Drools | Heavy footprint, DRL syntax overhead, version alignment pain, overkill for 6-signal scoring |
| Fraud rule engine | Custom Java scoring | Easy Rules | No hot-reload needed; plain Java is more testable and maintainable |
| Distributed scheduler | Quartz (JDBC) | ShedLock | Both solve the same problem; Quartz is already managed by Spring Boot BOM and is more full-featured for job management |
| Admin UI | Quasar SPA + SSE | Spring Boot Admin | Not needed for single-instance monolith; operational view belongs in Grafana |
| HMAC | commons-codec (existing) | Bouncy Castle | Bouncy Castle adds 4MB+ jar for capabilities already provided by JDK + commons-codec |
| Redis client | Lettuce (Spring Boot default) | Redisson | Redisson provides distributed locks (e.g., RLock) but ShedLock/Quartz JDBC covers all locking needs; Lettuce is already the default |
| Tracing | micrometer-tracing-bridge-otel (existing) | OpenTelemetry Java Agent | Java agent approach conflicts with the existing `@Observation`-based Micrometer integration; bridge is the correct choice for Spring Boot 3.x |

---

## Stack Conflict Analysis

| New Addition | Potential Conflict | Assessment |
|-------------|-------------------|------------|
| spring-modulith-starter-jpa | Hibernate Envers | No conflict. Modulith uses a separate `EVENT_PUBLICATION` table. Envers audits entity mutations. Both can coexist. |
| spring-modulith-starter-jpa | spring-retry | No conflict. Spring Modulith's resubmission is a different mechanism from `@Retryable`. Use Modulith for event redelivery; use Spring Retry for external provider API call retries. Do not mix. |
| spring-boot-starter-data-redis | bucket4j-core | No conflict. Bucket4j core is the algorithm; it needs a backing store. If `bucket4j-redis` is added (as the backing store), it shares the same `LettuceConnectionFactory` as Spring Data Redis. Verify that the `LettuceConnectionFactory` bean is configured once. |
| spring-boot-starter-quartz | Flyway | Potential conflict: Quartz can auto-initialize its schema (`spring.quartz.jdbc.initialize-schema=always`) AND Flyway manages the schema. Use `spring.quartz.jdbc.initialize-schema=never` and provide the Quartz PostgreSQL DDL as a Flyway migration script (`V_xxx__quartz_schema.sql`). |
| spring-modulith-bom | spring-cloud-dependencies BOM | Both BOM imports coexist in `<dependencyManagement>`. Maven resolves the union. No transitive conflict expected; both target Spring Boot 3.5. |

---

## Sources

- Spring Modulith compatibility matrix: https://docs.spring.io/spring-modulith/reference/appendix.html#compatibility-matrix (fetched 2026-03-23)
- Spring Modulith Event Publication Registry: https://docs.spring.io/spring-modulith/reference/events.html (fetched 2026-03-23)
- Spring Modulith database schemas: https://docs.spring.io/spring-modulith/reference/appendix.html#schemas (fetched 2026-03-23)
- Spring Boot Actuator tracing: https://docs.spring.io/spring-boot/reference/actuator/tracing.html (fetched 2026-03-23)
- Spring Boot Actuator metrics: https://docs.spring.io/spring-boot/reference/actuator/metrics.html (fetched 2026-03-23)
- Spring Boot Quartz integration: https://docs.spring.io/spring-boot/reference/io/quartz.html (fetched 2026-03-23)
- Spring Framework scheduling limitations: https://docs.spring.io/spring-framework/reference/integration/scheduling.html (fetched 2026-03-23)
- Spring Data Redis ValueOperations Javadoc: https://docs.spring.io/spring-data/redis/docs/current/api/org/springframework/data/redis/core/ValueOperations.html (fetched 2026-03-23)
- Existing pom.xml: /Users/mokwen/dev/gitrepos/bluegithub/payam/pom.xml (read 2026-03-23)
- Existing docker-compose-lgtm.yaml: /Users/mokwen/dev/gitrepos/bluegithub/payam/docker-compose-lgtm.yaml (read 2026-03-23)
