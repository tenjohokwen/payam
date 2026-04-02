# Feature Landscape

**Domain:** Multi-tenant API gateway — Tenant & API Key Management subsystem (v5 milestone)
**Researched:** 2026-04-02
**Confidence:** HIGH — core patterns verified against Stripe, Zuplo, and industry documentation; spec validated against tenant-management.md and PROJECT.md

---

## Scope Note

This document covers the **v5 Tenant & API Key Management milestone** specifically. It is an addendum to the
original FEATURES.md (researched 2026-03-23), which covers the broader payment gateway feature landscape.
The question being answered: what does production-quality tenant/key management look like, what is table stakes
vs differentiator, and what is the expected behavior and UX for each feature in the spec?

---

## Industry Reference Points

Before classifying features, it is worth anchoring on what mature API platforms (Stripe, Twilio, Apigee) do
for tenant/key management. This prevents gold-plating things users will never notice and missing things
they will absolutely notice.

### Stripe Key Model (HIGH confidence — verified against Stripe docs)

- Two keys per environment (live/test): one publishable, one secret.
- Live-mode secret keys: one-time display only; cannot be revealed again.
- Sandbox keys: can be revealed repeatedly (lower risk; no real money).
- Rotation: create new key, optionally set delayed expiry on old one for grace period.
- Key statuses: Active, Expired (admin-set date passed), Compromised (immediate revoke candidate).
- No hard constraint of "one ACTIVE key per environment" — Stripe allows multiple active keys.

### Twilio / similar payment-adjacent platforms (MEDIUM confidence)

- Environment scoping (test vs. live) is universal; PROD/DEV/SANDBOX variants of this are common.
- Key prefix from account name or product type is common (humanizes opaque UUIDs).
- Webhook signing secrets: stored hashed server-side, shown once or reveal-on-demand with MFA prompt.
- Audit logs for every key lifecycle event: who did what, when.

### Payam v5 Design Position

Payam's spec makes **stronger** constraints than Stripe: one ACTIVE key per env per tenant; 24-hour automated
ROTATED → REVOKED job; suspension kills all keys immediately. These are all appropriate tightenings for a
payment gateway where operators manage tenants they may not fully trust, and where an exposed key in
a regulated environment has immediate fraud consequences.

---

## Table Stakes

Features operators and tenants will expect before trusting the platform. Missing any of these makes the
admin system feel unsafe or incomplete.

### Tenant Lifecycle

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Tenant create with name + email + status | Core identity record; every multi-tenant system starts here | LOW | Name mandatory, email optional (per spec); TenantRef UUID generated on create |
| Tenant ACTIVE / SUSPENDED status toggle | Operators need to disable a merchant without deleting them | LOW | Suspension must be atomic: all keys revoked in same transaction |
| Tenant name and email editable by admin | Contact info changes; name typos must be correctable | LOW | Name change does NOT alter key prefix — prefix is frozen at creation |
| Tenant list and search in admin UI | Operators manage many tenants; manual lookup is not acceptable | MEDIUM | Filterable by status; searchable by name/email/ref |

### API Key Lifecycle

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Key generation with one-time raw display | Industry standard for any key with hash-only storage (Stripe, GitHub, Supabase) | MEDIUM | Raw key shown exactly once in modal; backend stores only hash; user must copy before closing |
| Hash-only storage (never store plaintext) | Database breach must not leak usable keys; non-negotiable security property | LOW | bcrypt or SHA-256; spec says SHA-256; either works for API key verification |
| Key revocation | Immediate invalidation on compromise or tenant offboarding | LOW | Status → REVOKED; authentication filter rejects immediately |
| Key status visible to admin | Admin must know what state each key is in per env | LOW | Show ACTIVE / ROTATED / REVOKED per environment row in UI |
| Key tied to environment (PROD/DEV/SANDBOX) | Developers must be able to test without touching production data (Stripe pattern) | MEDIUM | One row per env in key management table; env displayed in UI |

### Key Rotation

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Rotate key without downtime | Live systems cannot have a hard cutoff between old and new key | MEDIUM | Old key enters ROTATED state; both ACTIVE and ROTATED keys accepted during grace window |
| Grace period for ROTATED keys | Clients may be deployed in multiple places; instant revocation causes outages | MEDIUM | 24 hours is tight but appropriate for a controlled B2B context (not a public API with unknown consumers) |
| Automated ROTATED → REVOKED after grace period | Human follow-up is error-prone; automation closes the window consistently | MEDIUM | Quartz job; already in stack; runs on schedule |
| One ACTIVE key per env per tenant | Prevents confusion about which key is current; forces clean rotation flow | LOW | DB constraint + service enforcement; rotation creates new ACTIVE, old goes ROTATED |

### Suspension / Reactivation

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Suspension revokes all keys immediately (all envs) | A suspended tenant must not be able to process payments through any key | MEDIUM | Atomic update: tenant.status = SUSPENDED + all keys.status = REVOKED in same transaction |
| Reactivation auto-generates new PROD key | Operator should not need a separate "create key" step after reactivating a tenant | MEDIUM | Auto-generate on status transition SUSPENDED → ACTIVE; show raw key in same response/modal as the status toggle |
| Admin shown new PROD key on reactivation | One-time display applies here too; the admin must see the key because it cannot be retrieved again | MEDIUM | Not just a background operation — admin UI must surface the generated key prominently |

### Webhook Secret

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| WebhookSecret per tenant | Every platform that delivers signed webhooks needs a per-client signing secret (Stripe, Twilio) | LOW | UUID generated at tenant creation; stored hashed or encrypted |
| Admin reveal (eye icon) | Support staff must be able to share the secret with a tenant who lost it | LOW | Reveal endpoint returns plaintext secret; requires storing recoverable (encrypted, not hashed) |
| Admin regenerate | Secret rotation on compromise or periodic policy | LOW | Generates new UUID; old signature on in-flight webhooks immediately invalid |

### Email Notifications

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Email on key generation/rotation | Operator and tenant need to know a new key was issued (security audit trail) | LOW | To platform notification email AND tenant email if present |
| Email on key revocation | Unexpected revocation is a security event; both parties must be informed | LOW | Same dual-recipient pattern |
| Email on tenant status change | Suspension/reactivation has commercial impact; tenant must be notified | LOW | Sent to tenant email + platform notification email |
| Email on webhookUrl change | A changed webhook URL is a potential SSRF vector; platform notification required | LOW | Platform notification email especially |
| Email on tenant email change | Email change could be an account takeover signal; audit notification required | LOW | Send to both old and new email addresses ideally; at minimum platform notification |
| Email on webhook secret regeneration | Secret change means existing HMAC signatures will fail; tenant must re-configure | LOW | Tenant email + platform email |

### Audit Trail

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Hibernate Envers on tenant and key tables | All financial platforms require immutable audit logs; state changes must be traceable | MEDIUM | Already in stack; apply @Audited to TenantEntity and ApiKeyEntity |
| Admin ID + timestamp on every key event | "Who generated this key?" is a common support and compliance question | LOW | Columns on key generation events; Envers revision metadata captures this |

---

## Differentiators

Features in the spec that go beyond what most platforms offer. These are worth implementing correctly because
they become trust signals.

### Immutable Key Prefix Tied to Tenant Identity

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| API key prefix derived from tenant name at creation | Admin can identify which tenant a key belongs to without a database lookup | LOW | First 3 chars of name, uppercase, 0-padded; frozen at creation even if name changes |

Most platforms use opaque UUIDs or environment-type prefixes (sk_live_, sk_test_). Payam's tenant-name prefix
is more useful for operational debugging: seeing `GOO_` in a log line immediately tells a support engineer
which tenant the request came from. The immutability (even on name change) is correct: changing the prefix
mid-life would invalidate all existing key identifications in logs.

### Tighter Active-Key Constraint Than Stripe

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| Hard constraint: one ACTIVE key per env per tenant | Eliminates ambiguity about which key is current; reduces support load | LOW | DB unique partial index on (tenant_id, environment) WHERE status = 'ACTIVE' |

Stripe allows multiple active keys. For a B2B payment gateway where the operator manages tenants (not
developers managing their own keys), the simpler constraint is better. It prevents operators from accidentally
leaving stale keys active on tenants.

### Suspension → Reactivation as a Complete Flow (Not Two Separate Operations)

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| Reactivation auto-generates PROD key and shows it in same UX action | Reduces operator error (no "forgot to create key after reactivating") | MEDIUM | Status toggle and key generation happen in same service transaction; one modal shows result |

In most platforms, key generation is a separate action from account activation. Coupling them here is
appropriate because the previous PROD key was permanently revoked during suspension — there is guaranteed to
be no live PROD key after a suspension cycle.

### 24-Hour Automated ROTATED → REVOKED Job

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| Automated grace-period closure without operator action | Operators cannot be relied upon to manually close rotation windows | MEDIUM | Quartz JDBC job; idempotent; runs every hour or on a fixed schedule |

Industry guidance (Zuplo, AWS Secrets Manager) treats 24 hours as a reasonable internal-application grace
period. External/public APIs typically need 7–30 days. Payam's tenants are controlled B2B partners, not
anonymous developers, so 24 hours is defensible and tighter than the industry default.

---

## Anti-Features

Features that seem natural to add but should be explicitly excluded from this milestone.

| Anti-Feature | Why Skip | What to Do Instead |
|---|---|---|
| Key-level permission scopes (read-only key, write-only key) | Adds complexity without a clear need in the current spec; Payam keys authenticate tenants, not individual operations; ACL is at the tenant level | Stay with single-scope per-env keys; revisit if a tenant requests read-only reporting key |
| Self-service key management for tenant users | The spec is admin-driven; tenant users do not have accounts in Payam's admin system | Admin manages all keys on behalf of tenants; keep the portal admin-only in v5 |
| Key expiration dates (calendar-based TTL) | Different from grace-period rotation; adds a "when does this expire?" question that operators will not be able to answer for tenants | Use rotation + revocation as the expiry mechanism; no calendar expiry needed |
| Multiple ROTATED keys in flight simultaneously | If admin rotates again before 24h, should there be two ROTATED keys? No — re-rotation should revoke the previous ROTATED immediately | On rotate: if a ROTATED key already exists for this env, move it to REVOKED first before creating the new ACTIVE |
| Webhook secret as HMAC-verifiable per-request signature | Webhook secret in this system is the shared secret given to the tenant for them to verify inbound Payam-signed webhooks — not an auth header on tenant-to-Payam requests | Keep webhook secret in its documented role: tenant uses it to verify Payam webhook signatures |
| Audit log search UI in v5 | Hibernate Envers tables exist but building a queryable audit log viewer is a separate feature worth its own phase | Capture the data now; UI can come in a later milestone when compliance requirements drive it |
| Email templates with full HTML design | Current email module uses transactional templates; over-designing tenant management emails in v5 adds scope | Use the existing transactional email pattern; keep templates consistent with existing notifications |
| Bulk tenant operations (suspend 50 tenants at once) | No spec requirement; adds UI and transactional complexity | Single-tenant operations cover all stated requirements |

---

## Feature Dependencies

```
Tenant Create
  ├── generates: TenantRef (UUID, immutable)
  ├── generates: key prefix (first-3-chars, immutable)
  └── generates: WebhookSecret (UUID)
        └── requires: secure storage (encrypted, not hashed — must be revealable)

Tenant ACTIVE status
  └── enables: API key generation per environment
        └── requires: one-time raw key display modal in UI
              └── requires: hash-only storage (backend)

Key Rotation
  ├── requires: one ACTIVE key to rotate (cannot rotate REVOKED)
  ├── creates: new ACTIVE key (one-time display)
  ├── moves: old key to ROTATED (24h grace)
  └── requires: Quartz job (ROTATED → REVOKED after 24h)
        └── requires: Quartz JDBC job store (already in stack)

Tenant Suspension
  ├── requires: atomic bulk key revocation (all envs)
  └── triggers: email notification (tenant + platform email)

Tenant Reactivation (SUSPENDED → ACTIVE)
  ├── requires: auto key generation for PROD env
  ├── requires: one-time display of new PROD key in same UI action as status toggle
  └── triggers: email notification (tenant + platform email)

Email Notifications (all 6 events)
  └── requires: platform notification email in application config (already established pattern)
  └── requires: tenant email field on tenant record (optional, conditional send)

Audit (Hibernate Envers)
  └── requires: @Audited on TenantEntity and ApiKeyEntity
  └── requires: admin ID captured per key generation event (additional column, not just Envers metadata)
```

---

## Admin UX Flow Notes

These describe the expected admin experience. The UI must surface these flows correctly or the security
properties of the system are undermined.

### Tenant Create Flow

1. Admin fills: name, optional email, optional webhookUrl.
2. On submit: backend generates TenantRef (UUID), derives key prefix (first 3 chars of name), generates WebhookSecret.
3. Response shows: a modal with the generated PROD API key (raw, one-time display) + the webhook secret.
4. Modal has prominent copy buttons for both secrets. "I have copied these safely" confirm button.
5. After confirm: key and secret are gone from UI forever. Only hash/encrypted form remains in DB.

**Critical:** Do not close the modal without user acknowledgment. The Mastodon webhook UI bug (2024) showed
that allowing webhooks to be active before the secret is copied causes immediate failures. Apply the same
caution here.

### Key Rotation Flow (per environment)

1. Admin clicks "Rotate" on the ACTIVE key for a given environment.
2. Confirmation prompt: "This will put the current key in a 24-hour grace period. The new key must be deployed before 24 hours."
3. On confirm: new ACTIVE key generated, old key moves to ROTATED with `rotatedAt` timestamp, one-time display modal for new key.
4. UI shows environment row with: new ACTIVE key (masked except prefix), "ROTATED — expires in Xh" indicator for old key.
5. After 24h: Quartz job moves ROTATED → REVOKED; environment row shows only the ACTIVE key.

**Note on re-rotation during grace period:** If admin rotates again before the 24h window closes, the still-ROTATED
key must be moved to REVOKED immediately. Two overlapping grace periods on the same env should not exist.

### Manual Revocation Flow

1. Admin clicks "Revoke" on any key.
2. Confirmation prompt (irreversible action warning).
3. Key immediately moves to REVOKED. No grace period for manual revocation.
4. If revoking the only ACTIVE key for PROD: prompt admin to generate a new one (tenant will be unable to process payments otherwise).

### Suspension Flow

1. Admin clicks "Suspend Tenant" on tenant detail page.
2. Confirmation prompt: "This will immediately revoke all API keys across all environments. The tenant cannot process payments until reactivated."
3. On confirm: tenant.status = SUSPENDED, all keys.status = REVOKED in single transaction.
4. Email sent to tenant (if email present) + platform notification email.
5. UI shows tenant as SUSPENDED with red badge; all environment key rows show REVOKED.

### Reactivation Flow

1. Admin clicks "Reactivate Tenant" on SUSPENDED tenant.
2. On confirm: tenant.status = ACTIVE, new PROD key auto-generated.
3. One-time display modal shows new PROD key (same UX as create flow).
4. Email sent to tenant + platform notification email.
5. Admin must copy the new PROD key and deliver it to the tenant (out-of-band, as with any initial key delivery).

**This is the most complex UX flow.** The modal that surfaces the new PROD key is non-optional — without it,
the admin has activated a tenant but has no way to give them credentials to work with.

### WebhookSecret Reveal / Regenerate

1. Eye icon next to masked webhook secret field.
2. Click: reveals plaintext UUID in the field (no separate endpoint needed for reveal if stored encrypted).
3. "Regenerate" button: confirmation prompt, then new UUID generated, old secret immediately invalid, new secret shown in revealed state.
4. Email notification sent on regeneration (tenant must know to update their webhook verification code).

---

## MVP Scope for v5 Milestone

All features in the "Table Stakes" section are required for v5. The differentiators are already in spec.
The anti-features define the explicit scope boundary.

### Must Ship in v5

- [ ] Tenant create with TenantRef + key prefix generation
- [ ] Tenant ACTIVE / SUSPENDED status toggle
- [ ] Tenant name + email + webhookUrl edit
- [ ] Per-environment API key generation (one-time display, hash-only storage)
- [ ] One ACTIVE key per env constraint
- [ ] Key rotation with 24h ROTATED grace period
- [ ] Quartz job: ROTATED → REVOKED after 24h
- [ ] Manual key revocation
- [ ] Suspension: atomic revoke all keys
- [ ] Reactivation: auto-generate new PROD key + show to admin
- [ ] WebhookSecret: generate, reveal, regenerate
- [ ] Email notifications: all 6 event types (dual recipient: tenant + platform)
- [ ] Hibernate Envers audit on tenant + key tables
- [ ] Admin ID + timestamp on key generation events
- [ ] Admin UI screens: tenant list, tenant detail, key management per env

### Explicitly Deferred

- [ ] Audit log viewer UI — data captured by Envers; UI is future milestone
- [ ] Key permission scopes — not in spec; not needed in v5
- [ ] Self-service tenant portal — admin-managed only in v5
- [ ] DEV and SANDBOX key auto-generation on tenant create — spec generates only PROD key on create/reactivation; DEV/SANDBOX keys are admin-on-demand

---

## Complexity Assessment

| Feature Area | Complexity | Reason |
|---|---|---|
| Tenant CRUD + status toggle | LOW | Standard JPA entity CRUD; no unusual logic |
| Key prefix generation | LOW | String manipulation at creation; frozen in column |
| One-time display + hash-only storage | MEDIUM | Flow requires careful coordination between service, response, and UI modal |
| One ACTIVE key per env constraint | LOW | Partial unique index + service check |
| Key rotation with ROTATED state | MEDIUM | State machine: ACTIVE → ROTATED (old) + new ACTIVE; one-time display |
| Quartz ROTATED → REVOKED job | MEDIUM | Idempotent Quartz job; edge case: re-rotation during grace period |
| Suspension atomic revoke | MEDIUM | Bulk update + transactional integrity required |
| Reactivation with auto PROD key | MEDIUM | Status change + key gen + one-time display in single coordinated response |
| WebhookSecret reveal | LOW-MEDIUM | Requires encrypted storage (not hashed) for revealability; decrypt on reveal |
| Email notifications (6 events) | LOW | Existing email module + transactional event listeners; pattern established |
| Hibernate Envers audit | LOW | @Audited annotation + verify schema migration generates revision tables |
| Admin UI screens | MEDIUM-HIGH | Multiple screens: tenant list, detail, key management per env, modals |

**Overall milestone complexity:** MEDIUM-HIGH. No single feature is technically novel, but the coordination
between atomic state transitions, one-time display modals, dual-recipient email, and audit capture across
all flows requires careful implementation discipline.

---

## Sources

- Stripe API key documentation: [https://docs.stripe.com/keys](https://docs.stripe.com/keys) [HIGH confidence — fetched 2026-04-02]
- Zuplo API key lifecycle guide: [https://zuplo.com/learning-center/api-key-rotation-lifecycle-management](https://zuplo.com/learning-center/api-key-rotation-lifecycle-management) [HIGH confidence — fetched 2026-04-02]
- OneUptime API key management best practices 2026: [https://oneuptime.com/blog/post/2026-02-20-api-key-management-best-practices/view](https://oneuptime.com/blog/post/2026-02-20-api-key-management-best-practices/view) [MEDIUM]
- Octopus Deploy — hashing API keys: [https://octopus.com/blog/hashing-api-keys](https://octopus.com/blog/hashing-api-keys) [MEDIUM]
- FreeCodeCamp — best practices for building secure API keys: [https://www.freecodecamp.org/news/best-practices-for-building-api-keys-97c26eabfea9/](https://www.freecodecamp.org/news/best-practices-for-building-api-keys-97c26eabfea9/) [MEDIUM]
- Mastodon webhook UI bug (secret timing issue): [https://github.com/mastodon/mastodon/issues/30498](https://github.com/mastodon/mastodon/issues/30498) [MEDIUM — informs "acknowledge before close" modal pattern]
- Project tenant-management.md: `/requirements/tenant-management.md` [HIGH — source of truth for spec]
- Project PROJECT.md: `/.planning/PROJECT.md` [HIGH — source of truth for existing features]

---

*Feature research for: Payam v5 — Tenant & API Key Management milestone*
*Researched: 2026-04-02*
