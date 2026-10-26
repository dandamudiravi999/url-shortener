package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.core.ArtifactLookup;
import com.urlshortener.orchestrator.core.WorkflowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** SECURITY: no credential-looking strings may enter any artifact (they would end up in code, docs, logs). */
public final class SecretLeakRule implements PolicyRule {

    private static final Map<String, Pattern> PATTERNS = Map.of(
            "AWS access key", Pattern.compile("AKIA[0-9A-Z]{16}"),
            "private key block", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH |)PRIVATE KEY-----"),
            "GitHub token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{36}"),
            "hard-coded credential", Pattern.compile(
                    "(?i)(password|passwd|secret|api[_-]?key|token)\\s*[:=]\\s*['\"][^'\"\\s]{6,}['\"]"));

    @Override
    public String id() {
        return "SEC-001-no-secrets";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.SECURITY;
    }

    @Override
    public List<PolicyFinding> evaluate(WorkflowNode node, Map<String, String> proposed, ArtifactLookup view) {
        List<PolicyFinding> findings = new ArrayList<>();
        proposed.forEach((name, content) -> PATTERNS.forEach((label, pattern) -> {
            if (pattern.matcher(content).find()) {
                findings.add(new PolicyFinding(id(), category(), Verdict.DENY, name,
                        label + " detected; secrets must come from the environment or a vault"));
            }
        }));
        return findings;
    }
}
