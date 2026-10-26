package com.urlshortener.orchestrator.cli;

import com.urlshortener.orchestrator.agent.CommandRunner;
import com.urlshortener.orchestrator.agent.TestAgent;
import com.urlshortener.orchestrator.audit.AuditLog;
import com.urlshortener.orchestrator.core.ApprovalGateway;
import com.urlshortener.orchestrator.core.EngineConfig;
import com.urlshortener.orchestrator.core.NodeReport;
import com.urlshortener.orchestrator.core.RunReport;
import com.urlshortener.orchestrator.core.RunStatus;
import com.urlshortener.orchestrator.core.ScriptedApprovalGateway;
import com.urlshortener.orchestrator.scenario.Scenario;
import com.urlshortener.orchestrator.scenario.ScenarioEnvironment;
import com.urlshortener.orchestrator.scenario.ScenarioRunner;
import com.urlshortener.orchestrator.scenario.Scenarios;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Command line entry point.
 *
 * <pre>
 *   list
 *   run &lt;greenfield|brownfield|ambiguous|all&gt; [--interactive] [--out DIR] [--repo DIR]
 *       [--tests auto|run|reports|plan] [--fail NODE] [--reject NODE] [--max-attempts N]
 *   verify-audit &lt;audit.jsonl&gt;
 * </pre>
 * Exit code: 0 when every run succeeded, 1 when any run failed or stopped, 2 on usage errors.
 */
public final class OrchestratorCli {

    private final PrintStream out;

    public OrchestratorCli(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        System.exit(new OrchestratorCli(System.out).execute(args));
    }

    public int execute(String[] args) throws Exception {
        if (args.length == 0) {
            return usage();
        }
        return switch (args[0]) {
            case "list" -> list();
            case "run" -> args.length < 2 ? usage() : run(args);
            case "verify-audit" -> args.length < 2 ? usage() : verify(Path.of(args[1]));
            default -> usage();
        };
    }

    private int list() {
        for (Scenario s : Scenarios.all()) {
            out.printf("%-11s %s%n            \"%s\"%n", s.name(), s.title(), s.requirement());
        }
        return 0;
    }

    private int run(String[] args) throws Exception {
        boolean interactive = false;
        Path outRoot = Path.of("runs");
        Path repo = Path.of(".");
        String tests = "auto";
        Set<String> fail = new HashSet<>();
        Set<String> reject = new HashSet<>();
        EngineConfig config = EngineConfig.defaults();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--interactive" -> interactive = true;
                case "--out" -> outRoot = Path.of(args[++i]);
                case "--repo" -> repo = Path.of(args[++i]);
                case "--tests" -> tests = args[++i].toLowerCase(Locale.ROOT);
                case "--fail" -> fail.add(args[++i]);
                case "--reject" -> reject.add(args[++i]);
                case "--max-attempts" -> config = config.withMaxTotalAttempts(Integer.parseInt(args[++i]));
                default -> {
                    out.println("unknown option " + args[i]);
                    return usage();
                }
            }
        }
        repo = repo.toAbsolutePath().normalize();
        config = config.withKillSwitch(repo.resolve(".orchestrator/STOP"));
        TestAgent.Mode mode = switch (tests) {
            case "run" -> TestAgent.Mode.RUN;
            case "reports" -> TestAgent.Mode.REPORTS;
            case "plan" -> TestAgent.Mode.PLAN_ONLY;
            default -> Files.isDirectory(ScenarioEnvironment.defaultReportsDir(repo))
                    ? TestAgent.Mode.REPORTS : TestAgent.Mode.RUN;
        };

        List<Scenario> selected = new ArrayList<>();
        if (args[1].equalsIgnoreCase("all")) {
            selected.addAll(Scenarios.all());
        } else {
            var s = Scenarios.byName(args[1]);
            if (s.isEmpty()) {
                out.println("unknown scenario " + args[1]);
                return usage();
            }
            selected.add(s.get());
        }

        ScenarioRunner runner = new ScenarioRunner(ScenarioEnvironment.forRepo(repo, mode, CommandRunner.system(), fail),
                config, Clock.systemUTC());
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        boolean allOk = true;
        for (Scenario scenario : selected) {
            ApprovalGateway human = interactive
                    ? new ConsoleApprovalGateway(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out, System.getProperty("user.name", "operator"))
                    : withRejections(scenario.scriptedHuman(), reject).build();
            Path dir = outRoot.resolve(stamp + "-" + scenario.name());
            out.println("\n>>> " + scenario.name() + ": " + scenario.title());
            out.println("    test mode: " + mode + (interactive ? ", interactive approvals" : ", scripted approvals"));
            RunReport report = runner.run(scenario, human, dir);
            print(report, dir);
            allOk &= report.status() == RunStatus.SUCCEEDED;
        }
        return allOk ? 0 : 1;
    }

    private static ScriptedApprovalGateway.Builder withRejections(ScriptedApprovalGateway.Builder b, Set<String> reject) {
        reject.forEach(node -> b.reject(node, "rejected via --reject"));
        return b;
    }

    private void print(RunReport r, Path dir) {
        out.printf("    status: %s%s%n", r.status(), r.reason() == null ? "" : " (" + r.reason() + ")");
        for (NodeReport n : r.nodes()) {
            out.printf("    %-18s %-12s attempts=%d runs=%d %s%n", n.id(), n.state(), n.attempts(), n.executions(),
                    n.message() == null ? "" : truncate(n.message(), 90));
        }
        var m = r.metrics();
        out.printf("    metrics: successRate=%.0f%% retries=%d fallbacks=%d replans=%d planExtensions=%d rollbacks=%d approvals=%d/%d rejected, mttr=%s, e2e=%dms%n",
                m.successRate() * 100, m.retries(), m.fallbacks(), m.replans(), m.planExtensions(), m.rollbacks(),
                m.approvalsRequested(), m.approvalsRejected(), m.mttrMillis() == null ? "n/a" : m.mttrMillis() + "ms",
                m.endToEndMillis());
        out.printf("    audit: %d events, chain %s%n", r.auditEvents(), r.auditChainIntact() ? "intact" : "BROKEN");
        out.println("    report: " + dir.resolve("SUMMARY.md"));
    }

    private int verify(Path file) throws Exception {
        var events = AuditLog.read(file);
        long broken = AuditLog.verify(events);
        if (broken == -1) {
            out.println("audit chain intact: " + events.size() + " events");
            return 0;
        }
        out.println("audit chain BROKEN at event #" + broken);
        return 1;
    }

    private int usage() {
        out.println("""
                usage:
                  list
                  run <greenfield|brownfield|ambiguous|all> [--interactive] [--out DIR] [--repo DIR]
                      [--tests auto|run|reports|plan] [--fail NODE] [--reject NODE] [--max-attempts N]
                  verify-audit <audit.jsonl>
                safe stop: create <repo>/.orchestrator/STOP while a run is in progress""");
        return 2;
    }

    private static String truncate(String s, int max) {
        String one = s.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max - 3) + "...";
    }
}
