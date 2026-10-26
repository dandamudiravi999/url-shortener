package com.urlshortener.orchestrator.core;

import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {

    @TempDir
    Path tmp;

    @Test
    void persistedChainVerifiesAndDetectsTampering() throws Exception {
        Path file = tmp.resolve("audit.jsonl");
        try (AuditLog log = new AuditLog("r1", Clock.systemUTC(), file)) {
            log.record(AuditEventType.RUN_STARTED, null, "start", Map.of("k", 1));
            log.record(AuditEventType.NODE_STARTED, "a", "node a", Map.of("list", List.of("x", "y")));
            log.record(AuditEventType.RUN_FINISHED, null, "done");
        }
        assertThat(AuditLog.verify(AuditLog.read(file))).isEqualTo(-1);

        List<String> lines = Files.readAllLines(file);
        lines.set(1, lines.get(1).replace("node a", "node b"));
        Files.write(file, lines);
        assertThat(AuditLog.verify(AuditLog.read(file))).isEqualTo(2);

        Files.write(file, List.of(lines.get(0), lines.get(2)));
        assertThat(AuditLog.verify(AuditLog.read(file))).isEqualTo(3); // deleted line breaks the chain
    }
}
