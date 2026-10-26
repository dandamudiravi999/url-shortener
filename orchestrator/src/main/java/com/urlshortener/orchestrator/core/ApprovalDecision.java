package com.urlshortener.orchestrator.core;

import java.util.Map;

public record ApprovalDecision(boolean approved, String approver, String comment, Map<String, String> answers) {

    public static ApprovalDecision approve(String approver, String comment) {
        return new ApprovalDecision(true, approver, comment, Map.of());
    }

    public static ApprovalDecision reject(String approver, String comment) {
        return new ApprovalDecision(false, approver, comment, Map.of());
    }

    public static ApprovalDecision answered(String approver, Map<String, String> answers) {
        return new ApprovalDecision(true, approver, "clarifications provided", Map.copyOf(answers));
    }
}
