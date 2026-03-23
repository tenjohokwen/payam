# Feature Landscape

**Domain:** Multi-tenant mobile money payment gateway wrapper (Cameroon — MTN MoMo + Orange Money)
**Researched:** 2026-03-23
**Confidence:** MEDIUM — competitor analysis draws on documented knowledge of Campay, Monetbil, and Notchpay APIs
as of mid-2025; no live documentation fetch was possible. Provider capabilities are HIGH confidence (verified
against project's own requirements documents).

---

## Methodology Note

Web search and WebFetch were unavailable in this environment. Competitor analysis (Campay, Monetbil, Notchpay)
is drawn from accumulated knowledge of their public APIs and developer documentation up to August 2025.
Claims marked [VERIFIED] were cross-checked against the project's provider documentation files.
Claims marked [MEDIUM] reflect well-documented public APIs that should be verified against current docs before
coding decisions are made. Claims marked [LOW] reflect observed community patterns with no direct verification.

---

## Context: What the Providers Actually Offer

Before defining features, it is essential to ground every decision in what the underlying providers expose,
because Payam can only offer what the providers support.

### MTN MoMo (Collections API) [VERIFIED]

| Capability | Available | Notes |
|---|---|---|
| Payment collection (RequestToPay) | Yes | Async, customer approves on device |
| Account holder validation | Yes | Active/inactive check only |
| Balance query | Yes | Merchant wallet balance |
| KYC / basic user info | Yes | Requires explicit customer consent |
| Pre-approval (recurring debit) | Yes | Customer pre-authorizes future debits |
| Refund | Via disbursement transfer | No native refund endpoint |
| Callback (webhook from MTN) | Yes | Sent once per transaction, no retry |
| B2B transfer (Remittance API) | Yes | Separate API product |

### Orange Money (Core API v1.0.2) [VERIFIED]

| Capability | Available | Notes |
|---|---|---|
| Merchant payment collection (/mp) | Yes | 3-step: init → pay → push/poll |
| Cashout (partner to customer) | Yes | Payout direction |
| C2C / Inverse C2C | Yes | Channel-to-channel, channel-to-customer |
| Agent cashout | Yes | Agent flow |
| Account holder validation (/infos/subscriber) | Yes | Returns subscriber name |
| Balance query | NO | No balance endpoint in v1.0.2 |
| Refund / reversal | NO | No reversal endpoint; back-office only |
| Webhook (notifUrl) | Yes | Posted by Orange on completion |
| Bulk transaction status | Yes | /transactions/paymentstatus |

**Critical constraint:** Orange Money provides no balance endpoint and no refund endpoint.
Any Payam feature that implies these must either be approximated (running balance from ledger)
or deferred to a higher API tier requiring additional Orange partner credentials.

---

## Competitor Analysis: Campay, Monetbil, Notchpay

### Campay [MEDIUM confidence]

Campay is a Cameroon-focused aggregator supporting MTN MoMo and Orange Money.

**What Campay offers:**
- Unified collection endpoint: single API call routes to MTN or Orange based on phone prefix
- Disbursement endpoint: pay out to mobile money numbers
- Transaction status polling endpoint
- Webhook delivery on completion (single attempt, no retry guarantee documented)
- Sandbox / test environment with test phone numbers
- API key authentication (username + password → access token pattern)
- Dashboard: transaction history, balance display, basic analytics
- Python, PHP, JavaScript SDKs

**What Campay lacks (observable gaps):**
- No idempotency key enforcement at the API level — developers must manage duplicate prevention themselves
- No per-webhook retry with exponential backoff — if your server is down, you miss the event
- No structured event log exposed to developers — no way to replay missed webhooks
- No per-client (multi-tenant) API key isolation — Campay is itself B2C, not a platform for building platforms
- No SDK for Java / Spring Boot
- No fraud scoring or risk API surface
- No fee management API — fees are fixed by Campay's pricing
- No sandbox that mirrors production failure modes (e.g., insufficient funds, user timeout)
- No programmatic reconciliation export (CSV download only in dashboard)

### Monetbil [MEDIUM confidence]

Monetbil is older, widely used in Francophone Africa, supports Cameroon operators.

**What Monetbil offers:**
- Payment widget / hosted checkout page (iframe or redirect)
- REST API for direct integration
- MTN MoMo + Orange Money + other operators
- Webhook on payment completion
- Transaction query endpoint
- Dashboard with transaction list and filters
- Multi-currency display (XAF primary)

**What Monetbil lacks (observable gaps):**
- API is primarily widget/redirect-oriented, not headless-first — integration pattern is different
  from what Cameroon mobile app developers want
- No idempotency guarantees documented
- No programmatic sandbox with controllable outcomes
- No multi-tenant features — one account, one merchant
- No structured error codes — error messages vary and are hard to handle programmatically
- No webhook signing / HMAC verification documented
- No refund API (same underlying provider constraint)
- No rate limit headers — developers cannot tell when they are approaching limits
- Documentation is primarily in French, with sparse English coverage

### Notchpay [MEDIUM confidence]

Notchpay is the most developer-forward of the three Cameroon aggregators, positioned closer to Stripe's DX.

**What Notchpay offers:**
- Unified charge API (MTN, Orange, plus card)
- Transfer / payout API
- Transaction status and list endpoints
- Webhook delivery with retry logic (documented)
- HTTPS webhook signature verification
- API key + secret model (public/private key pair)
- Sandbox environment
- Dashboard: transactions, analytics, settings
- SDKs: JavaScript, PHP, Python
- Refund endpoint (presumably a disbursement under the hood)
- Customer object model (store recurring customer references)
- Multi-currency support (XAF, USD, EUR)
- Detailed error codes

**What Notchpay lacks (observable gaps):**
- No multi-tenant / platform model — it is a direct merchant integration, not a gateway
  that lets you build your own payment product on top
- No per-client fee configuration
- No fraud/risk API surface exposed to integrators
- Java SDK absent
- No programmatic reconciliation report generation
- No account balance visibility at API level (provider constraint)
- No event replay for missed webhooks

### Gap Summary: What All Three Competitors Miss

| Gap | Why It Matters for Payam |
|---|---|
| No multi-tenant platform model | Payam's primary differentiator: you ARE the platform |
| No idempotency enforcement | Double charges are a real risk in Cameroon's unstable network |
| No webhook retry with replay | Missed webhooks cause manual reconciliation work |
| No per-client fee configuration | Platform operators need revenue management |
| No fraud/risk API | Operators have no programmatic protection |
| No Java/Spring SDK | Payam's existing stack has no ecosystem support |
| No structured reconciliation export | Finance teams are doing this manually |
| No immutable audit trail | Regulatory and dispute-resolution gap |
| No sandbox with failure modes | Developers cannot test unhappy paths |

---

## Table Stakes

These are features developers and operators will expect before integrating. Missing any of these
means the product feels incomplete and untrustworthy.

### Core Payment Features

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Unified payment initiation (MTN + Orange, one endpoint) | Every aggregator does this | Medium | Route by phone prefix (6X → MTN, 6[5/9] → Orange) |
| Provider auto-detection from MSISDN | Developers should not hardcode routing | Low | Prefix lookup table |
| Idempotency key enforcement on all write operations | Network instability in Cameroon is the norm, not exception | Medium | Redis-backed key store with TTL |
| Transaction status endpoint (GET by reference ID) | Developers need to poll when webhooks fail | Low | Core query capability |
| Bulk transaction status endpoint | Batch queries for reconciliation | Low | Provider already exposes this for Orange |
| Standardized status lifecycle: INITIATED → PROCESSING → SUCCESS / FAILED / TIMEOUT | Predictable states reduce integration bugs | Medium | State machine, no hidden transitions |
| Standardized error codes across providers | MTN and Orange have different error vocabularies | Medium | Error normalization layer |
| Account holder validation endpoint | Validate phone is active before charging | Low | Proxy to provider validate endpoints |
| Transaction listing with filters (date, status, provider, client) | Standard dashboard and API need | Medium | Paginated, filterable |
| Async payment confirmation model | Both providers are async; synchronous response is not possible | Medium | Webhook + polling fallback |

### Webhook / Notification Features

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Outbound webhook delivery to client's configured URL | Developers build event-driven systems | Medium | On final transaction state change |
| Webhook signing with HMAC-SHA256 | Without this, developers cannot verify webhook authenticity | Medium | Per-client secret used for signing |
| Webhook retry with exponential backoff | Provider webhooks have no retry; Payam must compensate | Medium | At least 3 retries over 24 hours |
| Webhook event log (queryable) | Developers need to replay missed events | Medium | Persisted, queryable by transaction ID |
| Webhook endpoint validation on registration | Catch bad URLs at registration, not at payment time | Low | HTTP HEAD or GET check on URL |
| Webhook delivery status (delivered, failed, retrying) | Developers need visibility into webhook health | Low | Expose in transaction detail response |

### Authentication and API Key Management

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| API key + secret pair per client | Standard payment API auth model | Low | Existing security module handles user management |
| Key rotation without downtime | Keys get compromised; rotation is mandatory | Medium | Grace period where old + new key both work |
| Key revocation | Immediate invalidation on compromise | Low | |
| Sandbox vs. production key separation | Developers need to test without real money | Medium | Environment flag per key |
| Per-client rate limiting | Protect the system from one noisy client | Medium | Redis sliding window counter |
| Rate limit headers in responses (X-RateLimit-*) | Developers need to know their limits | Low | Standard HTTP headers |

### Operator / Admin Dashboard Features

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Transaction list with real-time updates | Operators need live visibility | Medium | SSE or polling |
| Transaction detail view with full event timeline | Dispute resolution and debugging | Medium | Show every state transition with timestamps |
| Search by transaction ID, phone number, client | Core investigation tool | Low | |
| Client management (create, suspend, configure) | Multi-tenant admin core | Medium | |
| Per-client transaction history | Tenant isolation in UI | Low | |
| System health dashboard (provider latency, success rate, TPS) | Operational awareness | Medium | |
| Fee configuration per client | Revenue management | Low | |
| Alert configuration (failure rate thresholds, fraud spikes) | Operators should not monitor manually | Medium | |

### Reconciliation Features

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Internal ledger recording every credit and debit | Cannot rely solely on provider data | Medium | Double-entry, append-only |
| Daily reconciliation job (Payam ledger vs. provider reports) | Finance teams require this for accounting | Medium | Detect missing/mismatched transactions |
| Reconciliation report export (CSV/JSON) | Finance teams use this in spreadsheets | Low | |
| Discrepancy flagging with investigation workflow | Unresolved mismatches need operator action | Medium | Mark as disputed, assignable |

---

## Differentiators

Features that set Payam apart from Campay, Monetbil, and Notchpay. Not expected by default, but
become strong retention factors once experienced.

### Platform / Multi-Tenant Model

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| Multi-tenant API key isolation | Each client is a separate tenant with isolated data and keys | High | Payam's core value: BE the platform |
| Per-client sandbox environments | Clients can test without affecting production data | High | Sandbox transactions isolated per tenant |
| Per-client webhook configuration | Each tenant has its own callback URL and signing secret | Low | |
| Per-client fee rules (fixed fee, percentage, tiered) | Operators can monetize differently per client | Medium | Fee engine evaluated at transaction time |
| Client-facing API usage dashboard | Clients see their own transaction stats and webhook health | Medium | Scoped view of operator dashboard |

### Developer Experience

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| Consistent idempotency enforcement | Payam guarantees no double charge even if client retries | Medium | Already planned; competitors lack this |
| Replay missed webhooks on demand | GET /webhooks/{event_id}/replay | Low | Huge DX win; competitors have no equivalent |
| Human-readable error messages with error codes | Developers debug faster | Low | Enum-based error catalog |
| Structured sandbox with controllable outcomes | Test insufficient funds, user timeout, provider error | High | Simulate specific failure scenarios |
| Single integration point for future providers | Adding a third provider (Wave, Moov) requires no client changes | High | Abstract provider adapter pattern |

### Security and Auditability

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| Immutable event-sourced audit log with hash chain | Tamper-evident record; regulatory compliance | High | SHA-256 chaining; already in security architecture |
| Double-check pattern on provider webhooks | Never trust a provider webhook without verification | Medium | Already in security architecture |
| Fraud velocity rules per client | Operators can set transaction rate limits per tenant | Medium | Redis-backed rule evaluation |
| Risk scoring per transaction | Block or flag high-risk transactions before hitting provider | High | Score 0-100 based on signals |
| Device fingerprinting support | Associate transactions with device identifiers | Medium | Passed by client in request |

### Operational Excellence

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| Circuit breaker per provider | Graceful degradation when MTN or Orange is down | Medium | Stop hammering a failing provider |
| Provider health status endpoint | Clients can check if a provider is currently degraded | Low | Exposed as GET /providers/status |
| Estimated balance from ledger (Orange Money workaround) | Compensates for Orange's missing balance endpoint | Medium | Running balance computed from ledger; accuracy depends on completeness |
| Automatic polling fallback when webhook not received | If no webhook arrives within N minutes, poll provider | Medium | Scheduled job per pending transaction |

---

## Anti-Features

Things to deliberately NOT build in v1, with reasoning. These represent scope that would delay
delivery without proportional value, or that create maintenance burden before the core is proven.

| Anti-Feature | Why Skip in v1 | What to Do Instead |
|---|---|---|
| Native refund endpoint | Neither Orange (v1.0.2) nor MTN Collections expose a refund API. Implementing it requires disbursement credentials, a separate product approval with providers, and financial risk management. | Document the limitation clearly. Route refund requests to back-office workflow. Add to v2 roadmap once disbursement API access is confirmed. |
| Hosted payment page / checkout widget | Payam is an API-first product for developer integration, not a Monetbil-style widget. Building a hosted page doubles the surface area (web UI, CORS, CSP, redirect flows) with no benefit to the target developer audience. | Provide clear API docs and client SDKs. Merchants build their own UI. |
| Card payment support | Cameroon card infrastructure is thin. No provider in scope offers card processing. Adding cards requires a separate acquiring relationship (Visa/Mastercard), PCI-DSS scope, and entirely different integration. | Out of scope until a card provider partner is identified. |
| Multi-currency (USD, EUR) | Transaction currency in Cameroon is XAF. Both providers operate in XAF. Adding currency conversion introduces FX risk, regulatory complexity (COBAC), and margin management with no immediate demand. | XAF only in v1. Flag as v2 when cross-border use cases emerge. |
| Machine learning fraud detection | Rule-based velocity checks and risk scoring cover the common fraud patterns (SIM swap, bot cashout) in Cameroon. ML requires labeled training data that does not exist yet. | Implement deterministic rule engine in v1. Collect signal data. ML is a v3+ concern once transaction volume exists. |
| Customer wallet / stored balance | Running a wallet means becoming a financial institution under COBAC regulations. This is a different business model from a gateway. | Payam holds no customer funds. It routes. Internal ledger is for reconciliation only, not customer balance management. |
| SDK generation for all languages | SDKs require maintenance, versioning, and documentation at least as complex as the API itself. | Provide excellent REST API docs and an OpenAPI spec. The community will generate SDKs. Prioritize Java (internal use) first if any. |
| Recurring payment / subscription management | MTN's PreApproval endpoint exists but is poorly documented for Cameroon. Orange has no equivalent. Building a scheduler that relies on pre-approval creates provider dependency that is not production-proven at scale. | Document PreApproval as experimental. Do not build a subscription scheduler in v1. |
| Smart retry on provider errors | Retrying a failed payment requires knowing WHY it failed. Provider error codes are inconsistent. Automatic retry without operator review risks charging customers twice for legitimately declined payments. | Expose failed transactions with provider error code. Let operator or client decide to retry explicitly. |
| Customer-facing self-service portal | Payam's users are developers (B2B). There is no B2C customer who needs to log in and view their payment history through Payam. | The integrating merchant's app handles customer-facing UX. |
| Real-time balance push / balance alerts | Orange has no balance endpoint. MTN balance reflects merchant wallet, not individual transactions. Balance push notifications would be misleading and operationally complex. | Provide balance query (MTN only, on-demand). Orange approximation via ledger. No push. |
| USSD payment flow management | Both providers handle USSD prompts internally. Payam does not mediate the customer-facing USSD session. Attempting to do so would violate provider terms and create support burden. | Let providers own the USSD flow. Payam only tracks outcomes. |

---

## Feature Dependencies

Some features are prerequisites for others. Build in this order to avoid rework.

```
Provider adapters (MTN + Orange)
  └── Unified payment initiation
        └── Transaction state machine
              ├── Webhook delivery (outbound)
              │     └── Webhook retry + replay
              └── Transaction status endpoint
                    └── Automatic polling fallback

API key management (per client)
  ├── Per-client rate limiting
  ├── Per-client webhook configuration
  └── Per-client fee rules

Internal ledger
  ├── Daily reconciliation job
  ├── Reconciliation report export
  └── Estimated balance (Orange workaround)

Fraud velocity rules
  └── Risk scoring engine
        └── Device fingerprinting integration
```

---

## MVP Recommendation

For MVP (production-ready v1), prioritize:

**Must have (gate to first customer):**
1. Unified payment initiation with idempotency enforcement
2. Transaction status lifecycle and status query endpoint
3. Outbound webhook delivery with HMAC signing
4. Webhook retry (minimum 3 attempts)
5. API key authentication and rotation
6. Per-client rate limiting
7. Internal ledger for reconciliation
8. Transaction investigation tools in admin dashboard
9. Standardized error codes across both providers
10. Automatic polling fallback (webhook safety net)

**Add before public launch:**
11. Risk scoring + velocity rules (fraud protection)
12. Daily reconciliation job
13. Per-client fee configuration
14. Webhook event log with replay
15. Sandbox environment per client
16. Provider health status endpoint

**Defer to v2:**
- Refund via disbursement (pending provider commercial approval)
- Tiered/percentage fee structures (fixed fee covers v1)
- ML fraud detection (needs data first)
- Structured sandbox with controllable failure injection
- Pre-approval / recurring payments (experimental MTN feature)
- Multi-provider expansion (Wave, Moov)

---

## Sources

- Project requirements file: `/requirements/paymentApi_security_architecture.md` [VERIFIED]
- Project requirements file: `/requirements/sendam-wrapper-requirements.md` [VERIFIED]
- Project requirements file: `/requirements/mtn-api.md` [VERIFIED]
- Project requirements file: `/requirements/orange-money-integration-guide.md` [VERIFIED]
- Campay API knowledge as of August 2025 [MEDIUM confidence — verify against campay.net/en/documentation/]
- Monetbil API knowledge as of August 2025 [MEDIUM confidence — verify against monetbil.com/developers]
- Notchpay API knowledge as of August 2025 [MEDIUM confidence — verify against developer.notchpay.co]
- Paystack / Flutterwave / Stripe patterns: well-documented, treated as HIGH confidence for general
  payment API best practices (idempotency keys, webhook signing, error codes, sandbox patterns)
