# Requirements: Payam

**Defined:** 2026-05-06
**Core Value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## v12 Requirements

Architectural reorganization from flat package explosion to explicit bounded contexts, following the spec in `requirements/architecture.md`.

### Payment Bounded Context (PAY)

- [x] **PAY-01**: `payment` package (collection orchestration, MSISDN routing) is relocated to `payment.core` with all imports updated
- [x] **PAY-02**: `transaction` package (ledger, idempotency, transaction repository) is relocated to `payment.ledger` with all imports updated
- [x] **PAY-03**: `disbursement` package (payout orchestration) is relocated to `payment.disbursement` with all imports updated
- [x] **PAY-04**: `fee` package (fee evaluation and rules) is relocated to `payment.fee` with all imports updated
- [x] **PAY-05**: `reconciliation` package (provider reconciliation) is relocated to `payment.reconciliation` with all imports updated
- [x] **PAY-06**: `fraud` package (fraud detection) is relocated to `payment.fraud` with all imports updated
- [x] **PAY-07**: `webhook` package (outbound delivery subsystem) is relocated to `payment.webhook` with all imports updated

### Provider Infrastructure (PROV)

- [x] **PROV-01**: `mtn` package is relocated to `payment.provider.mtn` (MTN clients, token services, callback controllers) with all imports updated
- [x] **PROV-02**: `orange` package is relocated to `payment.provider.orange` (Orange clients, token services, callback controllers) with all imports updated

### Platform Layer (PLAT)

- [x] **PLAT-01**: `tenant` package is relocated to `platform.tenant` with all imports updated
- [x] **PLAT-02**: `security` package is relocated to `platform.security` with all imports updated
- [x] **PLAT-03**: `email` and `alert` packages are merged into `platform.notification` with all imports updated
- [x] **PLAT-04**: `health` and `ops` packages are merged into `platform.monitoring` with all imports updated
- [x] **PLAT-05**: `admin` and `platform` packages are merged into `platform.admin` with all imports updated

### Common Redistribution (CMN)

- [x] **CMN-01**: `common.payment` and `common.refund` classes are relocated to `payment.core` with all imports updated
- [x] **CMN-02**: `common.persistence`, `common.logging`, `common.threadpool`, `common.client`, `common.config`, `common.util`, `common.message`, `common.exception`, `common.validation`, and remaining `common` classes are relocated to `infrastructure.*` sub-packages with all imports updated
- [x] **CMN-03**: Domain-specific enums in `common.enums` are moved to their owning domain packages with all imports updated
- [x] **CMN-04**: `common` package is fully emptied and removed after redistribution is complete

### Infrastructure Layer (INFRA)

- [x] **INFRA-01**: `config` package (AsyncConfig, DataSourceConfig, ObservabilityConfig) is relocated to `infrastructure.config` with all imports updated
- [x] **INFRA-02**: Spring filters, interceptors, and web infrastructure are consolidated under `infrastructure.web`
- [x] **INFRA-03**: Shared persistence base classes and configuration are consolidated under `infrastructure.persistence`

### Build Quality (BUILD)

- [x] **BUILD-01**: `mvn verify` passes green (all unit + integration tests) after every phase commit — no deferred red phases
- [x] **BUILD-02**: No functional behavior changes — all existing REST API contracts, database schemas, and Flyway migrations are unchanged
- [x] **BUILD-03**: Spring component-scan, Flyway configuration, and security filter registration verified functional after each phase

## Future Requirements

*(None identified — this milestone is a closed-scope refactoring with no planned follow-on scope)*

## Out of Scope

| Feature | Reason |
|---------|--------|
| New functionality | Pure structural refactoring — no behavioral changes |
| Flyway schema changes | Package moves do not require DDL changes (V32 remains the last migration) |
| API contract changes | REST endpoints, request/response DTOs unchanged — only package locations move |
| Microservice extraction | Bounded context naming does not imply service split; monolith architecture unchanged |
| Sub-package re-ordering within moved packages | Internal sub-package structure (contract/repo/service/api) preserved as-is within each new location |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| PAY-01 | Phase 63 | Complete |
| PAY-02 | Phase 63 | Complete |
| PAY-03 | Phase 63 | Complete |
| PAY-04 | Phase 63 | Complete |
| PAY-05 | Phase 63 | Complete |
| PAY-06 | Phase 63 | Complete |
| PAY-07 | Phase 63 | Complete |
| PROV-01 | Phase 64 | Complete |
| PROV-02 | Phase 64 | Complete |
| PLAT-01 | Phase 62 | Complete |
| PLAT-02 | Phase 62 | Complete |
| PLAT-03 | Phase 62 | Complete |
| PLAT-04 | Phase 62 | Complete |
| PLAT-05 | Phase 62 | Complete |
| CMN-01 | Phase 65 | Complete |
| CMN-02 | Phase 65 | Complete |
| CMN-03 | Phase 65 | Complete |
| CMN-04 | Phase 65 | Complete |
| INFRA-01 | Phase 61 | Complete |
| INFRA-02 | Phase 61 | Complete |
| INFRA-03 | Phase 61 | Complete |
| BUILD-01 | cross-cutting (61–65) | Complete |
| BUILD-02 | cross-cutting (61–65) | Complete |
| BUILD-03 | cross-cutting (61–65) | Complete |

**Coverage:**
- v12 requirements: 24 total
- Functional requirements mapped: 21/21 (Phases 61–65)
- Cross-cutting gates: 3/3 (BUILD-01/02/03 applied to all phases)
- Unmapped: 0

---
*Requirements defined: 2026-05-06*
*Last updated: 2026-05-06 — traceability populated after roadmap creation*
