package com.urlshortener.orchestrator.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Proposed outputs of one attempt. Nothing here is visible to other nodes until the engine commits it. */
public final class AgentResult {

    public record Decision(String summary, String rationale) {
    }

    private final boolean success;
    private final boolean retryable;
    private final String summary;
    private final Map<String, String> artifacts = new LinkedHashMap<>();
    private final List<Decision> decisions = new ArrayList<>();
    private final List<PlanChange> planChanges = new ArrayList<>();
    private final List<String> approvalReasons = new ArrayList<>();

    private AgentResult(boolean success, boolean retryable, String summary) {
        this.success = success;
        this.retryable = retryable;
        this.summary = summary;
    }

    public static AgentResult success(String summary) {
        return new AgentResult(true, false, summary);
    }

    public static AgentResult failure(String reason, boolean retryable) {
        return new AgentResult(false, retryable, reason);
    }

    public AgentResult artifact(String name, String content) {
        artifacts.put(name, content);
        return this;
    }

    public AgentResult decision(String summary, String rationale) {
        decisions.add(new Decision(summary, rationale));
        return this;
    }

    public AgentResult planChange(PlanChange change) {
        planChanges.add(change);
        return this;
    }

    /** Agent-initiated escalation: the agent itself judges that a human should look at this. */
    public AgentResult requestApproval(String reason) {
        approvalReasons.add(reason);
        return this;
    }

    public boolean success() { return success; }
    public boolean retryable() { return retryable; }
    public String summary() { return summary; }
    public Map<String, String> artifacts() { return Map.copyOf(artifacts); }
    public List<Decision> decisions() { return List.copyOf(decisions); }
    public List<PlanChange> planChanges() { return List.copyOf(planChanges); }
    public List<String> approvalReasons() { return List.copyOf(approvalReasons); }
}
