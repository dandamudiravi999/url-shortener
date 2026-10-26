package com.urlshortener.orchestrator.core;

import java.util.List;
import java.util.Map;

/**
 * What a human is asked to decide. {@code questions} is non-empty for clarification requests,
 * where the human supplies answers rather than (only) a yes/no.
 */
public record ApprovalRequest(String runId, String nodeId, String action, Impact impact,
                              List<String> reasons, Map<String, String> evidence, List<String> questions) {

    public static ApprovalRequest approval(String runId, String nodeId, String action, Impact impact,
                                           List<String> reasons, Map<String, String> evidence) {
        return new ApprovalRequest(runId, nodeId, action, impact, List.copyOf(reasons), Map.copyOf(evidence), List.of());
    }

    public static ApprovalRequest clarification(String runId, String nodeId, List<String> questions) {
        return new ApprovalRequest(runId, nodeId, "clarify requirements", Impact.MEDIUM,
                List.of("requirement contains ambiguous terms"), Map.of(), List.copyOf(questions));
    }

    public boolean isClarification() {
        return !questions.isEmpty();
    }
}
