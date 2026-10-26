package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.core.ArtifactLookup;
import com.urlshortener.orchestrator.core.StageType;
import com.urlshortener.orchestrator.core.WorkflowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * COMPLIANCE: release readiness can only be proposed with evidence. Missing or failing evidence
 * is a DENY, not a warning; a release candidate always needs a human sign-off.
 */
public final class ReleaseComplianceRule implements PolicyRule {

    @Override
    public String id() {
        return "CMP-002-release-evidence";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.COMPLIANCE;
    }

    @Override
    public List<PolicyFinding> evaluate(WorkflowNode node, Map<String, String> proposed, ArtifactLookup view) {
        if (node.stage() != StageType.RELEASE_READINESS) {
            return List.of();
        }
        List<PolicyFinding> findings = new ArrayList<>();
        String tests = view.contentOf("test-report");
        if (!tests.contains("status: PASSED")) {
            findings.add(deny("test-report", "release requires a test report with status: PASSED"));
        }
        if (view.has("security-review") && !view.contentOf("security-review").contains("verdict: PASS")) {
            findings.add(deny("security-review", "security review did not pass"));
        }
        if (!view.has("documentation")) {
            findings.add(deny("documentation", "release requires generated documentation"));
        }
        if (!view.has("requirements")) {
            findings.add(deny("requirements", "release requires traceable requirements"));
        }
        findings.add(new PolicyFinding(id(), category(), Verdict.REQUIRE_APPROVAL, "release-checklist",
                "release candidates always need a human sign-off"));
        return findings;
    }

    private PolicyFinding deny(String artifact, String message) {
        return new PolicyFinding(id(), category(), Verdict.DENY, artifact, message);
    }
}
