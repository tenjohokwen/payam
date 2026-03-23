package com.softropic.payam.tenant.config;

import com.softropic.payam.security.config.AppEndpoints;
import com.softropic.payam.tenant.service.ApiKeyService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code @Order(1)} security filter chain scoped to {@code /v1/**} paths that are NOT
 * user account management paths ({@code /v1/account/**}).
 *
 * <p>This chain intercepts all tenant API payment/admin requests before the existing JWT chain
 * (which has no {@code @Order}, defaulting to lowest priority).
 *
 * <p>Scope strategy — why the securityMatcher excludes {@code /v1/account/**}:
 * <ul>
 *   <li>The user account management endpoints ({@code /v1/account/}) are accessed via JWT
 *       cookies, not via API keys. They belong to the JWT chain.
 *   <li>By excluding them from this chain's scope, the JWT chain handles them correctly —
 *       the public subpaths (register, activate) remain public, and the secured subpath
 *       (GET /v1/account/) requires JWT authentication as before.
 *   <li>This preserves all existing {@code SecurityFilterChainIT} test behaviour.
 * </ul>
 *
 * <p>Security rules for matched paths (i.e. {@code /v1/**} except {@code /v1/account/**}):
 * <ul>
 *   <li>{@code AppEndpoints.PUBLIC_ENDPOINTS} paths that fall within this scope are
 *       permitted without authentication (defence-in-depth).
 *   <li>All other matched {@code /v1/**} paths require a valid {@code X-Api-Key} header.
 * </ul>
 *
 * <p>CSRF is disabled — API clients use token-based auth, not browser sessions.
 * Session management is STATELESS — no HTTP session is created.
 *
 * <p>{@code ClientIdAccessDecisionManager} is NOT wired here — that belongs to the JWT
 * chain only (see {@code SecurityConfiguration}).
 */
@Configuration
public class TenantSecurityConfig {

    /**
     * Define the filter as a Spring bean but NOT as a {@code @Component} — this prevents
     * automatic registration with the servlet container, which would cause the filter to
     * run for ALL requests (not just tenant payment paths).
     * The filter is added exclusively via {@code addFilterBefore} in this configuration.
     */
    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        return new ApiKeyAuthenticationFilter(apiKeyService);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain tenantApiKeyFilterChain(HttpSecurity http,
                                                       ApiKeyAuthenticationFilter apiKeyFilter)
                                                       throws Exception {
        // Match /v1/** BUT NOT /v1/account/**
        // /v1/account/** is user account management (JWT territory) — let the JWT chain handle it.
        RequestMatcher tenantPaths = new AndRequestMatcher(
            new AntPathRequestMatcher("/v1/**"),
            new NegatedRequestMatcher(new AntPathRequestMatcher("/v1/account/**"))
        );

        // Build PUBLIC_ENDPOINTS permit-all matchers for paths within this chain's scope
        List<RequestMatcher> publicMatchers = AppEndpoints.PUBLIC_ENDPOINTS.stream()
            .map(AntPathRequestMatcher::new)
            .collect(Collectors.toList());
        RequestMatcher publicPathsMatcher = new OrRequestMatcher(publicMatchers);

        http
            .securityMatcher(tenantPaths)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(publicPathsMatcher).permitAll()   // PUBLIC_ENDPOINTS pass through
                .anyRequest().authenticated())
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
