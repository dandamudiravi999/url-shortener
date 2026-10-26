package com.urlshortener.orchestrator.core;

import com.urlshortener.orchestrator.agent.Agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Static definition of one step in the plan. Runtime state lives in the engine, not here. */
public final class WorkflowNode {

    private final String id;
    private final StageType stage;
    private final Agent agent;
    private final Agent fallback;
    private final Set<String> dependsOn;
    private final List<Gate> entryGates;
    private final List<Gate> exitGates;
    private final RetryPolicy retryPolicy;
    private final Impact impact;
    private final boolean approvalRequired;
    private final Compensation compensation;
    private final String description;

    private WorkflowNode(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.stage = Objects.requireNonNull(b.stage, "stage");
        this.agent = Objects.requireNonNull(b.agent, "agent");
        this.fallback = b.fallback;
        this.dependsOn = Set.copyOf(b.dependsOn);
        this.entryGates = List.copyOf(b.entryGates);
        this.exitGates = List.copyOf(b.exitGates);
        this.retryPolicy = b.retryPolicy;
        this.impact = b.impact;
        this.approvalRequired = b.approvalRequired || b.impact == Impact.HIGH;
        this.compensation = b.compensation;
        this.description = b.description == null ? stage.name().toLowerCase().replace('_', ' ') : b.description;
    }

    public static Builder builder(String id, StageType stage, Agent agent) {
        return new Builder(id, stage, agent);
    }

    /** Copy with one extra dependency; used when the plan is changed at run time. */
    public WorkflowNode withDependency(String upstreamId) {
        Builder b = toBuilder();
        b.dependsOn.add(upstreamId);
        return b.build();
    }

    private Builder toBuilder() {
        Builder b = new Builder(id, stage, agent);
        b.fallback = fallback;
        b.dependsOn.addAll(dependsOn);
        b.entryGates.addAll(entryGates);
        b.exitGates.addAll(exitGates);
        b.retryPolicy = retryPolicy;
        b.impact = impact;
        b.approvalRequired = approvalRequired;
        b.compensation = compensation;
        b.description = description;
        return b;
    }

    public String id() { return id; }
    public StageType stage() { return stage; }
    public Agent agent() { return agent; }
    public Optional<Agent> fallback() { return Optional.ofNullable(fallback); }
    public Set<String> dependsOn() { return dependsOn; }
    public List<Gate> entryGates() { return entryGates; }
    public List<Gate> exitGates() { return exitGates; }
    public RetryPolicy retryPolicy() { return retryPolicy; }
    public Impact impact() { return impact; }
    public boolean approvalRequired() { return approvalRequired; }
    public Optional<Compensation> compensation() { return Optional.ofNullable(compensation); }
    public String description() { return description; }

    @Override
    public String toString() {
        return id + "(" + stage + ")";
    }

    public static final class Builder {
        private final String id;
        private final StageType stage;
        private final Agent agent;
        private Agent fallback;
        private final Set<String> dependsOn = new LinkedHashSet<>();
        private final List<Gate> entryGates = new ArrayList<>();
        private final List<Gate> exitGates = new ArrayList<>();
        private RetryPolicy retryPolicy = RetryPolicy.standard();
        private Impact impact = Impact.LOW;
        private boolean approvalRequired;
        private Compensation compensation;
        private String description;

        private Builder(String id, StageType stage, Agent agent) {
            this.id = id;
            this.stage = stage;
            this.agent = agent;
        }

        public Builder dependsOn(String... ids) { dependsOn.addAll(List.of(ids)); return this; }
        public Builder fallback(Agent agent) { this.fallback = agent; return this; }
        public Builder entryGate(Gate gate) { entryGates.add(gate); return this; }
        public Builder exitGate(Gate gate) { exitGates.add(gate); return this; }
        public Builder retry(RetryPolicy policy) { this.retryPolicy = policy; return this; }
        public Builder impact(Impact impact) { this.impact = impact; return this; }
        public Builder requiresApproval() { this.approvalRequired = true; return this; }
        public Builder compensation(Compensation c) { this.compensation = c; return this; }
        public Builder description(String d) { this.description = d; return this; }

        public WorkflowNode build() {
            return new WorkflowNode(this);
        }
    }
}
