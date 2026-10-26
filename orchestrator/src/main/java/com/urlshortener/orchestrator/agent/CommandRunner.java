package com.urlshortener.orchestrator.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Process execution seam so the test agent can run real builds in the CLI and fakes in unit tests. */
@FunctionalInterface
public interface CommandRunner {

    record Result(int exitCode, String output) {
    }

    Result run(List<String> command, Path workingDir, Duration timeout) throws Exception;

    static CommandRunner system() {
        return (command, dir, timeout) -> {
            Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var in = p.inputReader()) {
                    in.lines().forEach(l -> out.append(l).append('\n'));
                } catch (java.io.IOException ignored) {
                    // process ended
                }
            });
            if (!p.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new java.util.concurrent.TimeoutException("command timed out after " + timeout);
            }
            reader.join(1000);
            return new Result(p.exitValue(), out.toString());
        };
    }
}
