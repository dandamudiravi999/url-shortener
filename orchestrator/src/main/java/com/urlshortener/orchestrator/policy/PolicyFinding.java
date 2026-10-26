package com.urlshortener.orchestrator.policy;

public record PolicyFinding(String ruleId, PolicyCategory category, Verdict verdict, String artifact, String message) {
}
