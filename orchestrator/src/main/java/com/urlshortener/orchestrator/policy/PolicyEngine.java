package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.core.ArtifactLookup;
import com.urlshortener.orchestrator.core.WorkflowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs every rule; a rule that throws counts as DENY (fail closed). */
public final class PolicyEngine {

    private final List<PolicyRule> rules;

    public PolicyEngine(List<PolicyRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static PolicyEngine defaults() {
        return new PolicyEngine(List.of(
                new SecretLeakRule(),
                new ChangeControlRule(),
                new PersonalDataRule(),
                new ReleaseComplianceRule()));
    }

    public List<PolicyRule> rules() {
        return rules;
    }

    public PolicyEvaluation evaluate(WorkflowNode node, Map<String, String> proposed, ArtifactLookup view) {
        List<PolicyFinding> findings = new ArrayList<>();
        for (PolicyRule rule : rules) {
            try {
                findings.addAll(rule.evaluate(node, proposed, view));
            } catch (RuntimeException e) {
                findings.add(new PolicyFinding(rule.id(), rule.category(), Verdict.DENY, null,
                        "policy rule failed to evaluate (fail closed): " + e.getMessage()));
            }
        }
        return new PolicyEvaluation(List.copyOf(findings));
    }
}
