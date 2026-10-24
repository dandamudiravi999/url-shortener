package com.urlshortener.orchestrator.core;

import com.urlshortener.orchestrator.metrics.RunMetrics;
import com.urlshortener.orchestrator.policy.PolicyFinding;

import java.util.List;

/** Everything a reviewer needs after a run; serialised to report.json and rendered to SUMMARY.md. */
public record RunReport(String runId, String scenario, String requirement, RunStatus status, String reason,
                        List<List<String>> finalPlan, int planRevision, List<NodeReport> nodes,
                        List<Artifact> artifacts, List<Artifact> artifactHistory, List<DecisionRecord> decisions,
                        List<PolicyFinding> policyFindings, List<ApprovalRecord> approvals,
                        RunMetrics.Snapshot metrics, boolean auditChainIntact, int auditEvents) {

    public NodeReport node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no node " + id));
    }

    public Artifact artifact(String name) {
        return artifacts.stream().filter(a -> a.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no artifact " + name));
    }
}
