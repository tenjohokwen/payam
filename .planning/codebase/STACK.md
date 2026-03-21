# Technology Stack

**Analysis Date:** 2026-03-06

## Languages

**Primary:**
- Java 17 - Backend application (`src/main/java/`)
- JavaScript (ES Modules) - Frontend SPA (`src/frontend/src/`)
- Vue 3 (`.vue` SFCs) - Frontend components (`src/frontend/src/components/`, `src/frontend/src/pages/`)

**Secondary:**
- SQL - Database migrations via Flyway (`src/main/resources/db/`)
- YAML - Spring configuration (`src/main/resources/application.yaml`, `src/main/resources/application-dev.yaml`)
- HTML (Thymeleaf) - Email templates (`src/main/resources/mails/`)

## Runtime

**Backend Environment:**
- JVM (Java 17+)
- Embedded Tomcat (via Spring Boot)
- Default port: `9990`

**Frontend Environment:**
- Node.js v22.16.0 (pinned in `pom.xml` via `frontend-maven-plugin`)
- npm 11.4.2

## Package Manager

**Backend:**
- Maven (wrapper at `mvnw` / `mvnw.cmd`)
- Lockfile: `pom.xml`

**Frontend:**
- npm
- Lockfile: `src/frontend/package-lock.json` (standard npm)
- Node engine requirement: `^20 || ^22 || ^24 || ^26 || ^28` (`src/frontend/package.json`)

## Frameworks

**Backend Core:**
- Spring Boot 3.5.11 - Application framework (`pom.xml`, parent BOM)
- Spring Cloud 2025.0.1 - Cloud features (Resilience4j circuit breaker)
- Spring Security 6 - Authentication and authorization (`src/main/java/com/softropic/payam/security/`)
- Spring Data JPA - ORM layer (`src/main/java/com/softropic/payam/common/persistence/`)
- Spring MVC (Web) - REST API
- Spring Mail - Email sending
- Spring Cache - Caching abstraction
- Spring AOP - Aspect-oriented programming (rate limiting, audit)
- Spring Actuator - Health and metrics endpoints at `/manage`
- Spring Retry - Retry logic for resilience
- Thymeleaf 6 - Email template rendering (`src/main/java/com/softropic/payam/email/service/MailService.java`)

**Frontend Core:**
- Vue 3.5.22 - UI framework
- Quasar 2.16.0 - Vue UI component library and build system (`src/frontend/quasar.config.js`)
- Vite (via `@quasar/app-vite`) - Dev server and bundler
- Vue Router 4 - Client-side routing (`src/frontend/src/router/`)
- Pinia 3 - State management (`src/frontend/src/stores/`)
- vue-i18n 11 - Internationalization; locales: en-US, fr-FR (`src/frontend/src/i18n/`)

**Testing:**
- Spring Boot Test + JUnit Jupiter - Unit/integration tests
- Testcontainers (PostgreSQL) - Database integration testing (`src/test/java/com/softropic/payam/config/CustomPostgresContainer.java`)
- Mockito - Mocking
- AssertJ 3.24.2 - Fluent assertions
- Instancio 2.10.0 - Test data generation
- Awaitility 4.2.0 - Async test assertions
- json-unit-assertj 4.1.0 - JSON assertions
- datasource-proxy 1.10 - SQL query recording in tests (`src/test/java/com/softropic/payam/utils/sql/`)
- Guava TestLib - Additional test utilities

**Build/Dev:**
- `frontend-maven-plugin` 1.15.1 - Installs Node/npm, runs Quasar build during Maven lifecycle
- Maven Failsafe Plugin - Integration test execution
- ESLint 9 + `eslint-plugin-vue` - Frontend linting (`src/frontend/eslint.config.js`)
- Prettier 3.3.3 - Frontend formatting
- `vite-plugin-checker` - ESLint in Vite dev server

## Key Dependencies

**Critical:**
- `spring-boot-starter-security` - JWT-based auth with custom filter chain
- `io.jsonwebtoken:jjwt-api` 0.13.0 - JWT creation and validation (`src/main/java/com/softropic/payam/security/jwt/`)
- `spring-cloud-starter-circuitbreaker-resilience4j` - Circuit breaker for external HTTP calls
- `org.flywaydb:flyway-core` + `flyway-database-postgresql` - Database schema migrations
- `org.postgresql:postgresql` - PostgreSQL JDBC driver
- `org.mapstruct:mapstruct` 1.6.3 - DTO/entity mapping (`src/main/java/com/softropic/payam/security/core/mapper/`)
- `org.projectlombok:lombok` - Boilerplate reduction
- `com.bucket4j:bucket4j-core` 8.10.1 - Token-bucket rate limiting (`src/main/java/com/softropic/payam/security/api/ratelimit/`)
- `axios` 1.2.1 - HTTP client for frontend API calls (`src/frontend/src/boot/axios.js`)

**Infrastructure:**
- `io.micrometer:micrometer-registry-prometheus` - Prometheus metrics export
- `net.logstash.logback:logstash-logback-encoder` 8.1 - JSON structured logging
- `org.hibernate.orm:hibernate-envers` 6.6.14 - Entity audit history
- `io.hypersistence:hypersistence-utils-hibernate-63` 3.9.10 - Advanced Hibernate utilities (JSON column types)
- `com.vladmihalcea:hibernate-types-60` 2.21.1 - Additional Hibernate type mappings
- `net.ttddyy:datasource-proxy` 1.10 - SQL statement interception
- `org.jasypt:jasypt` 1.9.3 - String encryption utility
- `com.googlecode.libphonenumber:libphonenumber` 9.0.25 - Phone number validation (`src/main/java/com/softropic/payam/common/util/PhoneNumberUtil.java`)
- `org.sqids:sqids` 0.1.0 - Short ID generation
- `com.github.ua-parser:uap-java` 1.6.1 - User-agent parsing
- `@rajesh896/broprint.js` 2.2.0 - Browser fingerprinting for fraud prevention (`src/frontend/src/boot/axios.js`)

## Configuration

**Environment Variables Required (production):**
- `SPRING_MAIL_PASSWORD` - Default SMTP mail password
- `GMX_PASSWORD` - GMX email provider password
- `GMAIL_PASSWORD` - Gmail email provider password
- `MAIL_DE_PASSWORD` - Mail.de email provider password
- `MOMO_SUBSCRIPTION_KEY` - MTN MoMo API subscription key
- `LOKI_API_KEY` - Grafana Loki logging API key
- `port` - Server port (defaults to 9990)

**Spring Profiles:**
- Default (`application.yaml`) - Production-oriented settings; Flyway `ddl-auto: none`
- `dev` (`application-dev.yaml`) - Development; `ddl-auto: create-drop`; relaxed Actuator exposure

**Build Configuration:**
- `pom.xml` - Maven build; frontend build integrated via `frontend-maven-plugin`
- `src/frontend/quasar.config.js` - Quasar/Vite build, dev proxy, plugins
- `src/main/resources/config/logback-spring.xml` - Logback configuration (console, file, Loki)

## Platform Requirements

**Development:**
- Java 17+
- Maven 3.x (or use `mvnw`)
- Node.js v22.16.0 (auto-installed by Maven build) or managed locally
- PostgreSQL (local or Docker container; `datasource.container: true` connects to test container config)

**Production:**
- Deployable as a fat JAR (Spring Boot executable)
- Frontend SPA is bundled into `target/classes/static/` and served by the embedded Tomcat
- Logs written to `/var/log/payam/spring.log` (rolling) and pushed to Grafana Loki
- Tomcat access logs written to `/usr/local/var/ledger/payam_access.ledger`
- PostgreSQL database required

---

*Stack analysis: 2026-03-06*
