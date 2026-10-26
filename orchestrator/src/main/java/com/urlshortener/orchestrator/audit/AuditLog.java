package com.urlshortener.orchestrator.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.urlshortener.orchestrator.core.Artifact;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Append-only, hash-chained audit trail. Written as JSON Lines (one event per line) and flushed per
 * event, so a crash loses at most the event being written. Thread-safe.
 */
public final class AuditLog implements AutoCloseable {

    static final String GENESIS = "0".repeat(64);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final String runId;
    private final Clock clock;
    private final BufferedWriter writer;
    private final List<AuditEvent> events = new ArrayList<>();
    private String lastHash = GENESIS;

    public AuditLog(String runId, Clock clock, Path file) {
        this.runId = runId;
        this.clock = clock;
        try {
            if (file != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                this.writer = null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static AuditLog inMemory(String runId, Clock clock) {
        return new AuditLog(runId, clock, null);
    }

    public synchronized AuditEvent record(AuditEventType type, String nodeId, String message, Map<String, ?> data) {
        Map<String, Object> payload = new TreeMap<>(data == null ? Map.of() : data);
        long seq = events.size() + 1L;
        AuditEvent unsigned = new AuditEvent(seq, clock.instant(), runId, nodeId, type, message, payload, lastHash, null);
        String hash = Artifact.sha256(lastHash + canonical(unsigned));
        AuditEvent event = new AuditEvent(seq, unsigned.at(), runId, nodeId, type, message, payload, lastHash, hash);
        events.add(event);
        lastHash = hash;
        if (writer != null) {
            try {
                writer.write(JSON.writeValueAsString(event));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("audit write failed; refusing to continue unaudited", e);
            }
        }
        return event;
    }

    public AuditEvent record(AuditEventType type, String nodeId, String message) {
        return record(type, nodeId, message, Map.of());
    }

    public synchronized List<AuditEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized long count(AuditEventType type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    /** Recomputes the chain; returns the seq of the first broken event, or -1 when intact. */
    public static long verify(List<AuditEvent> events) {
        String prev = GENESIS;
        for (AuditEvent e : events) {
            AuditEvent unsigned = new AuditEvent(e.seq(), e.at(), e.runId(), e.nodeId(), e.type(), e.message(),
                    new TreeMap<>(e.data()), e.prevHash(), null);
            if (!e.prevHash().equals(prev) || !Artifact.sha256(prev + canonical(unsigned)).equals(e.hash())) {
                return e.seq();
            }
            prev = e.hash();
        }
        return -1;
    }

    public static List<AuditEvent> read(Path file) throws IOException {
        List<AuditEvent> list = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                list.add(JSON.readValue(line, AuditEvent.class));
            }
        }
        return list;
    }

    private static String canonical(AuditEvent unsigned) {
        try {
            return JSON.writeValueAsString(unsigned);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
    }
}
