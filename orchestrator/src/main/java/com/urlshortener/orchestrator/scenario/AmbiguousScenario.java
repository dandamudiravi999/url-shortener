package com.urlshortener.orchestrator.scenario;

import com.urlshortener.orchestrator.agent.ClarificationAgent;
import com.urlshortener.orchestrator.agent.DesignAgent;
import com.urlshortener.orchestrator.agent.DocumentationAgent;
import com.urlshortener.orchestrator.agent.ImpactAnalysisAgent;
import com.urlshortener.orchestrator.agent.ImplementationAgent;
import com.urlshortener.orchestrator.agent.ReleaseReadinessAgent;
import com.urlshortener.orchestrator.agent.RequirementsAgent;
import com.urlshortener.orchestrator.core.Gate;
import com.urlshortener.orchestrator.core.Impact;
import com.urlshortener.orchestrator.core.ScriptedApprovalGateway;
import com.urlshortener.orchestrator.core.StageType;
import com.urlshortener.orchestrator.core.WorkflowGraph;
import com.urlshortener.orchestrator.core.WorkflowNode;

/**
 * Ambiguous: "faster, more reliable, better analytics". The requirements agent flags the vague terms
 * and the plan proceeds optimistically on documented assumptions while a human is asked in parallel.
 * The answers change the requirements (unique visitors with hashed IPs), so the engine re-plans: impact
 * analysis, design and everything after them re-run on the new version, and the design's personal-data
 * handling pulls in a security review.
 */
public final class AmbiguousScenario implements Scenario {

    @Override
    public String name() {
        return "ambiguous";
    }

    @Override
    public String title() {
        return "Act on a vague request: faster, more reliable, better analytics";
    }

    @Override
    public String requirement() {
        return "Make the short links faster and more reliable, and give us better analytics.";
    }

    @Override
    public WorkflowGraph plan(ScenarioEnvironment env) {
        return WorkflowGraph.of(
                WorkflowNode.builder("requirements", StageType.REQUIREMENTS, env.wrap("requirements", new RequirementsAgent()))
                        .exitGate(Gate.contentContains("requirements", "## Open questions")).build(),
                WorkflowNode.builder("clarification", StageType.CLARIFICATION, env.wrap("clarification", new ClarificationAgent()))
                        .dependsOn("requirements")
                        .description("ask a human to resolve ambiguous terms").build(),
                WorkflowNode.builder("impact-analysis", StageType.IMPACT_ANALYSIS, env.wrap("impact-analysis", new ImpactAnalysisAgent(env.repoRoot())))
                        .dependsOn("requirements").build(),
                WorkflowNode.builder("design", StageType.DESIGN, env.wrap("design", new DesignAgent("implementation", "release-readiness")))
                        .dependsOn("impact-analysis")
                        .exitGate(Gate.contentContains("design", "## API contract")).build(),
                WorkflowNode.builder("implementation", StageType.IMPLEMENTATION, env.wrap("implementation",
                                new ImplementationAgent(ImplementationAgent.Strategy.ADDITIVE_MIGRATION)))
                        .dependsOn("design")
                        .impact(Impact.MEDIUM)
                        .compensation(Compensations.discardBranch()).build(),
                WorkflowNode.builder("testing", StageType.TESTING, env.wrap("testing", env.testAgent()))
                        .dependsOn("implementation").build(),
                WorkflowNode.builder("documentation", StageType.DOCUMENTATION, env.wrap("documentation", new DocumentationAgent()))
                        .dependsOn("design").build(),
                WorkflowNode.builder("release-readiness", StageType.RELEASE_READINESS, env.wrap("release-readiness", new ReleaseReadinessAgent()))
                        .dependsOn("testing", "documentation", "clarification")
                        .impact(Impact.HIGH)
                        .compensation(Compensations.withdrawReleaseCandidate()).build());
    }

    @Override
    public ScriptedApprovalGateway.Builder scriptedHuman() {
        return ScriptedApprovalGateway.builder("product-owner (scripted)")
                .answer("fast", "p99 redirect latency under 20 ms at 500 req/s")
                .answer("reliable", "99.95% availability; redirects must keep working when analytics storage is down")
                .answer("better", "Show unique visitors per day. Hash the client IP with a daily rotating salt, never store raw IPs, 30-day retention for hashes.")
                .approve("impact-analysis", "confirmed: continue while clarification is pending")
                .approve("implementation", "additive migration for visitor_hash reviewed")
                .approve("release-readiness", "clarified scope delivered; GO");
    }
}
