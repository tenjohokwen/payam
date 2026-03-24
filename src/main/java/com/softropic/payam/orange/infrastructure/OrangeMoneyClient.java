package com.softropic.payam.orange.infrastructure;

import com.softropic.payam.common.client.AbstractClient;
import com.softropic.payam.common.client.RestRequestInterceptor;
import com.softropic.payam.orange.config.OrangeMoneyConfig;
import com.softropic.payam.orange.contract.dto.CashoutRequest;
import com.softropic.payam.orange.contract.dto.C2CRequest;
import com.softropic.payam.orange.contract.dto.MerchantInfoResponse;
import com.softropic.payam.orange.contract.dto.OrangeTokenResponse;
import com.softropic.payam.orange.contract.dto.PayRequest;
import com.softropic.payam.orange.contract.dto.PayResponse;
import com.softropic.payam.orange.contract.dto.SubscriberInfoResponse;
import com.softropic.payam.orange.contract.exception.OrangeApiException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public class OrangeMoneyClient extends AbstractClient {

    private final OrangeMoneyConfig config;

    public OrangeMoneyClient(OrangeMoneyConfig config) {
        super(new RestRequestInterceptor(), config.getBaseUrl(), "/infos/merchant");
        this.config = config;
    }

    /**
     * Fetch OAuth2 Bearer token using Basic Auth with consumerKey:consumerSecret.
     * POST to tokenUrl, Content-Type: application/x-www-form-urlencoded, body: grant_type=client_credentials
     */
    public OrangeTokenResponse fetchToken() {
        String credentials = config.getConsumerKey() + ":" + config.getConsumerSecret();
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = toHttpHeaders(Map.of(
                "Authorization", basicAuth,
                "Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE
        ));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        ResponseEntity<OrangeTokenResponse> response = makeHttpRequest(
                config.getTokenUrl(), HttpMethod.POST, body, OrangeTokenResponse.class, headers);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("Failed to fetch Orange token — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /** GET /infos/subscriber?msisdn={msisdn} */
    public SubscriberInfoResponse getSubscriberInfo(String bearerToken, String msisdn) {
        String url = buildClientURI("/infos/subscriber",
                Map.of(),
                Map.of("msisdn", msisdn)).toString();

        ResponseEntity<SubscriberInfoResponse> response = makeHttpRequest(
                url, HttpMethod.GET, null, SubscriberInfoResponse.class, bearerHeaders(bearerToken));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("getSubscriberInfo failed — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /** GET /infos/merchant — returns payToken */
    public MerchantInfoResponse getMerchantInfo(String bearerToken) {
        String url = buildClientURL("/infos/merchant");

        ResponseEntity<MerchantInfoResponse> response = makeHttpRequest(
                url, HttpMethod.GET, null, MerchantInfoResponse.class, bearerHeaders(bearerToken));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("getMerchantInfo failed — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /** POST /mp/pay (uses config.getPayUrl() which is v1.0.1 — not baseUrl) */
    public PayResponse pay(String bearerToken, PayRequest request) {
        String url = config.getPayUrl() + "/mp/pay";

        ResponseEntity<PayResponse> response = makeHttpRequest(
                url, HttpMethod.POST, request, PayResponse.class, bearerHeaders(bearerToken));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("pay failed — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /** GET /mp/paymentstatus/{payToken} */
    public PayResponse getPaymentStatus(String bearerToken, String payToken) {
        String url = buildClientURL("/mp/paymentstatus/" + payToken);

        ResponseEntity<PayResponse> response = makeHttpRequest(
                url, HttpMethod.GET, null, PayResponse.class, bearerHeaders(bearerToken));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("getPaymentStatus failed — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /** POST /cashout */
    public ResponseEntity<Map> cashout(String bearerToken, CashoutRequest request) {
        String url = buildClientURL("/cashout");
        return makeHttpRequest(url, HttpMethod.POST, request, Map.class, bearerHeaders(bearerToken));
    }

    /** POST /c2c */
    public ResponseEntity<Map> c2c(String bearerToken, C2CRequest request) {
        String url = buildClientURL("/c2c");
        return makeHttpRequest(url, HttpMethod.POST, request, Map.class, bearerHeaders(bearerToken));
    }

    private HttpHeaders bearerHeaders(String token) {
        return toHttpHeaders(Map.of(
                "Authorization", "Bearer " + token,
                "Content-Type", "application/json"
        ));
    }
}
