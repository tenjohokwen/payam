package com.softropic.payam.security.api.ratelimit;

import com.softropic.payam.security.exposed.exception.AuthorizationException;
import com.softropic.payam.security.exposed.util.RequestMetadataProvider;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static com.softropic.payam.security.exposed.exception.SecurityError.TOO_MANY_REQUESTS;

/**
 * Aspect for enforcing rate limits on methods annotated with {@link RateLimited}.
 */
@Aspect
@Component
public class RateLimitingAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitingAspect.class);

    private final RateLimitingService rateLimitingService;

    public RateLimitingAspect(RateLimitingService rateLimitingService) {
        this.rateLimitingService = rateLimitingService;
    }

    @Before("@annotation(rateLimited)")
    public void enforceRateLimit(JoinPoint joinPoint, RateLimited rateLimited) {
        String identifier = getClientIdentifier();
        
        boolean allowed = rateLimitingService.tryConsume(
                identifier,
                rateLimited.key(),
                rateLimited.capacity(),
                rateLimited.duration(),
                rateLimited.unit()
        );

        if (!allowed) {
            LOGGER.warn("Rate limit exceeded for client: {}, key: {}", identifier, rateLimited.key());
            // Using a generic exception or creating a specific one.
            // Based on SecurityError, we might want to throw something that results in 429.
            throw new AuthorizationException("Too many requests. Please try again later.", TOO_MANY_REQUESTS);
        }
    }

    private String getClientIdentifier() {
        try {
            return RequestMetadataProvider.getClientInfo().getIpAddress();
        } catch (Exception e) {
            LOGGER.warn("Failed to get client IP address for rate limiting, using 'unknown'");
            return "unknown";
        }
    }
}
