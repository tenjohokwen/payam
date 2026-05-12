# Payam Architectural Reorganization Proposal

## Current State Assessment
The application currently employs a flat package structure under `com.softropic.payam`. While functional, this has led to "package explosion" at the root level, obscuring the primary **Bounded Contexts** of the system and making it difficult to distinguish between core domain logic and infrastructure adapters.

## Proposed Architectural Recommendations

### 1. Consolidate the `payment` Bounded Context
Consolidate `payment`, `transaction`, `disbursement`, `fee`, `reconciliation`, and `fraud` under a single `payment` umbrella. This reflects the core business domain more accurately.

**Proposed Structure:**
- `com.softropic.payam.payment.core`: Collection Orchestration, Msisdn Routing.
- `com.softropic.payam.payment.disbursement`: Payout logic, Fraud evaluation for payouts.
- `com.softropic.payam.payment.ledger`: Current `transaction` package (Accounting, Idempotency, Transaction Repository).
- `com.softropic.payam.payment.fee`: Fee Evaluation and Rules.
- `com.softropic.payam.payment.reconciliation`: Provider reconciliation jobs and adapters.
- `com.softropic.payam.payment.fraud`: General fraud detection logic.

### 2. Encapsulate Provider Infrastructure (Hexagonal Adapters)
Move provider-specific packages (`mtn`, `orange`) under `payment.provider`. These are **Infrastructure Adapters** and should be isolated from the core domain.

**Proposed Structure:**
- `com.softropic.payam.payment.provider.mtn`: MTN-specific clients, token services, and callback controllers.
- `com.softropic.payam.payment.provider.orange`: Orange-specific clients, token services, and callback controllers.

### 3. Formalize the Platform Layer
Group foundational services that support the payment domain (multi-tenancy, security, notifications) under a `platform` namespace.

**Proposed Structure:**
- `com.softropic.payam.platform.tenant`: Tenant Management.
- `com.softropic.payam.platform.security`: AuthZ/AuthN, JWT, and Security Infrastructure.
- `com.softropic.payam.platform.notification`: Consolidation of `email` and `alert`.
- `com.softropic.payam.platform.monitoring`: Health indicators and operational tools (`ops`).
- `com.softropic.payam.platform.admin`: System Administrative APIs.

### 4. Refactor the `common` Package
The `common` package currently acts as a "junk drawer." Logic should be redistributed based on its nature.

- **Domain Common:** Move `common.payment` and `common.refund` into `payment.core`.
- **Infrastructure:** Move `common.persistence`, `common.logging`, and `common.threadpool` into an `infrastructure` or `foundation` package.
- **Enums:** Move specific enums to the domain packages they are most closely related to.

---

## Target Package Hierarchy

```text
src/main/java/com/softropic/payam
├── payment             <-- The Core Domain
│   ├── core            <-- Collection Orchestration
│   ├── disbursement    <-- Payout Orchestration
│   ├── ledger          <-- Transactions & Idempotency
│   ├── fee             <-- Fee Calculation
│   ├── reconciliation  <-- Provider Reconciliation
│   ├── fraud           <-- Fraud Detection logic
│   └── provider        <-- External Adapters
│       ├── mtn
│       └── orange
├── platform            <-- Supporting Sub-domains
│   ├── tenant          <-- Tenant Management
│   ├── security        <-- Security Infrastructure
│   ├── notification    <-- Email & Alerts (Consolidated)
│   ├── monitoring      <-- Health & Ops
│   └── admin           <-- System Admin
└── infrastructure      <-- Technical Foundation
    ├── config          <-- Global Spring Configuration
    ├── web             <-- Global Web Filters/Intercepts
    └── persistence     <-- Shared Persistence Config
```

## Strategic Benefits
1. **Separation of Concerns:** Clear distinction between Business Logic (`payment.core`) and Infrastructure (`payment.provider`).
2. **Reduced Cognitive Load:** Navigating the codebase follows logical business boundaries rather than a flat list of technical packages.
3. **Scalability:** Adding new payment providers or payment types (e.g., Bank Transfer) has a clear, predefined location.
4. **Improved Testability:** Modules can be tested in isolation, and dependencies are easier to mock.
