# Phase 29: Quartz Rotation Cleanup Job - Context

**Gathered:** 2026-04-06
**Status:** Ready for planning

<domain>
## Phase Boundary

Add a Quartz scheduled job (`RotatedKeyCleanupJob`) that runs every 5 minutes and auto-expires API keys in ROTATED status whose 24-hour grace period has elapsed. The job queries `TenantApiKey` for rows with `status = ROTATED` and `rotatedAt < now() - 24h`, loads each entity, sets `keyStatus = REVOKED`, and saves — triggering Hibernate Envers audit capture per key. No new columns, no Flyway migration.

Scope is the job + its scheduler config + integration test. No REST endpoints, no UI changes.

</domain>

<decisions>
## Implementation Decisions

### revokedAt timestamp
- **D-01:** Do NOT add a `revokedAt` column or Flyway migration. Just flip `keyStatus` to `REVOKED`. Consistent with the existing `revoke()` and `revokeAllActiveAndRotatedByTenantId` paths — neither sets a dedicated timestamp. `AbstractAuditingEntity.lastModifiedDate` (`updated_at`) records when the row last changed, which is sufficient.

### Envers audit capture
- **D-02:** Use entity-level load + `setKeyStatus(REVOKED)` + `save` (NOT a bulk `@Modifying` JPQL). Each auto-revocation must produce a Hibernate Envers revision row to fully honor AUDIT-02 ("all changes to API key states are captured by Hibernate Envers"). The batch is expected to be small (keys expire once per 24h per rotation event), so individual saves are acceptable.

### Operational logging
- **D-03:** Log at the job scan level only — one structured log line per run with `expiredCount` (how many keys were found), and one line after processing with `revokedCount`. Use `kv()` structured args (project standard). Consistent with `WebhookDeliveryJob` pattern. Log nothing when `expiredCount = 0`.

### Claude's Discretion
- Package placement: `tenant/service/RotatedKeyCleanupJob.java` + `tenant/config/RotatedKeyCleanupSchedulerConfig.java` (consistent with `webhook/` and `reconciliation/` package structure)
- Repository query: new `findExpiredRotatedKeys(Instant cutoff)` JPQL in `TenantApiKeyRepository` (returns `List<TenantApiKey>` where `status = ROTATED AND rotatedAt < :cutoff`)
- Job schedule: `SimpleScheduleBuilder.repeatMinutelyForever(5)` (consistent with `WebhookSchedulerConfig` approach for frequent jobs)
- Integration test approach: seed a `TenantApiKey` with `rotatedAt` set to `Instant.now().minusSeconds(86500)` via JDBC (25h ago), call `ApiKeyService.revokeExpiredRotatedKeys()` directly (Quartz prevented from auto-firing via `spring.quartz.properties`), assert `keyStatus = REVOKED`; also assert a key with `rotatedAt = 1h ago` is untouched

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` §AKEY — AKEY-05 defines the requirement; AKEY-04/AKEY-08 provide rotation/grace-period context

### Existing Quartz job patterns (replicate these)
- `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java` — canonical Quartz job structure: `extends QuartzJobBean`, `@Transactional executeInternal`, `@Autowired` service, `kv()` logging
- `src/main/java/com/softropic/payam/webhook/config/WebhookSchedulerConfig.java` — canonical scheduler config: `JobDetail` + `Trigger` beans, `SimpleScheduleBuilder.repeatMinutelyForever(N)`
- `src/main/java/com/softropic/payam/reconciliation/config/ReconciliationSchedulerConfig.java` — alternate pattern using `CronScheduleBuilder` (reference only)

### Domain model
- `src/main/java/com/softropic/payam/tenant/repo/TenantApiKey.java` — entity with `rotatedAt` field and `keyStatus` enum; no `revokedAt` (D-01)
- `src/main/java/com/softropic/payam/tenant/repo/TenantApiKeyRepository.java` — existing JPQL queries; add `findExpiredRotatedKeys(@Param("cutoff") Instant cutoff)`
- `src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java` — `GRACE_PERIOD = Duration.ofHours(24)` constant already defined here; reuse it

### Integration test patterns
- `src/test/java/com/softropic/payam/reconciliation/ReconciliationJobIT.java` — canonical IT for Quartz jobs: `@SpringBootTest`, Quartz prevented via properties, direct service call
- `src/test/java/com/softropic/payam/tenant/TenantServiceIT.java` — tenant/key test infrastructure (DbUtils, TestDataCleaner, seeding patterns)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ApiKeyService.GRACE_PERIOD` — `Duration.ofHours(24)` already declared; pass `Instant.now().minus(GRACE_PERIOD)` as the cutoff to the new repository query
- `TenantApiKeyRepository` — add one new `@Query` method `findExpiredRotatedKeys(Instant cutoff)` returning `List<TenantApiKey>`
- `QuartzJobBean` base class — Spring's adapter; `executeInternal(JobExecutionContext)` is the override point

### Established Patterns
- All Quartz jobs: `@Component` class extending `QuartzJobBean`, `@Autowired` service (no constructor injection in jobs — Quartz instantiates them)
- Scheduler config: `@Configuration` with `@Bean JobDetail` + `@Bean Trigger`; `JobBuilder.newJob(...).withIdentity(...).storeDurably().build()`
- Logging: structured `kv()` args from `net.logstash.logback.argument.StructuredArguments`

### Integration Points
- New `ApiKeyService.revokeExpiredRotatedKeys()` method — job delegates to service (not repository directly), consistent with `WebhookDeliveryJob → WebhookDeliveryService`
- Envers audit tables: `tenant_api_key_aud` in `main` schema — entity-level save will produce a revision row automatically via `@Audited` on `TenantApiKey`

</code_context>

<specifics>
## Specific Ideas

- The job name/identity string should be `"rotatedKeyCleanupJob"` (camelCase, consistent with `"webhookDeliveryJob"` and `"reconciliationJob"`)
- Trigger identity: `"rotatedKeyCleanupTrigger"`
- Schedule: every 5 minutes via `SimpleScheduleBuilder.repeatMinutelyForever(5)`

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 29-quartz-rotation-cleanup-job*
*Context gathered: 2026-04-06*
