package com.urlshortener.orchestrator.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.urlshortener.orchestrator.policy.PolicyFinding;
import com.urlshortener.orchestrator.policy.Verdict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Writes report.json, SUMMARY.md (the engineering summary) and every artifact version to a run folder. */
public final class ReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ReportWriter() {
    }

    public static void write(RunReport report, Path dir) throws IOException {
        Files.createDirectories(dir.resolve("artifacts"));
        JSON.writeValue(dir.resolve("report.json").toFile(), report);
        Files.writeString(dir.resolve("SUMMARY.md"), toMarkdown(report));
        for (Artifact a : report.artifactHistory()) {
            Files.writeString(dir.resolve("artifacts").resolve(a.name() + ".v" + a.version() + ".md"), a.content());
        }
    }

    public static String toMarkdown(RunReport r) {
        StringBuilder md = new StringBuilder();
        md.append("# Engineering summary: ").append(r.scenario()).append("\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Run | `").append(r.runId()).append("` |\n");
        md.append("| Status | **").append(r.status()).append("**")
                .append(r.reason() == null ? "" : " (" + r.reason() + ")").append(" |\n");
        md.append("| Plan revision | ").append(r.planRevision()).append(" |\n");
        md.append("| Audit chain | ").append(r.auditChainIntact() ? "intact" : "**BROKEN**")
                .append(", ").append(r.auditEvents()).append(" events (`audit.jsonl`) |\n\n");

        md.append("## Requirement\n\n> ").append(r.requirement().replace("\n", "\n> ")).append("\n\n");

        md.append("## Plan (final dependency graph)\n\n");
        md.append("Nodes on the same level have no dependency on each other and ran in parallel.\n\n");
        for (int i = 0; i < r.finalPlan().size(); i++) {
            md.append("- Level ").append(i).append(": ").append(String.join(", ", r.finalPlan().get(i))).append("\n");
        }
        md.append("\n```mermaid\nflowchart LR\n");
        for (NodeReport n : r.nodes()) {
            md.append("  ").append(mermaidId(n.id())).append("[\"").append(n.id()).append("<br/>")
                    .append(n.state()).append("\"]\n");
            for (String dep : n.dependsOn()) {
                md.append("  ").append(mermaidId(dep)).append(" --> ").append(mermaidId(n.id())).append("\n");
            }
        }
        md.append("```\n\n");

        md.append("## Execution\n\n| Node | Stage | Impact | State | Attempts | Runs | Agent | Time (ms) | Outcome |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (NodeReport n : r.nodes()) {
            md.append("| ").append(n.id()).append(" | ").append(n.stage()).append(" | ").append(n.impact())
                    .append(" | ").append(n.state()).append(" | ").append(n.attempts()).append(" | ")
                    .append(n.executions()).append(" | ").append(n.agent() == null ? "-" : n.agent()).append(" | ")
                    .append(n.durationMillis()).append(" | ").append(cell(n.message())).append(" |\n");
        }

        var m = r.metrics();
        md.append("\n## Reliability metrics\n\n| Metric | Value |\n|---|---|\n");
        md.append("| Success rate | ").append(String.format(Locale.ROOT, "%.0f%%", m.successRate() * 100)).append(" |\n");
        md.append("| Attempts / retries | ").append(m.attempts()).append(" / ").append(m.retries()).append(" |\n");
        md.append("| Fallbacks | ").append(m.fallbacks()).append(" |\n");
        md.append("| Rollbacks | ").append(m.rollbacks()).append(" |\n");
        md.append("| Re-plans / plan extensions | ").append(m.replans()).append(" / ").append(m.planExtensions()).append(" |\n");
        md.append("| Approvals requested / rejected | ").append(m.approvalsRequested()).append(" / ")
                .append(m.approvalsRejected()).append(" |\n");
        md.append("| Policy denials | ").append(m.policyDenials()).append(" |\n");
        md.append("| MTTR (recovered nodes) | ").append(m.mttrMillis() == null ? "n/a" : m.mttrMillis() + " ms over " + m.recoveredNodes() + " recovered node(s)").append(" |\n");
        md.append("| End-to-end latency | ").append(m.endToEndMillis()).append(" ms |\n");

        md.append("\n## Human oversight\n\n");
        if (r.approvals().isEmpty()) {
            md.append("No approvals were needed.\n");
        }
        for (ApprovalRecord a : r.approvals()) {
            md.append("- **").append(a.nodeId()).append("**: ").append(a.action()).append(" -> ")
                    .append(a.approved() ? "approved" : "REJECTED").append(" by ").append(a.approver())
                    .append(a.comment() == null ? "" : " (\"" + a.comment() + "\")").append("\n");
            a.reasons().forEach(reason -> md.append("  - reason: ").append(reason).append("\n"));
            a.answers().forEach((q, ans) -> md.append("  - Q: ").append(q).append("\n    A: ").append(ans).append("\n"));
        }

        md.append("\n## Policy guardrails\n\n");
        List<PolicyFinding> notable = r.policyFindings().stream().filter(f -> f.verdict() != Verdict.ALLOW).toList();
        if (notable.isEmpty()) {
            md.append("No findings.\n");
        }
        for (PolicyFinding f : notable) {
            md.append("- `").append(f.verdict()).append("` ").append(f.ruleId()).append(" (").append(f.category())
                    .append(") on `").append(f.artifact()).append("`: ").append(f.message()).append("\n");
        }

        md.append("\n## Decision lineage\n\n");
        for (DecisionRecord d : r.decisions()) {
            md.append("- **").append(d.id()).append("** [").append(d.nodeId()).append(", plan rev ")
                    .append(d.planRevision()).append("] ").append(d.summary()).append("\n  - why: ")
                    .append(d.rationale()).append("\n  - based on: ")
                    .append(d.basedOn().isEmpty() ? "original requirement" : String.join(", ", d.basedOn())).append("\n");
        }

        md.append("\n## Artifacts\n\n| Artifact | Version | Produced by | SHA-256 |\n|---|---|---|---|\n");
        for (Artifact a : r.artifacts()) {
            md.append("| ").append(a.name()).append(" | v").append(a.version()).append(" | ").append(a.producedBy())
                    .append(" | `").append(a.shortHash()).append("` |\n");
        }
        md.append("\nEvery version is saved under `artifacts/`.\n");

        r.artifacts().stream().filter(a -> a.name().equals("requirements")).findFirst().ifPresent(req -> {
            String assumptions = section(req.content(), "## Assumptions");
            if (!assumptions.isBlank()) {
                md.append("\n## Assumptions\n\n").append(assumptions).append("\n");
            }
        });
        md.append("\n## Limitations of this run\n\n")
                .append("- Agents are deterministic and rule-based (no LLM call); the `Agent` interface is the seam for one.\n")
                .append("- The implementation agent proposes a change set and plan; it does not edit source files.\n")
                .append("- Test evidence is only as strong as the configured test mode (see `test-report`).\n");
        return md.toString();
    }

    static String section(String markdown, String heading) {
        int start = markdown.indexOf(heading);
        if (start < 0) {
            return "";
        }
        int bodyStart = markdown.indexOf('\n', start) + 1;
        int next = markdown.indexOf("\n## ", bodyStart);
        return markdown.substring(bodyStart, next < 0 ? markdown.length() : next).strip();
    }

    private static String cell(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replace("|", "\\|").replace("\n", " ");
        return oneLine.length() > 140 ? oneLine.substring(0, 137) + "..." : oneLine;
    }

    private static String mermaidId(String id) {
        return id.chars().mapToObj(c -> Character.isLetterOrDigit(c) ? String.valueOf((char) c) : "_")
                .collect(Collectors.joining());
    }
}
