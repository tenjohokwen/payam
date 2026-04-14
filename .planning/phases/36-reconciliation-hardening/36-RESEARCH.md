# Phase 36: Reconciliation Hardening — Research

**Researched:** 2026-04-14
**Domain:** Spring Data JPA pagination, Spring transaction propagation, reconciliation state machine
**Confidence:** HIGH

---

## Summary

Phase 36 fixes two bugs in the reconciliation pipeline:

**Bug 1 (RECON-01 — unbounded heap load):** `LedgerSnapshotService.findTransactionsForDateAndProvider()` calls `findForReconciliation()`, which returns `List<Transaction>` — the entire day's transaction set for one provider in a single query. A day with 1000+ transactions loads all rows into heap simultaneously. The fix is: add a `Pageable`-accepting variant of `findForReconciliation` to `TransactionRepository`, then rewrite `LedgerSnapshotService` (and the calling loop in `ReconciliationService.runForProviderAndDate()`) to iterate page by page (page size ≤ 1000). Discrepancies discovered each page are persisted before the next page is fetched — satisfying the incremental-persistence criterion.

**Bug 2 (RECON-02 — IN_PROGRESS stuck on failure):** `runForDate()` is `@Transactional`. `runForProviderAndDate()` is a private method called within that single transaction. When `discrepancyRepository.saveAll()` or any other step throws, the exception is caught in `runForDate()`'s per-provider `try/catch` — but at that point Spring has flagged the transaction as rollback-only. Any attempt to write `status = "FAILED"` inside the same catch block will either be silently ignored or throw `TransactionSystemException: Could not commit JPA transaction`. The report is left permanently at `IN_PROGRESS`. The fix is: move each provider's work into its own independent transaction using a dedicated `@Transactional(propagation = REQUIRES_NEW)` method on a separate Spring bean (Spring AOP cannot proxy private methods or self-invocations).

**Primary recommendation:** Add a `ReconciliationProviderRunner` @Service bean with a `@Transactional(propagation = REQUIRES_NEW)` public method that owns one provider's full run (page loop + discrepancy persistence + report status). `ReconciliationService.runForDate()` delegates to this bean per provider. Each provider run is independent — an exception rolls back only that provider's transaction and allows the finally block to write `FAILED` in a new transaction.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| RECON-01 | Reconciliation processes transactions in bounded pages (≤1000 rows per batch) — no full-day set is loaded into heap; discrepancies are persisted incrementally | Requires: (1) new `findForReconciliationPaged(provider, from, to, Pageable)` in `TransactionRepository`; (2) page loop in `LedgerSnapshotService` or directly in the runner; (3) `discrepancyRepository.saveAll(pageDiscrepancies)` inside the loop before fetching next page |
| RECON-02 | When discrepancy persistence fails, the ReconciliationReport transitions to FAILED state — no report is left stuck in IN_PROGRESS | Requires: (1) each provider run executes in its own `REQUIRES_NEW` transaction on a proxied Spring bean; (2) try/finally or catch ensures `report.setStatus("FAILED")` is written in a separate transaction when the main one rolls back |
</phase_requirements>

---

## Standard Stack

No new libraries. All patterns are already present in the codebase.

### Core (already in pom.xml)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Data JPA | via Spring Boot 3.5.11 | `Pageable` + `Page<T>` for paged queries | Already used in `TransactionRepository.adminSearch()` and poller jobs |
| Spring `@Transactional(propagation = REQUIRES_NEW)` | via Spring Boot 3.5.11 | Independent per-provider transaction | Already used in `TrailService`, `WebhookTransitionService`, `UserAdminService` |
| `PageRequest.of(page, PAGE_SIZE)` | Spring Data | Construct pageable slice | Already used in `MtnStatusPollerJob.runPoller()` (size = 100) |

**No new installation needed.**

---

## Architecture Patterns

### RECON-01: Paged Transaction Fetch

The existing `TransactionRepository.findForReconciliation()` returns `List<Transaction>` — no pagination. The pattern used for the status poller (`findByTxStatusAndProviderAndLastModifiedDateBefore` accepts `Pageable`) must be replicated.

**Step A — Add paged query to `TransactionRepository`:**
```java
// Source: existing adminSearch pattern + Pageable from poller pattern
@Query("SELECT t FROM Transaction t WHERE t.provider = :provider " +
       "AND t.createdDate >= :from AND t.createdDate < :to " +
       "AND t.txStatus IN ('SUCCESS','FAILED','PROCESSING') " +
       "AND t.providerRef IS NOT NULL " +
       "ORDER BY t.id ASC")
Page<Transaction> findForReconciliationPaged(
    @Param("provider") MobilePaymentProvider provider,
    @Param("from") Instant from,
    @Param("to") Instant to,
    Pageable pageable);
```

**Step B — Page loop (inside REQUIRES_NEW transaction per provider):**
```java
private static final int PAGE_SIZE = 1000;

int pageNum = 0;
Page<Transaction> page;
do {
    page = transactionRepository.findForReconciliationPaged(
        provider, from, to, PageRequest.of(pageNum, PAGE_SIZE));
    List<ReconciliationDiscrepancy> pageDiscrepancies = new ArrayList<>();
    for (Transaction tx : page.getContent()) {
        ReconciliationDiscrepancy d = compareTransaction(tx, ...);
        if (d != null) pageDiscrepancies.add(d);
        else matched++;
    }
    // Persist BEFORE fetching next page (incremental persistence requirement)
    discrepancyRepository.saveAll(pageDiscrepancies);
    totalChecked += page.getNumberOfElements();
    totalDiscrepancies += pageDiscrepancies.size();
    pageNum++;
} while (!page.isLast());
```

Key design note: `ORDER BY t.id ASC` is mandatory on the paged query. Without a stable sort, keyset-style offset pagination can miss or double-count rows as the cursor advances. Using id ordering gives deterministic pages.

### RECON-02: Independent Per-Provider Transaction via REQUIRES_NEW

**The problem with the current design:**

`runForDate()` is `@Transactional`. `runForProviderAndDate()` is private — Spring AOP cannot proxy it. All provider work runs in the same transaction. If an exception is thrown inside a provider loop, Spring marks the transaction rollback-only. The `catch (Exception e)` in `runForDate()` swallows the exception, but the transaction is tainted. Any subsequent `reportRepository.save(report.setStatus("FAILED"))` inside that catch block is a write to a rollback-only transaction — it either silently discards the save or throws.

**The fix — extract per-provider execution to a proxied bean:**

```java
// New bean: ReconciliationProviderRunner
@Service
public class ReconciliationProviderRunner {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runForProvider(MobilePaymentProvider provider, LocalDate reportDate, ...) {
        ReconciliationReport report = ...; // find or create, set IN_PROGRESS, save
        try {
            // page loop here — saveAll per page
            report.setStatus("COMPLETE");
            reportRepository.save(report);
        } catch (Exception e) {
            log.error(...);
            // This catch block runs inside REQUIRES_NEW — the transaction is not yet rolled back.
            // Mark FAILED, then let the exception propagate to roll back cleanly.
            report.setStatus("FAILED");
            reportRepository.save(report);  // committed before rollback only if done before rethrow
            throw e;  // rethrow so REQUIRES_NEW transaction rolls back and leaves FAILED written
        }
    }
}
```

**Critical subtlety — FAILED write must commit BEFORE rollback:**

Option A (preferred): Use a separate `REQUIRES_NEW` transaction to write the FAILED status after the main provider transaction has already rolled back:

```java
// In ReconciliationProviderRunner
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void runForProvider(...) {
    ReconciliationReport report = createOrReset(reportDate, provider); // IN_PROGRESS
    try {
        runPageLoop(report, ...);
        report.setStatus("COMPLETE");
        reportRepository.save(report);
    } catch (Exception e) {
        log.error("Provider run failed", kv("provider", provider), e);
        throw e;   // REQUIRES_NEW rolls back — IN_PROGRESS reverts too
    }
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void markFailed(Long reportId) {
    ReconciliationReport report = reportRepository.findById(reportId).orElseThrow();
    report.setStatus("FAILED");
    reportRepository.save(report);
}
```

Then in `runForDate()`:
```java
for (provider : providers) {
    ReconciliationReport report = runner.createOrReset(reportDate, provider); // own tx
    try {
        runner.runForProvider(report, provider, reportDate, ...);
    } catch (Exception e) {
        log.error(...);
        runner.markFailed(report.getId()); // own REQUIRES_NEW tx, always commits
    }
}
```

Option B (simpler): Keep a single `REQUIRES_NEW` method with try/catch that writes FAILED before rethrowing. However, this approach requires the FAILED write to happen INSIDE the REQUIRES_NEW transaction before it rolls back — which means catching exception, writing FAILED, then flushing before rethrow. This is fragile because if writing FAILED also fails, both writes are lost.

**Recommended: Option A** — separate `markFailed()` method with its own `REQUIRES_NEW` guarantees FAILED is committed even if the original transaction's rollback was caused by a persistence exception.

### Anti-Patterns to Avoid

- **Private method with `@Transactional`:** Spring AOP cannot proxy private methods. Annotating `runForProviderAndDate()` with `@Transactional(propagation = REQUIRES_NEW)` has no effect — it is already private. The bean split is mandatory.
- **Self-invocation:** Calling `this.runForProviderAndDate()` inside `ReconciliationService` bypasses the AOP proxy even if the method is public. The call must go through a different injected bean.
- **Catching and swallowing inside rollback-only transaction:** Any write attempted after an exception inside a `@Transactional` method is a write into a rollback-only Hibernate session — it is silently queued but never committed. This is the root cause of the current RECON-02 bug.
- **Unstable sort on paged query:** Omitting `ORDER BY` on a `Page<T>` query produces non-deterministic pagination — the same row can appear on multiple pages or be skipped entirely. Always sort by `id ASC` or a stable unique column.
- **Persisting all discrepancies after the loop:** Collecting all discrepancies into one list and calling `saveAll` once at the end (current code) defeats the incremental-persistence requirement of RECON-01. Persist per page inside the loop.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Paged DB iteration | Custom LIMIT/OFFSET with JdbcTemplate | `Page<T>` + `Pageable` from Spring Data JPA | Spring Data handles offset math, last-page detection, total count; already in codebase |
| Independent per-unit transaction | Manual `Connection.setAutoCommit(true)` or nested try/catch flushing | `@Transactional(propagation = REQUIRES_NEW)` on a separate Spring bean | Spring handles savepoint, commit, rollback cleanly; already proven in `TrailService` |
| "Write FAILED on exception" without a second transaction | Catching exception inside rollback-only transaction and calling `save()` | Separate `REQUIRES_NEW` method for the FAILED write | Rollback-only transactions silently discard further writes |

---

## Common Pitfalls

### Pitfall 1: Writing FAILED into a rollback-only transaction
**What goes wrong:** Exception thrown during `discrepancyRepository.saveAll()` marks the current `@Transactional` context as rollback-only. Any subsequent `reportRepository.save()` with status `FAILED` is accepted by Hibernate (no exception) but discarded at flush time. The commit throws `TransactionSystemException` or the transaction just rolls back, leaving the report at `IN_PROGRESS`.
**Why it happens:** Spring's `@Transactional` semantics: once a RuntimeException escapes a `@Transactional` method boundary, the transaction is marked rollback-only and nothing can prevent rollback.
**How to avoid:** Write FAILED status in a separate, independent `@Transactional(propagation = REQUIRES_NEW)` method after the original transaction has rolled back.
**Warning signs:** Report stays permanently `IN_PROGRESS` despite the error log showing the exception. `TransactionSystemException: Could not commit JPA transaction` appears in logs.

### Pitfall 2: Self-invocation bypasses REQUIRES_NEW
**What goes wrong:** Adding `@Transactional(propagation = REQUIRES_NEW)` to a method in the same class and calling it via `this.method()` has no effect — Spring AOP proxy is bypassed.
**Why it happens:** Spring AOP wraps the bean, not `this`. Internal calls go directly to the target object, not through the proxy.
**How to avoid:** Extract the REQUIRES_NEW method to a separate `@Service` bean. Inject that bean and call through it.
**Warning signs:** The method has the annotation but rollback from the outer transaction still rolls back the inner work.

### Pitfall 3: Missing ORDER BY on paged query
**What goes wrong:** Without `ORDER BY`, Postgres may return rows in arbitrary order across pages. Row X can appear on page 0 and page 1, or be omitted entirely when the planner changes plan between queries.
**Why it happens:** SQL has no guaranteed row order without explicit ORDER BY.
**How to avoid:** Add `ORDER BY t.id ASC` to `findForReconciliationPaged`.
**Warning signs:** `totalChecked` in the report does not match actual transaction count; discrepancies are duplicated or missing.

### Pitfall 4: Off-by-one on last page detection
**What goes wrong:** Iterating with `while (page.hasNext())` and calling `getNextPageable()` can skip the processing of page content when written incorrectly. The do/while pattern shown above is safer.
**Why it happens:** `page.hasNext()` is false on the last page, so a `while (page.hasNext())` loop processes 0..N-2 pages only.
**How to avoid:** Use `do { ... } while (!page.isLast())` or `while (true) { ...; if (page.isLast()) break; }`.

### Pitfall 5: Existing E2E tests may fail if runForDate() transaction scope changes
**What goes wrong:** `DailyReconciliationE2ETest` and `ReconciliationJobIT` both call `reconciliationService.runForDate()` directly and then assert DB state in the same test method. If the REQUIRES_NEW transactions for each provider now commit independently, the test transactions and cleanup in `@AfterEach` still work correctly (Testcontainers Postgres, not H2). However, if any test relies on a rolled-back outer transaction to clean up, that assumption breaks.
**Why it happens:** Isolation level change from single transaction to multiple REQUIRES_NEW transactions changes visibility guarantees in tests.
**How to avoid:** Check that all existing tests remain green after refactoring. The `@AfterEach` in both test classes uses explicit JDBC DELETEs rather than `@Transactional` rollback — they are safe.

---

## Code Examples

### Paged Query — TransactionRepository (new method)
```java
// Source: existing adminSearch() + findByTxStatusAndProviderAndLastModifiedDateBefore() patterns
@Query("SELECT t FROM Transaction t WHERE t.provider = :provider " +
       "AND t.createdDate >= :from AND t.createdDate < :to " +
       "AND t.txStatus IN ('SUCCESS','FAILED','PROCESSING') " +
       "AND t.providerRef IS NOT NULL " +
       "ORDER BY t.id ASC")
Page<Transaction> findForReconciliationPaged(
    @Param("provider") MobilePaymentProvider provider,
    @Param("from") Instant from,
    @Param("to") Instant to,
    Pageable pageable);
```

### REQUIRES_NEW provider runner bean (skeleton)
```java
@Service
@RequiredArgsConstructor
public class ReconciliationProviderRunner {

    private static final int PAGE_SIZE = 1000;

    private final TransactionRepository transactionRepository;
    private final ReconciliationReportRepository reportRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;

    /** Creates/resets the report row in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconciliationReport createOrReset(LocalDate reportDate, MobilePaymentProvider provider) {
        ReconciliationReport report = reportRepository
            .findByReportDateAndProvider(reportDate, provider)
            .orElseGet(() -> ReconciliationReport.builder()
                .reportDate(reportDate).provider(provider)
                .runAt(Instant.now()).totalChecked(0)
                .totalMatched(0).totalDiscrepancies(0)
                .status("IN_PROGRESS").build());
        report.setStatus("IN_PROGRESS");
        return reportRepository.save(report);
    }

    /** Runs the full page loop for one provider. Own REQUIRES_NEW transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runForProvider(ReconciliationReport report,
                               MobilePaymentProvider provider,
                               LocalDate reportDate,
                               ProviderReportPort port,
                               Instant from, Instant to) {
        int totalChecked = 0, totalMatched = 0, totalDiscrepancies = 0;
        int pageNum = 0;
        Page<Transaction> page;
        do {
            page = transactionRepository.findForReconciliationPaged(
                provider, from, to, PageRequest.of(pageNum, PAGE_SIZE));
            List<ReconciliationDiscrepancy> pageDiscrepancies = new ArrayList<>();
            for (Transaction tx : page.getContent()) {
                ProviderTransactionRecord record = port.fetchProviderRecord(tx.getProviderRef(), reportDate);
                ReconciliationDiscrepancy d = compareTransaction(tx, record, report.getId(), reportDate, provider);
                if (d != null) pageDiscrepancies.add(d);
                else totalMatched++;
            }
            discrepancyRepository.saveAll(pageDiscrepancies); // persist before next page
            totalChecked += page.getNumberOfElements();
            totalDiscrepancies += pageDiscrepancies.size();
            pageNum++;
        } while (!page.isLast());

        report.setTotalChecked(totalChecked);
        report.setTotalMatched(totalMatched);
        report.setTotalDiscrepancies(totalDiscrepancies);
        report.setStatus("COMPLETE");
        reportRepository.save(report);
    }

    /** Writes FAILED status in its own independent transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long reportId) {
        reportRepository.findById(reportId).ifPresent(r -> {
            r.setStatus("FAILED");
            reportRepository.save(r);
        });
    }
}
```

### Updated ReconciliationService.runForDate() (skeleton)
```java
// runForDate() no longer needs @Transactional — each provider has its own
public void runForDate(LocalDate reportDate) {
    Instant from = reportDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant to = reportDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    for (MobilePaymentProvider provider : new MobilePaymentProvider[]{MTN, ORANGE}) {
        ProviderReportPort port = providerPorts.get(provider);
        if (port == null) { /* log skip */ continue; }

        ReconciliationReport report = runner.createOrReset(reportDate, provider);
        try {
            runner.runForProvider(report, provider, reportDate, port, from, to);
        } catch (Exception e) {
            log.error("Reconciliation provider run failed",
                kv("provider", provider), kv("reportId", report.getId()), e);
            runner.markFailed(report.getId()); // REQUIRES_NEW — always commits
        }
    }
}
```

### New E2E test cases (to add)

**RECON-01 pagination test (new `@Test` in `ReconciliationJobIT` or new class):**
```java
@Test
void runForDate_processesLargeDataset_withoutUnboundedHeap() {
    // Seed 1001 MTN transactions for YESTERDAY
    // Call reconciliationService.runForDate(YESTERDAY)
    // Assert report.totalChecked == 1001
    // Assert all discrepancies persisted (provider mock returns null status → all MISSING)
    // Heap growth assertion is implicit: if the query loaded all rows, OOM would occur
    // For a unit-level guarantee, verify findForReconciliationPaged is called with Pageable
}
```

**RECON-02 FAILED state test (new `@Test` in `ReconciliationJobIT`):**
```java
@Test
void runForDate_marksReportFailed_whenDiscrepancyPersistenceThrows() {
    // Mock discrepancyRepository (or use a spy) to throw on saveAll
    // Call reconciliationService.runForDate(YESTERDAY)
    // Assert report.status == "FAILED" (not "IN_PROGRESS")
}
```

Note: The FAILED state test requires either a spy on `discrepancyRepository` or a dedicated unit test for `ReconciliationProviderRunner` that uses a mocked repository. Integration-level testing of RECON-02 is harder because it requires making `saveAll` throw inside a Spring-managed transaction. A unit test with Mockito is preferred for RECON-02 verification.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `List<T>` bulk load | `Page<T>` + Pageable paging | Spring Data 2.x+ | Bounded heap regardless of dataset size |
| Exception swallowing in `@Transactional` | `REQUIRES_NEW` bean + separate FAILED write tx | Long-established Spring pattern | Report status is always terminal (COMPLETE or FAILED) |

---

## Open Questions

1. **Should `compareTransaction` logic stay in `ReconciliationService` or move to `ReconciliationProviderRunner`?**
   - What we know: it is a pure function with no Spring dependencies; it can live in either class.
   - What's unclear: coupling vs cohesion — moving it creates a larger `ReconciliationProviderRunner`.
   - Recommendation: Keep it in `ReconciliationService`; pass it as a method reference or move it to a static utility. The comparison logic is orthogonal to transaction management.

2. **Should the old `findForReconciliation` (returning `List<Transaction>`) be deleted or kept?**
   - What we know: it is only used by `LedgerSnapshotService.findTransactionsForDateAndProvider()`, which in turn is only used by `ReconciliationService`.
   - Recommendation: Deprecate and remove the old method. Keep only the paged variant to prevent future callers from reintroducing the unbounded load.

3. **Should `LedgerSnapshotService` still exist after the refactor?**
   - What we know: its current responsibility (call `findForReconciliation`) moves into the page loop inside `ReconciliationProviderRunner`.
   - Recommendation: Either delete `LedgerSnapshotService` (if the page loop is fully in the runner) or update it to accept `Pageable` and return `Page<Transaction>`. Deleting it reduces indirection. Update existing tests that may reference it.

---

## Environment Availability

Step 2.6: SKIPPED (no external dependencies beyond the existing Spring Boot + PostgreSQL stack, which is already verified in Phase 35).

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + AssertJ |
| Config file | `pom.xml` (maven-failsafe-plugin for IT tests) |
| Quick run command | `mvn test -pl . -Dtest=ReconciliationJobIT -Dfailsafe.skip=true` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| RECON-01 | 1001 transactions processed in ≤1000-row pages; discrepancies persisted per page | Integration | `mvn verify -Dtest=ReconciliationJobIT` | ✅ (extend existing file) |
| RECON-01 | `findForReconciliationPaged` called with Pageable | Unit | `mvn test -Dtest=ReconciliationProviderRunnerTest` | ❌ Wave 0 |
| RECON-02 | Report transitions to FAILED when saveAll throws | Unit | `mvn test -Dtest=ReconciliationProviderRunnerTest` | ❌ Wave 0 |
| RECON-02 | Report is never left IN_PROGRESS after exception | Integration | `mvn verify -Dtest=ReconciliationJobIT` | ✅ (extend existing file) |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=ReconciliationProviderRunnerTest,ReconciliationJobIT`
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full `mvn verify` green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/reconciliation/ReconciliationProviderRunnerTest.java` — unit tests for RECON-01 (Pageable call) and RECON-02 (FAILED transition)
- [ ] New `@Test` in `ReconciliationJobIT`: integration test seeding 1001 rows, asserting `totalChecked == 1001`
- [ ] New `@Test` in `ReconciliationJobIT`: integration test asserting `FAILED` status when discrepancy persistence throws (may require spy)

---

## Sources

### Primary (HIGH confidence)
- Direct source code inspection of `ReconciliationService`, `LedgerSnapshotService`, `TransactionRepository`, `ReconciliationReport`, `ReconciliationJob` — all patterns observed in-project
- Existing test files: `ReconciliationJobIT`, `DailyReconciliationE2ETest`, `ReconciliationApiIT` — test structure confirmed
- Existing `REQUIRES_NEW` usage: `TrailService`, `WebhookTransitionService`, `UserAdminService` — pattern verified in codebase
- Existing `Pageable` usage: `MtnStatusPollerJob`, `TenantAdminResource`, `AdminTransactionResource` — pattern verified

### Secondary (MEDIUM confidence)
- Spring Framework documentation on `@Transactional` propagation semantics (rollback-only behaviour is well-documented and consistent across Spring 5+/6+)
- Spring Data JPA `Page<T>` / `Pageable` pagination API — stable since Spring Data 1.x

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new libraries; all patterns verified in existing codebase
- Architecture: HIGH — `REQUIRES_NEW` and `Page<T>` patterns directly confirmed in other services in this project
- Pitfalls: HIGH — rollback-only and self-invocation pitfalls are well-understood Spring behaviours; confirmed by code inspection

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (stable Spring Boot ecosystem)
