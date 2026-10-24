package com.urlshortener.service.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.urlshortener.service.config.ShortenerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Per-client rate limit on link creation (the write path that can be abused to fill storage).
 * Redirects are deliberately not limited here: they are cheap, cached, and blocking them
 * would hurt legitimate end users. Buckets live in a bounded, expiring Caffeine cache so
 * many distinct clients cannot exhaust memory.
 *
 * <p>Single-node only. Behind a load balancer this should move to a shared store (e.g. Redis).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ShortenerProperties.RateLimit config;
    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    public RateLimitFilter(ShortenerProperties properties) {
        this.config = properties.rateLimit();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.enabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/urls");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.nanoTime();
        TokenBucket bucket = buckets.get(clientKey(request),
                k -> new TokenBucket(config.capacity(), config.refillPerMinute(), now));
        if (bucket.tryConsume(now)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(bucket.secondsUntilNextToken(now)));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"Too many requests","status":429,\
                "detail":"Rate limit exceeded for link creation; retry later"}""");
    }

    /**
     * Uses the socket address. X-Forwarded-For is ignored on purpose because clients can forge it;
     * behind a trusted proxy enable server.forward-headers-strategy so Spring rewrites remoteAddr.
     */
    private static String clientKey(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
