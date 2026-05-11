package com.softropic.payam.payment.provider.orange.web;

import com.softropic.payam.payment.provider.orange.config.OrangeMoneyConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Set;

/**
 * Intercepts Orange callback requests and enforces IP whitelist.
 * Registered ONLY for /v1/callbacks/orange path via OrangeWebConfig — does not affect other endpoints.
 *
 * Empty whitelist = sandbox mode (accept all IPs, log warning).
 * Production: set orange.callback-ip-whitelist in application.yaml.
 *
 * Respects X-Forwarded-For (server.forward-headers-strategy=native is set in config).
 * Supports exact IP match and octet-boundary CIDR (/8, /16, /24, /32).
 */
@Component
public class OrangeIpWhitelistInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OrangeIpWhitelistInterceptor.class);

    private final Set<String> allowedIps;

    public OrangeIpWhitelistInterceptor(OrangeMoneyConfig config) {
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
            log.warn("Orange callback IP whitelist is empty — accepting all IPs (sandbox mode)");
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
            log.warn("Orange callback rejected: IP not whitelisted",
                    kv("operation", "ip_whitelist_check"),
                    kv("provider", "ORANGE"),
                    kv("remoteIp", clientIp),
                    kv("status", "REJECTED"));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
