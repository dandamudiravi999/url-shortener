package com.urlshortener.service.service;

public class UrlExpiredException extends RuntimeException {
    public UrlExpiredException(String code) {
        super("Short URL '" + code + "' has expired");
    }
}
