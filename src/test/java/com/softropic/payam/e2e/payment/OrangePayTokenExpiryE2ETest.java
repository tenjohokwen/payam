package com.softropic.payam.e2e.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractFailureFlowTest;
import com.softropic.payam.e2e.builder.DeterministicUuidFactory;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.CacheVerifier;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.orange.service.OrangeStatusPollerJob;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.tenant.repo.TenantRepository;
import com.softropic.payam.tenant.service.TenantService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-PAY-04: Orange payToken expiry — poller detects expired payToken, increments
 * pollAttempts, leaves transaction in PROCESSING (re-initiation is Phase 5 responsibility).
 *
 * <p>The payToken is deliberately backdated via {@code pay_token_issued_at} to a timestamp
 * 1 year in the past. The default expiry threshold is 8 minutes, so the poller's
 * {@code assertPayTokenFresh()} call throws {@code PayTokenExpiredException}, the poller
 * increments {@code pollAttempts} and returns early without transitioning to FAILED.
 *
 * <p>This test asserts the ACTUAL production behavior on payToken expiry: the transaction
 * remains PROCESSING and no ledger entries are created. The idempotency key written for
 * the original 202 PROCESSING response IS still in cache.
 *
 * <p>Extends {@link AbstractFailureFlowTest} — phase order:
 * setupPreconditions → injectFault → executeFlow → verifyFailureHandled.
 *
 * <p>Note: {@code injectFault()} is a no-op here because the expiry fault is injected
 * via SQL in {@code verifyFailureHandled()} after the transactionId is known.
 */
public class OrangePayTokenExpiryE2ETest extends AbstractFailureFlowTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrangeStatusPollerJob orangeStatusPollerJob;

    private TenantBuilder.CreatedTenant tenant;
    private String transactionId;
    private String idempotencyKey;
    private String providerRef;
    private InvariantVerifier invariant;
    private DeterministicUuidFactory uuidFactory;

    @Override
    protected void setupPreconditions() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // Stub Orange initiation stubs — subscriber info, merchant info, and pay endpoint.
        // The /mp/paymentstatus endpoint is intentionally NOT stubbed here — the poller
        // must NOT reach it once payToken expiry is detected (expiry triggers an early return).
        orangeServer.stubFor(get(urlPathEqualTo("/infos/subscriber"))
            .willReturn(okJson("{\"status\":\"ACTIF\",\"message\":\"OK\"}")));
        orangeServer.stubFor(post(urlPathEqualTo("/mp/init"))
            .willReturn(okJson("{\"data\":{\"payToken\":\"tok-expiry-test\"},\"message\":\"OK\"}")));
        orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
            .willReturn(okJson("{\"status\":\"SUCCESS\",\"message\":\"OK\"}")));

        tenant = new TenantBuilder()
            .withName("Orange-Expiry-Tenant")
            .create(tenantService, tenantRepository);

        uuidFactory = new DeterministicUuidFactory(0xEF0AB1L);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    /**
     * No-op — the expiry fault is injected via SQL in {@link #verifyFailureHandled()}
     * after the transactionId is known from the 202 response.
     */
    @Override
    protected void injectFault() {
        // Fault injection (backdating pay_token_issued_at) deferred to verifyFailureHandled()
        // because the transactionId is not yet known at this phase of the lifecycle.
    }

    @Override
    protected void executeFlow() {
        PaymentRequest req = new PaymentRequestBuilder()
            .forOrange()
            .withDeterministicIdempotencyKey(uuidFactory)
            .build();
        idempotencyKey = req.idempotencyKey();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

        String body;
        try {
            body = new ObjectMapper().writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize PaymentRequest", e);
        }

        ResponseEntity<PaymentResponse> response = new RestTemplate()
            .exchange(
                "http://localhost:" + serverPort + "/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                PaymentResponse.class);

        assertThat(response.getStatusCode().value())
            .as("POST /v1/payments must return 202 Accepted for Orange payment")
            .isEqualTo(202);

        assertThat(response.getBody()).isNotNull();
        transactionId = response.getBody().transactionId();
        providerRef = response.getBody().providerRef();

        assertThat(transactionId)
            .as("transactionId must be non-null in 202 response")
            .isNotNull();
        assertThat(providerRef)
            .as("providerRef (payToken) must be non-null in 202 response for Orange payment")
            .isNotNull();
    }

    @Override
    protected void verifyFailureHandled() {
        // Inject fault: backdate pay_token_issued_at to force payToken expiry.
        // Default threshold is 8 minutes. Setting issuance time to 1 year ago guarantees
        // age.toMinutes() (≈ 525,600) >= 8, triggering PayTokenExpiredException in assertPayTokenFresh().
        // Use REQUIRES_NEW so the update commits immediately in its own transaction, independent
        // of any JPA session. Without this, Hibernate's L1 cache flush on the outer transaction
        // commit silently overwrites the raw JDBC change with the original entity state.
        final Instant now = Instant.now();
        Instant backdated = now.minus(7, ChronoUnit.MINUTES);
        DefaultTransactionDefinition requiresNew = new DefaultTransactionDefinition();
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactionManager, requiresNew).execute(status -> {
            jdbcTemplate.update(
                    "UPDATE main.transaction SET pay_token_issued_at = NOW() - INTERVAL '1 year', " +
                "last_modified_date = ? WHERE transaction_id = ?",
                    Timestamp.from(backdated), transactionId);
            return null;
        });

        // Capture poll_attempts before poller runs so we can assert the increment.
        Integer attemptsBefore = jdbcTemplate.queryForObject(
            "SELECT poll_attempts FROM main.transaction WHERE transaction_id = ?",
            Integer.class, transactionId);
        int before = attemptsBefore != null ? attemptsBefore : 0;

        // Trigger Orange poller directly (bypasses Quartz scheduler timing).
        //
        // OrangeStatusPollerJob.executeInternal is @Transactional. However, reflection on a
        // protected method from OrangeStatusPollerJob.class invoked on a CGLIB proxy object does
        // NOT go through CGLIB's method interception (the method reference bypasses the proxy
        // dispatch mechanism). Without @Transactional, dirty-tracked entity changes from
        // tx.incrementPollAttempts() are never flushed to the DB.
        //
        // Solution: wrap the reflection call in a TransactionTemplate so JPA's EntityManager
        // participates in a managed transaction and flushes on commit.
        //
        // OrangeStatusPollerJob does not use the JobExecutionContext parameter — passing null is safe.
        try {
            java.lang.reflect.Method exec = OrangeStatusPollerJob.class
                .getDeclaredMethod("executeInternal", org.quartz.JobExecutionContext.class);
            exec.setAccessible(true);
            transactionTemplate.execute(status -> {
                try {
                    exec.invoke(orangeStatusPollerJob, (Object) null);
                } catch (Exception e) {
                    throw new RuntimeException("OrangeStatusPollerJob.executeInternal failed", e);
                }
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke OrangeStatusPollerJob.executeInternal", e);
        }

        // Assert: payToken expiry causes poller to increment pollAttempts and return early.
        // The transaction stays in PROCESSING — transitioning to FAILED requires pollAttempts >= 15
        // and the expiry check must NOT trigger (a separate code path). Re-initiation is Phase 5 scope.
        String status = jdbcTemplate.queryForObject(
            "SELECT tx_status FROM main.transaction WHERE transaction_id = ?",
            String.class, transactionId);
        assertThat(status)
            .as("Transaction must remain PROCESSING when payToken expires " +
                "(poller increments attempts and returns early — FAILED only on pollAttempts overflow)")
            .isEqualTo("PROCESSING");

        // Assert: pollAttempts incremented by exactly 1 — proves expiry path executed.
        Integer attemptsAfter = jdbcTemplate.queryForObject(
            "SELECT poll_attempts FROM main.transaction WHERE transaction_id = ?",
            Integer.class, transactionId);
        int after = attemptsAfter != null ? attemptsAfter : 0;
        assertThat(after)
            .as("pollAttempts must increment by 1 on payToken expiry (before=%d, after=%d)",
                before, after)
            .isEqualTo(before + 1);

        // Assert: no ledger entries — PROCESSING path without SUCCESS never posts to ledger.
        invariant.ledger().assertNoLedgerEntries(transactionId);

        // Assert: idempotency key IS present — the original 202 PROCESSING response was committed.
        // "Not consumed" means the FAILED outcome was not cached; the key itself is still present.
        new CacheVerifier(redis).assertIdempotencyKeyPresent(tenant.tenantId(), idempotencyKey);
    }
}
