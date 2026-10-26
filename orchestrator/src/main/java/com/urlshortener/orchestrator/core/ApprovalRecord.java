package com.urlshortener.orchestrator.core;

import java.util.List;
import java.util.Map;

public record ApprovalRecord(String nodeId, String action, List<String> reasons, List<String> questions,
                             boolean approved, String approver, String comment, Map<String, String> answers) {
}
