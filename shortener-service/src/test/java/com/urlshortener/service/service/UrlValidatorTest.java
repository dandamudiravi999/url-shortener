package com.urlshortener.service.service;

import com.urlshortener.service.config.ShortenerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(new ShortenerProperties(
            "https://sho.rt", 7, 5, 2048,
            new ShortenerProperties.Analytics(false, 30),
            new ShortenerProperties.RateLimit(false, 10, 10),
            List.of("localhost", "evil.example")));

    @Test
    void acceptsAndNormalisesSchemeAndHostCase() {
        assertThat(validator.validateAndNormalize("  HTTPS://Example.COM/Path?Q=1  "))
                .isEqualTo("https://example.com/Path?Q=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file",
            "javascript:alert(1)",
            "example.com/no-scheme",
            "http://",
            "https://user:pass@example.com/",
            "http://localhost:8080/admin",
            "http://127.0.0.1/",
            "http://192.168.1.10/router",
            "http://10.0.0.5/",
            "http://172.20.1.1/",
            "http://printer.local/",
            "https://evil.example/phish",
            "https://sho.rt/abc1234",
            "not a url at all"
    })
    void rejectsUnsafeOrInvalidUrls(String url) {
        assertThatThrownBy(() -> validator.validateAndNormalize(url))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsOverlongUrls() {
        String url = "https://example.com/" + "a".repeat(2100);
        assertThatThrownBy(() -> validator.validateAndNormalize(url))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("2048");
    }
}
