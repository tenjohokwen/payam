# External Integrations

**Analysis Date:** 2026-03-06

## APIs & External Services

**Mobile Money Payments:**
- MTN MoMo (Mobile Money) API - Mobile payment collection (request-to-pay, balance, user info)
  - SDK/Client: Spring `RestTemplate` via `src/main/java/com/softropic/payam/common/client/AbstractClient.java`
  - Base URL: `https://sandbox.momodeveloper.mtn.com/collection` (sandbox)
  - Auth: `MOMO_SUBSCRIPTION_KEY` env var (header: `Ocp-Apim-Subscription-Key`)
  - Target environment header: `X-Target-Environment: sandbox`
  - Endpoints configured in `application.yaml` under `client.momo.endpoints`:
    - `POST /v1_0/requesttopay` - Initiate payment
    - `GET /v1_0/requesttopay/{referenceId}` - Check transaction status
    - `GET /v1_0/account/balance` - Get account balance
    - `GET /v1_0/accountholder/{type}/{id}/basicuserinfo` - Get user info
    - `POST /collection/token/` - Request auth token
  - Resilience: Circuit breaker via Resilience4j (`spring-cloud-starter-circuitbreaker-resilience4j`)

- ORANGE (enum value) - Mobile payment provider; configured in `src/main/java/com/softropic/payam/common/payment/MobilePaymentProvider.java` but no dedicated client detected
- NEXTTEL (enum value) - Mobile payment provider; same as above

## Data Storage

**Databases:**
- PostgreSQL
  - Connection URL: `jdbc:postgresql://localhost/payam?TimeZone=UTC`
  - Env vars: `spring.datasource.username` / `spring.datasource.password` (default: `postgres/postgres` in dev)
  - Client: HikariCP connection pool (`com.zaxxer.hikari`) with Spring Data JPA / Hibernate 6
  - Schema: `main` (set via `hibernate.default_schema` and `flyway.defaultSchema`)
  - Schema migrations: Flyway (`src/main/resources/db/` - no migration files currently present)
  - Pool config: max 25 connections, min 8 idle, auto-commit disabled
  - Audit history: Hibernate Envers (`org.hibernate.orm:hibernate-envers`) — entities tracked in `src/main/java/com/softropic/payam/common/persistence/AbstractAuditingEntity.java`
  - Test isolation: Testcontainers PostgreSQL (`src/test/java/com/softropic/payam/config/CustomPostgresContainer.java`)

**File Storage:**
- Not detected (no S3/GCS/Azure blob SDK present)

**Caching:**
- Spring Cache abstraction enabled (`spring-boot-starter-cache`); no external cache provider (Redis, Memcached) detected — defaults to in-memory `ConcurrentHashMap`

## Authentication & Identity

**Auth Provider:**
- Custom (self-contained JWT-based auth)
  - Implementation: Custom Spring Security filter chain (`src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java`)
  - JWT: `io.jsonwebtoken:jjwt` 0.13.0 — token creation/validation in `src/main/java/com/softropic/payam/security/jwt/api/`
  - Filters: `JWTAuthenticationFilter` (login), `JWTAuthorizationFilter` (request auth), `SecondFactorLoginFilter` (2FA), `SessionRefreshFilter` (token refresh)
  - Password encoding: BCrypt (`BCryptPasswordEncoder`)
  - 2FA: OTP-based second factor (`src/main/java/com/softropic/payam/security/manager/TwoFactorLoginManager.java`)
  - Fraud detection: `FraudAwareAuthenticationManager` wraps login with attempt tracking and lockout (`src/main/java/com/softropic/payam/security/manager/`)
  - Rate limiting: `bucket4j-core` via `@RateLimited` AOP annotation (`src/main/java/com/softropic/payam/security/api/ratelimit/`)
  - Client ID enforcement: `ClientIdAccessDecisionManager` validates `allowed.clients` list header per request
  - Browser fingerprint: `@rajesh896/broprint.js` sets `fcookie` cookie for fraud prevention (`src/frontend/src/boot/axios.js`)

## Email

**SMTP Providers (multi-provider with round-robin/fallback):**
- GMX (primary)
  - Host: `mail.gmx.net:587` (STARTTLS)
  - Username: configured in `application.yaml`
  - Auth env var: `GMX_PASSWORD`
- Gmail
  - Host: `smtp.gmail.com:587` (STARTTLS)
  - Auth env var: `GMAIL_PASSWORD`
- Mail.de (dev profile only)
  - Host: `smtp.mail.de:587`
  - Auth env var: `MAIL_DE_PASSWORD`
- Implementation: `src/main/java/com/softropic/payam/email/service/MailService.java` uses `MailSenderProvider` to select sender; templates rendered via Thymeleaf
- Email templates: `src/main/resources/mails/` (activation, password reset, OTP, profile change, duplicate account)
- Delivery tracking: `EnvelopeEntity` persisted to DB (`src/main/java/com/softropic/payam/email/persistence/entity/`)

## Monitoring & Observability

**Metrics:**
- Prometheus / Micrometer (`micrometer-registry-prometheus`) — metrics scraped from `/manage/actuator/prometheus`
- Spring Boot Actuator endpoint base: `/manage`; production exposes `health`, `info`, `env` (ROLE_ADMIN required for details)

**Logs:**
- Structured JSON via Logstash Logback Encoder (`net.logstash.logback:logstash-logback-encoder`)
- Grafana Loki push (`com.github.loki4j` Loki4j appender in `src/main/resources/config/logback-spring.xml`)
  - Endpoint: `https://logs-prod-012.grafana.net/loki/api/v1/push`
  - Auth env var: `LOKI_API_KEY` (username `1350490`)
- Rolling file appender: `/var/log/payam/spring.log` (10MB max, 30 day retention, 1GB cap)
- Tomcat access log: `/usr/local/var/ledger/payam_access.ledger`
- Immutable log component: custom auditing system (`immutable-log.*` config in `application.yaml`)

**Error Tracking:**
- No external error tracking service (Sentry, Rollbar, etc.) detected

## CI/CD & Deployment

**Hosting:**
- Not explicitly configured in codebase (no Dockerfile, k8s manifests, or cloud config detected)
- Artifact: Spring Boot fat JAR (Maven `spring-boot-maven-plugin`)
- Frontend SPA bundled into JAR static resources at build time

**CI Pipeline:**
- Not detected (no `.github/workflows`, `.gitlab-ci.yml`, etc.)

## Webhooks & Callbacks

**Incoming:**
- MTN MoMo webhook: not explicitly defined in source; the MoMo `requestToPay` flow typically requires a callback URL — not yet implemented or configured in the visible codebase

**Outgoing:**
- MTN MoMo `requestToPay` POST call from `client.momo.endpoints.requestToPay`

## Environment Configuration

**Required environment variables (all profiles):**
- `SPRING_MAIL_PASSWORD` - Primary SMTP password
- `GMX_PASSWORD` - GMX provider password
- `GMAIL_PASSWORD` - Gmail provider password
- `MOMO_SUBSCRIPTION_KEY` - MTN MoMo API key
- `LOKI_API_KEY` - Grafana Loki API key

**Dev-only optional vars (have fallback defaults in `application-dev.yaml`):**
- `MAIL_DE_PASSWORD` - Mail.de provider (dev only)

**Secrets location:**
- Environment variables (no `.env` file detected in repository; no secrets management service integration detected)

---

*Integration audit: 2026-03-06*
