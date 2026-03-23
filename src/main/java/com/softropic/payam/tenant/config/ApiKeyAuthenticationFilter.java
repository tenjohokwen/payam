package com.softropic.payam.tenant.config;

import com.softropic.payam.security.common.util.TenantContext;
import com.softropic.payam.tenant.contract.TenantPrincipal;
import com.softropic.payam.tenant.repo.TenantApiKey;
import com.softropic.payam.tenant.service.ApiKeyService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet filter that authenticates incoming API requests via the {@code X-Api-Key} header.
 *
 * <p>This filter is registered in the {@code @Order(1)} security filter chain
 * ({@code TenantSecurityConfig}) which scopes it exclusively to {@code /v1/**} paths.
 *
 * <p>Authentication flow:
 * <ol>
 *   <li>Read the {@code X-Api-Key} header. If absent, send 401 immediately.
 *   <li>Delegate to {@link ApiKeyService#authenticate(String)} which hashes the raw key
 *       and queries with the 24-hour rotation grace window.
 *   <li>On success: set {@link TenantContext} and populate {@link SecurityContextHolder}.
 *   <li>Always clear {@link TenantContext} in the {@code finally} block — servlet containers
 *       reuse threads so leaking context between requests is a real risk.
 * </ol>
 *
 * <p>The raw key value is NEVER logged. Only the first 8 characters (the {@code key_prefix}
 * column value) are used in log output to aid debugging without exposing secrets.
 *
 * <p>The {@link TenantApiKey#getTenant()} call is safe here because
 * {@code TenantApiKeyRepository.findValidKeyByHash} uses {@code JOIN FETCH k.tenant},
 * meaning the tenant proxy is fully loaded before the {@code @Transactional(readOnly=true)}
 * authenticate() transaction closes.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rawKey = request.getHeader(API_KEY_HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-Api-Key header");
            return;
        }

        TenantApiKey tenantApiKey;
        try {
            tenantApiKey = apiKeyService.authenticate(rawKey);
        } catch (Exception e) {
            log.warn("API key authentication failed for prefix [{}]",
                rawKey.length() >= 8 ? rawKey.substring(0, 8) : "[short]");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired API key");
            return;
        }

        String tenantRef = tenantApiKey.getTenant().getTenantRef();
        Long   tenantId  = tenantApiKey.getTenant().getId();
        TenantContext.set(tenantRef);

        TenantPrincipal principal = new TenantPrincipal(tenantRef, tenantId);
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();   // ALWAYS clear — servlet containers reuse threads
            SecurityContextHolder.clearContext();
        }
    }
}
