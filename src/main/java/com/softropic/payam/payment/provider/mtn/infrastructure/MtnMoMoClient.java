package com.softropic.payam.payment.provider.mtn.infrastructure;

import com.softropic.payam.common.client.AbstractClient;
import com.softropic.payam.common.client.RestRequestInterceptor;
import com.softropic.payam.payment.provider.mtn.config.MtnMoMoConfig;
import com.softropic.payam.payment.provider.mtn.contract.dto.AccountBalanceResponse;
import com.softropic.payam.payment.provider.mtn.contract.dto.AccountHolderInfoResponse;
import com.softropic.payam.payment.provider.mtn.contract.dto.TransferStatusResponse;
import com.softropic.payam.payment.provider.mtn.contract.dto.DisbursementRequest;
import com.softropic.payam.payment.provider.mtn.contract.dto.MtnTokenResponse;
import com.softropic.payam.payment.provider.mtn.contract.dto.RequestToPayRequest;
import com.softropic.payam.payment.provider.mtn.contract.dto.RequestToPayStatusResponse;
import com.softropic.payam.payment.provider.mtn.contract.exception.MtnAccountInactiveException;
import com.softropic.payam.payment.provider.mtn.contract.exception.MtnApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static net.logstash.logback.argument.StructuredArguments.kv;

public class MtnMoMoClient extends AbstractClient {

    private static final Logger log = LoggerFactory.getLogger(MtnMoMoClient.class);

    private final MtnMoMoConfig config;

    public MtnMoMoClient(MtnMoMoConfig config) {
        super(new RestRequestInterceptor(), config.getCollectionBaseUrl(), "/v1_0/account/balance");
        this.config = config;
        // NOTE: No FormHttpMessageConverter added — MTN token endpoint does NOT use form body (unlike Orange).
        // Sending a form body returns 400. AbstractClient's JSON converter is sufficient.
    }

    /**
     * Fetch OAuth2 Bearer token for the Collection product.
     * POST to collectionTokenUrl with Basic auth. Body MUST be null — MTN returns 400 if form body sent.
     */
    public MtnTokenResponse fetchCollectionToken() {
        String credentials = config.getApiUserId() + ":" + config.getApiKey();
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = mtnBasicAuthHeaders(basicAuth, config.getCollectionSubscriptionKey());

        long start = System.currentTimeMillis();
        ResponseEntity<MtnTokenResponse> response = makeHttpRequest(
                config.getCollectionTokenUrl(), HttpMethod.POST, null, MtnTokenResponse.class, headers);
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "MTN_MOMO"),
                kv("operation", "fetchCollectionToken"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new MtnApiException("Failed to fetch MTN collection token — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /**
     * Fetch OAuth2 Bearer token for the Disbursement product.
     * Uses disbursementTokenUrl and disbursementSubscriptionKey — separate from collection.
     */
    public MtnTokenResponse fetchDisbursementToken() {
        String credentials = config.getApiUserId() + ":" + config.getApiKey();
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = mtnBasicAuthHeaders(basicAuth, config.getDisbursementSubscriptionKey());

        long start = System.currentTimeMillis();
        ResponseEntity<MtnTokenResponse> response = makeHttpRequest(
                config.getDisbursementTokenUrl(), HttpMethod.POST, null, MtnTokenResponse.class, headers);
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "MTN_MOMO"),
                kv("operation", "fetchDisbursementToken"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new MtnApiException("Failed to fetch MTN disbursement token — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /**
     * POST /collection/v1_0/requesttopay
     * MTN returns 202 Accepted with no body. Caller generates UUID referenceId before calling.
     */
    public void requestToPay(String referenceId, RequestToPayRequest request, String bearerToken) {
        String url = config.getCollectionBaseUrl() + "/v1_0/requesttopay";

        HttpHeaders headers = mtnHeaders(bearerToken, referenceId, config.getCollectionSubscriptionKey());

        long start = System.currentTimeMillis();
        ResponseEntity<Void> response = makeHttpRequest(url, HttpMethod.POST, request, Void.class, headers);
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "MTN_MOMO"),
                kv("operation", "requestToPay"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new MtnApiException("requestToPay failed — referenceId: " + referenceId
                    + ", status: " + response.getStatusCode());
        }
    }

    /**
     * GET /collection/v1_0/requesttopay/{referenceId}
     */
    public RequestToPayStatusResponse getRequestToPayStatus(String referenceId, String bearerToken) {
        String url = config.getCollectionBaseUrl() + "/v1_0/requesttopay/" + referenceId;

        HttpHeaders headers = mtnHeaders(bearerToken, null, config.getCollectionSubscriptionKey());

        long start = System.currentTimeMillis();
        ResponseEntity<RequestToPayStatusResponse> response = makeHttpRequest(
                url, HttpMethod.GET, null, RequestToPayStatusResponse.class, headers);
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "MTN_MOMO"),
                kv("operation", "getRequestToPayStatus"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new MtnApiException("getRequestToPayStatus failed — referenceId: " + referenceId
                    + ", status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /**
     * GET /collection/v1_0/accountholder/MSISDN/{msisdn}/basicuserinfo
     * MTN returns 404 (not a body with status=INACTIVE) for inactive accounts.
     */
    public AccountHolderInfoResponse validateAccountHolder(String msisdn, String bearerToken) {
        String url = config.getCollectionBaseUrl() + "/v1_0/accountholder/MSISDN/" + msisdn + "/basicuserinfo";

        HttpHeaders headers = mtnHeaders(bearerToken, null, config.getCollectionSubscriptionKey());

        try {
            long start = System.currentTimeMillis();
            ResponseEntity<AccountHolderInfoResponse> response = makeHttpRequest(
                    url, HttpMethod.GET, null, AccountHolderInfoResponse.class, headers);
            // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
            // Note: log does NOT emit on exception path — that is acceptable; the exception propagates for the not-found case.
            log.info("Provider HTTP call",
                    kv("externalService", "MTN_MOMO"),
                    kv("operation", "validateAccountHolder"),
                    kv("externalLatencyMs", System.currentTimeMillis() - start),
                    kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new MtnApiException("validateAccountHolder failed — msisdn: " + msisdn
                        + ", status: " + response.getStatusCode());
            }
            return response.getBody();
        } catch (com.softropic.payam.common.client.exception.HttpClientException e) {
            // RestRequestInterceptor converts 4xx/5xx to HttpClientException before RestTemplate
            // can throw HttpClientErrorException. Check httpStatusCode string for 404.
            if (e.getHttpStatusCode() != null && e.getHttpStatusCode().contains("404")) {
                throw new MtnAccountInactiveException(msisdn);
            }
            throw new MtnApiException("validateAccountHolder failed — msisdn: " + msisdn
                    + ", cause: " + e.getMessage());
        } catch (HttpClientErrorException e) {
            // Fallback for any direct HttpClientErrorException that bypasses the interceptor
            if (e instanceof HttpClientErrorException.NotFound) {
                throw new MtnAccountInactiveException(msisdn);
            }
            throw new MtnApiException("validateAccountHolder failed — msisdn: " + msisdn
                    + ", status: " + e.getStatusCode());
        }
    }

    /**
     * GET /collection/v1_0/account/balance
     */
    public AccountBalanceResponse getBalance(String bearerToken) {
        String url = config.getCollectionBaseUrl() + "/v1_0/account/balance";

        HttpHeaders headers = mtnHeaders(bearerToken, null, config.getCollectionSubscriptionKey());

        long start = System.currentTimeMillis();
        ResponseEntity<AccountBalanceResponse> response = makeHttpRequest(
                url, HttpMethod.GET, null, AccountBalanceResponse.class, headers);
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "MTN_MOMO"),
                kv("operation", "getBalance"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new MtnApiException("getBalance failed — status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /**
     * POST /disbursement/v1_0/transfer
     * Uses disbursementSubscriptionKey — NOT the collection key (Pitfall 5).
     * MTN returns 202 Accepted with no body.
     */
    public void disburse(String referenceId, DisbursementRequest request, String bearerToken) {
        String url = config.getDisbursementBaseUrl() + "/v1_0/transfer";

        HttpHeaders headers = mtnHeaders(bearerToken, referenceId, config.getDisbursementSubscriptionKey());

        long start = System.currentTimeMillis();
        ResponseEntity<Void> response = makeHttpRequest(url, HttpMethod.POST, request, Void.class, headers);
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "MTN_MOMO"),
                kv("operation", "disburse"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new MtnApiException("disburse failed — referenceId: " + referenceId
                    + ", status: " + response.getStatusCode());
        }
    }

    /**
     * GET /disbursement/v1_0/transfer/{referenceId}
     */
    public TransferStatusResponse getTransferStatus(String referenceId, String bearerToken) {
        String url = config.getDisbursementBaseUrl() + "/v1_0/transfer/" + referenceId;

        HttpHeaders headers = mtnHeaders(bearerToken, null, config.getDisbursementSubscriptionKey());

        long start = System.currentTimeMillis();
        ResponseEntity<TransferStatusResponse> response = makeHttpRequest(
                url, HttpMethod.GET, null, TransferStatusResponse.class, headers);
        // LOG-BUS-06: structured latency event (co-exists with RestRequestInterceptor log)
        log.info("Provider HTTP call",
                kv("externalService", "MTN_MOMO"),
                kv("operation", "getTransferStatus"),
                kv("externalLatencyMs", System.currentTimeMillis() - start),
                kv("status", response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED"));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new MtnApiException("getTransferStatus failed — referenceId: " + referenceId
                    + ", status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    /**
     * Build headers for authenticated MTN API calls.
     * referenceId is optional (null for GET requests).
     */
    private HttpHeaders mtnHeaders(String bearerToken, String referenceId, String subscriptionKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + bearerToken);
        if (referenceId != null) {
            headers.set("X-Reference-Id", referenceId);
        }
        headers.set("X-Target-Environment", config.getTargetEnvironment());
        headers.set("Ocp-Apim-Subscription-Key", subscriptionKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Build headers for Basic auth token requests (no bearer token, no reference id).
     */
    private HttpHeaders mtnBasicAuthHeaders(String basicAuth, String subscriptionKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", basicAuth);
        headers.set("Ocp-Apim-Subscription-Key", subscriptionKey);
        return headers;
    }
}
