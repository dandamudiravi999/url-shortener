package com.urlshortener.orchestrator.core;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cross-stage memory of a run: the original requirement, the latest version of every artifact,
 * full artifact history, decision lineage and free-form facts (e.g. clarification answers).
 * Agents read it; only the engine writes artifacts, and only after gates and policy pass.
 */
public final class WorkflowContext implements ArtifactLookup {

    public enum PublishOutcome { NEW, UNCHANGED, CHANGED }

    private final String runId;
    private final String requirement;
    private final Clock clock;
    private final Map<String, Artifact> latest = new ConcurrentHashMap<>();
    private final List<Artifact> history = new CopyOnWriteArrayList<>();
    private final List<DecisionRecord> decisions = new CopyOnWriteArrayList<>();
    private final Map<String, String> facts = new ConcurrentHashMap<>();
    private volatile int planRevision = 1;

    public WorkflowContext(String runId, String requirement, Clock clock) {
        this.runId = runId;
        this.requirement = requirement;
        this.clock = clock;
    }

    public String runId() { return runId; }
    public String requirement() { return requirement; }
    public Clock clock() { return clock; }
    public int planRevision() { return planRevision; }

    void bumpPlanRevision() {
        planRevision++;
    }

    @Override
    public Optional<Artifact> find(String name) {
        return Optional.ofNullable(latest.get(name));
    }

    /** Publishes new content under a name. Identical content is a no-op (no version bump, no re-plan). */
    PublishOutcome publish(String name, String content, String producedBy) {
        Artifact current = latest.get(name);
        if (current != null && current.sha256().equals(Artifact.sha256(content))) {
            return PublishOutcome.UNCHANGED;
        }
        int version = current == null ? 1 : current.version() + 1;
        Artifact artifact = Artifact.of(name, content, producedBy, version, clock.instant());
        latest.put(name, artifact);
        history.add(artifact);
        return current == null ? PublishOutcome.NEW : PublishOutcome.CHANGED;
    }

    /** Snapshot of artifact name -> version currently visible; recorded as a node's inputs. */
    public Map<String, Integer> versions() {
        Map<String, Integer> v = new java.util.TreeMap<>();
        latest.forEach((k, a) -> v.put(k, a.version()));
        return v;
    }

    public List<Artifact> latestArtifacts() {
        List<Artifact> list = new ArrayList<>(latest.values());
        list.sort(java.util.Comparator.comparing(Artifact::name));
        return list;
    }

    public List<Artifact> history() {
        return Collections.unmodifiableList(history);
    }

    void addDecision(DecisionRecord record) {
        decisions.add(record);
    }

    public List<DecisionRecord> decisions() {
        return Collections.unmodifiableList(decisions);
    }

    public void putFact(String key, String value) {
        facts.put(key, value);
    }

    public Optional<String> fact(String key) {
        return Optional.ofNullable(facts.get(key));
    }

    public Map<String, String> facts() {
        return Map.copyOf(facts);
    }

    /**
     * Immutable-by-convention copy handed to an agent for one execution. Agents read a consistent
     * snapshot, so the versions recorded as "consumed" are exactly the versions the agent saw, even if
     * a parallel node commits newer ones meanwhile.
     */
    WorkflowContext snapshot() {
        WorkflowContext copy = new WorkflowContext(runId, requirement, clock);
        copy.latest.putAll(latest);
        copy.facts.putAll(facts);
        copy.decisions.addAll(decisions);
        copy.planRevision = planRevision;
        return copy;
    }
}
