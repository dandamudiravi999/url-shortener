package com.urlshortener.service.web.dto;

import java.time.Instant;

public record ShortUrlResponse(
        String code,
        String shortUrl,
        String originalUrl,
        boolean customAlias,
        Instant createdAt,
        Instant expiresAt) {
}
