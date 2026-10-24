package com.urlshortener.orchestrator.scenario;

import com.urlshortener.orchestrator.agent.DesignAgent;
import com.urlshortener.orchestrator.agent.DocumentationAgent;
import com.urlshortener.orchestrator.agent.FaultInjectingAgent;
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
 * Greenfield: build the shortener from a clear requirement. Shows parallel branches (docs alongside
 * implementation/testing), run-time plan extension (design inserts a security review), recovery from
 * an injected transient test failure (retry, MTTR) and approval gates for scaffolding and release.
 */
public final class GreenfieldScenario implements Scenario {

    @Override
    public String name() {
        return "greenfield";
    }

    @Override
    public String title() {
        return "Build a URL shortener service from scratch";
    }

    @Override
    public String requirement() {
        return "Build a URL shortener service: shorten long URLs into short links, redirect short links to the "
                + "original URL, support custom aliases and link expiry, provide click analytics (stats), allow "
                + "deleting links, and rate limit link creation to prevent abuse.";
    }

    @Override
    public WorkflowGraph plan(ScenarioEnvironment env) {
        return WorkflowGraph.of(
                WorkflowNode.builder("requirements", StageType.REQUIREMENTS, env.wrap("requirements", new RequirementsAgent()))
                        .exitGate(Gate.contentContains("requirements", "AC-1"))
                        .description("normalise requirement into FRs with acceptance criteria").build(),
                WorkflowNode.builder("design", StageType.DESIGN, env.wrap("design", new DesignAgent("implementation", "release-readiness")))
                        .dependsOn("requirements")
                        .entryGate(Gate.requiresArtifacts("requirements"))
                        .exitGate(Gate.contentContains("design", "## API contract"))
                        .description("API contract, data model, key decisions").build(),
                WorkflowNode.builder("implementation", StageType.IMPLEMENTATION, env.wrap("implementation",
                                new ImplementationAgent(ImplementationAgent.Strategy.ADDITIVE_MIGRATION)))
                        .dependsOn("design")
                        .entryGate(Gate.requiresArtifacts("design"))
                        .impact(Impact.MEDIUM)
                        .compensation(Compensations.discardBranch())
                        .description("change set and implementation plan").build(),
                WorkflowNode.builder("testing", StageType.TESTING, env.wrap("testing",
                                FaultInjectingAgent.transientFailures(env.testAgent(), 1, "test JVM fork crashed (simulated transient fault)")))
                        .dependsOn("implementation")
                        .exitGate(Gate.contentContains("test-report", "status: "))
                        .description("map acceptance criteria to tests and collect evidence").build(),
                WorkflowNode.builder("documentation", StageType.DOCUMENTATION, env.wrap("documentation", new DocumentationAgent()))
                        .dependsOn("design")
                        .exitGate(Gate.contentContains("documentation", "## API"))
                        .description("reviewer and operator documentation").build(),
                WorkflowNode.builder("release-readiness", StageType.RELEASE_READINESS, env.wrap("release-readiness", new ReleaseReadinessAgent()))
                        .dependsOn("testing", "documentation")
                        .impact(Impact.HIGH)
                        .compensation(Compensations.withdrawReleaseCandidate())
                        .description("go/no-go checklist and release notes").build());
    }

    @Override
    public ScriptedApprovalGateway.Builder scriptedHuman() {
        return ScriptedApprovalGateway.builder("tech-lead (scripted)")
                .approve("implementation", "scaffolding, build files and V1 schema reviewed")
                .approve("release-readiness", "evidence reviewed; GO for release candidate");
    }
}
