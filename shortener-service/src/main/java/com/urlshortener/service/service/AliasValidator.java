package com.urlshortener.service.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Rules for user-chosen aliases. Reserved words protect the service's own routes. */
@Component
public class AliasValidator {

    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    private static final Set<String> RESERVED = Set.of(
            "api", "actuator", "swagger-ui", "swagger-ui.html", "v3", "h2-console",
            "health", "admin", "login", "logout", "static", "favicon.ico", "error");

    public void validate(String alias) {
        if (!ALIAS.matcher(alias).matches()) {
            throw new InvalidRequestException(
                    "alias must be 3-32 characters of letters, digits, '-' or '_'");
        }
        if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidRequestException("alias '" + alias + "' is reserved");
        }
    }
}
