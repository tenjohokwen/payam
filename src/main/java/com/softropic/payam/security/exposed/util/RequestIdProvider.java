package com.softropic.payam.security.exposed.util;



import com.softropic.payam.common.Constants;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import static com.softropic.payam.common.Constants.REQUEST_ID_HEADER_NAME;


/**
 * Gets the request id associated with the current thread.
 * TODO eventually move Constants.REQUEST_ID_NAME and Constants.REQUEST_ID_HEADER_NAME here
 * see TransactionIdProvider (this is to mark txn boundaries)
 */
@Slf4j
public final class RequestIdProvider {

    private RequestIdProvider() {}

    public static String provideRequestId() {
        return Optional.ofNullable(MDC.get(Constants.REQUEST_ID_NAME)).orElseGet(RequestIdProvider::addNewRequestIdToThread);
    }

    public static String addNewRequestIdToThread() {
        final String reqId = UUID.randomUUID().toString();
        addReqIdToThread(reqId);
        return reqId;
    }

    public static void addReqIdToThread(final String reqId) {
        RequestMetadataProvider.getClientInfo().setRequestId(reqId);
        MDC.put(Constants.REQUEST_ID_NAME, reqId);
    }

    public static void addReqIdToThread(final HttpServletRequest request) {
        final String reqId = request.getHeader(REQUEST_ID_HEADER_NAME);
        if(StringUtils.isBlank(reqId)) {
            log.info("################ Creating new request id since request does not contain any. reqId: {}", reqId);
            addNewRequestIdToThread();
        } else {
            log.debug("############# requestId '{}' found in request", reqId);
        }
    }

    public static void removeReqIdFromThread() {
        log.info("################### About to remove reqId from thread");
        RequestMetadataProvider.getClientInfo().setRequestId("");
        MDC.remove(Constants.REQUEST_ID_NAME);
    }


}
