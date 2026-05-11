package com.softropic.payam.payment.provider.orange.infrastructure;

import com.softropic.payam.common.client.AbstractClient;
import com.softropic.payam.common.client.RestRequestInterceptor;
import com.softropic.payam.payment.provider.orange.config.OrangeMoneyConfig;
import com.softropic.payam.payment.provider.orange.contract.dto.CashoutRequest;
import com.softropic.payam.payment.provider.orange.contract.dto.C2CRequest;
import com.softropic.payam.payment.provider.orange.contract.dto.InitTransactionResponse;
import com.softropic.payam.payment.provider.orange.contract.dto.OrangeTokenResponse;
import com.softropic.payam.payment.provider.orange.contract.dto.PayRequest;
import com.softropic.payam.payment.provider.orange.contract.dto.PayResponse;
import com.softropic.payam.payment.provider.orange.contract.dto.SubscriberInfoResponse;
import com.softropic.payam.payment.provider.orange.contract.exception.OrangeApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

public class OrangeMoneyClient extends AbstractClient {

    private static final Logger log = LoggerFactory.getLogger(OrangeMoneyClient.class);

    private final OrangeMoneyConfig config;

    public OrangeMoneyClient(OrangeMoneyConfig config) {
        super(new RestRequestInterceptor(), config.getBaseUrl(), "/mp/init");
        this.config = config;
        // FormHttpMessageConverter is required for application/x-www-form-urlencoded (token fetch).
        // AbstractClient.messageConverters() only registers JSON; we add form support here.
        this.restTemplate.getMessageConverters().add(new FormHttpMessageConverter());
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

        long start = System.currentTimeMillis();
        ResponseEntity<OrangeTokenResponse> response = makeHttpRequest(
                config.getTokenUrl(), HttpMethod.POST, body, OrangeTokenResponse.class, headers);
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "ORANGE_MONEY"),
                kv("operation", "fetchToken"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("Failed to fetch Orange token — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /** POST /infos/subscriber/{userType}/{msisdn} — validates a subscriber and returns their registered name */
    public SubscriberInfoResponse getSubscriberInfo(String bearerToken, String userType, String msisdn, String channelMsisdn) {
        String url = buildClientURL("/infos/subscriber/" + userType + "/" + msisdn);
        Map<String, String> body = Map.of("channelMsisdn", channelMsisdn);

        long start = System.currentTimeMillis();
        ResponseEntity<SubscriberInfoResponse> response = makeHttpRequest(
                url, HttpMethod.POST, body, SubscriberInfoResponse.class, bearerHeaders(bearerToken));
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "ORANGE_MONEY"),
                kv("operation", "getSubscriberInfo"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("getSubscriberInfo failed — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /**
     * POST /mp/init — returns a payToken for a new merchant payment transaction.
     * Requires Bearer token and X-AUTH-TOKEN (base64 of apiUsername:apiPassword).
     */
    public InitTransactionResponse initTransaction(String bearerToken) {
        String url = buildClientURL("/mp/init");

        long start = System.currentTimeMillis();
        ResponseEntity<InitTransactionResponse> response = makeHttpRequest(
                url, HttpMethod.POST, null, InitTransactionResponse.class, xAuthHeaders(bearerToken));
        log.info("Provider HTTP call",
                kv("externalService", "ORANGE_MONEY"),
                kv("operation", "initTransaction"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("initTransaction failed — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /** POST /mp/pay (uses config.getPayUrl() which is v1.0.2 — not baseUrl) */
    public PayResponse pay(String bearerToken, PayRequest request) {
        String url = config.getPayUrl() + "/mp/pay";

        long start = System.currentTimeMillis();
        ResponseEntity<PayResponse> response = makeHttpRequest(
                url, HttpMethod.POST, request, PayResponse.class, xAuthHeaders(bearerToken));
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "ORANGE_MONEY"),
                kv("operation", "pay"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("pay failed — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /** GET /mp/paymentstatus/{payToken} */
    public PayResponse getPaymentStatus(String bearerToken, String payToken) {
        String url = buildClientURL("/mp/paymentstatus/" + payToken);

        long start = System.currentTimeMillis();
        ResponseEntity<PayResponse> response = makeHttpRequest(
                url, HttpMethod.GET, null, PayResponse.class, xAuthHeaders(bearerToken));
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "ORANGE_MONEY"),
                kv("operation", "getPaymentStatus"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OrangeApiException("getPaymentStatus failed — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /** POST /cashout (renamed to disburse for domain consistency) */
    public ResponseEntity<Map> disburse(String bearerToken, CashoutRequest request) {
        String url = buildClientURL("/cashout");
        long start = System.currentTimeMillis();
        ResponseEntity<Map> result = makeHttpRequest(url, HttpMethod.POST, request, Map.class, bearerHeaders(bearerToken));
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "ORANGE_MONEY"),
                kv("operation", "disburse"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", result.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));
        return result;
    }

    /** POST /c2c */
    public ResponseEntity<Map> c2c(String bearerToken, C2CRequest request) {
        String url = buildClientURL("/c2c");
        long start = System.currentTimeMillis();
        ResponseEntity<Map> result = makeHttpRequest(url, HttpMethod.POST, request, Map.class, bearerHeaders(bearerToken));
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "ORANGE_MONEY"),
                kv("operation", "c2c"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", result.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));
        return result;
    }

    private HttpHeaders bearerHeaders(String token) {
        return toHttpHeaders(Map.of(
                "Authorization", "Bearer " + token,
                "Content-Type", "application/json"
        ));
    }

    private HttpHeaders xAuthHeaders(String token) {
        String authHeader = config.getApiUsername() + ":" + config.getApiPassword();
        String xAuthToken = Base64.getEncoder().encodeToString(authHeader.getBytes(StandardCharsets.UTF_8));
        return toHttpHeaders(Map.of(
                "Authorization", "Bearer " + token,
                "X-AUTH-TOKEN", xAuthToken,
                "Content-Type", "application/json"
        ));
    }
}
