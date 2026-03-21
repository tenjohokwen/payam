# External Integrations

**Analysis Date:** 2026-03-21

## APIs & External Services

**Mobile Money (Payments):**
- MTN MoMo (Mobile Money) - Collection API for payment processing
  - SDK/Client: Custom `AbstractClient` / `RestTemplate` (`src/main/java/com/softropic/payam/common/client/AbstractClient.java`)
  - Base URL: `https://sandbox.momodeveloper.mtn.com/collection` (sandbox only currently)
  - Auth: `MOMO_SUBSCRIPTION_KEY` env var, sent as `Ocp-Apim-Subscription-Key` header; OAuth2 token via `/collection/token/`
  - Endpoints configured in `application.yaml` under `client.momo.endpoints`:
    - `POST /collection/v1_0/requesttopay` - Initiate payment
    - `GET /collection/v1_0/requesttopay/{referenceId}` - Check transaction status
    - `GET /collection/v1_0/account/balance` - Account balance
    - `GET /collection/v1_0/accountholder/{type}/{id}/basicuserinfo` - User info
  - Configured payment providers enum: MTN, ORANGE, NEXTTEL (`src/main/java/com/softropic/payam/common/payment/MobilePaymentProvider.java`)

**Observability / Log Aggregation:**
- Grafana Loki - Log shipping
  - Endpoint: `https://logs-prod-012.grafana.net/loki/api/v1/push` (configured in `src/main/resources/config/logback-spring.xml`)
  - Auth: username `1350490`, password via `LOKI_API_KEY` env var
  - Client: `loki-logback-appender` (Loki4j)

## Data Storage

**Databases:**
- PostgreSQL (primary relational database)
  - Connection: `spring.datasource.url` = `jdbc:postgresql://localhost/payam?TimeZone=UTC`
  - Credentials: `spring.datasource.username` / `spring.datasource.password` (default: `postgres`/`postgres` for local/dev)
  - Client: Spring Data JPA + Hibernate 6 with HikariCP connection pool
  - Schema: `main` (Hibernate `default_schema`, Flyway `defaultSchema`)
  - Pool: HikariCP (`hikari-db-pool`), max 25 connections, auto-commit disabled
  - Migrations: Flyway (`src/main/resources/db/migration/`) - `baseline-on-migrate: true`
  - Audit: Hibernate Envers tracks entity history (`org.hibernate.envers.store_data_at_delete: true`)

**File Storage:**
- Local filesystem only - access logs written to `/usr/local/var/ledger/` and application logs to `/var/log/payam/`

**Caching:**
- Spring Cache (`spring-boot-starter-cache`) is on classpath; no explicit `CacheManager` bean or `@Cacheable` annotations found in current codebase. Effectively not yet wired.

## Authentication & Identity

**Auth Provider:**
- Custom (no external provider like Auth0/Keycloak)
  - Implementation: Full in-house JWT-based authentication stack
  - Password hashing: BCrypt (`BCryptPasswordEncoder`, 10 rounds) - `src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java`
  - JWT library: `jjwt 0.13.0` - `src/main/java/com/softropic/payam/security/infrastructure/jwt/`
  - Token transport: HttpOnly cookies (`JWT_COOKIE_NAME`, `USER_COOKIE`, `ADMIN_COOKIE`, `B_COOKIE`)
  - 2FA: `TwoFactorLoginService` + `SecondFactorLoginFilter` (`src/main/java/com/softropic/payam/security/service/TwoFactorLoginService.java`)
  - Rate limiting: Bucket4j token-bucket on auth endpoints (`src/main/java/com/softropic/payam/security/api/ratelimit/`)
  - Fraud detection: `FraudAwareAuthenticationManager` + browser fingerprint (`fcookie`) set by `@rajesh896/broprint.js` in frontend
  - Client whitelisting: `allowed.clients` config property, enforced by `ClientIdAccessDecisionManager`

## Email / Messaging

**Email Providers (SMTP):**
- GMX (`mail.gmx.net:587`) - Auth: `GMX_PASSWORD` env var; username: `blue-bone@gmx.de`
- Gmail (`smtp.gmail.com:587`) - Auth: `GMAIL_PASSWORD` env var; username: `enkap24@gmail.com`
- Mail.de (`smtp.mail.de:587`) - Auth: `MAIL_DE_PASSWORD` env var; username: `blue-bone@mail.de`
- Multi-provider config in `email.providerConfigs` array (`application.yaml`)
- All use STARTTLS
- Provider abstraction: `SenderProvider` / `MailSenderProvider` (`src/main/java/com/softropic/payam/email/infrastructure/MailSenderProvider.java`)
- Email templates (Thymeleaf HTML): `src/main/resources/mails/` - activation, password reset, OTP, profile change, duplicate creation warning
- Delivery reliability: Circuit Breaker (Resilience4j) + Spring Retry wrapping each send attempt (`src/main/java/com/softropic/payam/email/service/MailManager.java`)
- Delivery tracking: `EnvelopeEntity` persisted to DB with status SENT/FAILED and retry flag (`src/main/java/com/softropic/payam/email/repo/`)
- Retry scheduler: `EmailRetryScheduler` (`src/main/java/com/softropic/payam/email/infrastructure/EmailRetryScheduler.java`) retries failed deliveries

## Monitoring & Observability

**Metrics:**
- Prometheus via Micrometer (`micrometer-registry-prometheus`)
  - Exposed at `/manage/actuator/prometheus` (all endpoints exposed in `application.yaml`, restricted to `health,info,env` in `application-dev.yaml`)
  - Scraped by local Prometheus container (config: `prometheus.yml`, port 9090)

**Distributed Tracing:**
- OpenTelemetry via Micrometer tracing bridge (`micrometer-tracing-bridge-otel`)
  - Exporter: OTLP HTTP to `http://localhost:4318/v1/traces`
  - Sampling: 100% (`probability: 1.0`)
  - Tempo container receives traces (port 4318, config: `tempo.yml`)
  - Trace IDs injected into structured log output via MDC

**Logs:**
- Structured JSON logs (Logstash encoder) shipped to Grafana Loki Cloud (`logs-prod-012.grafana.net`)
- Rolling file appender to `/var/log/payam/spring.log` (10MB / 30 days / 1GB total cap)
- Tomcat access log written to `/usr/local/var/ledger/payam_access.ledger` with custom pattern including request body length and user/session tracking fields
- Configuration: `src/main/resources/config/logback-spring.xml`

**Visualization:**
- Grafana (local Docker, port 3000) - connects to Prometheus, Loki, Tempo
- `docker-compose-lgtm.yaml` - spins up full LGTM stack locally

**Error Tracking:**
- None (no Sentry, Rollbar, etc. detected)

## CI/CD & Deployment

**Hosting:**
- Not detected (no Kubernetes manifests, Dockerfile, or cloud deployment configs found)

**CI Pipeline:**
- Not detected (no `.github/workflows/`, Jenkinsfile, or similar found)

## Webhooks & Callbacks

**Incoming:**
- None detected (no webhook receiver endpoints found)

**Outgoing:**
- None detected (MTN MoMo integration is outbound request/response, no webhook callbacks configured)

## Environment Configuration

**Required env vars:**
- `SPRING_MAIL_PASSWORD` - Primary Spring mail SMTP password
- `GMX_PASSWORD` - GMX SMTP password
- `GMAIL_PASSWORD` - Gmail SMTP password
- `MAIL_DE_PASSWORD` - Mail.de SMTP password
- `MOMO_SUBSCRIPTION_KEY` - MTN MoMo API subscription key
- `LOKI_API_KEY` - Grafana Loki log push API key

**Secrets location:**
- Environment variables only; no `.env` file committed; dev fallback defaults (`dev_*_password`) in `application-dev.yaml`

---

*Integration audit: 2026-03-21*
