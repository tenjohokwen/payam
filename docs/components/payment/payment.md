# Payment Orchestrator (`com.softropic.payam.payment`)

The Payment Orchestrator is the central hub of the Payam system. It coordinates the lifecycle of a payment from initial request to provider dispatch, ensuring idempotency, fraud checks, fee evaluation, and transaction state management.

## Role & Purpose
- **Central Coordinator**: Wires together routing, idempotency, transactions, and provider ports.
- **State Manager**: Orchestrates the state transitions of a transaction (e.g., `INITIATED` -> `PROCESSING`).
- **Integrity Guard**: Ensures that payments are not double-processed (idempotency) and are properly logged.
- **Performance Optimized**: Carefully manages database connections by performing outbound network calls (to MTN/Orange) outside of database transactions.

## Key Service: `PaymentOrchestrator`
The `PaymentOrchestrator.initiate()` method is the entry point for all mobile money payments.

### Interaction Flow
```plantuml
@startuml
actor Merchant
participant "PaymentOrchestrator" as PO
participant "MsisdnRouter" as Router
participant "IdempotencyService" as Idem
participant "TransactionService" as TxService
participant "FraudScoringService" as Fraud
participant "FeeEvaluationService" as Fee
participant "MobileMoneyPort" as Port

Merchant -> PO: initiate(tenantId, request)
activate PO

PO -> Router: resolve(msisdn)
Router --> PO: provider (MTN/ORANGE)

PO -> Idem: checkAndReserve(tenantId, key)
Idem --> PO: (cached response or reserved)

PO -> TxService: initiate(tenantId, provider, amount...)
TxService --> PO: transaction (committed INITIATED)

PO -> Fraud: evaluate(command)
alt Fraud Blocked
    PO -> TxService: applyFailed(FRAUD_BLOCKED)
    PO --> Merchant: Error (FRAUD_BLOCKED)
end

PO -> Fee: evaluateFee(tenantId, amount)
Fee --> PO: feeAmount

PO -> PO: persist fee & riskScore (new DB tx)

PO -> Port: initiateMerchantPayment(command)
activate Port
Port --> PO: ProviderResult (PENDING)
deactivate Port

PO -> PO: Transition Tx to PROCESSING (new DB tx)
PO -> Idem: store(response)

PO --> Merchant: PaymentResponse (PROCESSING)
deactivate PO
@enduml
```

## Dependencies
- **Inbound**: Called by `PaymentResource` (REST API).
- **Outbound**:
    - `MsisdnRouter`: To determine if it's an MTN or Orange number.
    - `IdempotencyService`: To prevent duplicate payments.
    - `TransactionService` / `TransactionRepository`: For database persistence.
    - `FraudScoringService`: To block risky transactions.
    - `FeeEvaluationService`: To calculate merchant fees.
    - `MtnMoMoPort` / `OrangeMoneyPort`: To talk to the actual mobile operators.
    - `PaymentMetricsService`: To track success/failure rates.

## Triggering Mechanisms
- **`initiate(tenantId, request)`**: Invoked when a merchant calls the `/v1/payments` API. This is the main "happy path" trigger.
- **`applyFailed(...)`**: Triggered internally when an error occurs during initiation (e.g., provider down, fraud block, subscriber inactive).

## Junior Dev Tips
- **No `@Transactional` on `initiate()`**: We don't want to hold a DB connection while waiting for MTN/Orange to respond. This prevents the system from locking up under high load.
- **State Machine**: Transactions follow a strict state machine. You cannot jump from `INITIATED` directly to `SUCCESS`. The orchestrator handles the `INITIATED` -> `PROCESSING` jump.
- **Idempotency**: Always use the `idempotencyKey` provided by the merchant. If they retry the same request, the orchestrator will return the same response without charging the customer again.
