package com.softropic.payam.security.audit.filter;


import com.softropic.payam.common.util.BodySanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPatternParser;
import org.thymeleaf.util.Validate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Slf4j
public class LoggingFilter extends OncePerRequestFilter {

    private final List<PathPatternRequestMatcher> staticResourcesMatchers;

    public LoggingFilter(List<String> ignoredStaticResources) {
        //Objects.requireNonNull()
        Validate.notEmpty(ignoredStaticResources, "The ignored static resources list should not be null");

        staticResourcesMatchers = ignoredStaticResources.stream()
                                                        .map(pattern -> PathPatternRequestMatcher.withPathPatternParser(PathPatternParser.defaultInstance)
                                                                                                 .matcher(pattern))
                                                        .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isAsyncDispatch(request)) {
            filterChain.doFilter(request, response);
        } else {
            doFilterWrapped(wrapRequest(request), wrapResponse(response), filterChain);
        }
    }

    protected void doFilterWrapped(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);

        } finally {
            afterRequest(request, response);
            // Copy the cached response content to the actual response
            response.copyBodyToResponse();
        }
    }

    protected void afterRequest(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response) {
        final Map<String, List<String>> requestHeaders = getRequestHeaders(request);
        byte[] content = request.getContentAsByteArray();
        if (content.length > 0) {
            String sanitizedBody = BodySanitizer.sanitize(content, request.getContentType());
            log.info("Request Headers: [{}], Request payload: [{}], Request URL: [{}]",
                        requestHeaders,
                        sanitizedBody,
                        request.getRequestURL());
        } else {
            log.info("Request Headers: [{}], Request URL: [{}]", requestHeaders, request.getRequestURL());
        }
        logResponse(response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return staticResourcesMatchers.stream().anyMatch(matcher -> matcher.matches(request));
    }

    private Map<String, List<String>> getRequestHeaders(ContentCachingRequestWrapper request) {
        List<String> headerNames = Collections.list(request.getHeaderNames());
        Map<String, List<String>> headers = new HashMap<>();

        headerNames.sort(Comparator.naturalOrder());
        headerNames.forEach(headerName -> headers.put(headerName, Collections.list(request.getHeaders(headerName))));
        return headers;
    }

    private void logResponse(ContentCachingResponseWrapper response) {
        List<String> headerNames = new ArrayList<>(response.getHeaderNames());
        Map<String, List<String>> headers = new HashMap<>();
        int status = response.getStatus();
        headers.put("response status", List.of(String.valueOf(status)));
        headers.put("reason phrase", List.of(HttpStatus.valueOf(status).getReasonPhrase()));

        headerNames.sort(Comparator.naturalOrder());
        headerNames.forEach(headerName -> headers.put(headerName, new ArrayList<>(response.getHeaders(headerName))));


        byte[] content = response.getContentAsByteArray();
        if (content.length > 0) {
            String sanitizedBody = BodySanitizer.sanitize(content, response.getContentType());
            log.info("Response Headers: {}, Response payload: [{}]", headers, sanitizedBody);
        } else {
            log.info("Response Headers: {}", headers);
        }
    }

    private static ContentCachingRequestWrapper wrapRequest(HttpServletRequest request) {
        if (request instanceof ContentCachingRequestWrapper cachingReqWrapper) {
            return cachingReqWrapper;
        } else {
            return new ContentCachingRequestWrapper(request);
        }
    }

    private static ContentCachingResponseWrapper wrapResponse(HttpServletResponse response) {
        if (response instanceof ContentCachingResponseWrapper cachingResp) {
            return cachingResp;
        } else {
            return new ContentCachingResponseWrapper(response);
        }
    }
}