package com.softropic.payam.e2e.verify;

import com.softropic.payam.utils.sql.SqlStatementHolder;
import net.ttddyy.dsproxy.QueryCountHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIF-10: N+1 query regression detection wrapping the SqlStatementHolder thread-local
 * and the datasource-proxy QueryCountHolder.
 *
 * <p><strong>Precondition:</strong> The datasource-proxy spy must be activated before using
 * this verifier. The spy is controlled by the {@code log.database.spy=true} test property,
 * which activates {@code TestConfig.spyDataSource}. This verifier does NOT activate the spy —
 * the test class or test properties file must enable it. Attempting to use this verifier without
 * the spy active will result in all counts being 0 (no queries are recorded).</p>
 *
 * <p>Typical usage in a test:</p>
 * <pre>{@code
 *   queryCountVerifier.reset();
 *   // ... execute the operation under test ...
 *   queryCountVerifier.assertSelectCountAtMost(5);
 * }</pre>
 */
public class QueryCountVerifier {

    /**
     * Clears all query counters in the current thread's SqlStatementHolder
     * and the datasource-proxy QueryCountHolder grand total.
     * Call this immediately before the operation under test.
     */
    public void reset() {
        SqlStatementHolder.initStatement();
        QueryCountHolder.clear();
    }

    /**
     * Asserts that the number of SELECT queries executed since the last {@link #reset()} call
     * is at most {@code maxSelects}.
     *
     * Uses {@code QueryCountHolder.getGrandTotal().getSelect()} from datasource-proxy,
     * which is populated by the {@code .countQuery()} configuration in {@code TestConfig.spyDataSource}.
     *
     * @param maxSelects the upper bound on SELECT query count (inclusive)
     */
    public void assertSelectCountAtMost(int maxSelects) {
        long actual = QueryCountHolder.getGrandTotal().getSelect();
        assertThat(actual)
            .as("SELECT query count must be at most %d (N+1 regression check)", maxSelects)
            .isLessThanOrEqualTo(maxSelects);
    }

    /**
     * Asserts that the number of SELECT queries executed since the last {@link #reset()} call
     * is exactly {@code expectedSelects}.
     *
     * Uses {@code SqlStatementHolder.getStatement().assertThatSelect().hasCount()} for exact assertion.
     *
     * @param expectedSelects the exact expected SELECT query count
     */
    public void assertSelectCountExact(int expectedSelects) {
        SqlStatementHolder.getStatement().assertThatSelect().hasCount(expectedSelects);
    }
}
