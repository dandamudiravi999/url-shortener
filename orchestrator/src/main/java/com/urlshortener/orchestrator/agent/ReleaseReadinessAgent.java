package com.urlshortener.orchestrator.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RELEASE_READINESS stage: builds the go/no-go checklist and release notes. It proposes; the
 * release-compliance policy verifies evidence and a human signs off (HIGH impact).
 */
public final class ReleaseReadinessAgent implements Agent {

    @Override
    public String name() {
        return "release-readiness-agent";
    }

    @Override
    public AgentResult execute(AgentTask task) {
        var ctx = task.context();
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("Requirements normalised with acceptance criteria", ctx.contentOf("requirements").contains("AC-1"));
        checks.put("No open questions", ctx.contentOf("open-questions").strip().equals("none") || !ctx.has("open-questions"));
        checks.put("Design reviewed", ctx.has("design"));
        checks.put("Change set passed change control", ctx.has("changeset"));
        checks.put("Tests passed", ctx.contentOf("test-report").contains("status: PASSED"));
        checks.put("Security review passed (if required)", !ctx.has("security-review") || ctx.contentOf("security-review").contains("verdict: PASS"));
        checks.put("Documentation generated", ctx.has("documentation"));

        StringBuilder md = new StringBuilder("# Release checklist\n\n");
        checks.forEach((k, v) -> md.append("- [").append(v ? "x" : " ").append("] ").append(k).append("\n"));
        long open = checks.values().stream().filter(v -> !v).count();
        md.append("\nrecommendation: ").append(open == 0 ? "GO" : "NO-GO (" + open + " open items)").append("\n");

        String notes = "# Release notes\n\n" + DocumentationAgent.section(ctx.contentOf("requirements"), "## Functional requirements")
                + "\n\nVerification: " + firstLine(ctx.contentOf("test-report"), "tests:") + "\n";
        AgentResult result = AgentResult.success(open == 0 ? "release candidate ready (GO)" : "NO-GO: " + open + " open items")
                .artifact("release-checklist", md.toString())
                .artifact("release-notes", notes)
                .decision(open == 0 ? "Recommend GO" : "Recommend NO-GO", open + " checklist items open");
        if (!ctx.contentOf("open-questions").strip().equals("none") && ctx.has("open-questions")) {
            result.requestApproval("release rests on unconfirmed assumptions");
        }
        return result;
    }

    private static String firstLine(String text, String prefix) {
        return text.lines().filter(l -> l.startsWith(prefix)).findFirst().orElse("not available");
    }
}
