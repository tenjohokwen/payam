package com.softropic.payam.common.client;


import com.softropic.payam.common.TransactionIdProvider;
import com.softropic.payam.common.client.exception.HttpClientException;
import com.softropic.payam.common.client.exception.MomoError;
import com.softropic.payam.security.exposed.util.RequestIdProvider;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import lombok.extern.slf4j.Slf4j;

import static com.softropic.payam.common.Constants.HTTP_REQUEST_ID_DELIM;
import static com.softropic.payam.common.Constants.REQUEST_ID_HEADER_NAME;


@Slf4j
@Component
public class RestRequestInterceptor implements ClientHttpRequestInterceptor {

    //private final MetricRegistry metricRegistry;

    @Autowired
    public RestRequestInterceptor() {
        //TODO add timer metrics
        //this.metricRegistry = metricRegistry;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        //Histogram latencyHistogram = metricRegistry.histogram(name(request.getMethod().name(), request.getURI().toASCIIString(), "latency"));
        long startTime = System.currentTimeMillis();
        try {
            addTransactionIdToThread(request.getHeaders());
            if (log.isDebugEnabled()) {
                log.debug("Request method: {} url: {} headers: {} Payload: {}",
                        request.getMethod(),
                        request.getURI().toASCIIString(),
                        request.getHeaders(),
                        HttpMethod.POST.equals(request.getMethod()) ? new String(body, StandardCharsets.UTF_8): "");
            }
            ClientHttpResponse httpResponse = execution.execute(request, body);
            //latencyHistogram.update(System.currentTimeMillis() - startTime);
            final HttpStatus httpStatus = HttpStatus.resolve(httpResponse.getStatusCode().value());
            markResponse(request, (HttpStatus)httpResponse.getStatusCode());
            logRequestMetrics(request, httpStatus.name(), startTime);
            if(httpResponse.getStatusCode().value() > 399) {
                markResponse(request, httpStatus);
                logRequestMetrics(request, httpStatus.name(), startTime);
                final String response = StreamUtils.copyToString(httpResponse.getBody(),
                                                                 StandardCharsets.UTF_8);
                log.error("Response method: {} url: {} headers: {} status: {} Payload: {}",
                         request.getMethod(),
                         request.getURI().toASCIIString(),
                         httpResponse.getHeaders(),
                         httpResponse.getStatusCode().value(),
                         response
                         );

                throw HttpClientException.builder(RequestIdProvider.provideRequestId())
                                         .withHttpMethod(request.getMethod())
                                         .withUri(request.getURI())
                                         .withStatusCode(String.valueOf(httpResponse.getStatusCode()))
                                         .withResponse(response)
                                         .build();

            }
            log.info("Response method: {} url: {} headers: {} status: {} Payload: {}",
                      request.getMethod(),
                      request.getURI().toASCIIString(),
                      httpResponse.getHeaders(),
                      httpResponse.getStatusCode().value(),
                      StreamUtils.copyToString(httpResponse.getBody(), //This is a buffering response so no issues
                                               StandardCharsets.UTF_8)
            );

            return httpResponse;
        } catch (UnknownHostException uhe){
            throw HttpClientException.builder(RequestIdProvider.provideRequestId())
                                     .withHttpMethod(request.getMethod())
                                     .withUri(request.getURI())
                                     .withErrorCode(MomoError.CLIENT_NOT_REACHABLE)
                                     .withException(uhe)
                                     .build();
        }
        finally {
            TransactionIdProvider.removeTransactionIdFromThread();
        }

    }

    private void markResponse(HttpRequest request, HttpStatus status) {
        //Meter responseMeter = metricRegistry.meter(name(request.getMethod().name(), request.getURI().toASCIIString(), status.name()));
        //responseMeter.mark();
    }

    private void logRequestMetrics(HttpRequest request, String status, long startTime) {
        log.info("RESPONSE method: {}  url: {}  status: {}  latency: {}", request.getMethod(), request.getURI().toASCIIString(), StringUtils.trim(status), System.currentTimeMillis() - startTime);
    }

    public void addTransactionIdToThread(HttpHeaders httpHeaders) {
        try {
            String txnId;
            if(httpHeaders == null ||
                    httpHeaders.get(REQUEST_ID_HEADER_NAME) == null ||
                    StringUtils.isBlank(httpHeaders.get(REQUEST_ID_HEADER_NAME).get(0))) {
                txnId = "";
            } else {
                String[] fragments = httpHeaders.get(REQUEST_ID_HEADER_NAME).get(0).split(HTTP_REQUEST_ID_DELIM);
                txnId = fragments.length == 2 ? fragments[1] : "";
            }
            TransactionIdProvider.addTransactionIdToThread(txnId);
        } catch (Exception e) {
            log.error("Error occurred while trying to set txnId for http request with headers: {}", httpHeaders, e);
        }
    }

}