package com.urlshortener.service.service;

import com.urlshortener.service.repository.ShortUrlRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached code -> target lookup for the redirect hot path. Kept in its own bean so the
 * Spring cache proxy applies (self-invocation inside ShortUrlService would bypass it).
 * Misses are not cached, so a newly created code is visible immediately.
 */
@Service
public class UrlLookupService {

    public static final String CACHE = "shortUrlLookup";

    private final ShortUrlRepository repository;

    public UrlLookupService(ShortUrlRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = CACHE, unless = "#result == null")
    @Transactional(readOnly = true)
    public ResolvedUrl find(String code) {
        return repository.findByCode(code)
                .filter(s -> !s.isDeleted())
                .map(s -> new ResolvedUrl(s.getId(), s.getCode(), s.getOriginalUrl(), s.getExpiresAt()))
                .orElse(null);
    }

    @CacheEvict(cacheNames = CACHE)
    public void evict(String code) {
        // annotation does the work
    }
}
