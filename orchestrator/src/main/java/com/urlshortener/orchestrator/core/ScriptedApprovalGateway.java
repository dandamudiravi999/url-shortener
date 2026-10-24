package com.urlshortener.orchestrator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-recorded human decisions for demos, CI and tests. It stands in for a person, so it is
 * deliberately strict: any approval it has no script for is rejected (safe default).
 */
public final class ScriptedApprovalGateway implements ApprovalGateway {

    private final String approver;
    private final Map<String, ApprovalDecision> byNode;
    private final Map<String, String> answersByTerm;
    private final List<ApprovalRequest> requests = Collections.synchronizedList(new ArrayList<>());

    private ScriptedApprovalGateway(Builder b) {
        this.approver = b.approver;
        this.byNode = Map.copyOf(b.byNode);
        this.answersByTerm = Map.copyOf(b.answersByTerm);
    }

    public static Builder builder(String approver) {
        return new Builder(approver);
    }

    @Override
    public ApprovalDecision decide(ApprovalRequest request) {
        requests.add(request);
        if (request.isClarification()) {
            Map<String, String> answers = new LinkedHashMap<>();
            for (String q : request.questions()) {
                answersByTerm.forEach((term, answer) -> {
                    if (q.startsWith("[" + term + "]")) {
                        answers.put(q, answer);
                    }
                });
            }
            return answers.isEmpty()
                    ? ApprovalDecision.reject(approver, "no scripted answers")
                    : ApprovalDecision.answered(approver, answers);
        }
        ApprovalDecision d = byNode.get(request.nodeId());
        return d != null ? d : ApprovalDecision.reject(approver, "no scripted decision for " + request.nodeId() + " (safe default: reject)");
    }

    public List<ApprovalRequest> requests() {
        return List.copyOf(requests);
    }

    public static final class Builder {
        private final String approver;
        private final Map<String, ApprovalDecision> byNode = new HashMap<>();
        private final Map<String, String> answersByTerm = new LinkedHashMap<>();

        private Builder(String approver) {
            this.approver = approver;
        }

        public Builder approve(String nodeId, String comment) {
            byNode.put(nodeId, ApprovalDecision.approve(approver, comment));
            return this;
        }

        public Builder reject(String nodeId, String comment) {
            byNode.put(nodeId, ApprovalDecision.reject(approver, comment));
            return this;
        }

        public Builder answer(String term, String answer) {
            answersByTerm.put(term, answer);
            return this;
        }

        public ScriptedApprovalGateway build() {
            return new ScriptedApprovalGateway(this);
        }
    }
}
