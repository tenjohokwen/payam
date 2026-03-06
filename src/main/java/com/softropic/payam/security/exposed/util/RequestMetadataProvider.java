package com.softropic.payam.security.exposed.util;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.util.WebUtils;

import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import static com.softropic.payam.security.common.util.SecurityConstants.ANONYMOUS_SESSION_COOKIE;
import static com.softropic.payam.security.common.util.SecurityConstants.API_KEY_HEADER;
import static com.softropic.payam.security.common.util.SecurityConstants.B_COOKIE;
import static com.softropic.payam.security.common.util.SecurityConstants.F_COOKIE;
import static com.softropic.payam.security.common.util.SecurityConstants.JWT_SESSION_COOKIE;


public final class RequestMetadataProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestMetadataProvider.class);
    private static final ThreadLocal<RequestMetadata> CONTEXT_HOLDER = new ThreadLocal<>();
    public static final String REQUEST_METADATA = "requestMetadata";

    private RequestMetadataProvider() {}

    public static RequestMetadata getClientInfo() {
        RequestMetadata requestMetadata = CONTEXT_HOLDER.get();
        if(requestMetadata == null) {
            LOGGER.info("################# Creating RequestMetadata for current thread.");
            requestMetadata = new RequestMetadata();
            CONTEXT_HOLDER.set(requestMetadata);
        }
        return requestMetadata;
    }

    public static void cleanup() {
        //TODO eventually move logic from RequestIdProvider to this class
        RequestIdProvider.removeReqIdFromThread();
        CONTEXT_HOLDER.remove();
        MDC.remove(REQUEST_METADATA);
    }

    public static void initRequestMetadata(final HttpServletRequest request) {
        LOGGER.info("################# in initRequestMetadata before getClientInfo() called ");

        final RequestMetadata requestMetadata = getClientInfo();

        //TODO eventually move logic from RequestIdProvider to this class
        RequestIdProvider.addReqIdToThread(request);

        String ipAddress = request.getHeader("Forwarded"); //request.getHeader("X-FORWARDED-FOR");
        if (StringUtils.isBlank(ipAddress)) {
            ipAddress = request.getRemoteAddr();
        }
        requestMetadata.setIpAddress(ipAddress);

        requestMetadata.setUserAgent(request.getHeader("user-agent"));

        requestMetadata.setHttps(StringUtils.equalsAnyIgnoreCase(request.getScheme(), "https"));

        requestMetadata.setBrowserCookie(getCookieValue(request, B_COOKIE));

        requestMetadata.setFingerprintCookie(getCookieValue(request, F_COOKIE));

        requestMetadata.setApiKey(request.getHeader(API_KEY_HEADER));
        requestMetadata.setReqUrl(request.getRequestURL().toString());

        requestMetadata.setSessionId(getSessionId(request));
        requestMetadata.setMethod(request.getMethod());

        MDC.put(REQUEST_METADATA, requestMetadata.toString());
    }

    private static String getSessionId(HttpServletRequest req) {
        String cookieValue = getCookieValue(req, JWT_SESSION_COOKIE);
        if(StringUtils.isNotBlank(cookieValue)){
            //TODO to avoid fraud, ensure the session id is found in the claims
        } else {
            final String anonCookie = getCookieValue(req, ANONYMOUS_SESSION_COOKIE);
            if(StringUtils.isNotBlank(anonCookie)){
                cookieValue = anonCookie;
            } else {
                long id = UUID.randomUUID().hashCode();
                id = Math.abs(id);
                cookieValue = ShortCode.shortenUsingDefault(id);
            }
        }
        return cookieValue;
    }

    private static String getCookieValue(final HttpServletRequest req, final String cookieName) {
        final Cookie cookie = WebUtils.getCookie(req, cookieName);
        return cookie != null ? cookie.getValue() : null;
    }

    public static void setUserName(final String userName) {
        LOGGER.info("################# in setUserName before getClientInfo() called ");
        final RequestMetadata requestMetadata = getClientInfo();
        requestMetadata.setUserName(userName);
        //reset metadata with username
        MDC.put(REQUEST_METADATA, requestMetadata.toString());
        LOGGER.info("################# in setUserName after requestMetadata called ");
    }

    public static void setChosenLang(final String lang) {
        LOGGER.info("################# in setChosenLang before getClientInfo() called ");
        final RequestMetadata requestMetadata = getClientInfo();
        requestMetadata.setChosenLang(lang);
        MDC.put(REQUEST_METADATA, requestMetadata.toString());
        LOGGER.info("################# in setChosenLang after requestMetadata called ");
    }

}
