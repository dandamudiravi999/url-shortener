package com.urlshortener.service.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Random Base62 codes. With length 7 the keyspace is 62^7 (about 3.5 trillion), so
 * collisions are rare and handled by bounded retry. Random (rather than sequential)
 * codes are not enumerable, which stops scraping of other users' links.
 */
@Component
public class Base62CodeGenerator implements CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
