package com.urlshortener.service.service;

import java.io.Serializable;
import java.time.Instant;

/** Immutable, cacheable projection of what the redirect path needs. */
public record ResolvedUrl(Long id, String code, String originalUrl, Instant expiresAt) implements Serializable {
}
