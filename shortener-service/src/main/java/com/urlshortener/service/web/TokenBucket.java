package com.urlshortener.service.web;

/**
 * Classic token bucket: {@code capacity} burst, refilled continuously at a fixed rate.
 * Synchronized per bucket; contention is per client, so this is cheap in practice.
 */
public class TokenBucket {

    private final long capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, long refillPerMinute, long nowNanos) {
        this.capacity = capacity;
        this.refillPerNano = refillPerMinute / 60_000_000_000.0;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    public synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    /** Seconds until one token is available (at least 1, for the Retry-After header). */
    public synchronized long secondsUntilNextToken(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            return 0;
        }
        double nanos = (1 - tokens) / refillPerNano;
        return Math.max(1, (long) Math.ceil(nanos / 1_000_000_000.0));
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = nowNanos;
        }
    }
}
