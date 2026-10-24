package com.urlshortener.service.service;

import com.urlshortener.service.config.ShortenerProperties;
import com.urlshortener.service.domain.ShortUrl;
import com.urlshortener.service.repository.ClickEventRepository;
import com.urlshortener.service.repository.ShortUrlRepository;
import com.urlshortener.service.web.dto.CreateShortUrlRequest;
import com.urlshortener.service.web.dto.UrlStatsResponse;
import com.urlshortener.service.web.dto.UrlStatsResponse.DailyClicks;
import com.urlshortener.service.web.dto.UrlStatsResponse.ReferrerClicks;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class ShortUrlService {

    private static final Logger log = LoggerFactory.getLogger(ShortUrlService.class);
    private static final int TOP_REFERRERS = 5;

    private final ShortUrlRepository repository;
    private final ClickEventRepository clickEvents;
    private final UrlLookupService lookup;
    private final ClickRecorder clickRecorder;
    private final CodeGenerator codeGenerator;
    private final UrlValidator urlValidator;
    private final AliasValidator aliasValidator;
    private final ShortenerProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public ShortUrlService(ShortUrlRepository repository,
                           ClickEventRepository clickEvents,
                           UrlLookupService lookup,
                           ClickRecorder clickRecorder,
                           CodeGenerator codeGenerator,
                           UrlValidator urlValidator,
                           AliasValidator aliasValidator,
                           ShortenerProperties properties,
                           Clock clock,
                           MeterRegistry meters) {
        this.repository = repository;
        this.clickEvents = clickEvents;
        this.lookup = lookup;
        this.clickRecorder = clickRecorder;
        this.codeGenerator = codeGenerator;
        this.urlValidator = urlValidator;
        this.aliasValidator = aliasValidator;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Creates a short URL. Not one big transaction on purpose: each insert attempt commits
     * or fails on its own, so a unique-key collision can be retried with a fresh code.
     */
    public CreateResult create(CreateShortUrlRequest request) {
        String url = urlValidator.validateAndNormalize(request.url());
        Instant now = clock.instant();
        Instant expiresAt = resolveExpiry(request, now);
        String alias = blankToNull(request.alias());

        if (alias != null) {
            aliasValidator.validate(alias);
            return new CreateResult(insertWithAlias(alias, url, now, expiresAt), true);
        }

        // Idempotency: the same permanent URL maps to the same code instead of filling the table.
        if (expiresAt == null) {
            var existing = repository.findFirstByOriginalUrlAndCustomAliasFalseAndExpiresAtIsNullAndDeletedAtIsNull(url);
            if (existing.isPresent()) {
                meters.counter("shortener.urls.reused").increment();
                return new CreateResult(existing.get(), false);
            }
        }
        return new CreateResult(insertWithGeneratedCode(url, now, expiresAt), true);
    }

    private ShortUrl insertWithAlias(String alias, String url, Instant now, Instant expiresAt) {
        if (repository.existsByCode(alias)) {
            throw new AliasConflictException(alias);
        }
        try {
            ShortUrl saved = repository.saveAndFlush(new ShortUrl(alias, url, true, now, expiresAt));
            meters.counter("shortener.urls.created", "type", "alias").increment();
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request for the same alias.
            throw new AliasConflictException(alias);
        }
    }

    private ShortUrl insertWithGeneratedCode(String url, Instant now, Instant expiresAt) {
        int attempts = properties.maxGenerationAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String code = codeGenerator.generate(properties.codeLength());
            if (repository.existsByCode(code)) {
                log.debug("Code collision on attempt {} ({}), retrying", attempt, code);
                continue;
            }
            try {
                ShortUrl saved = repository.saveAndFlush(new ShortUrl(code, url, false, now, expiresAt));
                meters.counter("shortener.urls.created", "type", "generated").increment();
                return saved;
            } catch (DataIntegrityViolationException e) {
                log.debug("Concurrent insert took code {} on attempt {}, retrying", code, attempt);
            }
        }
        meters.counter("shortener.urls.generation_exhausted").increment();
        throw new CodeGenerationException(attempts);
    }

    /** Resolves a code for redirect and records the click asynchronously. */
    public String resolveForRedirect(String code, String referrer, String userAgent) {
        ResolvedUrl resolved = lookup.find(code);
        if (resolved == null) {
            meters.counter("shortener.redirects", "outcome", "not_found").increment();
            throw new UrlNotFoundException(code);
        }
        if (resolved.expiresAt() != null && !clock.instant().isBefore(resolved.expiresAt())) {
            meters.counter("shortener.redirects", "outcome", "expired").increment();
            throw new UrlExpiredException(code);
        }
        clickRecorder.record(resolved.id(), referrer, userAgent);
        meters.counter("shortener.redirects", "outcome", "ok").increment();
        return resolved.originalUrl();
    }

    @Transactional(readOnly = true)
    public ShortUrl get(String code) {
        return repository.findByCode(code)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new UrlNotFoundException(code));
    }

    @Transactional(readOnly = true)
    public UrlStatsResponse stats(String code) {
        ShortUrl url = get(code);
        Instant now = clock.instant();
        int days = properties.analytics().recentDays();
        Instant since = now.truncatedTo(ChronoUnit.DAYS).minus(days - 1L, ChronoUnit.DAYS);

        Map<LocalDate, Long> perDay = clickEvents.findClickTimesSince(url.getId(), since).stream()
                .collect(Collectors.groupingBy(t -> LocalDate.ofInstant(t, ZoneOffset.UTC),
                        TreeMap::new, Collectors.counting()));
        List<DailyClicks> daily = perDay.entrySet().stream()
                .map(e -> new DailyClicks(e.getKey(), e.getValue()))
                .toList();
        List<ReferrerClicks> referrers = clickEvents.topReferrers(url.getId(), PageRequest.of(0, TOP_REFERRERS))
                .stream()
                .map(r -> new ReferrerClicks(r.getReferrer(), r.getClicks()))
                .toList();

        return new UrlStatsResponse(url.getCode(), url.getOriginalUrl(), url.getClickCount(),
                url.getCreatedAt(), url.getLastAccessedAt(), url.getExpiresAt(), url.isExpired(now),
                daily, referrers);
    }

    /** Soft delete: keeps analytics history and prevents the code from being handed out again. */
    @Transactional
    public void delete(String code) {
        ShortUrl url = get(code);
        url.markDeleted(clock.instant());
        lookup.evict(code);
        meters.counter("shortener.urls.deleted").increment();
    }

    private Instant resolveExpiry(CreateShortUrlRequest request, Instant now) {
        if (request.expiresAt() != null && request.ttlSeconds() != null) {
            throw new InvalidRequestException("give either expiresAt or ttlSeconds, not both");
        }
        if (request.ttlSeconds() != null) {
            return now.plusSeconds(request.ttlSeconds());
        }
        if (request.expiresAt() != null) {
            if (!request.expiresAt().isAfter(now)) {
                throw new InvalidRequestException("expiresAt must be in the future");
            }
            return request.expiresAt();
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
