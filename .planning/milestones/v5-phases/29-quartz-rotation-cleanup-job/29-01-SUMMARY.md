---
plan: 29-01
phase: 29-quartz-rotation-cleanup-job
status: complete
completed: 2026-04-06
commits:
  - 671a121
  - c6c7946
key-files:
  created:
    - src/main/java/com/softropic/payam/tenant/service/RotatedKeyCleanupJob.java
    - src/main/java/com/softropic/payam/tenant/config/RotatedKeyCleanupSchedulerConfig.java
    - src/main/resources/db/migration/V21__rotated_at_timestamptz.sql
    - src/test/java/com/softropic/payam/tenant/RotatedKeyCleanupJobIT.java
  modified:
    - src/main/java/com/softropic/payam/tenant/repo/TenantApiKeyRepository.java
    - src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java
---

## What was built

Implemented AKEY-05: Quartz rotation cleanup job that automatically revokes ROTATED API keys after their 24-hour grace period expires.

- `findExpiredRotatedKeys(Instant cutoff)` JPQL query added to `TenantApiKeyRepository`
- `revokeExpiredRotatedKeys()` added to `ApiKeyService` — entity-level load+save (not bulk JPQL) so Hibernate Envers captures each auto-revocation as a separate audit revision
- `RotatedKeyCleanupJob` — extends `QuartzJobBean`, `@Autowired ApiKeyService`, logs only when keys are revoked (per D-03)
- `RotatedKeyCleanupSchedulerConfig` — `JobDetail` + `Trigger` beans, `repeatMinutelyForever(5)`
- `V21__rotated_at_timestamptz.sql` — migrates `rotated_at` from `TIMESTAMP` to `TIMESTAMPTZ` in both `tenant_api_key` and `tenant_api_key_aud`
- `RotatedKeyCleanupJobIT` — 4 integration tests all green

## Notable deviations

- **V21 Flyway migration added**: `rotated_at` changed from `TIMESTAMP` (no tz) to `TIMESTAMPTZ`. Root cause: Postgres session timezone was `Europe/Berlin` in Testcontainers (despite `connection-init-sql` in dev config not applying), causing Hibernate's `jdbc.time_zone=UTC` Instant parameters to compare against Berlin-local stored values. TIMESTAMPTZ eliminates the mismatch.
- **Test backdating via SQL interval**: `NOW() - INTERVAL '25 hours'` used instead of `Timestamp.from(Instant.now().minusSeconds(90000))` — avoids JVM timezone → Postgres TIMESTAMP storage inconsistency.

## Self-Check: PASSED

All 4 integration tests green: `revokeExpiredRotatedKeys_revokesOverdueKey`, `revokeExpiredRotatedKeys_leavesUnderGraceKeyUntouched`, `revokeExpiredRotatedKeys_isIdempotent_noOp`, `revokeExpiredRotatedKeys_createsEnversAuditRow`.
