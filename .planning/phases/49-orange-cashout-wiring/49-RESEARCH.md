# Phase 49: Orange Cashout Wiring - Research

**Researched:** 2026-04-22
**Domain:** Java 17 / Spring Boot — Orange Money cashout path + double-entry ledger integration
**Confidence:** HIGH

---

## Summary

Phase 49 wires the Orange Money cashout path to the double-entry ledger. The infrastructure is fully ready: `LedgerService.postEntry(txId, tenantId, LedgerPosting)` is implemented (Phase 47), `LedgerPosting.disbursement(principal, fee, currency)` already handles zero-fee correctly, `OrangeMoneyClient.cashout()` already exists, and `FeeEvaluationService.evaluateFee()` is already available in `PaymentOrchestrator`. The only missing pieces are:

1. `PaymentCommand` lacks a `feeAmount` field — the orchestrator cannot pass the evaluated fee to `OrangeMoneyPort`
2. `OrangeMoneyPort.initiateCashout()` is a `UnsupportedOperationException` stub — it needs to call the provider and post the ledger entry

**Primary recommendation:** Two plans — Plan 01 adds `feeAmount` to `PaymentCommand` with backward-compatible constructor + orchestrator fee wiring; Plan 02 implements `initiateCashout()` with provider call and ledger posting + updates the test.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CASHOUT-01 | `PaymentCommand` gains optional `feeAmount` (nullable `BigDecimal`); orchestrator populates from `FeeEvaluationService` before dispatching to `OrangeMoneyPort` | Single production construction site in `PaymentOrchestrator:168`; 7 test sites in 4 test files — all need updating to pass `null` as 14th arg; Java record non-canonical constructor makes existing 13-arg sites compile without change |
| CASHOUT-02 | `OrangeMoneyPort.initiateCashout()` calls `LedgerService.postEntry()` after provider confirms success, inside `TransactionTemplate`; no `@Transactional`; `feeAmount = null` → `BigDecimal.ZERO` | `OrangeMoneyClient.cashout()` exists; `CashoutRequest` DTO exists; `LedgerService` must be injected into `OrangeMoneyPort`; existing test must be replaced |

</phase_requirements>

---

## Architecture Patterns

### PaymentCommand — Adding feeAmount Without Breaking Call Sites

`PaymentCommand` is a Java record with 13 fields. Java records do not support default parameter values in the canonical constructor, but they **do** support additional non-canonical constructors that delegate to the canonical form:

```java
public record PaymentCommand(
    String transactionId,
    String traceId,
    Long tenantId,
    String msisdn,
    BigDecimal amount,
    String currency,
    String externalReference,
    String idempotencyKey,
    MobilePaymentProvider provider,
    String clientIp,
    String userAgent,
    String deviceFingerprint,
    String description,
    BigDecimal feeAmount          // NEW — last field, nullable
) {
    // Backward-compat: all 13-arg call sites delegate to canonical with feeAmount=null
    public PaymentCommand(String transactionId, String traceId, Long tenantId,
                          String msisdn, BigDecimal amount, String currency,
                          String externalReference, String idempotencyKey,
                          MobilePaymentProvider provider, String clientIp,
                          String userAgent, String deviceFingerprint, String description) {
        this(transactionId, traceId, tenantId, msisdn, amount, currency,
             externalReference, idempotencyKey, provider, clientIp,
             userAgent, deviceFingerprint, description, null);
    }
}
```

**Effect:** All 7 existing test construction sites (`OrangeMoneyPortIT:142,186,204`, `MtnMoMoPortIT:136`, `FraudScoringServiceIT:140`, `FraudThresholdGuardTest:59,98`) compile without any change — they invoke the 13-arg constructor. The single production site in `PaymentOrchestrator:168` also compiles without change (still uses 13-arg). The orchestrator then uses `cmd.withFeeAmount(fee)` or builds a new 14-arg command for the cashout path.

### Orchestrator Fee Wiring for Cashout

`PaymentOrchestrator` already:
- Has `feeEvaluationService` injected (field + constructor, lines 76 and 98)
- Evaluates fee at line 202: `BigDecimal fee = feeEvaluationService.evaluateFee(tenantId, request.amount())`
- Stores fee on the `Transaction` entity at line 216: `locked.setFeeAmount(fee)`

For the cashout dispatch path, the orchestrator needs to pass the fee into the `PaymentCommand`. Since `PaymentCommand` is an immutable record, the cleanest approach is to add a `withFeeAmount` instance method or build the command with the 14-arg constructor directly:

```java
// Option A: withFeeAmount helper on the record
public PaymentCommand withFeeAmount(BigDecimal fee) {
    return new PaymentCommand(this.transactionId, ..., this.description, fee);
}

// Option B: build cashout cmd directly with 14 args
PaymentCommand cashoutCmd = new PaymentCommand(
    tx.getTransactionId(), tx.getTraceId(), tenantId,
    request.msisdn(), request.amount(), request.currency(),
    request.externalReference(), request.idempotencyKey(),
    provider, clientInfo.getIpAddress(), clientInfo.getUserAgent(),
    request.deviceFingerprint(), request.description(), evaluatedFee
);
```

**Recommendation:** Add `withFeeAmount(BigDecimal fee)` instance method to the record — keeps the orchestrator clean and the change isolated to `PaymentCommand`.

### OrangeMoneyPort.initiateCashout() — Provider Call Pattern

`OrangeMoneyClient.cashout(bearerToken, CashoutRequest)` already exists (line 163) and posts to `/cashout`. The `CashoutRequest` DTO requires: `merchant_key`, `amount`, `currency`, `reference`, `msisdn` (national number).

`OrangeMoneyPort` already has all required dependencies except `LedgerService`:
- `orangeMoneyClient` — for the HTTP call
- `orangeTokenService` — for the bearer token
- `transactionTemplate` — for the DB transaction (no `@Transactional` on method)
- `platformConfigService` — for merchant msisdn (used as `merchant_key`)

New dependency to inject: `LedgerService` (add to constructor and field).

**Implementation pattern** (mirrors `persistPayToken` which already uses `transactionTemplate`):

```java
public ProviderResult initiateCashout(PaymentCommand cmd) {
    String token = orangeTokenService.getAccessToken();
    String nationalMsisdn = stripCountryCode(cmd.msisdn());  // already exists

    CashoutRequest request = buildCashoutRequest(cmd, nationalMsisdn);
    ResponseEntity<Map> response = orangeMoneyClient.cashout(token, request);

    if (response.getStatusCode().is2xxSuccessful()) {
        BigDecimal fee = cmd.feeAmount() != null ? cmd.feeAmount() : BigDecimal.ZERO;
        transactionTemplate.execute(status -> {
            ledgerService.postEntry(
                cmd.transactionId(), cmd.tenantId(),
                LedgerPosting.disbursement(cmd.amount(), fee, cmd.currency())
            );
            return null;
        });
        return ProviderResult.success(null, "CASHOUT_SUCCESS");
    }
    return ProviderResult.pending(null, "CASHOUT_PENDING");
}
```

**Key constraints:**
- No `@Transactional` on the method — success criterion explicitly forbids it
- `transactionTemplate.execute()` wraps the ledger call (matches pattern at line 196 in `processWebhook`)
- `feeAmount == null` → `BigDecimal.ZERO` (success criterion 3)
- `LedgerPosting.disbursement()` already validates fee >= 0 — `BigDecimal.ZERO` is valid

### Test: Replace Stub Test with Integration Test

Current test `initiateCashout_throws_UnsupportedOperationException` at `OrangeMoneyPortIT:179-196` must be replaced. New test verifies:
1. WireMock stubs `/cashout` to return 200 OK
2. `initiateCashout(cmd)` succeeds (no exception)
3. `LedgerEntryRepository` has 3 rows for the transaction (disbursement: DEBIT MERCHANT_WALLET + 2 CREDITs)
4. DEBIT amount = principal + fee; CREDIT amounts = principal and fee respectively

**Test setup:** The test class already has `orangeServer` (WireMock), `transactionService`, `jdbcTemplate`. It will need `LedgerEntryRepository` injected to verify DB state.

A second test verifies the null-fee path: `feeAmount = null` → PROVIDER_FEE credit row has amount = 0.

---

## Call Site Inventory

### PaymentCommand construction sites (production):

| File | Line | Change Required |
|------|------|-----------------|
| `PaymentOrchestrator.java` | 168 | None — 13-arg compat constructor applies; orchestrator then uses `withFeeAmount` for cashout path |

### PaymentCommand construction sites (tests):

| File | Lines | Change Required |
|------|-------|-----------------|
| `OrangeMoneyPortIT.java` | 142, 186, 204 | None — 13-arg compat constructor applies |
| `MtnMoMoPortIT.java` | 136 | None |
| `FraudScoringServiceIT.java` | 140 | None |
| `FraudThresholdGuardTest.java` | 59, 98 | None |

### initiateCashout call sites:

| File | Line | Change Required |
|------|------|-----------------|
| `OrangeMoneyPortIT.java` | 193 | Replace entire test — was asserting UnsupportedOperationException |

---

## Dependency Injection Change

`OrangeMoneyPort` constructor currently takes 8 parameters. Adding `LedgerService` makes 9. The Spring context auto-wires by type; no XML or configuration change needed — just add the field and constructor parameter.

```java
// New field
private final LedgerService ledgerService;

// Updated constructor (add LedgerService ledgerService param, add this.ledgerService = ledgerService)
```

---

## Validation Architecture

### Test Infrastructure

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + WireMock |
| Quick run | `mvn test -pl . -Dtest="OrangeMoneyPortIT" -q` |
| Full suite | `mvn verify -q` |

### Sampling After Each Task

- **After Plan 01 (PaymentCommand + orchestrator):** `mvn test -pl . -Dtest="OrangeMoneyPortIT,FraudScoringServiceIT,MtnMoMoPortIT,FraudThresholdGuardTest,PaymentOrchestratorIT" -q` — verifies all existing call sites still compile and pass
- **After Plan 02 (initiateCashout + test):** `mvn verify -q` — verifies full suite including new ledger posting tests and no regressions in Orange tests

### Acceptance Signals

| Signal | What it proves |
|--------|---------------|
| `OrangeMoneyPortIT` passes (all tests) | `initiateCashout` posts ledger entries; null-fee path works; existing tests unbroken |
| `FraudScoringServiceIT` / `MtnMoMoPortIT` / `FraudThresholdGuardTest` pass | 13-arg compat constructor works; no regression |
| `LedgerServiceIT` passes | Disbursement ledger logic intact |
| `mvn verify` green | Full suite — no regression |

---

## Risk Flags

1. **`LedgerService.postEntry` is `@Transactional`** — calling it from inside `transactionTemplate.execute()` will use the existing transaction (Spring's `REQUIRED` propagation). This is fine and intentional.

2. **`OrangeMoneyClient.cashout()` returns `ResponseEntity<Map>`** not a typed DTO — the success check is `response.getStatusCode().is2xxSuccessful()`. WireMock must return HTTP 200 for the success test case.

3. **`buildCashoutRequest` needs `merchant_key`** — the `CashoutRequest` field `merchant_key` is not clearly documented in the code. `platformConfigService.findByProvider("ORANGE").platformMsisdn()` is the most likely candidate (same as channelMsisdn used in `buildPayRequest`). Use `apiUsername` from config as merchant key if platformMsisdn is not appropriate.

4. **`initiateCashout_throws_UnsupportedOperationException` test** — removing it is intentional but the test documents a ROADMAP deviation (SC-3). The deviation comment should be preserved in a new test or in code comments.

## ## RESEARCH COMPLETE
