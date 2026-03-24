package com.softropic.payam.mtn.web;

import com.softropic.payam.mtn.config.MtnMoMoConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Set;

/**
 * Intercepts MTN callback requests and enforces IP whitelist.
 * Registered ONLY for /v1/callbacks/mtn path via MtnWebConfig — does not affect other endpoints.
 *
 * Empty whitelist = sandbox mode (accept all IPs, log warning).
 * Production: set mtn.callback-ip-whitelist in application.yaml.
 *
 * Respects X-Forwarded-For (server.forward-headers-strategy=native is set in config).
 * Supports exact IP match and octet-boundary CIDR (/8, /16, /24, /32).
 */
@Component
public class MtnIpWhitelistInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MtnIpWhitelistInterceptor.class);

    private final Set<String> allowedIps;

    public MtnIpWhitelistInterceptor(MtnMoMoConfig config) {
        this.allowedIps = Set.copyOf(config.getCallbackIpWhitelist());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // Extract client IP — respect X-Forwarded-For (server.forward-headers-strategy=native is set)
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        // Take first IP if comma-separated proxy chain
        ip = ip.split(",")[0].trim();

        if (allowedIps.isEmpty()) {
            // Empty whitelist = sandbox mode (accept all) — log warning
            log.warn("MTN callback IP whitelist is empty — accepting all IPs (sandbox mode)");
            return true;
        }

        final String clientIp = ip;
        boolean allowed = allowedIps.stream().anyMatch(entry -> {
            if (entry.contains("/")) {
                // Minimal CIDR: check if IP starts with network prefix (sufficient for /8 ranges)
                String prefix = entry.substring(0, entry.indexOf('/'));
                int maskBits = Integer.parseInt(entry.substring(entry.indexOf('/') + 1));
                int octets = maskBits / 8;
                String[] parts = prefix.split("\\.");
                String[] ipParts = clientIp.split("\\.");
                if (ipParts.length < octets || parts.length < octets) return false;
                for (int i = 0; i < octets; i++) {
                    if (!ipParts[i].equals(parts[i])) return false;
                }
                return true;
            }
            return entry.equals(clientIp);
        });

        if (!allowed) {
            log.warn("MTN callback rejected — IP not whitelisted: {}", clientIp);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
