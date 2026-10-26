package com.urlshortener.orchestrator.agent;

/** Deterministic failure (bad input, missing capability): retrying would only waste budget. */
public class NonRetryableException extends Exception {
    public NonRetryableException(String message) {
        super(message);
    }
}
