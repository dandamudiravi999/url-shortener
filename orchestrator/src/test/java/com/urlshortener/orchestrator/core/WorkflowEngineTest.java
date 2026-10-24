package com.urlshortener.orchestrator.core;

import com.urlshortener.orchestrator.agent.Agent;
import com.urlshortener.orchestrator.agent.AgentResult;
import com.urlshortener.orchestrator.agent.PlanChange;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLog;
import com.urlshortener.orchestrator.policy.PolicyEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.urlshortener.orchestrator.TestAgents.flaky;
import static com.urlshortener.orchestrator.TestAgents.of;
import static com.urlshortener.orchestrator.TestAgents.producing;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEngineTest {

    private static final RetryPolicy FAST_RETRY = new RetryPolicy(3, Duration.ofMillis(1), 1.0, Duration.ofMillis(1));

    @TempDir
    Path tmp;

    private AuditLog audit;
    private WorkflowContext ctx;

    private RunReport run(WorkflowGraph graph, ApprovalGateway human) {
        return run(graph, human, EngineConfig.defaults().withKillSwitch(tmp.resolve("STOP")));
    }

    private RunReport run(WorkflowGraph graph, ApprovalGateway human, EngineConfig config) {
        ctx = new WorkflowContext("test-run", "requirement", Clock.systemUTC());
        audit = AuditLog.inMemory("test-run", Clock.systemUTC());
        return new WorkflowEngine("test", graph, ctx, PolicyEngine.defaults(), human, audit, config).run();
    }

    private static ApprovalGateway approveAll() {
        return r -> ApprovalDecision.approve("tester", "ok");
    }

    private static WorkflowNode.Builder node(String id, Agent agent, String... deps) {
        return WorkflowNode.builder(id, StageType.IMPLEMENTATION, agent).dependsOn(deps).retry(FAST_RETRY);
    }

    @Test
    void independentBranchesRunInParallelAndJoin() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        Agent waiting = of("branch", t -> {
            bothStarted.countDown();
            try {
                // Only succeeds if the sibling is running at the same time.
                return bothStarted.await(5, TimeUnit.SECONDS)
                        ? AgentResult.success("ok").artifact(t.nodeId(), "done")
                        : AgentResult.failure("sibling never started", false);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        WorkflowGraph g = WorkflowGraph.of(
                node("start", producing("s", "seed", "x")).build(),
                node("left", waiting, "start").build(),
                node("right", waiting, "start").build(),
                node("join", of("j", t -> AgentResult.success("joined " + t.context().has("left") + t.context().has("right"))), "left", "right").build());

        RunReport report = run(g, approveAll());

        assertThat(report.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(report.node("join").message()).isEqualTo("joined truetrue");
        assertThat(report.finalPlan()).containsExactly(List.of("start"), List.of("left", "right"), List.of("join"));
    }

    @Test
    void transientFailuresAreRetriedWithinBoundsAndMttrIsRecorded() {
        AtomicInteger calls = new AtomicInteger();
        RunReport report = run(WorkflowGraph.of(node("a", flaky("flaky", 2, "out", "v", calls)).build()), approveAll());

        assertThat(report.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(calls).hasValue(3);
        assertThat(report.metrics().retries()).isEqualTo(2);
        assertThat(report.metrics().recoveredNodes()).isEqualTo(1);
        assertThat(report.metrics().mttrMillis()).isNotNull();
    }

    @Test
    void exhaustedRetriesFailTheRunSkipDownstreamAndRollBackInReverseOrder() {
        List<String> undone = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        WorkflowGraph g = WorkflowGraph.of(
                node("a", producing("a", "a", "1")).compensation(c -> { undone.add("a"); return "undo a"; }).build(),
                node("b", producing("b", "b", "1"), "a").compensation(c -> { undone.add("b"); return "undo b"; }).build(),
                node("c", flaky("never", 99, "c", "1", calls), "b").build(),
                node("d", producing("d", "d", "1"), "c").build());

        RunReport report = run(g, approveAll());

        assertThat(report.status()).isEqualTo(RunStatus.FAILED);
        assertThat(calls).hasValue(3); // bounded by the retry policy
        assertThat(report.node("c").state()).isEqualTo(NodeState.FAILED);
        assertThat(report.node("d").state()).isEqualTo(NodeState.SKIPPED);
        assertThat(report.node("a").state()).isEqualTo(NodeState.ROLLED_BACK);
        assertThat(undone).containsExactly("b", "a");
    }

    @Test
    void nonRetryableFailureIsNotRetriedAndFallbackTakesOver() {
        AtomicInteger primaryCalls = new AtomicInteger();
        Agent primary = of("primary", t -> {
            primaryCalls.incrementAndGet();
            return AgentResult.failure("bad input", false);
        });
        RunReport report = run(WorkflowGraph.of(
                node("a", primary).fallback(producing("fallback", "out", "safe")).build()), approveAll());

        assertThat(report.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(primaryCalls).hasValue(1);
        assertThat(report.node("a").agent()).isEqualTo("fallback");
        assertThat(report.metrics().fallbacks()).isEqualTo(1);
        assertThat(ctx.contentOf("out")).isEqualTo("safe");
    }

    @Test
    void exitGateFailureIsRetriedAndOutputsAreNeverCommitted() {
        RunReport report = run(WorkflowGraph.of(
                node("a", producing("a", "doc", "missing heading")).exitGate(Gate.contentContains("doc", "## API")).build()),
                approveAll());

        assertThat(report.status()).isEqualTo(RunStatus.FAILED);
        assertThat(report.node("a").attempts()).isEqualTo(3);
        assertThat(ctx.has("doc")).isFalse();
    }

    @Test
    void policyDenyBlocksSecretsFromEnteringTheContext() {
        RunReport report = run(WorkflowGraph.of(
                node("a", producing("a", "config", "password = \"hunter2hunter2\"")).build()), approveAll());

        assertThat(report.status()).isEqualTo(RunStatus.FAILED);
        assertThat(report.node("a").attempts()).isEqualTo(1); // DENY is not retryable
        assertThat(report.metrics().policyDenials()).isEqualTo(1);
        assertThat(ctx.has("config")).isFalse();
    }

    @Test
    void highImpactNodesNeedApprovalAndRejectionFailsTheRun() {
        WorkflowGraph g = WorkflowGraph.of(
                node("build", producing("b", "artifact", "x")).compensation(c -> "undo build").build(),
                node("release", producing("r", "release", "x"), "build").impact(Impact.HIGH).build());

        RunReport report = run(g, r -> r.nodeId().equals("release")
                ? ApprovalDecision.reject("lead", "not this week")
                : ApprovalDecision.approve("lead", "ok"));

        assertThat(report.status()).isEqualTo(RunStatus.FAILED);
        assertThat(report.node("release").state()).isEqualTo(NodeState.REJECTED);
        assertThat(report.node("build").state()).isEqualTo(NodeState.ROLLED_BACK);
        assertThat(report.approvals()).singleElement().satisfies(a -> assertThat(a.approved()).isFalse());
        assertThat(ctx.has("release")).isFalse();
    }

    @Test
    void brokenApprovalChannelMeansReject() {
        RunReport report = run(WorkflowGraph.of(node("a", producing("a", "x", "y")).impact(Impact.HIGH).build()),
                r -> { throw new IllegalStateException("pager down"); });

        assertThat(report.status()).isEqualTo(RunStatus.FAILED);
        assertThat(report.node("a").message()).contains("approval channel failed");
    }

    @Test
    void upstreamChangeRerunsStaleConsumers() {
        // "spec" is consumed by "build" while "revise" (a sibling) later publishes a new spec version.
        CountDownLatch buildDone = new CountDownLatch(1);
        AtomicInteger buildRuns = new AtomicInteger();
        Agent build = of("build", t -> {
            buildRuns.incrementAndGet();
            buildDone.countDown();
            return AgentResult.success("built from " + t.context().contentOf("spec")).artifact("binary", t.context().contentOf("spec"));
        });
        Agent revise = of("revise", t -> {
            try {
                buildDone.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return AgentResult.success("revised").artifact("spec", "spec v2");
        });
        WorkflowGraph g = WorkflowGraph.of(
                node("spec", producing("s", "spec", "spec v1")).build(),
                node("build", build, "spec").build(),
                node("revise", revise, "spec").build(),
                node("ship", of("ship", t -> AgentResult.success("shipped " + t.context().contentOf("binary"))), "build", "revise").build());

        RunReport report = run(g, approveAll());

        assertThat(report.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(buildRuns).hasValue(2);
        assertThat(report.metrics().replans()).isEqualTo(1);
        assertThat(report.node("ship").message()).isEqualTo("shipped spec v2");
        assertThat(audit.count(AuditEventType.REPLANNED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void agentsCanExtendThePlanAndGateDownstreamNodes() {
        WorkflowNode review = node("review", producing("rev", "review", "ok")).build();
        Agent design = of("design", t -> AgentResult.success("designed").artifact("design", "d")
                .planChange(new PlanChange(review, List.of("release"), "needs review")));
        Agent release = of("release", t -> AgentResult.success("review seen: " + t.context().has("review")));
        WorkflowGraph g = WorkflowGraph.of(node("design", design).build(), node("release", release, "design").build());

        RunReport report = run(g, approveAll());

        assertThat(report.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(report.node("release").dependsOn()).contains("review");
        assertThat(report.node("release").message()).isEqualTo("review seen: true");
        assertThat(report.metrics().planExtensions()).isEqualTo(1);
    }

    @Test
    void killSwitchSafeStopsBeforeStartingNewWork() throws Exception {
        Path stop = tmp.resolve("STOP");
        Agent first = of("first", t -> {
            try {
                Files.createFile(stop); // operator pulls the switch while this node runs
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return AgentResult.success("ok").artifact("a", "1");
        });
        WorkflowGraph g = WorkflowGraph.of(node("first", first).build(), node("second", producing("s", "b", "2"), "first").build());

        RunReport report = run(g, approveAll(), EngineConfig.defaults().withKillSwitch(stop));

        assertThat(report.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(report.reason()).contains("kill switch");
        assertThat(report.node("second").state()).isEqualTo(NodeState.CANCELLED);
        assertThat(audit.count(AuditEventType.SAFE_STOP)).isEqualTo(1);
    }

    @Test
    void attemptBudgetSafeStops() {
        AtomicInteger calls = new AtomicInteger();
        RunReport report = run(WorkflowGraph.of(node("a", flaky("f", 99, "x", "y", calls)).build()), approveAll(),
                EngineConfig.defaults().withKillSwitch(tmp.resolve("STOP")).withMaxTotalAttempts(2));

        assertThat(report.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(calls).hasValue(2);
    }

    @Test
    void auditTrailIsHashChainedAndRecordsDecisionLineage() {
        Agent decider = of("d", t -> AgentResult.success("ok").artifact("plan", "p").decision("chose X", "because Y"));
        RunReport report = run(WorkflowGraph.of(node("seed", producing("s", "req", "r")).build(), node("d", decider, "seed").build()), approveAll());

        assertThat(report.auditChainIntact()).isTrue();
        assertThat(report.decisions()).singleElement().satisfies(d -> {
            assertThat(d.summary()).isEqualTo("chose X");
            assertThat(d.basedOn()).containsExactly("req@v1");
        });
        assertThat(AuditLog.verify(audit.events())).isEqualTo(-1);
    }
}
