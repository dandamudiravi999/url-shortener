package com.urlshortener.service.service;

/** Raised when bounded retries could not find a free code; signals the keyspace needs widening. */
public class CodeGenerationException extends RuntimeException {
    public CodeGenerationException(int attempts) {
        super("Could not generate a unique code after " + attempts + " attempts");
    }
}
