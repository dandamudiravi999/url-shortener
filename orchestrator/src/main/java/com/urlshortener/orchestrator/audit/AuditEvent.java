package com.urlshortener.orchestrator.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One tamper-evident audit record. {@code hash = sha256(prevHash + canonical payload)}, so editing,
 * deleting or reordering any line breaks verification of every line after it.
 */
public record AuditEvent(long seq, Instant at, String runId, String nodeId, AuditEventType type,
                         String message, Map<String, Object> data, String prevHash, String hash) {
}
