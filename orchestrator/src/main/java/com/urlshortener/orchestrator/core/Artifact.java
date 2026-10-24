package com.urlshortener.orchestrator.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * A versioned, content-addressed output of a stage (requirements, design, changeset, test report...).
 * The hash is what the engine compares to decide whether downstream work became stale.
 */
public record Artifact(String name, String content, String producedBy, int version, String sha256, Instant createdAt) {

    public static Artifact of(String name, String content, String producedBy, int version, Instant createdAt) {
        return new Artifact(name, content, producedBy, version, sha256(content), createdAt);
    }

    public String ref() {
        return name + "@v" + version;
    }

    public String shortHash() {
        return sha256.substring(0, 12);
    }

    public static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
