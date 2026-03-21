# Technology Stack

**Analysis Date:** 2026-03-21

## Languages

**Primary:**
- Java 17 - Backend application server (`src/main/java/`)
- JavaScript (ES Module) - Frontend SPA (`src/frontend/src/`)

**Secondary:**
- SQL - Database migrations via Flyway (`src/main/resources/db/migration/`)
- YAML - Application configuration (`src/main/resources/application.yaml`, `application-dev.yaml`)
- HTML/Thymeleaf - Email templates (`src/main/resources/mails/`)
- SCSS - Frontend styles (`src/frontend/src/css/`)

## Runtime

**Environment:**
- JVM (Java 17) - Backend
- Node.js v22.16.0 - Frontend build (managed by `frontend-maven-plugin`)

**Package Manager:**
- Maven (wrapper: `mvnw`) - Backend and unified build
- npm 11.4.2 - Frontend (managed via Maven plugin, lockfile: `src/frontend/package-lock.json`)

## Frameworks

**Core Backend:**
- Spring Boot 3.5.11 - Application framework (`pom.xml`, parent POM)
- Spring Security - Authentication and authorization (`src/main/java/com/softropic/payam/security/`)
- Spring Data JPA / Hibernate 6 - ORM and persistence (`src/main/java/com/softropic/payam/*/repo/`)
- Spring Web MVC - REST API layer (`src/main/java/com/softropic/payam/security/api/`)
- Spring Boot Actuator - Health and metrics endpoints at `/manage`
- Spring Boot Mail - SMTP email sending
- Spring Cloud CircuitBreaker (Resilience4j) - Fault tolerance for email sending (`src/main/java/com/softropic/payam/email/service/MailManager.java`)
- Spring Retry - Retry logic for email delivery
- Spring Boot AOP - Aspect-oriented cross-cutting concerns

**Core Frontend:**
- Vue 3.5.22 - UI framework (`src/frontend/src/`)
- Quasar 2.16.0 - Vue component library and build system (`src/frontend/quasar.config.js`)
- Pinia 3.0.1 - State management (`src/frontend/src/stores/`)
- Vue Router 4 - SPA routing (`src/frontend/src/router/`)
- vue-i18n 11 - Internationalization with en-US and fr-FR (`src/frontend/src/i18n/`)

**Build/Dev:**
- Vite (via `@quasar/app-vite 2.1.0`) - Frontend bundler
- `frontend-maven-plugin 1.15.1` - Downloads Node/npm, builds frontend, copies built SPA into `target/classes/static/`
- MapStruct 1.6.3 - DTO/entity mapping code generation
- Lombok - Boilerplate reduction for Java classes

**Testing:**
- Spring Boot Test / JUnit 5 - Backend testing
- Testcontainers (PostgreSQL) - Integration tests with real DB
- Mockito - Mocking framework
- AssertJ 3.24.2 - Fluent assertions
- json-unit-assertj 4.1.0 - JSON assertion
- Awaitility 4.2.0 - Async test assertions
- Instancio 2.10.0 - Test data generation
- Guava TestLib 33.4.8 - Additional test utilities
- datasource-proxy / sql-table-name-parser - SQL query inspection in tests

## Key Dependencies

**Critical:**
- `spring-boot-starter-security` - Full custom JWT security stack with 2FA
- `io.jsonwebtoken:jjwt-api 0.13.0` - JWT creation and validation (`src/main/java/com/softropic/payam/security/infrastructure/jwt/`)
- `org.flywaydb:flyway-database-postgresql` - Schema versioning; migrations in `src/main/resources/db/migration/`
- `org.postgresql:postgresql` - JDBC driver (runtime)
- `com.zaxxer:HikariCP` - Connection pool (bundled with Spring Boot, configured via `spring.datasource.hikari`)

**Infrastructure:**
- `io.hypersistence:hypersistence-utils-hibernate-63 3.9.10` - Advanced Hibernate type mappings (JSON columns etc.)
- `com.vladmihalcea:hibernate-types-60 2.21.1` - Additional Hibernate type support
- `org.hibernate.orm:hibernate-envers 6.6.14.Final` - Audit trail via entity versioning
- `com.bucket4j:bucket4j-core 8.10.1` - Token-bucket rate limiting (`src/main/java/com/softropic/payam/security/api/ratelimit/`)
- `com.github.ua-parser:uap-java 1.6.1` - User-agent parsing for request metadata
- `com.googlecode.libphonenumber:libphonenumber 9.0.25` - Phone number validation (`src/main/java/com/softropic/payam/common/validation/`)
- `org.sqids:sqids 0.1.0` - Short unique ID generation
- `org.jasypt:jasypt 1.9.3` - Symmetric encryption utilities
- `commons-codec 1.19.0` - Encoding/hashing
- `org.apache.commons:commons-lang3 3.20.0` + `commons-text 1.13.1` - String utilities
- `commons-validator 1.9.0` - Input validation
- `com.google.guava 33.4.8-jre` - General utilities
- `net.logstash.logback:logstash-logback-encoder 8.1` - Structured JSON log output
- `com.github.loki4j:loki-logback-appender 1.6.0` - Log shipping to Grafana Loki
- `io.micrometer:micrometer-registry-prometheus` - Prometheus metrics export
- `io.micrometer:micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` - Distributed tracing via OpenTelemetry

**Frontend:**
- `axios 1.2.1` - HTTP client with interceptors for auth and loading state (`src/frontend/src/boot/axios.js`)
- `@rajesh896/broprint.js 2.2.0` - Browser fingerprinting for fraud detection
- `@quasar/extras 1.16.4` - Roboto font, Material Icons

## Configuration

**Environment:**
- Active profile selected at runtime (default, `dev`)
- `application.yaml` - Base config (production-oriented)
- `application-dev.yaml` - Development overrides (Testcontainers DB, relaxed actuator, `create-drop` DDL)
- Server port: `9990` (configurable via `${port}`)

**Required env vars (production):**
- `SPRING_MAIL_PASSWORD` - Primary SMTP password
- `GMX_PASSWORD` - GMX email provider password
- `GMAIL_PASSWORD` - Gmail provider password
- `MOMO_SUBSCRIPTION_KEY` - MTN MoMo API subscription key
- `LOKI_API_KEY` - Grafana Loki log shipping key

**Build:**
- `pom.xml` - Maven build; runs frontend build as part of `generate-resources` phase
- `src/frontend/quasar.config.js` - Vite/Quasar build config; router mode is `hash`
- Built frontend SPA copied to `target/classes/static/` and served as Spring Boot static resources
- `src/main/resources/config/logback-spring.xml` - Logback config (Loki + rolling file + console)

## Platform Requirements

**Development:**
- Java 17+
- Maven (or `./mvnw`) - handles Node/npm installation automatically
- PostgreSQL instance (or Docker via Testcontainers for tests)
- Optional: Docker Compose (`docker-compose-lgtm.yaml`) for local observability stack (Prometheus, Loki, Tempo, Grafana)

**Production:**
- JVM 17+, deployed as a Spring Boot fat JAR (`target/payam-*.jar`)
- PostgreSQL database
- SMTP relay (GMX, Gmail, or mail.de)
- Optional: LGTM observability stack (Prometheus on port 9090, Loki on 3100, Tempo on 3200/4317/4318, Grafana on 3000)

---

*Stack analysis: 2026-03-21*
