package com.urlshortener.orchestrator.policy;

import java.util.Comparator;
import java.util.List;

public record PolicyEvaluation(List<PolicyFinding> findings) {

    public Verdict verdict() {
        return findings.stream().map(PolicyFinding::verdict).max(Comparator.naturalOrder()).orElse(Verdict.ALLOW);
    }

    public List<PolicyFinding> withVerdict(Verdict verdict) {
        return findings.stream().filter(f -> f.verdict() == verdict).toList();
    }
}
