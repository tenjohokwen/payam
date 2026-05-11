package com.softropic.payam.e2e.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractPaymentFlowTest;
import com.softropic.payam.e2e.builder.DeterministicUuidFactory;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.CacheVerifier;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.payment.provider.mtn.service.MtnStatusPollerJob;
import com.softropic.payam.payment.core.contract.PaymentRequest;
import com.softropic.payam.payment.core.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-PAY-03: MTN polling fallback — no webhook arrives; poller drives SUCCESS.
 *
 * <p>The payment reaches PROCESSING normally via POST /v1/payments. No inbound callback
 * is dispatched. The last_modified_date is backdated to put the transaction past the
 * 2-minute poller cutoff, then MtnStatusPollerJob is invoked directly (bypassing Quartz
 * scheduler timing). The poller calls the stubbed GET status endpoint and transitions
 * the transaction to SUCCESS synchronously.
 *
 * <p>Extends {@link AbstractPaymentFlowTest} — phase order:
 * setupPreconditions → executeFlow → simulateProviderCallback (no-op) → verifyFinalState.
 */
public class MtnPollingFallbackE2ETest extends AbstractPaymentFlowTest {

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
    private MtnStatusPollerJob mtnStatusPollerJob;

    private TenantBuilder.CreatedTenant tenant;
    private String transactionId;
    private String idempotencyKey;
    private InvariantVerifier invariant;
    private DeterministicUuidFactory uuidFactory;

    @Override
    protected void setupPreconditions() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // Stub MTN initiation endpoints. The GET status endpoint is NOT stubbed here —
        // it will be stubbed in verifyFinalState() just before poller invocation (pitfall 6 guard).
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        tenant = new TenantBuilder()
            .withName("MTN-Poll-Tenant")
            .create(tenantService, tenantRepository);

        uuidFactory = new DeterministicUuidFactory(0xBCDEFL);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    @Override
    protected void executeFlow() {
        PaymentRequest req = new PaymentRequestBuilder()
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
            .as("POST /v1/payments must return 202 Accepted")
            .isEqualTo(202);

        assertThat(response.getBody()).isNotNull();
        transactionId = response.getBody().transactionId();
        assertThat(transactionId).as("transactionId must be non-null in 202 response").isNotNull();
    }

    @Override
    protected void simulateProviderCallback() {
        // Intentionally empty — this is the polling fallback path. No webhook arrives.
        // The Quartz scheduler would normally trigger the poller; here we invoke it directly
        // in verifyFinalState() after backdating last_modified_date.
    }

    @Override
    protected void verifyFinalState() {
        // Pitfall 6: Backdate last_modified_date so the poller's 2-minute cutoff is satisfied.
        // A freshly created PROCESSING transaction has lastModifiedDate=now, which is NOT before
        // the 2-minute cutoff. Without this backdating, executeInternal() logs "stuckCount=0"
        // and the transaction stays PROCESSING indefinitely.
        // Backdate last_modified_date so the poller's 2-minute cutoff is satisfied.
        // Use REQUIRES_NEW to immediately commit the backdate in its own transaction,
        // independent of any active JPA session or L1 cache.
        Instant backdated = Instant.now().minus(7, ChronoUnit.MINUTES);
        DefaultTransactionDefinition requiresNew = new DefaultTransactionDefinition();
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactionManager, requiresNew).execute(status -> {
            jdbcTemplate.update(
                    "UPDATE main.transaction SET last_modified_date = ? WHERE transaction_id = ?",
                    Timestamp.from(backdated), transactionId);
            return null;
        });

        // Stub the GET status endpoint with SUCCESSFUL BEFORE invoking poller (pitfall: stub must
        // exist before poller calls the provider — not stubbed in setupPreconditions to keep
        // intent clear: this stub is only needed for the polling path).
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-poll-001\"}")));

        // Direct poller invocation via reflection inside an explicit TransactionTemplate.
        //
        // MtnStatusPollerJob.executeInternal is @Transactional.  However, reflection on a
        // protected method from MtnStatusPollerJob.class invoked on a CGLIB proxy object does
        // NOT go through CGLIB's method interception (the method reference bypasses the proxy
        // dispatch mechanism).  Without @Transactional, dirty-tracked entity changes from
        // applyTransition() are never flushed to the DB.
        //
        // Solution: wrap the reflection call in a TransactionTemplate so JPA's EntityManager
        // participates in a managed transaction and flushes on commit.
        //
        // MtnStatusPollerJob does not use the JobExecutionContext parameter — passing null is safe.
        try {
            java.lang.reflect.Method exec = MtnStatusPollerJob.class
                .getDeclaredMethod("executeInternal", org.quartz.JobExecutionContext.class);
            exec.setAccessible(true);
            transactionTemplate.execute(status -> {
                try {
                    exec.invoke(mtnStatusPollerJob, (Object) null);
                } catch (Exception e) {
                    throw new RuntimeException("MtnStatusPollerJob.executeInternal failed", e);
                }
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke MtnStatusPollerJob.executeInternal", e);
        }

        // Poller is @Transactional and executes synchronously — no Awaitility needed.
        // Note: assertAll() is NOT used here because it includes assertLedgerBalanced().
        // The polling path does NOT post ledger entries — ledger posting happens only in
        // WebhookTransitionService (called from the webhook double-check path, not the poller).
        // Assert each invariant individually except ledger balance.
        invariant.assertLegalStateTransition(transactionId, "SUCCESS");
        invariant.assertNoDoubleCharge(tenant.tenantId(), idempotencyKey);
        invariant.chain().assertChainValid(transactionId);
        invariant.provider().assertMtnCallCount("/v1_0/requesttopay", 1);
        invariant.events().assertEventPresent(transactionId, "PROVIDER_SUCCESS");
        invariant.assertFraudEvaluatedBeforeProviderCall(transactionId);
        new CacheVerifier(redis).assertIdempotencyKeyPresent(tenant.tenantId(), idempotencyKey);
    }
}
