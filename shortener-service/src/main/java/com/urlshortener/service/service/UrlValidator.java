package com.urlshortener.service.service;

import com.urlshortener.service.config.ShortenerProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Guards what we are willing to redirect to. The service never fetches the target,
 * so this is about abuse prevention (open-redirect loops, javascript: payloads,
 * pointing users at internal hosts), not SSRF. No DNS lookups: validation stays
 * fast and deterministic.
 */
@Component
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Pattern PRIVATE_IPV4 = Pattern.compile(
            "^(10\\.|127\\.|0\\.|169\\.254\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.).*");

    private final ShortenerProperties properties;
    private final Set<String> blockedHosts;
    private final String ownHost;

    public UrlValidator(ShortenerProperties properties) {
        this.properties = properties;
        this.blockedHosts = properties.blockedHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.ownHost = hostOf(properties.baseUrl());
    }

    /** Returns the normalised URL or throws {@link InvalidRequestException} explaining why it was refused. */
    public String validateAndNormalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidRequestException("url must not be blank");
        }
        String url = rawUrl.strip();
        if (url.length() > properties.maxUrlLength()) {
            throw new InvalidRequestException("url exceeds " + properties.maxUrlLength() + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("url is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidRequestException("only http and https URLs can be shortened");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidRequestException("url must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidRequestException("urls with embedded credentials are not allowed");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (blockedHosts.contains(lowerHost) || PRIVATE_IPV4.matcher(lowerHost).matches()
                || lowerHost.equals("[::1]") || lowerHost.endsWith(".local")) {
            throw new InvalidRequestException("host '" + host + "' is not allowed");
        }
        if (ownHost != null && lowerHost.equals(ownHost)) {
            throw new InvalidRequestException("cannot shorten a URL that points back to this service");
        }
        // Normalise only the parts that are case-insensitive by spec; path and query are kept verbatim.
        return url.replaceFirst("(?i)^" + Pattern.quote(uri.getScheme()), scheme)
                .replaceFirst(Pattern.quote(host), lowerHost);
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
