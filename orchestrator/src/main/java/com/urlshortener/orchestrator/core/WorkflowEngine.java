package com.urlshortener.orchestrator.core;

import com.urlshortener.orchestrator.agent.Agent;
import com.urlshortener.orchestrator.agent.AgentResult;
import com.urlshortener.orchestrator.agent.AgentTask;
import com.urlshortener.orchestrator.agent.NonRetryableException;
import com.urlshortener.orchestrator.agent.PlanChange;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLog;
import com.urlshortener.orchestrator.metrics.RunMetrics;
import com.urlshortener.orchestrator.policy.PolicyEngine;
import com.urlshortener.orchestrator.policy.PolicyEvaluation;
import com.urlshortener.orchestrator.policy.PolicyFinding;
import com.urlshortener.orchestrator.policy.Verdict;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes a {@link WorkflowGraph} with governance.
 *
 * <p><b>Threading model.</b> A single coordinator thread owns all run state (node states, graph,
 * commits to the context). Workers only execute agents against a read view and return a
 * {@link NodeOutcome}; their outputs become visible to other nodes only when the coordinator commits
 * them. That makes parallel branches safe without locks around business state, and makes "gates and
 * policy before commit" impossible to bypass.
 *
 * <p><b>Per node:</b> entry gates, then the agent with bounded retries, then exit gates and policy on
 * the staged outputs, then an optional fallback agent, then a human approval when the node is
 * high-impact, policy demands it, or the agent escalates. Only then are outputs committed.
 *
 * <p><b>Per run:</b> parallel scheduling of ready nodes (all dependencies succeeded), dynamic plan
 * extension requested by agents, re-planning when a committed artifact changes under nodes that already
 * consumed an older version, fail-fast with downstream skipping, reverse-order compensation on failure,
 * and safe-stop on kill switch, deadline, attempt budget or re-plan limit.
 */
public final class WorkflowEngine {

    private final String scenario;
    private final WorkflowGraph graph;
    private final WorkflowContext ctx;
    private final PolicyEngine policy;
    private final ApprovalGateway human;
    private final AuditLog audit;
    private final RunMetrics metrics = new RunMetrics();
    private final EngineConfig config;
    private final Clock clock;

    private final Map<String, NodeState> states = new LinkedHashMap<>();
    private final Map<String, NodeRecord> records = new ConcurrentHashMap<>();
    private final List<String> completionOrder = new ArrayList<>();
    private final Set<String> staleWhileRunning = new HashSet<>();
    private final List<PolicyFinding> policyFindings = Collections.synchronizedList(new ArrayList<>());
    private final List<ApprovalRecord> approvals = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicInteger totalAttempts = new AtomicInteger();
    private final AtomicInteger decisionSeq = new AtomicInteger();
    private volatile String stopReason;
    private String failureReason;
    private int replans;
    private Instant deadlineAt;

    public WorkflowEngine(String scenario, WorkflowGraph graph, WorkflowContext ctx, PolicyEngine policy,
                          ApprovalGateway human, AuditLog audit, EngineConfig config) {
        this.scenario = scenario;
        this.graph = graph;
        this.ctx = ctx;
        this.policy = policy;
        this.human = human;
        this.audit = audit;
        this.config = config;
        this.clock = ctx.clock();
        graph.topologicalOrder(); // validates the DAG before anything runs
        graph.ids().forEach(id -> {
            states.put(id, NodeState.PENDING);
            records.put(id, new NodeRecord());
        });
    }

    /** Operator-initiated safe stop; in-flight attempts finish, nothing new starts. */
    public void requestStop(String reason) {
        if (stopRequested.compareAndSet(false, true)) {
            stopReason = reason;
        }
    }

    public RunReport run() {
        Instant started = clock.instant();
        deadlineAt = started.plus(config.deadline());
        metrics.runStarted(started);
        audit.record(AuditEventType.RUN_STARTED, null, "run started for scenario " + scenario,
                Map.of("requirement", ctx.requirement(), "maxParallelism", config.maxParallelism(),
                        "maxTotalAttempts", config.maxTotalAttempts(), "deadline", config.deadline().toString()));
        audit.record(AuditEventType.PLAN_CREATED, null, "initial plan", Map.of("levels", graph.parallelLevels()));

        ExecutorService pool = Executors.newFixedThreadPool(config.maxParallelism(), r -> {
            Thread t = new Thread(r, "agent-worker");
            t.setDaemon(true);
            return t;
        });
        CompletionService<NodeOutcome> completion = new ExecutorCompletionService<>(pool);
        int inFlight = 0;
        boolean failed = false;
        try {
            while (true) {
                checkStopConditions();
                if (!stopRequested.get() && !failed) {
                    inFlight += scheduleReady(completion, inFlight);
                }
                if (inFlight == 0) {
                    break;
                }
                Future<NodeOutcome> done = completion.poll(100, TimeUnit.MILLISECONDS);
                if (done == null) {
                    continue;
                }
                inFlight--;
                NodeOutcome outcome = get(done);
                failed |= handle(outcome);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requestStop("coordinator interrupted");
        } finally {
            pool.shutdownNow();
        }

        if (failed && config.rollbackOnFailure()) {
            rollback();
        }
        if (stopRequested.get()) {
            List<String> cancelled = cancelPending();
            audit.record(AuditEventType.SAFE_STOP, null, "safe stop: " + stopReason, Map.of("cancelled", cancelled));
        } else {
            cancelPending();
        }
        RunStatus status = failed ? RunStatus.FAILED : stopRequested.get() ? RunStatus.STOPPED : RunStatus.SUCCEEDED;
        metrics.runFinished(clock.instant());
        audit.record(AuditEventType.RUN_FINISHED, null, "run finished: " + status,
                Map.of("status", status.name(), "planRevision", ctx.planRevision()));
        return buildReport(status);
    }

    // ---------------------------------------------------------------- coordinator

    private int scheduleReady(CompletionService<NodeOutcome> completion, int inFlight) {
        int submitted = 0;
        for (String id : graph.topologicalOrder()) {
            if (inFlight + submitted >= config.maxParallelism()) {
                break;
            }
            if (states.get(id) != NodeState.PENDING) {
                continue;
            }
            WorkflowNode node = graph.require(id);
            boolean ready = node.dependsOn().stream().allMatch(d -> states.get(d) == NodeState.SUCCEEDED);
            if (ready) {
                states.put(id, NodeState.RUNNING);
                WorkflowContext view = ctx.snapshot();
                Map<String, Integer> consumed = view.versions();
                records.get(id).inFlightConsumed = consumed;
                completion.submit(() -> execute(node, view, consumed));
                submitted++;
            }
        }
        return submitted;
    }

    /** Applies a finished node's outcome. Returns true when the run must fail. */
    private boolean handle(NodeOutcome o) {
        NodeRecord rec = records.get(o.nodeId());
        rec.attempts += o.attempts();
        rec.executions++;
        rec.agent = o.agent();
        rec.message = o.message();
        rec.consumed = o.consumed();
        rec.durationMillis += Duration.between(o.startedAt(), o.finishedAt()).toMillis();

        switch (o.kind()) {
            case SUCCEEDED -> {
                if (staleWhileRunning.remove(o.nodeId())) {
                    states.put(o.nodeId(), NodeState.PENDING);
                    audit.record(AuditEventType.REPLANNED, o.nodeId(),
                            "inputs changed while running; outputs discarded and node re-queued");
                    return false;
                }
                commit(o);
                return false;
            }
            case STOPPED -> {
                states.put(o.nodeId(), NodeState.CANCELLED);
                return false;
            }
            default -> {
                states.put(o.nodeId(), o.kind() == NodeOutcome.Kind.REJECTED ? NodeState.REJECTED : NodeState.FAILED);
                metrics.nodeFailed();
                failureReason = o.nodeId() + ": " + o.message();
                audit.record(AuditEventType.NODE_FAILED, o.nodeId(), o.message(),
                        Map.of("kind", o.kind().name(), "attempts", o.attempts()));
                for (String d : graph.downstreamOf(o.nodeId())) {
                    if (states.get(d) == NodeState.PENDING) {
                        states.put(d, NodeState.SKIPPED);
                        metrics.nodeSkipped();
                        audit.record(AuditEventType.NODE_SKIPPED, d, "upstream " + o.nodeId() + " did not succeed");
                    }
                }
                return true;
            }
        }
    }

    private void commit(NodeOutcome o) {
        String id = o.nodeId();
        AgentResult result = o.result();
        List<String> changed = new ArrayList<>();
        result.artifacts().forEach((name, content) -> {
            WorkflowContext.PublishOutcome po = ctx.publish(name, content, id);
            Artifact a = ctx.find(name).orElseThrow();
            audit.record(AuditEventType.ARTIFACT_PUBLISHED, id, a.ref() + " " + po,
                    Map.of("artifact", name, "version", a.version(), "sha256", a.sha256(), "outcome", po.name()));
            if (po == WorkflowContext.PublishOutcome.CHANGED) {
                changed.add(name);
            }
        });
        List<String> basedOn = o.consumed().entrySet().stream().map(e -> e.getKey() + "@v" + e.getValue()).toList();
        for (AgentResult.Decision d : result.decisions()) {
            DecisionRecord rec = new DecisionRecord("D" + decisionSeq.incrementAndGet(), id, d.summary(), d.rationale(),
                    basedOn, ctx.planRevision(), clock.instant());
            ctx.addDecision(rec);
            audit.record(AuditEventType.DECISION_RECORDED, id, d.summary(),
                    Map.of("decisionId", rec.id(), "rationale", d.rationale(), "basedOn", basedOn));
        }
        states.put(id, NodeState.SUCCEEDED);
        completionOrder.remove(id);
        completionOrder.add(id);
        metrics.nodeSucceeded(id, Duration.between(o.startedAt(), o.finishedAt()));
        if (o.firstFailureAt() != null) {
            metrics.recovered(Duration.between(o.firstFailureAt(), o.finishedAt()));
        }
        audit.record(AuditEventType.NODE_SUCCEEDED, id, o.message(),
                Map.of("agent", o.agent(), "attempts", o.attempts()));

        for (PlanChange change : result.planChanges()) {
            applyPlanChange(id, change);
        }
        if (!changed.isEmpty()) {
            invalidateConsumers(changed, id);
        }
    }

    /** Agents may only add work (extra checks), never remove nodes or gates: an autonomy boundary. */
    private void applyPlanChange(String requester, PlanChange change) {
        WorkflowNode node = change.node();
        if (graph.contains(node.id())) {
            return;
        }
        try {
            graph.add(node);
        } catch (IllegalArgumentException e) {
            audit.record(AuditEventType.PLAN_EXTENDED, requester, "plan change rejected: " + e.getMessage());
            return;
        }
        states.put(node.id(), NodeState.PENDING);
        records.put(node.id(), new NodeRecord());
        List<String> gated = new ArrayList<>();
        List<String> notGated = new ArrayList<>();
        for (String gate : change.gates()) {
            if (states.get(gate) == NodeState.PENDING) {
                graph.addDependency(gate, node.id());
                gated.add(gate);
            } else {
                notGated.add(gate);
            }
        }
        ctx.bumpPlanRevision();
        metrics.planExtended();
        audit.record(AuditEventType.PLAN_EXTENDED, requester, "added " + node.id() + ": " + change.reason(),
                Map.of("node", node.id(), "dependsOn", List.copyOf(node.dependsOn()), "gates", gated,
                        "alreadyStarted", notGated, "planRevision", ctx.planRevision(),
                        "levels", graph.parallelLevels()));
    }

    /**
     * Dynamic re-plan: an artifact changed, so every node that consumed an older version (and everything
     * downstream of it) is stale. Completed ones go back to PENDING; running ones are re-queued when they finish.
     */
    private void invalidateConsumers(List<String> changed, String producer) {
        Map<String, Integer> current = new LinkedHashMap<>();
        changed.forEach(name -> current.put(name, ctx.find(name).map(Artifact::version).orElse(0)));
        Set<String> affected = new LinkedHashSet<>();
        for (String id : graph.ids()) {
            NodeState s = states.get(id);
            if (id.equals(producer) || (s != NodeState.SUCCEEDED && s != NodeState.RUNNING)) {
                continue;
            }
            NodeRecord rec = records.get(id);
            Map<String, Integer> seenVersions = s == NodeState.RUNNING ? rec.inFlightConsumed : rec.consumed;
            boolean stale = current.entrySet().stream().anyMatch(e -> {
                Integer seen = seenVersions.get(e.getKey());
                return seen != null && seen < e.getValue();
            });
            if (stale) {
                affected.add(id);
            }
        }
        if (affected.isEmpty()) {
            return;
        }
        Set<String> reset = new LinkedHashSet<>(affected);
        affected.forEach(a -> reset.addAll(graph.downstreamOf(a)));
        reset.remove(producer);
        List<String> requeued = new ArrayList<>();
        for (String id : reset) {
            NodeState s = states.get(id);
            if (s == NodeState.SUCCEEDED) {
                states.put(id, NodeState.PENDING);
                completionOrder.remove(id);
                metrics.nodeInvalidated();
                requeued.add(id);
            } else if (s == NodeState.RUNNING) {
                staleWhileRunning.add(id);
                requeued.add(id + " (running)");
            }
        }
        replans++;
        ctx.bumpPlanRevision();
        metrics.replan();
        audit.record(AuditEventType.REPLANNED, producer,
                "upstream change " + current + "; re-running stale downstream work",
                Map.of("changed", current, "staleConsumers", List.copyOf(affected),
                        "requeued", requeued, "planRevision", ctx.planRevision()));
        if (replans > config.maxReplans()) {
            requestStop("re-plan limit (" + config.maxReplans() + ") exceeded; plan is oscillating");
        }
    }

    private void rollback() {
        List<String> order = new ArrayList<>(completionOrder);
        Collections.reverse(order);
        audit.record(AuditEventType.ROLLBACK_STARTED, null, "compensating completed nodes in reverse order",
                Map.of("order", order, "cause", String.valueOf(failureReason)));
        for (String id : order) {
            Optional<Compensation> compensation = graph.require(id).compensation();
            if (compensation.isEmpty()) {
                continue;
            }
            try {
                String msg = compensation.get().rollback(ctx);
                states.put(id, NodeState.ROLLED_BACK);
                metrics.nodeRolledBack();
                audit.record(AuditEventType.NODE_ROLLED_BACK, id, msg);
            } catch (Exception e) {
                audit.record(AuditEventType.ROLLBACK_FAILED, id,
                        "compensation failed, manual intervention required: " + e.getMessage());
            }
        }
    }

    private List<String> cancelPending() {
        List<String> cancelled = new ArrayList<>();
        states.forEach((id, s) -> {
            if (s == NodeState.PENDING) {
                cancelled.add(id);
            }
        });
        cancelled.forEach(id -> states.put(id, NodeState.CANCELLED));
        return cancelled;
    }

    private void checkStopConditions() {
        if (config.killSwitch() != null && Files.exists(config.killSwitch())) {
            requestStop("kill switch present at " + config.killSwitch());
        } else if (clock.instant().isAfter(deadlineAt)) {
            requestStop("run deadline of " + config.deadline() + " exceeded");
        }
    }

    private NodeOutcome get(Future<NodeOutcome> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            // execute() catches everything; reaching here is an engine bug, surface it loudly.
            throw new IllegalStateException("worker crashed", e.getCause());
        }
    }

    // ---------------------------------------------------------------- worker side

    private NodeOutcome execute(WorkflowNode node, WorkflowContext view, Map<String, Integer> consumed) {
        Instant started = clock.instant();
        audit.record(AuditEventType.NODE_STARTED, node.id(), node.description(),
                Map.of("stage", node.stage().name(), "consumed", consumed, "planRevision", ctx.planRevision()));
        try {
            for (Gate gate : node.entryGates()) {
                GateResult r = gate.check(view);
                if (!r.passed()) {
                    audit.record(AuditEventType.ENTRY_GATE_FAILED, node.id(), r.reason(), Map.of("gate", r.gate()));
                    return NodeOutcome.failed(node.id(), "entry gate failed: " + r.reason(), node.agent().name(),
                            0, consumed, started, clock.instant(), null);
                }
            }

            Attempts primary = runWithRetries(node, view, node.agent(), node.retryPolicy());
            Attempts chosen = primary;
            if (!primary.success && !primary.stopped && node.fallback().isPresent()) {
                Agent fb = node.fallback().get();
                metrics.fallback();
                audit.record(AuditEventType.FALLBACK_STARTED, node.id(),
                        "primary agent failed (" + primary.message + "); trying fallback " + fb.name());
                Attempts fallback = runWithRetries(node, view, fb, RetryPolicy.none());
                fallback.attempts += primary.attempts;
                fallback.firstFailureAt = primary.firstFailureAt;
                chosen = fallback;
            }
            Instant finished = clock.instant();
            if (chosen.stopped) {
                return new NodeOutcome(node.id(), NodeOutcome.Kind.STOPPED, null, chosen.agent, chosen.attempts,
                        "stopped: " + stopReason, consumed, started, finished, chosen.firstFailureAt);
            }
            if (!chosen.success) {
                return NodeOutcome.failed(node.id(), chosen.message, chosen.agent, chosen.attempts, consumed,
                        started, finished, chosen.firstFailureAt);
            }

            List<String> reasons = new ArrayList<>();
            if (node.approvalRequired()) {
                reasons.add("node is " + node.impact() + " impact and requires sign-off");
            }
            chosen.evaluation.withVerdict(Verdict.REQUIRE_APPROVAL)
                    .forEach(f -> reasons.add("[" + f.ruleId() + "] " + f.message()));
            reasons.addAll(chosen.result.approvalReasons());
            if (!reasons.isEmpty()) {
                ApprovalDecision decision = requestApproval(node, chosen, reasons);
                if (!decision.approved()) {
                    return new NodeOutcome(node.id(), NodeOutcome.Kind.REJECTED, chosen.result, chosen.agent,
                            chosen.attempts, "rejected by " + decision.approver() + ": " + decision.comment(),
                            consumed, started, clock.instant(), chosen.firstFailureAt);
                }
            }
            return new NodeOutcome(node.id(), NodeOutcome.Kind.SUCCEEDED, chosen.result, chosen.agent,
                    chosen.attempts, chosen.result.summary(), consumed, started, clock.instant(), chosen.firstFailureAt);
        } catch (RuntimeException e) {
            return NodeOutcome.failed(node.id(), "engine error: " + e, node.agent().name(), 0, consumed,
                    started, clock.instant(), null);
        }
    }

    private Attempts runWithRetries(WorkflowNode node, WorkflowContext view, Agent agent, RetryPolicy retry) {
        Attempts a = new Attempts(agent.name());
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            if (stopRequested.get()) {
                a.stopped = true;
                return a;
            }
            if (totalAttempts.incrementAndGet() > config.maxTotalAttempts()) {
                requestStop("attempt budget of " + config.maxTotalAttempts() + " exhausted");
                a.stopped = true;
                return a;
            }
            a.attempts++;
            metrics.attempt();
            audit.record(AuditEventType.ATTEMPT_STARTED, node.id(), agent.name() + " attempt " + attempt,
                    Map.of("agent", agent.name(), "attempt", attempt));
            String failure;
            boolean retryable;
            try {
                AgentResult result = agent.execute(new AgentTask(ctx.runId(), node.id(), node.stage(), attempt, view,
                        auditedHuman(node)));
                if (!result.success()) {
                    failure = result.summary();
                    retryable = result.retryable();
                } else {
                    ArtifactLookup staged = name -> result.artifacts().containsKey(name)
                            ? Optional.of(Artifact.of(name, result.artifacts().get(name), node.id(), 0, clock.instant()))
                            : view.find(name);
                    List<GateResult> failedGates = node.exitGates().stream().map(g -> g.check(staged))
                            .filter(r -> !r.passed()).toList();
                    if (!failedGates.isEmpty()) {
                        failure = "exit gate failed: " + failedGates.stream().map(GateResult::reason).toList();
                        retryable = true;
                        audit.record(AuditEventType.EXIT_GATE_FAILED, node.id(), failure);
                    } else {
                        PolicyEvaluation eval = policy.evaluate(node, result.artifacts(), staged);
                        policyFindings.addAll(eval.findings());
                        audit.record(AuditEventType.POLICY_EVALUATED, node.id(), "verdict " + eval.verdict(),
                                Map.of("verdict", eval.verdict().name(), "findings",
                                        eval.findings().stream().map(f -> f.verdict() + " " + f.ruleId() + ": " + f.message()).toList()));
                        if (eval.verdict() == Verdict.DENY) {
                            metrics.policyDenied();
                            failure = "policy denied: " + eval.withVerdict(Verdict.DENY).stream().map(PolicyFinding::message).toList();
                            retryable = false;
                            audit.record(AuditEventType.POLICY_DENIED, node.id(), failure);
                        } else {
                            a.success = true;
                            a.result = result;
                            a.evaluation = eval;
                            a.message = result.summary();
                            return a;
                        }
                    }
                }
            } catch (NonRetryableException e) {
                failure = e.getMessage();
                retryable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                a.stopped = true;
                return a;
            } catch (Exception e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                retryable = true;
            }
            if (a.firstFailureAt == null) {
                a.firstFailureAt = clock.instant();
            }
            a.message = failure;
            audit.record(AuditEventType.ATTEMPT_FAILED, node.id(), failure,
                    Map.of("agent", agent.name(), "attempt", attempt, "retryable", retryable));
            if (!retryable || attempt == retry.maxAttempts()) {
                return a;
            }
            Duration backoff = retry.backoffAfter(attempt);
            metrics.retry();
            audit.record(AuditEventType.RETRY_SCHEDULED, node.id(), "retrying in " + backoff.toMillis() + "ms",
                    Map.of("nextAttempt", attempt + 1, "backoffMillis", backoff.toMillis()));
            try {
                Thread.sleep(backoff.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                a.stopped = true;
                return a;
            }
        }
        return a;
    }

    private ApprovalDecision requestApproval(WorkflowNode node, Attempts chosen, List<String> reasons) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("summary", chosen.result.summary());
        evidence.put("agent", chosen.agent);
        chosen.result.artifacts().forEach((name, content) ->
                evidence.put("artifact:" + name, Artifact.sha256(content).substring(0, 12)));
        ApprovalRequest request = ApprovalRequest.approval(ctx.runId(), node.id(), "accept outputs of " + node.id(),
                node.impact(), reasons, evidence);
        metrics.approvalRequested();
        audit.record(AuditEventType.APPROVAL_REQUESTED, node.id(), String.join("; ", reasons),
                Map.of("evidence", evidence));
        ApprovalDecision decision = safeDecide(request);
        approvals.add(new ApprovalRecord(node.id(), request.action(), reasons, List.of(), decision.approved(),
                decision.approver(), decision.comment(), decision.answers()));
        if (decision.approved()) {
            audit.record(AuditEventType.APPROVAL_GRANTED, node.id(), decision.comment(),
                    Map.of("approver", decision.approver()));
        } else {
            metrics.approvalRejected();
            audit.record(AuditEventType.APPROVAL_REJECTED, node.id(), decision.comment(),
                    Map.of("approver", decision.approver()));
        }
        return decision;
    }

    /** The human channel handed to agents (e.g. for clarifications), wrapped so every exchange is audited. */
    private ApprovalGateway auditedHuman(WorkflowNode node) {
        return request -> {
            audit.record(AuditEventType.CLARIFICATION_REQUESTED, node.id(), request.action(),
                    Map.of("questions", request.questions()));
            ApprovalDecision d = safeDecide(request);
            approvals.add(new ApprovalRecord(node.id(), request.action(), request.reasons(), request.questions(),
                    d.approved(), d.approver(), d.comment(), d.answers()));
            audit.record(AuditEventType.CLARIFICATION_ANSWERED, node.id(), d.comment(),
                    Map.of("approver", d.approver(), "answers", d.answers()));
            return d;
        };
    }

    /** A gateway that throws or returns null counts as a rejection: no human answer means no. */
    private ApprovalDecision safeDecide(ApprovalRequest request) {
        try {
            ApprovalDecision d = human.decide(request);
            return d != null ? d : ApprovalDecision.reject("system", "no decision returned");
        } catch (RuntimeException e) {
            return ApprovalDecision.reject("system", "approval channel failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- reporting

    private RunReport buildReport(RunStatus status) {
        List<NodeReport> nodes = new ArrayList<>();
        for (String id : graph.topologicalOrder()) {
            WorkflowNode n = graph.require(id);
            NodeRecord r = records.get(id);
            nodes.add(new NodeReport(id, n.stage(), n.description(), List.copyOf(n.dependsOn()), n.impact(),
                    states.get(id), r.attempts, r.executions, r.agent, r.message, r.durationMillis, r.consumed));
        }
        String reason = status == RunStatus.FAILED ? failureReason : status == RunStatus.STOPPED ? stopReason : null;
        return new RunReport(ctx.runId(), scenario, ctx.requirement(), status, reason, graph.parallelLevels(),
                ctx.planRevision(), nodes, ctx.latestArtifacts(), ctx.history(), ctx.decisions(),
                List.copyOf(policyFindings), List.copyOf(approvals), metrics.snapshot(),
                AuditLog.verify(audit.events()) == -1, audit.events().size());
    }

    public static String newRunId(String scenario) {
        return scenario + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- internal types

    private static final class NodeRecord {
        int attempts;
        int executions;
        String agent;
        String message;
        long durationMillis;
        Map<String, Integer> consumed = Map.of();
        volatile Map<String, Integer> inFlightConsumed = Map.of();
    }

    private static final class Attempts {
        final String agent;
        boolean success;
        boolean stopped;
        int attempts;
        String message;
        AgentResult result;
        PolicyEvaluation evaluation;
        Instant firstFailureAt;

        Attempts(String agent) {
            this.agent = agent;
        }
    }

    record NodeOutcome(String nodeId, Kind kind, AgentResult result, String agent, int attempts, String message,
                       Map<String, Integer> consumed, Instant startedAt, Instant finishedAt, Instant firstFailureAt) {

        enum Kind { SUCCEEDED, FAILED, REJECTED, STOPPED }

        static NodeOutcome failed(String id, String message, String agent, int attempts, Map<String, Integer> consumed,
                                  Instant started, Instant finished, Instant firstFailure) {
            return new NodeOutcome(id, Kind.FAILED, null, agent, attempts, message, consumed, started, finished, firstFailure);
        }
    }
}
