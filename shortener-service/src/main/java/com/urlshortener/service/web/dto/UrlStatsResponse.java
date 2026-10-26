package com.urlshortener.service.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record UrlStatsResponse(
        String code,
        String originalUrl,
        long totalClicks,
        Instant createdAt,
        Instant lastAccessedAt,
        Instant expiresAt,
        boolean expired,
        List<DailyClicks> clicksByDay,
        List<ReferrerClicks> topReferrers) {

    public record DailyClicks(LocalDate date, long clicks) {
    }

    public record ReferrerClicks(String referrer, long clicks) {
    }
}
