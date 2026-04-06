# Phase 29: Quartz Rotation Cleanup Job - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-06
**Phase:** 29-quartz-rotation-cleanup-job
**Areas discussed:** revokedAt timestamp, Envers audit capture, Operational logging

---

## revokedAt timestamp

| Option | Description | Selected |
|--------|-------------|----------|
| Skip it | Just flip keyStatus to REVOKED. Consistent with existing revoke() and revokeAllActiveAndRotatedByTenantId — neither sets a timestamp. AbstractAuditingEntity.updatedAt records when the row last changed. | ✓ |
| Add revokedAt column | Add revokedAt Instant field to TenantApiKey, V21 Flyway migration, and set it in the job. Makes the revocation moment explicitly queryable. | |

**User's choice:** Skip it
**Notes:** Consistent with the existing revocation paths; no migration needed.

---

## Envers audit capture

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — entity-level save | Load each expired key, call setKeyStatus(REVOKED), save. Envers writes a revision row for each one. Fully honors AUDIT-02. | ✓ |
| No — bulk @Modifying JPQL | One UPDATE WHERE status=ROTATED AND rotatedAt < cutoff. Fast, no Envers capture. Consistent with revokeAllActiveAndRotatedByTenantId. | |

**User's choice:** Yes — entity-level save
**Notes:** Fully honors AUDIT-02 ("all changes to API key states captured by Envers"). The batch is expected to be small.

---

## Operational logging

| Option | Description | Selected |
|--------|-------------|----------|
| Scan count + revoked count | One structured log line per run with expiredCount, one summary with revokedCount. Consistent with WebhookDeliveryJob. | ✓ |
| Per-key detail | Log a line for each revoked key: tenantId, environment, keyPrefix. More verbose. | |

**User's choice:** Scan count + revoked count
**Notes:** Zero noise when nothing to revoke; consistent with existing job logging pattern.

---

## Claude's Discretion

- Package placement (tenant/service + tenant/config)
- Repository query design (findExpiredRotatedKeys JPQL)
- Integration test approach (seed old rotatedAt via JDBC, direct service call)
- Job/trigger identity strings
