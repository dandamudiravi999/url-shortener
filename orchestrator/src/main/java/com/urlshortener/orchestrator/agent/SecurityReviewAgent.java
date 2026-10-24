package com.urlshortener.orchestrator.agent;

import com.urlshortener.orchestrator.core.Artifact;
import com.urlshortener.orchestrator.policy.SecretLeakRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SECURITY_REVIEW stage: STRIDE-style checklist against the design and change set, plus a secret scan
 * over every artifact produced so far. Missing controls fail the review (verdict: FAIL).
 */
public final class SecurityReviewAgent implements Agent {

    @Override
    public String name() {
        return "security-review-agent";
    }

    @Override
    public AgentResult execute(AgentTask task) {
        String design = task.context().contentOf("design").toLowerCase(Locale.ROOT);
        String requirements = task.context().contentOf("requirements").toLowerCase(Locale.ROOT);
        String all = design + "\n" + requirements;

        Map<String, Boolean> controls = new LinkedHashMap<>();
        controls.put("Tampering/abuse: input validation of target URLs", all.contains("rejected with 400") || all.contains("validation"));
        controls.put("DoS/abuse: rate limiting on link creation", all.contains("token bucket") || all.contains("rate-limit") || all.contains("rate limit"));
        controls.put("Information disclosure: non-enumerable codes", all.contains("non-enumerable"));
        controls.put("Information disclosure: no raw personal data stored", all.contains("no personal data") || all.contains("never persisted"));
        controls.put("Repudiation: errors are explicit (problem+json), audit trail on orchestration", all.contains("problem+json") || all.contains("404"));

        var secretRule = new SecretLeakRule();
        Map<String, String> everything = new LinkedHashMap<>();
        task.context().latestArtifacts().forEach(a -> everything.put(a.name(), a.content()));
        var secretFindings = secretRule.evaluate(null, everything, task.context());

        List<String> missing = new ArrayList<>();
        controls.forEach((k, ok) -> {
            if (!ok) missing.add(k);
        });
        boolean pass = missing.isEmpty() && secretFindings.isEmpty();

        StringBuilder md = new StringBuilder("# Security review\n\n");
        md.append("verdict: ").append(pass ? "PASS" : "FAIL").append("\n\n| Control | Present |\n|---|---|\n");
        controls.forEach((k, ok) -> md.append("| ").append(k).append(" | ").append(ok ? "yes" : "**no**").append(" |\n"));
        md.append("\nSecret scan over ").append(everything.size()).append(" artifacts (")
                .append(task.context().latestArtifacts().stream().map(Artifact::ref).toList()).append("): ")
                .append(secretFindings.isEmpty() ? "clean" : secretFindings.size() + " findings").append("\n");
        if (!pass) {
            md.append("\nMissing: ").append(missing).append("\n");
            return AgentResult.failure("security review failed: missing " + missing + ", secret findings " + secretFindings.size(), false);
        }
        return AgentResult.success("security review passed (" + controls.size() + " controls)")
                .artifact("security-review", md.toString())
                .decision("Security review passed", "All required controls present in the design; no secrets in artifacts");
    }
}
