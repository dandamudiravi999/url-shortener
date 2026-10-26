package com.urlshortener.service.web;

import com.urlshortener.service.config.ShortenerProperties;
import com.urlshortener.service.domain.ShortUrl;
import com.urlshortener.service.web.dto.ShortUrlResponse;
import org.springframework.stereotype.Component;

@Component
public class ShortUrlMapper {

    private final String baseUrl;

    public ShortUrlMapper(ShortenerProperties properties) {
        String base = properties.baseUrl();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public ShortUrlResponse toResponse(ShortUrl url) {
        return new ShortUrlResponse(url.getCode(), baseUrl + "/" + url.getCode(), url.getOriginalUrl(),
                url.isCustomAlias(), url.getCreatedAt(), url.getExpiresAt());
    }
}
