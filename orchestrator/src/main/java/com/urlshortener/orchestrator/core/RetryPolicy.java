package com.urlshortener.orchestrator.core;

import java.time.Duration;

/** Bounded exponential backoff. Bounded is the point: an agent never loops forever. */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO);
    }

    public static RetryPolicy standard() {
        return new RetryPolicy(3, Duration.ofMillis(50), 2.0, Duration.ofSeconds(2));
    }

    /** Backoff to wait after the given failed attempt (1-based). */
    public Duration backoffAfter(int attempt) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1);
        return Duration.ofMillis((long) Math.min(millis, maxBackoff.toMillis()));
    }
}
