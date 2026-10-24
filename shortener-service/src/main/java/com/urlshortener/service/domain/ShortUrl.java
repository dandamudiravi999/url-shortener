package com.urlshortener.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "short_url")
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(name = "custom_alias", nullable = false)
    private boolean customAlias;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    @Version
    private long version;

    protected ShortUrl() {
        // for JPA
    }

    public ShortUrl(String code, String originalUrl, boolean customAlias, Instant createdAt, Instant expiresAt) {
        this.code = code;
        this.originalUrl = originalUrl;
        this.customAlias = customAlias;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void markDeleted(Instant now) {
        this.deletedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public boolean isCustomAlias() {
        return customAlias;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public long getClickCount() {
        return clickCount;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }
}
