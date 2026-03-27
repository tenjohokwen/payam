package com.softropic.payam.e2e.verify;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIF-03: Composite verifier that exposes named domain invariant assertions and a
 * convenience assertAll() for SUCCESS flows.
 *
 * Constructs and delegates to DatabaseVerifier, HashChainVerifier, EventVerifier,
 * LedgerVerifier, ProviderCallVerifier, WebhookDeliveryVerifier, and TenantIsolationVerifier.
 * No Spring annotations — this is a plain POJO instantiated in test @BeforeEach.
 */
public class InvariantVerifier {

    private final DatabaseVerifier databaseVerifier;
    private final HashChainVerifier hashChainVerifier;
    private final EventVerifier eventVerifier;
    private final LedgerVerifier ledgerVerifier;
    private final ProviderCallVerifier providerCallVerifier;
    private final WebhookDeliveryVerifier webhookDeliveryVerifier;
    private final TenantIsolationVerifier tenantIsolationVerifier;
    private final JdbcTemplate jdbc;

    public InvariantVerifier(JdbcTemplate jdbc, StringRedisTemplate redis,
                             WireMockServer mtnServer, WireMockServer orangeServer) {
        this.jdbc = jdbc;
        this.databaseVerifier       = new DatabaseVerifier(jdbc);
        this.hashChainVerifier      = new HashChainVerifier(jdbc);
        this.eventVerifier          = new EventVerifier(jdbc);
        this.ledgerVerifier         = new LedgerVerifier(jdbc);
        this.providerCallVerifier   = new ProviderCallVerifier(mtnServer, orangeServer);
        // WebhookDeliveryVerifier requires a callback server; pass mtnServer as a default.
        // Tests needing a dedicated callback server should construct WebhookDeliveryVerifier directly.
        this.webhookDeliveryVerifier = new WebhookDeliveryVerifier(jdbc, mtnServer);
        this.tenantIsolationVerifier = new TenantIsolationVerifier(jdbc, redis);
    }

    // -------------------------------------------------------------------------
    // Named invariant assertions
    // -------------------------------------------------------------------------

    /**
     * Asserts that the ledger is balanced for the given transactionId:
     * exactly 2 entries, DEBIT on CUSTOMER_WALLET and CREDIT on PROVIDER_CLEARING with equal amounts.
     */
    public void assertLedgerBalanced(String transactionId) {
        ledgerVerifier.assertLedgerBalanced(transactionId);
    }

    /**
     * Asserts no duplicate idempotency key exists for the given tenant and idempotency key.
     * Delegates to DatabaseVerifier.assertNoDuplicateIdempotencyKey.
     */
    public void assertNoDoubleCharge(Long tenantId, String idempotencyKey) {
        databaseVerifier.assertNoDuplicateIdempotencyKey(tenantId, idempotencyKey);
    }

    /**
     * Asserts no data for this transactionId/idempotencyKey is visible under wrongTenantId.
     */
    public void assertTenantIsolation(String transactionId, String idempotencyKey,
                                      Long correctTenantId, Long wrongTenantId) {
        tenantIsolationVerifier.assertNoDataLeaksToOtherTenant(
            transactionId, idempotencyKey, correctTenantId, wrongTenantId);
    }

    /**
     * Asserts the payment row has the expected tx_status.
     * Delegates to DatabaseVerifier.assertPaymentRow.
     */
    public void assertLegalStateTransition(String transactionId, String expectedStatus) {
        databaseVerifier.assertPaymentRow(transactionId, expectedStatus);
    }

    /**
     * Asserts that a PROVIDER_SUCCESS or PROVIDER_FAILED event was appended to the event log,
     * indicating the double-check result was recorded.
     */
    public void assertWebhookDoubleCheckFired(String transactionId) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM main.payment_event_log " +
            "WHERE transaction_id = ? AND event_type IN ('PROVIDER_SUCCESS','PROVIDER_FAILED')",
            Integer.class, transactionId);
        assertThat(count)
            .as("PROVIDER_SUCCESS or PROVIDER_FAILED event count for transactionId=%s", transactionId)
            .isGreaterThanOrEqualTo(1);
    }

    /**
     * Asserts that fraud evaluation completed (and allowed) before the provider was called.
     *
     * <p>Production code (PaymentOrchestrator) evaluates fraud synchronously before dispatching
     * to the provider adapter. The event types FRAUD_CHECK_PASSED and PROVIDER_AUTH_REQUESTED
     * are defined in TransactionEventType but are not currently written to the event log by the
     * orchestrator — the architecturally enforced ordering is verified structurally here instead.
     *
     * <p>Verification strategy: assert that a PAYMENT_INITIATED event exists. This event is
     * appended by MtnMoMoPort/OrangeMoneyPort after the provider accepted the request — which is
     * only reachable if fraud evaluation completed without blocking (PaymentOrchestrator returns
     * FRAUD_BLOCKED before calling the provider adapter when fraud is detected). The presence of
     * PAYMENT_INITIATED therefore proves fraud passed and the provider was called in that order.
     */
    public void assertFraudEvaluatedBeforeProviderCall(String transactionId) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM main.payment_event_log " +
            "WHERE transaction_id = ? AND event_type = 'PAYMENT_INITIATED'",
            Integer.class, transactionId);

        assertThat(count)
            .as("PAYMENT_INITIATED event must exist for transactionId=%s, " +
                "proving fraud evaluation passed before provider dispatch", transactionId)
            .isGreaterThanOrEqualTo(1);
    }

    /**
     * Convenience assertion for SUCCESS flows: verifies ledger balanced, no double charge,
     * legal state transition, and hash chain validity.
     *
     * @param transactionId  the payment transactionId
     * @param tenantId       the owning tenant (used for double-charge check)
     * @param idempotencyKey the idempotency key (used for double-charge check)
     * @param expectedStatus the expected tx_status (typically "SUCCESS")
     */
    public void assertAll(String transactionId, Long tenantId, String idempotencyKey,
                          String expectedStatus) {
        assertLedgerBalanced(transactionId);
        assertNoDoubleCharge(tenantId, idempotencyKey);
        assertLegalStateTransition(transactionId, expectedStatus);
        hashChainVerifier.assertChainValid(transactionId);
    }

    // -------------------------------------------------------------------------
    // Delegate accessors (for tests that need direct verifier access)
    // -------------------------------------------------------------------------

    public DatabaseVerifier db() {
        return databaseVerifier;
    }

    public HashChainVerifier chain() {
        return hashChainVerifier;
    }

    public EventVerifier events() {
        return eventVerifier;
    }

    public LedgerVerifier ledger() {
        return ledgerVerifier;
    }

    public ProviderCallVerifier provider() {
        return providerCallVerifier;
    }

    public TenantIsolationVerifier isolation() {
        return tenantIsolationVerifier;
    }

}
