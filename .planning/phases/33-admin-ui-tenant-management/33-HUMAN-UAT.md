---
status: partial
phase: 33-admin-ui-tenant-management
source: [33-VERIFICATION.md]
started: 2026-04-09T00:00:00Z
updated: 2026-04-09T00:00:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. Tenant List — Visual Rendering and Filtering
expected: Tenants item appears with group icon between Transactions and Reconciliation; page shows paginated table with Name/Ref/Email/Status/Created columns; status chips color-coded (green ACTIVE, red SUSPENDED); status filter works; empty state shows "No tenants found"
result: [pending]

### 2. Row Click Navigation
expected: Click any tenant row → browser navigates to /admin/tenants/:tenantRef; detail page loads with tenant data in all three edit fields and key table populated
result: [pending]

### 3. Per-Field Save with Loading State
expected: Modify Name field, click "Update Name" → button shows loading spinner during request; success toast appears; no page reload
result: [pending]

### 4. Status Toggle Flow
expected: On ACTIVE tenant click Suspend → confirmation dialog → status changes to SUSPENDED, keys show REVOKED. Click Reactivate → confirmation dialog → OneTimeKeyModal opens with raw key
result: [pending]

### 5. OneTimeKeyModal Gate
expected: When modal open: clicking outside, pressing Escape, clicking Done without checkbox all fail to dismiss; Done button disabled until checkbox checked; after checking, Done closes modal
result: [pending]

### 6. Key Table Actions — Rotate/Revoke/Reactivate/Generate
expected: Rotate and Generate open OneTimeKeyModal with new raw key; Revoke and key-level Reactivate show success toast only (no modal); key table updates after each action
result: [pending]

### 7. Webhook Secret Reveal and Auto-Mask
expected: First click reveals secret in monospace with countdown; second click immediately re-masks; waiting 30s auto-masks
result: [pending]

## Summary

total: 7
passed: 0
issues: 0
pending: 7
skipped: 0
blocked: 0

## Gaps
