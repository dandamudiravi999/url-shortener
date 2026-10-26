package com.urlshortener.orchestrator.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * TESTING stage: maps acceptance criteria to test cases and gathers real evidence.
 * <ul>
 *   <li>RUN: executes the Maven test suite and parses Surefire reports.</li>
 *   <li>REPORTS: parses Surefire reports from the last build (fast; requires a prior {@code mvn verify}).</li>
 *   <li>PLAN_ONLY: produces the test plan only; status NOT_RUN, which release policy will refuse.</li>
 * </ul>
 */
public final class TestAgent implements Agent {

    public enum Mode { RUN, REPORTS, PLAN_ONLY }

    private static final Pattern SUITE = Pattern.compile(
            "<testsuite[^>]*?name=\"([^\"]+)\"[^>]*?tests=\"(\\d+)\"[^>]*?errors=\"(\\d+)\"[^>]*?skipped=\"(\\d+)\"[^>]*?failures=\"(\\d+)\"");
    private static final Pattern AC = Pattern.compile("^- (AC-\\d+): (.*)$", Pattern.MULTILINE);

    public static final List<String> DEFAULT_COMMAND = List.of("mvn", "-q", "-pl", "shortener-service", "test");

    private final Mode mode;
    private final Path repoRoot;
    private final Path reportsDir;
    private final CommandRunner runner;
    private final List<String> command;

    public TestAgent(Mode mode, Path repoRoot, Path reportsDir, CommandRunner runner, List<String> command) {
        this.mode = mode;
        this.repoRoot = repoRoot;
        this.reportsDir = reportsDir;
        this.runner = runner;
        this.command = List.copyOf(command);
    }

    @Override
    public String name() {
        return "test-agent(" + mode.name().toLowerCase() + ")";
    }

    @Override
    public AgentResult execute(AgentTask task) throws Exception {
        String plan = testPlan(task.context().contentOf("requirements"));
        if (mode == Mode.PLAN_ONLY) {
            return AgentResult.success("test plan only; suite not executed")
                    .artifact("test-plan", plan)
                    .artifact("test-report", "# Test report\n\nstatus: NOT_RUN\nmode: plan-only\n")
                    .decision("Tests not executed", "PLAN_ONLY mode selected; release policy will require real evidence");
        }
        String commandOutput = "";
        if (mode == Mode.RUN) {
            CommandRunner.Result r = runner.run(command, repoRoot, Duration.ofMinutes(10));
            commandOutput = r.output();
            if (r.exitCode() != 0 && !Files.isDirectory(reportsDir())) {
                return AgentResult.failure("test command exited " + r.exitCode() + " without reports", true);
            }
        }
        Totals totals = parseReports();
        if (totals.suites.isEmpty()) {
            throw new NonRetryableException("no Surefire reports under " + reportsDir() + "; run `mvn verify` first or use --tests run");
        }
        boolean passed = totals.failures == 0 && totals.errors == 0;
        StringBuilder report = new StringBuilder("# Test report\n\n");
        report.append("status: ").append(passed ? "PASSED" : "FAILED").append("\n")
                .append("mode: ").append(mode.name().toLowerCase()).append("\n")
                .append("tests: ").append(totals.tests).append("\n")
                .append("failures: ").append(totals.failures).append("\n")
                .append("errors: ").append(totals.errors).append("\n")
                .append("skipped: ").append(totals.skipped).append("\n\n")
                .append("| Suite | Tests | Failures | Errors | Skipped |\n|---|---|---|---|---|\n");
        totals.suites.forEach(s -> report.append("| ").append(s).append(" |\n"));
        if (!commandOutput.isBlank()) {
            String tail = commandOutput.length() > 2000 ? commandOutput.substring(commandOutput.length() - 2000) : commandOutput;
            report.append("\n## Build output (tail)\n\n```\n").append(tail).append("\n```\n");
        }
        if (!passed) {
            return AgentResult.failure(totals.failures + " failures, " + totals.errors + " errors in test suite", true);
        }
        return AgentResult.success(totals.tests + " tests passed")
                .artifact("test-plan", plan)
                .artifact("test-report", report.toString())
                .decision("Accepted test evidence: " + totals.tests + " tests, 0 failures",
                        "Surefire reports from mode " + mode + " are the verification of record");
    }

    static String testPlan(String requirements) {
        StringBuilder md = new StringBuilder("# Test plan\n\n| Criterion | Test level | Verification |\n|---|---|---|\n");
        Matcher m = AC.matcher(requirements);
        while (m.find()) {
            String text = m.group(2);
            String level = text.matches(".*(GET|POST|DELETE|answers|returns).*") ? "integration (MockMvc + H2)" : "unit";
            md.append("| ").append(m.group(1)).append(" | ").append(level).append(" | ").append(text).append(" |\n");
        }
        return md.toString();
    }

    private Path reportsDir() {
        return reportsDir;
    }

    private Totals parseReports() throws IOException {
        Totals t = new Totals();
        if (!Files.isDirectory(reportsDir())) {
            return t;
        }
        try (Stream<Path> files = Files.list(reportsDir())) {
            for (Path p : files.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                String xml = Files.readString(p);
                Matcher m = SUITE.matcher(xml);
                if (m.find()) {
                    int tests = Integer.parseInt(m.group(2));
                    int errors = Integer.parseInt(m.group(3));
                    int skipped = Integer.parseInt(m.group(4));
                    int failures = Integer.parseInt(m.group(5));
                    t.tests += tests;
                    t.errors += errors;
                    t.skipped += skipped;
                    t.failures += failures;
                    String name = m.group(1).substring(m.group(1).lastIndexOf('.') + 1);
                    t.suites.add(name + " | " + tests + " | " + failures + " | " + errors + " | " + skipped);
                }
            }
        }
        return t;
    }

    private static final class Totals {
        int tests;
        int failures;
        int errors;
        int skipped;
        final List<String> suites = new ArrayList<>();
    }
}
