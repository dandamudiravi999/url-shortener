package com.urlshortener.orchestrator.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reliability metrics for one run. MTTR here is "time from a node's first failed attempt to
 * its eventual success" (recovery by retry or fallback), averaged over recovered nodes.
 */
public final class RunMetrics {

    private Instant startedAt;
    private Instant finishedAt;
    private int nodesSucceeded;
    private int nodesFailed;
    private int nodesSkipped;
    private int nodesRolledBack;
    private int attempts;
    private int retries;
    private int fallbacks;
    private int rollbacks;
    private int replans;
    private int planExtensions;
    private int approvalsRequested;
    private int approvalsRejected;
    private int policyDenials;
    private final List<Duration> recoveries = new ArrayList<>();
    private final Map<String, Duration> stageLatency = new ConcurrentHashMap<>();

    public synchronized void runStarted(Instant at) { startedAt = at; }
    public synchronized void runFinished(Instant at) { finishedAt = at; }
    public synchronized void attempt() { attempts++; }
    public synchronized void retry() { retries++; }
    public synchronized void fallback() { fallbacks++; }
    public synchronized void replan() { replans++; }
    public synchronized void planExtended() { planExtensions++; }
    public synchronized void approvalRequested() { approvalsRequested++; }
    public synchronized void approvalRejected() { approvalsRejected++; }
    public synchronized void policyDenied() { policyDenials++; }
    public synchronized void nodeSucceeded(String nodeId, Duration latency) {
        nodesSucceeded++;
        stageLatency.merge(nodeId, latency, Duration::plus);
    }
    public synchronized void nodeFailed() { nodesFailed++; }
    public synchronized void nodeSkipped() { nodesSkipped++; }
    public synchronized void nodeRolledBack() { nodesRolledBack++; rollbacks++; }
    public synchronized void recovered(Duration timeToRecover) { recoveries.add(timeToRecover); }
    /** A re-run node is counted again on success; undo the earlier count so totals stay per-node. */
    public synchronized void nodeInvalidated() { nodesSucceeded = Math.max(0, nodesSucceeded - 1); }

    public synchronized int attempts() { return attempts; }
    public synchronized int retries() { return retries; }

    public synchronized Snapshot snapshot() {
        int finished = nodesSucceeded + nodesFailed;
        double successRate = finished == 0 ? 0.0 : (double) nodesSucceeded / finished;
        Long mttr = recoveries.isEmpty() ? null
                : (long) recoveries.stream().mapToLong(Duration::toMillis).average().orElse(0);
        long e2e = startedAt == null || finishedAt == null ? 0 : Duration.between(startedAt, finishedAt).toMillis();
        Map<String, Long> latency = new java.util.TreeMap<>();
        stageLatency.forEach((k, v) -> latency.put(k, v.toMillis()));
        return new Snapshot(successRate, nodesSucceeded, nodesFailed, nodesSkipped, nodesRolledBack,
                attempts, retries, fallbacks, rollbacks, replans, planExtensions,
                approvalsRequested, approvalsRejected, policyDenials, recoveries.size(), mttr, e2e, latency);
    }

    public record Snapshot(double successRate, int nodesSucceeded, int nodesFailed, int nodesSkipped,
                           int nodesRolledBack, int attempts, int retries, int fallbacks, int rollbacks,
                           int replans, int planExtensions, int approvalsRequested, int approvalsRejected,
                           int policyDenials, int recoveredNodes, Long mttrMillis, long endToEndMillis,
                           Map<String, Long> nodeLatencyMillis) {

        public double retryRate() {
            return attempts == 0 ? 0 : (double) retries / attempts;
        }
    }
}
