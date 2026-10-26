package com.urlshortener.service.service;

public class UrlNotFoundException extends RuntimeException {
    public UrlNotFoundException(String code) {
        super("No short URL found for code '" + code + "'");
    }
}
