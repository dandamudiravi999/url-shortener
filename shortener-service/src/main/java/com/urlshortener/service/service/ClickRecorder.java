package com.urlshortener.service.service;

import com.urlshortener.service.domain.ClickEvent;
import com.urlshortener.service.repository.ClickEventRepository;
import com.urlshortener.service.repository.ShortUrlRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executor;

/**
 * Records clicks without blocking the redirect. Analytics are best-effort by design:
 * a failure here is logged and counted but never turns a working redirect into an error.
 */
@Component
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    private final ShortUrlRepository shortUrls;
    private final ClickEventRepository clicks;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final Clock clock;
    private final Counter failures;

    public ClickRecorder(ShortUrlRepository shortUrls,
                         ClickEventRepository clicks,
                         TransactionTemplate tx,
                         @Qualifier("analyticsExecutor") Executor executor,
                         Clock clock,
                         MeterRegistry meters) {
        this.shortUrls = shortUrls;
        this.clicks = clicks;
        this.tx = tx;
        this.executor = executor;
        this.clock = clock;
        this.failures = meters.counter("shortener.analytics.failures");
    }

    public void record(Long shortUrlId, String referrer, String userAgent) {
        Instant now = clock.instant();
        executor.execute(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    shortUrls.incrementClicks(shortUrlId, now);
                    clicks.save(new ClickEvent(shortUrlId, now, referrer, userAgent));
                });
            } catch (RuntimeException e) {
                failures.increment();
                log.warn("Failed to record click for shortUrlId={}: {}", shortUrlId, e.getMessage());
            }
        });
    }
}
