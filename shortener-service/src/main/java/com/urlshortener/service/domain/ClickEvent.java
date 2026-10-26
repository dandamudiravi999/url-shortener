package com.urlshortener.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One redirect. Deliberately stores no IP address: referrer and user agent are enough
 * for the required analytics and keep the service clear of most PII obligations.
 */
@Entity
@Table(name = "click_event")
public class ClickEvent {

    public static final int MAX_HEADER_LENGTH = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_url_id", nullable = false)
    private Long shortUrlId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(length = MAX_HEADER_LENGTH)
    private String referrer;

    @Column(name = "user_agent", length = MAX_HEADER_LENGTH)
    private String userAgent;

    protected ClickEvent() {
        // for JPA
    }

    public ClickEvent(Long shortUrlId, Instant occurredAt, String referrer, String userAgent) {
        this.shortUrlId = shortUrlId;
        this.occurredAt = occurredAt;
        this.referrer = truncate(referrer);
        this.userAgent = truncate(userAgent);
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= MAX_HEADER_LENGTH ? value : value.substring(0, MAX_HEADER_LENGTH);
    }

    public Long getId() {
        return id;
    }

    public Long getShortUrlId() {
        return shortUrlId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getReferrer() {
        return referrer;
    }

    public String getUserAgent() {
        return userAgent;
    }
}
