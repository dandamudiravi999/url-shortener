package com.urlshortener.service.service;

/** A request that is syntactically valid JSON but violates a business rule. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
