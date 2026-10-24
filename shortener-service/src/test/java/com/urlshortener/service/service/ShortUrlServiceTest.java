package com.urlshortener.service.service;

import com.urlshortener.service.config.ShortenerProperties;
import com.urlshortener.service.domain.ShortUrl;
import com.urlshortener.service.repository.ClickEventRepository;
import com.urlshortener.service.repository.ShortUrlRepository;
import com.urlshortener.service.web.dto.CreateShortUrlRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the business rules; persistence is mocked, see the integration test for the real stack. */
class ShortUrlServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private ShortUrlRepository repository;
    private UrlLookupService lookup;
    private ClickRecorder clickRecorder;
    private CodeGenerator codeGenerator;
    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShortUrlRepository.class);
        lookup = mock(UrlLookupService.class);
        clickRecorder = mock(ClickRecorder.class);
        codeGenerator = mock(CodeGenerator.class);
        ShortenerProperties props = new ShortenerProperties("https://sho.rt", 7, 3, 2048,
                new ShortenerProperties.Analytics(false, 30),
                new ShortenerProperties.RateLimit(false, 10, 10), List.of());
        service = new ShortUrlService(repository, mock(ClickEventRepository.class), lookup, clickRecorder,
                codeGenerator, new UrlValidator(props), new AliasValidator(), props,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void retriesOnCodeCollisionThenSucceeds() {
        when(codeGenerator.generate(7)).thenReturn("taken01", "taken02", "free003");
        when(repository.existsByCode("taken01")).thenReturn(true);
        when(repository.existsByCode("taken02")).thenReturn(true);

        CreateResult result = service.create(new CreateShortUrlRequest("https://example.com", null, null, null));

        assertThat(result.created()).isTrue();
        assertThat(result.shortUrl().getCode()).isEqualTo("free003");
    }

    @Test
    void retriesWhenConcurrentInsertWinsTheRace() {
        when(codeGenerator.generate(7)).thenReturn("raced01", "free002");
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateResult result = service.create(new CreateShortUrlRequest("https://example.com", null, null, null));

        assertThat(result.shortUrl().getCode()).isEqualTo("free002");
    }

    @Test
    void givesUpAfterBoundedAttempts() {
        when(codeGenerator.generate(7)).thenReturn("same000");
        when(repository.existsByCode("same000")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", null, null, null)))
                .isInstanceOf(CodeGenerationException.class);
        verify(codeGenerator, times(3)).generate(7);
    }

    @Test
    void reusesExistingPermanentLinkForSameUrl() {
        ShortUrl existing = new ShortUrl("abc1234", "https://example.com", false, NOW, null);
        when(repository.findFirstByOriginalUrlAndCustomAliasFalseAndExpiresAtIsNullAndDeletedAtIsNull("https://example.com"))
                .thenReturn(Optional.of(existing));

        CreateResult result = service.create(new CreateShortUrlRequest("https://example.com", null, null, null));

        assertThat(result.created()).isFalse();
        assertThat(result.shortUrl()).isSameAs(existing);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aliasConflictIsReported() {
        when(repository.existsByCode("promo")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", "promo", null, null)))
                .isInstanceOf(AliasConflictException.class);
    }

    @Test
    void ttlIsConvertedToAbsoluteExpiry() {
        when(codeGenerator.generate(7)).thenReturn("ttl0001");

        CreateResult result = service.create(new CreateShortUrlRequest("https://example.com", null, null, 3600L));

        assertThat(result.shortUrl().getExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void rejectsBothExpiryFormsAndPastExpiry() {
        assertThatThrownBy(() -> service.create(
                new CreateShortUrlRequest("https://example.com", null, NOW.plusSeconds(60), 60L)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(
                new CreateShortUrlRequest("https://example.com", null, NOW.minusSeconds(1), null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void redirectRecordsClickAndReturnsTarget() {
        when(lookup.find("abc1234")).thenReturn(new ResolvedUrl(1L, "abc1234", "https://example.com", null));

        String target = service.resolveForRedirect("abc1234", "https://news.site", "curl/8");

        assertThat(target).isEqualTo("https://example.com");
        verify(clickRecorder).record(1L, "https://news.site", "curl/8");
    }

    @Test
    void expiredLinkIsGoneAndNotCounted() {
        when(lookup.find("old0001")).thenReturn(new ResolvedUrl(2L, "old0001", "https://example.com", NOW));

        assertThatThrownBy(() -> service.resolveForRedirect("old0001", null, null))
                .isInstanceOf(UrlExpiredException.class);
        verify(clickRecorder, never()).record(any(), any(), any());
    }

    @Test
    void unknownCodeIsNotFound() {
        assertThatThrownBy(() -> service.resolveForRedirect("nope123", null, null))
                .isInstanceOf(UrlNotFoundException.class);
    }
}
