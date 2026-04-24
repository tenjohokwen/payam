# Architecture Patterns — v10 Client Disbursement API

**Project:** Payam v10 — Client Disbursement API
**Researched:** 2026-04-24
**Confidence:** HIGH — based on direct codebase inspection of existing payment, orange, mtn, transaction, webhook, and security modules.

---

## Recommended Architecture

v10 adds a new `disbursement` module following the established module pattern (`contract → repo → service → infrastructure → api → config`). It is **additive-only** — no existing modules are modified, except for wiring new event types into the outbound webhook pipeline.

Full call chain:

```
Client → POST /v1/disbursements
           │
  DisbursementResource (@RestController, /v1/disbursements)
           │
  DisbursementOrchestrator.initiate(tenantId, request)
    ├─ IdempotencyService (existing, distinct "dsb:" prefix)
    ├─ FraudScoringService (existing, new disbursement signals)
    ├─ MsisdnPrefixRoute (existing routing table)
    ├─ MobileMoneyPort.validateAccountHolder() (existing)
    ├─ FeeEvaluationService (existing)
    ├─ WalletBalanceService.checkAndReserve() (NEW — SELECT FOR UPDATE)
    ├─ DisbursementRepository.save() (NEW)
    ├─ MtnMoMoPort.transfer() OR OrangeMoneyPort.ic2cDisbursement() (existing + new)
    └─ LedgerService.postEntry(LedgerPosting.disbursement(...)) (existing)

Async — provider callback:
  MtnDisbursementCallbackController  → /v1/callbacks/mtn/disbursement/{ref}
  OrangeDisbursementCallbackController → /v1/callbacks/orange/disbursement
    ├─ IP whitelist + signature verify
    ├─ Replay protection
    ├─ Double-check (provider status API)
    ├─ DisbursementRepository.updateStatus()
    ├─ WalletBalanceService.finalise() or release()
    └─ WebhookDeliveryService.enqueue(disbursement.completed / disbursement.failed)
```

---

## Entity Strategy: Separate `Disbursement` Entity (Recommended)

**Do NOT reuse `Transaction` with `flow=DISBURSEMENT`.**

Rationale from the codebase:
- `Transaction` is deeply coupled to the collection path: `PaymentOrchestrator`, `PaymentResource`, `PaymentEventLog` enum values, reconciliation queries — all assume collection semantics.
- Callback controllers look up transactions by `providerReferenceId`; mixing disbursements into the same table requires a discriminator on every lookup query.
- `PaymentEventLog` would need disbursement-specific enum values alongside collection values — enum pollution risk.
- Reconciliation reports (already in production) filter `Transaction` by date/tenant; adding flow filters would require query changes across the reconciliation module.

**New `Disbursement` entity** (`disbursement/repo/Disbursement.java`):

```
main.disbursement
  id                      BIGINT PK
  disbursement_id         VARCHAR(30) UK  (dsb_<tsid> format)
  tenant_id               BIGINT FK
  recipient_msisdn        VARCHAR(20)
  provider                VARCHAR(20)     (MTN / ORANGE)
  provider_reference_id   VARCHAR(100) UK
  provider_transaction_id VARCHAR(100)
  amount                  NUMERIC(19,4)
  fee                     NUMERIC(19,4)
  currency                VARCHAR(3)
  reference               VARCHAR(50)     (tenant external ref)
  description             VARCHAR(140)
  metadata                JSONB
  status                  VARCHAR(20)     (state machine)
  failure_reason          VARCHAR(100)
  created_at              TIMESTAMPTZ
  completed_at            TIMESTAMPTZ
  idempotency_key         VARCHAR(255)
  version                 BIGINT          (optimistic lock for status transitions — NOT for balance)
```

Flyway V26 creates this table + `main.disbursement_aud` (Hibernate Envers).

---

## Balance Reservation: Pessimistic Write Lock (Required)

`MERCHANT_WALLET` balance cannot use optimistic (`@Version`) retry for reservation — retries allow a second successful drain after the first completes.

**New `WalletBalance` entity + `WalletBalanceService`:**

```
main.merchant_wallet_balance
  id          BIGINT PK
  tenant_id   BIGINT UK
  balance     NUMERIC(19,4)
  currency    VARCHAR(3)
  version     BIGINT          (for status-transition guard, not for reservation)
```

`WalletBalanceService.checkAndReserve(tenantId, gross)`:
```java
// Inside TransactionTemplate
walletBalanceRepository.findByTenantIdForUpdate(tenantId)  // @Lock(PESSIMISTIC_WRITE)
    .filter(w -> w.getBalance().compareTo(gross) >= 0)
    .map(w -> { w.setBalance(w.getBalance().subtract(gross)); return w; })
    .orElseThrow(() -> new InsufficientBalanceException(...));
```

`WalletBalanceService.release(tenantId, gross)` — called on FAILED: returns reserved amount.  
`WalletBalanceService.finalise(tenantId)` — called on SUCCESS: balance already deducted at reservation, no further action unless fee capture is deferred.

Flyway V26 also creates `main.merchant_wallet_balance`.

---

## Callback Controller Strategy

**Use distinct paths — do not merge with collection controllers.**

| Controller | Path | Flow |
|------------|------|------|
| `MtnCallbackController` (existing) | `/v1/callbacks/mtn/{referenceId}` | COLLECTION |
| `MtnDisbursementCallbackController` (NEW) | `/v1/callbacks/mtn/disbursement/{referenceId}` | DISBURSEMENT |
| `OrangeCallbackController` (existing) | `/v1/callbacks/orange` | COLLECTION |
| `OrangeDisbursementCallbackController` (NEW) | `/v1/callbacks/orange/disbursement` | DISBURSEMENT |

Register disbursement callback URLs with providers using the `/disbursement/` path variant. The `notifUrl` field sent to Orange IC2C and the MTN `X-Callback-Url` header must use these paths.

Both new callback controllers follow the same pattern as existing ones:
1. IP whitelist guard
2. Signature/token verification
3. Replay deduplication (Redis `callbacks:dsb:<providerRefId>`)
4. Double-check via provider status API
5. `DisbursementRepository.updateStatus()` via `TransactionTemplate`
6. `WalletBalanceService.release()` on FAILED
7. Publish `DisbursementCompletedEvent` → `WebhookDeliveryService` via `@TransactionalEventListener(AFTER_COMMIT)`

---

## New Module Structure

```
disbursement/
  contract/
    DisbursementRequest.java         (API request DTO)
    DisbursementResponse.java        (API response DTO)
    DisbursementWebhookPayload.java  (outbound webhook payload)
    DisbursementStatus.java          (enum: INITIATED, PROCESSING, SUCCESS, FAILED, EXPIRED)
    DisbursementCompletedEvent.java  (Spring event for webhook trigger)
  repo/
    Disbursement.java                (JPA entity)
    DisbursementRepository.java      (Spring Data)
    WalletBalance.java               (JPA entity)
    WalletBalanceRepository.java     (with findByTenantIdForUpdate)
  service/
    DisbursementOrchestrator.java    (main orchestrator)
    WalletBalanceService.java        (checkAndReserve, release, finalise)
  api/
    DisbursementResource.java        (POST /v1/disbursements, GET /v1/disbursements/{id})
    MtnDisbursementCallbackController.java
    OrangeDisbursementCallbackController.java
  config/
    DisbursementConfig.java          (any disbursement-specific beans)
```

**Existing modules touched:**
- `orange/service/OrangeMoneyPort.java` — add `ic2cDisbursement()` method (or wire existing `initiateCashout()` if the endpoint matches)
- `mtn/service/MtnMoMoPort.java` — add `disbursementTransfer()` method wrapping `MtnMoMoClient.transfer()`
- `fraud/service/FraudScoringService.java` — add disbursement-specific signals (new MSISDN, amount outlier, known-fraud list)
- `webhook/service/WebhookDeliveryService.java` — handle `DisbursementCompletedEvent` alongside existing `PaymentCompletedEvent`

---

## Orange IC2C vs Cashout

The v9 `OrangeMoneyPort.initiateCashout()` calls `orangeMoneyClient.cashout()`. Before wiring for v10:

1. **Read `OrangeMoneyClient.cashout()` HTTP method path.** If it calls `/cashout` — that is the customer self-cashout flow, not the merchant-to-subscriber payout.
2. **Merchant-to-subscriber payout = `/ic2c/pay`** (IC2C: Internal Channel-to-Customer).
3. If `cashout()` calls the wrong endpoint, add `OrangeMoneyClient.ic2cTransfer()` pointing to `/ic2c/pay`.
4. Keep `initiateCashout()` but reroute it to call `ic2cTransfer()` internally — avoids breaking the v9 skeleton.

WireMock stubs in Orange E2E tests must match on exact URL path (`/ic2c/pay` vs `/cashout`).

---

## Idempotency Key Namespace

Use a distinct Redis namespace for disbursements:

```
idempotency:dsb:<tenantId>:<key>   ← disbursements (NEW)
idempotency:<tenantId>:<key>       ← collections (EXISTING, unchanged)
```

This prevents a key reused across flows from returning a cached collection response for a disbursement request, and enables independent TTL policy if needed.

---

## Build Order (Dependency-Respecting)

| Phase | Contents | Depends On |
|-------|----------|------------|
| 50 | Flyway V26 (disbursement + wallet_balance tables), `Disbursement` entity, `WalletBalance` entity, repositories, `WalletBalanceService` (with concurrency tests), `DisbursementStatus` enum | — |
| 51 | `DisbursementOrchestrator`, `DisbursementResource` (POST + GET), wire MTN disbursement path, wire Orange IC2C path | 50, existing MTN/Orange ports |
| 52 | `MtnDisbursementCallbackController`, `OrangeDisbursementCallbackController`, outbound webhook extension (`DisbursementCompletedEvent` → `WebhookDeliveryService`) | 50, 51 |
| 53 | E2E tests: both provider happy paths, insufficient balance, fraud block, idempotency race, callback replay protection | 50, 51, 52 |

---

## Data Flow: Disbursement State Transitions

```
INITIATED
  ├─[balance check fails]       → FAILED   (no provider call; balance not reserved)
  ├─[fraud blocked]             → FAILED   (no provider call)
  ├─[recipient invalid]         → FAILED   (no provider call)
  └─[provider 202/200]          → PROCESSING (balance reserved; ledger entries posted)

PROCESSING
  ├─[callback SUCCESS + verified] → SUCCESS (balance finalised)
  ├─[callback FAILED + verified]  → FAILED  (balance released)
  └─[polling timeout ~10 min]     → EXPIRED (manual ops review; balance held pending investigation)

SUCCESS  (terminal)
FAILED   (terminal — balance released)
EXPIRED  (terminal — ops alert; balance held until manual resolution)
```

`EXPIRED` must be added to `DisbursementStatus` enum AND documented in any existing state machine illegal-transition test to prevent unintended transitions.

---

## Patterns to Follow

| Pattern | Source | Apply To |
|---------|--------|---------|
| `@Lock(PESSIMISTIC_WRITE)` on JPA repo method | `TransactionRepository.findByTransactionIdForUpdate()` | `WalletBalanceRepository.findByTenantIdForUpdate()` |
| `TransactionTemplate` for post-HTTP DB writes | `OrangeMoneyPort.persistPayToken()` | All disbursement ledger writes |
| `@TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW` | `WebhookDeliveryService.onEnqueueRequested()` | `DisbursementCompletedEvent` listener |
| Distinct callback paths per flow | Established by collection controllers | Disbursement: `/v1/callbacks/mtn/disbursement/{ref}` |
| Tenant-scoped query isolation | All existing repositories | `DisbursementRepository.findByDisbursementIdAndTenantId()` |
| `@Immutable` + `@Tsid` ID generation | `PaymentEventLog` | `Disbursement.disbursementId` (dsb_ prefix) |

---

## Anti-Patterns to Avoid

- **Reusing `Transaction` entity** — conditional flow branching pollutes collection code
- **`@Transactional` on orchestrator methods that make HTTP calls** — connection pool exhaustion
- **Optimistic `@Version` retry for balance gate** — allows second drain after first succeeds
- **Sharing collection + disbursement callback paths** — wrong state transition on cross-routing
- **Trusting provider callback without double-check** — established "Trust But Verify" rule

---

## New vs Modified — Complete Map

| File | Status |
|------|--------|
| `disbursement/contract/DisbursementRequest.java` | NEW |
| `disbursement/contract/DisbursementResponse.java` | NEW |
| `disbursement/contract/DisbursementWebhookPayload.java` | NEW |
| `disbursement/contract/DisbursementStatus.java` | NEW |
| `disbursement/contract/DisbursementCompletedEvent.java` | NEW |
| `disbursement/repo/Disbursement.java` | NEW |
| `disbursement/repo/DisbursementRepository.java` | NEW |
| `disbursement/repo/WalletBalance.java` | NEW |
| `disbursement/repo/WalletBalanceRepository.java` | NEW |
| `disbursement/service/DisbursementOrchestrator.java` | NEW |
| `disbursement/service/WalletBalanceService.java` | NEW |
| `disbursement/api/DisbursementResource.java` | NEW |
| `disbursement/api/MtnDisbursementCallbackController.java` | NEW |
| `disbursement/api/OrangeDisbursementCallbackController.java` | NEW |
| `db/migration/V26__disbursement_schema.sql` | NEW |
| `orange/service/OrangeMoneyPort.java` | MODIFIED — add `ic2cDisbursement()` |
| `mtn/service/MtnMoMoPort.java` | MODIFIED — add `disbursementTransfer()` |
| `fraud/service/FraudScoringService.java` | MODIFIED — add disbursement signals |
| `webhook/service/WebhookDeliveryService.java` | MODIFIED — handle `DisbursementCompletedEvent` |

---

*Architecture research for: Payam v10 — Client Disbursement API*
*Researched: 2026-04-24*
