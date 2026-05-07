package com.softropic.payam.e2e.payment;

import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.PlatformConfigInitializer;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that only the internal transaction ID is sent to providers as a reference,
 * and that merchants can query payment status by their own external reference ID.
 */
public class PaymentLookupE2ETest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PlatformConfigInitializer platformConfigInitializer;

    private TenantBuilder.CreatedTenant tenant;
    private RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    void setup() {
        platformConfigInitializer.initOrange();
        tenant = new TenantBuilder()
            .withName("Lookup-E2E-Tenant")
            .create(tenantService, tenantRepository);

        // Stub Orange provider endpoints
        orangeServer.stubFor(get(urlPathEqualTo("/infos/subscriber"))
            .willReturn(okJson("{\"status\":\"ACTIF\",\"message\":\"OK\"}")));
        orangeServer.stubFor(post(urlPathEqualTo("/mp/init"))
            .willReturn(okJson("{\"data\":{\"payToken\":\"tok-lookup-test\"},\"message\":\"OK\"}")));
        orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
            .willReturn(okJson("{\"status\":\"SUCCESS\",\"message\":\"OK\"}")));
    }

    @AfterEach
    void tearDown() {
        platformConfigInitializer.clear();
    }

    @Test
    void shouldInitiatePaymentAndLookupByReference() {
        String externalRef = "ext-ref-" + java.util.UUID.randomUUID();
        PaymentRequest req = new PaymentRequestBuilder()
            .forOrange()
            .withExternalReference(externalRef)
            .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

        // 1. Initiate Payment
        ResponseEntity<PaymentResponse> initResponse = restTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(req, headers),
            PaymentResponse.class);

        assertThat(initResponse.getStatusCode().value()).isEqualTo(202);
        String transactionId = initResponse.getBody().transactionId();

        // 2. Verify Provider received transactionId and NOT externalRef
        // Orange /mp/pay uses 'orderId' field for the reference
        orangeServer.verify(postRequestedFor(urlPathEqualTo("/mp/pay"))
            .withRequestBody(containing("\"orderId\":\"" + transactionId + "\""))
            .withRequestBody(notMatching(".*" + externalRef + ".*")));

        // 3. Lookup by transactionId
        ResponseEntity<PaymentResponse> lookupByIdResponse = restTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments/" + transactionId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            PaymentResponse.class);

        assertThat(lookupByIdResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(lookupByIdResponse.getBody().transactionId()).isEqualTo(transactionId);

        // 4. Lookup by externalReference
        ResponseEntity<PaymentResponse> lookupByRefResponse = restTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments/reference/" + externalRef,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            PaymentResponse.class);

        assertThat(lookupByRefResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(lookupByRefResponse.getBody().transactionId()).isEqualTo(transactionId);

        // 5. Lookup non-existent reference
        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () -> {
            restTemplate.exchange(
                "http://localhost:" + serverPort + "/v1/payments/reference/non-existent",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                PaymentResponse.class);
        });
        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }
}
