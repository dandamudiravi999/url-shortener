package com.urlshortener.service.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * All tunables for the service, bound from the {@code shortener.*} namespace.
 * Validated at startup so a bad config fails fast instead of at request time.
 */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @NotBlank String baseUrl,
        @DefaultValue("7") @Min(4) @Max(16) int codeLength,
        @DefaultValue("5") @Min(1) @Max(20) int maxGenerationAttempts,
        @DefaultValue("2048") @Min(64) int maxUrlLength,
        @DefaultValue Analytics analytics,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue List<String> blockedHosts) {

    public record Analytics(
            @DefaultValue("true") boolean async,
            @DefaultValue("30") @Min(1) int recentDays) {
    }

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") @Min(1) int capacity,
            @DefaultValue("60") @Min(1) int refillPerMinute) {
    }
}
