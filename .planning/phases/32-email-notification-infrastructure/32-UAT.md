---
status: complete
phase: 32-email-notification-infrastructure
source: [32-01-SUMMARY.md, 32-02-SUMMARY.md, 32-03-SUMMARY.md]
started: 2026-04-09T00:00:00.000Z
updated: 2026-04-09T00:01:00.000Z
---

## Current Test

[testing complete]

## Tests

### 1. API Key Reactivation — Happy Path
expected: Call POST /{tenantId}/keys/{keyId}/reactivate where keyId is a REVOKED key. Response is 204 No Content (empty body). Fetching the key afterward shows status = ACTIVE.
result: pass

### 2. Reactivation Guard — Non-REVOKED Key
expected: Call POST /{tenantId}/keys/{keyId}/reactivate where the key is ACTIVE or ROTATED (not REVOKED). Response is 409 Conflict. Key status is unchanged.
result: pass

### 3. Reactivation Guard — AKEY-02 Conflict
expected: Call POST /{tenantId}/keys/{keyId}/reactivate on a REVOKED key when another ACTIVE key already exists for the same tenant + environment. Response is 409 Conflict. Neither key's status changes.
result: pass

### 4. Reactivation — Not Found
expected: Call POST /{tenantId}/keys/{keyId}/reactivate with a keyId that does not exist. Response is 404 Not Found.
result: pass

### 5. Email Notification on API Key Operations
expected: |
  With a working mail provider configured:
  - Create a new tenant → GENERATED email arrives at tenant's address with key prefix in body
  - Rotate the key → ROTATED email arrives; body references the new key prefix
  - Revoke the key → REVOKED email arrives; no raw key value in body
  - Reactivate the key → REACTIVATED email arrives; no raw key value in body
  All emails render with inline CSS (no broken styles in email client).
result: pass

### 6. Email Notification on Tenant Status Changes
expected: |
  With a working mail provider configured:
  - Suspend a tenant → SUSPENDED email arrives with correct subject and body variant
  - Reactivate the tenant → REACTIVATED email arrives with correct subject and body variant
  - Regenerate webhook secret → webhook secret regenerated email arrives; no secret value in body
  All emails reach tenant's registered address. No crash or error logged.
result: pass

### 7. EMAIL_CHANGED Routing — Old Address Receives Notification
expected: |
  Update the email address of a tenant. The EMAIL_CHANGED notification email is delivered to the OLD email address (the one before the update), not the new one. The new address does NOT receive the notification.
result: pass

## Summary

total: 7
passed: 7
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none yet]
