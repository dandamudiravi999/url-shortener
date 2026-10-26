package com.urlshortener.orchestrator.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorCliTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final OrchestratorCli cli = new OrchestratorCli(new PrintStream(buffer, true, StandardCharsets.UTF_8));

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void listsScenariosAndRejectsBadUsage() throws Exception {
        assertThat(cli.execute(new String[]{"list"})).isZero();
        assertThat(output()).contains("greenfield", "brownfield", "ambiguous");
        assertThat(cli.execute(new String[]{})).isEqualTo(2);
        assertThat(cli.execute(new String[]{"run", "nope"})).isEqualTo(2);
    }

    @Test
    void planOnlyTestsAreRefusedAtReleaseAndRunExitsNonZero() throws Exception {
        int code = cli.execute(new String[]{"run", "greenfield", "--repo", "..", "--tests", "plan", "--out", tmp.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(output()).contains("status: FAILED", "release requires a test report with status: PASSED");
        Path audit;
        try (Stream<Path> files = Files.walk(tmp)) {
            audit = files.filter(p -> p.getFileName().toString().equals("audit.jsonl")).findFirst().orElseThrow();
        }
        buffer.reset();
        assertThat(cli.execute(new String[]{"verify-audit", audit.toString()})).isZero();
        assertThat(output()).contains("audit chain intact");
    }
}
