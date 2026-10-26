package com.urlshortener.service.service;

/** Strategy for producing candidate short codes. Uniqueness is enforced by the caller and the DB. */
public interface CodeGenerator {
    String generate(int length);
}
