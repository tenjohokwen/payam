# Feature Landscape: Client Disbursement API (v10)

**Domain:** B2B disbursement/payout API — mobile money (MTN MoMo + Orange Money), Cameroon
**Researched:** 2026-04-24
**Confidence:** HIGH — cross-verified against MTN MoMo docs, PawaPay implementation guide, Stripe payout patterns, and the existing codebase requirements document

---

## Context: What Already Exists

This is a subsequent milestone on an established platform. The following are **NOT features to build** — they are already production-grade and must be reused, not recreated:

| Capability | Existing Location | Reuse Pattern |
|---|---|---|
| Idempotency (Redis NX + Postgres fallback) | `IdempotencyKeyRepository` | Same header, same semantics, new `tenantId:disbursementId` namespace |
| Fraud engine (velocity + risk scoring) | `FraudScoringService` | Invoke with adjusted disbursement signal weights |
| API key auth + tenant isolation | `ApiKeyAuthenticationFilter` / `TenantPrincipal` | Zero changes needed |
| Outbound webhook delivery (HMAC, retry, backoff) | `WebhookDeliveryService` | Add `disbursement.completed` / `disbursement.failed` event types |
| Event sourcing log (hash-chained `PaymentEventLog`) | `PaymentEventLog` | Extend enum, same append-only write path |
| Ledger DISBURSEMENT 3-entry flow | `LedgerService` + `LedgerPosting` | Fully implemented in v9; `LedgerPosting.disbursement()` is ready |
| Fee evaluation | `FeeEvaluationService` | Unchanged |
| Double-check webhook pattern | `WebhookDoubleCheckHandler` pattern | Replicate for disbursement callback controllers |
| Reconciliation runner | `ReconciliationProviderRunner` | Extend to include disbursement transactions |
| Structured logging + tracing | MDC enrichment pipeline | Add `disbursement_id` to enrichment; hash MSISDN in logs |
| MTN transfer client | `MtnMoMoClient.transfer()` + `fetchDisbursementToken()` | Wire through new orchestrator — client is complete |
| Orange IC2C client | `OrangeMoneyClient.ic2cTransfer()` | Wire through new orchestrator |
| MSISDN routing | `MsisdnPrefixRoute` | Unchanged |

---

## Table Stakes

Features tenant integrators **must** have. Missing = product is not shippable.

| Feature | Why Non-Negotiable | Complexity | Existing Dependencies |
|---|---|---|---|
| `POST /v1/disbursements` — single payout initiation | The core contract; without it the milestone has no value | Low (schema + orchestrator) | `MtnMoMoClient.transfer()`, `OrangeMoneyClient.ic2cTransfer()` both ready |
| `GET /v1/disbursements/{id}` — status query | Callers need to poll when webhook delivery is uncertain; Cameroon network instability makes this mandatory, not optional | Low (repo lookup + tenant scope guard) | `DisbursementRepository` (new) |
| 202 Accepted + async webhook delivery | Both MTN and Orange are async-first; a synchronous response model does not match provider reality | Low (existing webhook pipeline) | `WebhookDeliveryService` already handles retry/backoff |
| Idempotency-Key header enforcement | Cameroon networks cause automatic retries at the HTTP layer; without this, double-sends happen in production and are irreversible | Low (existing Redis NX pattern) | `IdempotencyKeyRepository` — zero new code, new namespace only |
| Pre-funded balance gate (`MERCHANT_WALLET`) | Money leaves the platform; sending to provider before reserving funds creates a race condition that drains wallets | Medium (atomic check-and-reserve with optimistic lock or SELECT FOR UPDATE) | `LedgerService` DISBURSEMENT flow ready; needs wallet balance query + lock |
| MSISDN routing (MTN vs Orange by prefix) | Single API — callers should never choose a provider; routing table already handles collections | Low | `MsisdnPrefixRoute` — zero changes |
| Tenant-scoped disbursement isolation | Tenant A must not see or affect Tenant B's disbursements or balances | Low (existing `TenantContext` pattern) | `ApiKeyAuthenticationFilter` already enforces this |
| Disbursement-specific fraud velocity rules | Disbursements carry higher risk than collections; collection thresholds are too lenient for outbound money transfers | Medium (new velocity counter dimensions in Redis) | `FraudScoringService` — add signal weights + disbursement-specific thresholds |
| `disbursement.completed` + `disbursement.failed` outbound webhooks | Tenants build async workflows on top of this; polling alone is not acceptable for production integrations | Low (extend existing event types) | `WebhookDeliveryService` — add event type, same pipeline |
| FAILED state balance reversal | If a disbursement fails after funds are reserved, the reservation must be released atomically to prevent wallet lockup | Medium (reversal ledger entries — pattern proven in `LedgerService`) | V9 DISBURSEMENT ledger flow includes reversal specification |
| MTN disbursement callback controller | MTN fires async callback; without it, every result requires polling fallback alone | Medium (new controller; pattern cloned from collection callback) | IP whitelist + HMAC + double-check pattern established |
| Orange disbursement callback controller | Orange IC2C fires callback to `notifUrl`; without it, same issue | Medium (new controller) | Same pattern as MTN |
| Polling fallback (Quartz, 5-min trigger) | Callbacks are not guaranteed in Cameroon's high-latency mobile networks; polling is non-optional for production reliability | Medium (extend existing Quartz poller pattern) | Existing Quartz scheduler + Resilience4j circuit breaker |
| E2E test coverage (both providers, happy path + failure + idempotency) | `mvn verify` must pass before every commit — this is a non-negotiable platform invariant | High (Testcontainers + WireMock stubs for both providers) | E2E infrastructure exists; WireMock, Testcontainers, builders all ready |

---

## Differentiators

Features that add measurable value for tenant developers or ops teams. Not expected by default, but meaningfully better than the baseline when included.

| Feature | Value Proposition | Complexity | When to Add |
|---|---|---|---|
| Disbursement-specific ops metrics (success rate, callback latency, fraud block rate, balance reservation failures) | Ops teams catch degraded disbursement performance before tenants notice; Micrometer counters are near-zero incremental cost | Low (new Micrometer gauges/counters + dashboard panel extension) | Include in same phase as basic instrumentation — trivial to add at write time |
| Per-MSISDN daily limit (block after 10 disbursements/day to same recipient) | Prevents concentrated cashout fraud and social-engineering attacks documented in Cameroon mobile money literature | Low (new Redis counter dimension — velocity counter infrastructure already exists) | Include in initial fraud rules; minimal incremental effort |
| SHA-256 hashed MSISDN in structured logs | Logs remain usable for debugging and correlation without storing raw phone numbers; satisfies data minimization requirements | Low (one-line SHA-256 at log write site) | Implement from day one; retrofitting later requires log pipeline migration |
| `EXPIRED` terminal state (polling timeout ~10 min) + ops alert | Distinguishes "provider delayed" from "provider failed" — important for manual investigation in Cameroon's high-latency environment | Low (one extra state enum value + one alert rule) | Include in state machine design from start |
| `failureReason` field in `disbursement.failed` webhook payload | Tenant developers can programmatically distinguish `RECIPIENT_ACCOUNT_BLOCKED` from `PROVIDER_UNAVAILABLE` and display correct UX to their end users | Low (enum field on terminal event) | Include from day one; same webhook payload construction effort |
| Configurable per-tenant daily disbursement cap (XAF amount) | High-volume tenants need higher limits; low-trust new tenants need lower limits; admin configures without deployment | Medium (new column on tenant or platform config + admin UI) | Add in v10; missing this requires deployments to change limits |
| `metadata` passthrough on disbursement (2KB arbitrary key-value) | Tenants store internal references (employeeId, invoice number) without building a separate tracking system | Low (JSONB column, already used on payments) | Include in initial schema; zero incremental cost |
| `providerTransactionId` returned in status response + webhook | Tenants can cross-reference with MTN/Orange statements for their own reconciliation | Low (map from provider response on callback) | Include in response DTO from day one |

---

## Anti-Features

Features that add implementation complexity without proportional value at this stage. Build the explicit ability to add them later — do not build them now.

| Anti-Feature | Why Avoid Now | What to Do Instead |
|---|---|---|
| Batch disbursement endpoint (`POST /v1/disbursements/batch`) | Batch APIs multiply error handling surface area: partial success states, per-item idempotency, rollback semantics, progress tracking. PawaPay's implementation guide explicitly notes this is contentious — single-entity APIs with client-side looping are safer for v1. | Build single-item API with conservative rate limits (20/min per tenant); tenants loop with their own backoff. If batch demand is confirmed by multiple tenants, add in v11 with a proper job-tracking API. |
| Two-step approval workflow (initiate + confirm for large amounts) | The disbursement-request.md Open Question #1 explicitly deferred this. Approval workflows require a stateful pending-approval entity, admin UI flows, email notifications, timeout semantics, and new security principals. High complexity for an unconfirmed use case. | Enforce the fraud-block threshold (>500K XAF raises score; >80 score = FRAUD_BLOCK) as a compensating control. Add approval gates as a separate phase only if tenants explicitly request them. |
| Manual reversal endpoint (`POST /v1/disbursements/{id}/reverse`) | Neither MTN nor Orange exposes a native reversal API. The PROJECT.md out-of-scope section explicitly excludes this. Any "reversal" is itself a new disbursement in the opposite direction requiring bilateral agreement and separate provider support. | Document that FAILED disbursements release the balance reservation atomically (already implemented). True reversals of SUCCESSFUL disbursements are a future feature with its own spec. |
| Recipient KYC verification beyond active-subscriber check | MTN MoMo provides an account-validation endpoint, but Orange IC2C does not. Building KYC on top of a non-uniform provider API surface creates asymmetric behaviour and provider-specific code paths in the orchestrator. | Use existing `MobileMoneyPort.validateAccountHolder()` for active-subscriber check. Full KYC enrichment is a future differentiator if regulatory requirements emerge. |
| Webhook delivery manual re-trigger admin UI | Webhooks already retry with exponential backoff for 24 hours (5 retries). A manual re-trigger requires new API endpoints, security controls (who can trigger?), and risks duplicate delivery. | Document the backoff schedule clearly. If tenants need re-delivery after the retry window, they call `GET /v1/disbursements/{id}` and reconcile from the status response. Add manual re-trigger only if support cases accumulate. |
| Multi-currency support | XAF is the only currency in scope per the PROJECT.md constraints. Currency conversion requires exchange rate management, rounding logic, and provider-specific currency constraints — a major separate workstream. | Hard-validate `currency == "XAF"` at input; return 400 for anything else. Reserve the field name in the schema for future expansion. |
| SMS/push notification to disbursement recipient | Both MTN and Orange already send USSD push / SMS to the recipient on transfer. Payam sending an additional notification creates duplicates and requires out-of-band contact data management. | Do nothing. Providers handle recipient notification natively. |
| Disbursement schedule / recurring payouts | Cron-based salary runs require a scheduling engine, calendar management, and tenant UI — a product-level feature, not an API primitive. | Document that tenants can implement scheduling on their side by calling `POST /v1/disbursements` on a schedule. Add native scheduling in a future milestone if demand is confirmed. |

---

## Feature Dependencies

```
MERCHANT_WALLET balance gate
  → requires LedgerService DISBURSEMENT flow (v9 DONE)
  → requires atomic check-and-reserve (new: SELECT FOR UPDATE on wallet balance row)
  → balance reversal on FAILED → requires balance gate to be implemented first

DisbursementOrchestrator
  → requires balance gate
  → requires MsisdnPrefixRoute (existing, unchanged)
  → requires FraudScoringService with disbursement signal weights (extends existing)
  → requires MtnMoMoPort.transfer() wired (new thin port wrapper around existing client)
  → requires OrangeMoneyPort.ic2cDisbursement() wired (new thin port wrapper)

Callback controllers (MTN + Orange)
  → require DisbursementOrchestrator state transitions to be defined
  → require double-check pattern (replicate from collection callback controller)
  → require balance reversal on FAILED state

Outbound webhooks
  → require callback controllers (terminal state triggers webhook enqueue)
  → require existing WebhookDeliveryService (extend event types only)

E2E tests
  → require all above to be complete
  → require WireMock stubs for MTN disbursement + Orange IC2C (new stubs)
  → require LedgerVerifier.assertDisbursementLedgerBalanced() (v9 DONE)

Polling fallback
  → requires DisbursementOrchestrator transaction entity queryable by providerReferenceId
  → requires Quartz job extension (replicate collection poller pattern)
```

---

## MVP Recommendation

The v10 milestone ships as a single cohesive block covering all table-stakes features. There is no safe subset: the balance gate + reversal + idempotency form an atomic safety guarantee — shipping the API without any one of these creates irreversible financial risk.

**Minimum shippable set (all required together):**

1. `DisbursementOrchestrator` — idempotency check → fraud scoring → balance gate → MSISDN routing → recipient validation → fee computation → provider call → ledger posting → 202 response
2. `MtnDisbursementCallbackController` + `OrangeDisbursementCallbackController` (double-check, IP whitelist, replay protection)
3. `DisbursementResource`: `POST /v1/disbursements` + `GET /v1/disbursements/{id}`
4. Balance reservation + atomic reversal on FAILED
5. Disbursement-specific fraud velocity rules (stricter than collection thresholds)
6. `disbursement.completed` / `disbursement.failed` outbound webhook events
7. Polling fallback Quartz job
8. E2E tests (both providers: happy path + failure + idempotency race)

**Include in same milestone (low incremental cost, high value):**

- SHA-256 MSISDN hashing in logs — free to add at write time; expensive to retrofit after logs are in Loki
- `failureReason` on terminal webhook — same payload construction effort as omitting it
- `EXPIRED` terminal state — one enum value + one alert rule
- Per-tenant configurable daily disbursement cap — prevents hard-coded limits requiring deployments to change
- Disbursement Micrometer metrics on ops dashboard — same effort as not adding them

**Explicitly defer:**

- Batch endpoint — defer to v11 pending confirmed tenant demand
- Two-step approval — defer until tenants request it explicitly
- Reversal endpoint — out of scope (PROJECT.md); document compensating process
- Scheduling / recurring — out of scope for API layer; tenants implement on their side

---

## Sources

- MTN MoMo API: https://momo.mtn.com/api/
- PawaPay Implementation Considerations (verified via WebFetch): https://docs.pawapay.io/implementation
- Stripe Payout Reconciliation: https://docs.stripe.com/payouts/reconciliation
- Modern Treasury — Ledger API with Optimistic Locking: https://www.moderntreasury.com/journal/designing-ledgers-with-optimistic-locking
- Idempotency in Payment APIs (Brandur): https://brandur.org/http-transactions
- AfricaNenda — Network connectivity as primary barrier to digital payment adoption: https://www.africanenda.org/en/blog/2025/the-biggest-barrier-to-digital-payment-adoption-may-be-dropped-network-connections
- Talli — Evaluating Disbursement Vendors: https://www.talli.ai/blog/evaluate-disbursement-vendors
- Sourcery — Race Conditions in Financial Transaction Processing: https://www.sourcery.ai/vulnerabilities/race-condition-financial-transactions
- PawaPay Product Overview: https://www.pawapay.io/product-overview
- Orange Developer Portal: https://developer.orange.com/apis/om-webpay

---

*Feature research for: Payam v10 — Client Disbursement API*
*Researched: 2026-04-24*
