package com.urlshortener.service.service;

public class AliasConflictException extends RuntimeException {
    public AliasConflictException(String alias) {
        super("Alias '" + alias + "' is already in use");
    }
}
