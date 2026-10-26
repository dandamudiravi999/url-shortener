package com.urlshortener.service.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Schema(description = "Request to create a short URL. Give at most one of expiresAt or ttlSeconds.")
public record CreateShortUrlRequest(
        @Schema(example = "https://example.com/articles/2026/10/how-we-scaled", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 2048) String url,

        @Schema(example = "launch-2026", description = "Optional custom code, 3-32 chars of [A-Za-z0-9_-]")
        String alias,

        @Schema(example = "2026-12-31T23:59:59Z", description = "Optional absolute expiry (UTC)")
        Instant expiresAt,

        @Schema(example = "86400", description = "Optional relative expiry in seconds")
        @Positive Long ttlSeconds) {
}
