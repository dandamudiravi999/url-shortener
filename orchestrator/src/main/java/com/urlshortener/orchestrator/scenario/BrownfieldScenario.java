package com.urlshortener.orchestrator.scenario;

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
 * Brownfield: enhance the existing service. Shows codebase reasoning (impact analysis over the real
 * shortener-service sources), a change-control DENY on the primary implementation (it edits an applied
 * migration), automatic fallback to an additive migration, and a human approval for the schema change.
 */
public final class BrownfieldScenario implements Scenario {

    @Override
    public String name() {
        return "brownfield";
    }

    @Override
    public String title() {
        return "Add a per-link click limit to the existing service";
    }

    @Override
    public String requirement() {
        return "Enhance the existing shortener: add a click limit so the owner can set maxClicks when creating a "
                + "short link. Once the limit is reached the link stops redirecting and returns 410 Gone. "
                + "Expose remaining clicks in stats.";
    }

    @Override
    public WorkflowGraph plan(ScenarioEnvironment env) {
        return WorkflowGraph.of(
                WorkflowNode.builder("requirements", StageType.REQUIREMENTS, env.wrap("requirements", new RequirementsAgent()))
                        .exitGate(Gate.contentContains("requirements", "AC-1")).build(),
                WorkflowNode.builder("impact-analysis", StageType.IMPACT_ANALYSIS, env.wrap("impact-analysis", new ImpactAnalysisAgent(env.repoRoot())))
                        .dependsOn("requirements")
                        .exitGate(Gate.contentMatches("at least one impacted file", "impact-analysis",
                                c -> !c.contains("impacted-files: 0")))
                        .description("scan shortener-service for impacted modules, APIs, data").build(),
                WorkflowNode.builder("design", StageType.DESIGN, env.wrap("design", new DesignAgent("implementation", "release-readiness")))
                        .dependsOn("impact-analysis")
                        .exitGate(Gate.contentContains("design", "## API contract")).build(),
                WorkflowNode.builder("implementation", StageType.IMPLEMENTATION, env.wrap("implementation",
                                new ImplementationAgent(ImplementationAgent.Strategy.IN_PLACE_SCHEMA_EDIT)))
                        .fallback(new ImplementationAgent(ImplementationAgent.Strategy.ADDITIVE_MIGRATION))
                        .dependsOn("design")
                        .impact(Impact.MEDIUM)
                        .compensation(Compensations.discardBranch())
                        .description("change set against existing code").build(),
                WorkflowNode.builder("testing", StageType.TESTING, env.wrap("testing", env.testAgent()))
                        .dependsOn("implementation")
                        .exitGate(Gate.contentContains("test-report", "status: "))
                        .description("regression evidence for the current codebase").build(),
                WorkflowNode.builder("documentation", StageType.DOCUMENTATION, env.wrap("documentation", new DocumentationAgent()))
                        .dependsOn("design").build(),
                WorkflowNode.builder("release-readiness", StageType.RELEASE_READINESS, env.wrap("release-readiness", new ReleaseReadinessAgent()))
                        .dependsOn("testing", "documentation")
                        .impact(Impact.HIGH)
                        .compensation(Compensations.withdrawReleaseCandidate()).build());
    }

    @Override
    public ScriptedApprovalGateway.Builder scriptedHuman() {
        return ScriptedApprovalGateway.builder("service-owner (scripted)")
                .approve("implementation", "additive migration V2 reviewed; nullable column, no backfill needed")
                .approve("release-readiness", "regression suite green; GO");
    }
}
