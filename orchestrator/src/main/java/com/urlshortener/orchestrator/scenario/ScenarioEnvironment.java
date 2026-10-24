package com.urlshortener.orchestrator.scenario;

import com.urlshortener.orchestrator.agent.Agent;
import com.urlshortener.orchestrator.agent.CommandRunner;
import com.urlshortener.orchestrator.agent.FaultInjectingAgent;
import com.urlshortener.orchestrator.agent.TestAgent;

import java.nio.file.Path;
import java.util.Set;

/**
 * Inputs shared by all scenarios.
 *
 * @param repoRoot   repository root containing shortener-service (read by impact analysis, used to run tests)
 * @param reportsDir where Surefire reports are read from
 * @param failNodes  nodes whose agent is forced to fail permanently (demonstrates failure, skip and rollback)
 */
public record ScenarioEnvironment(Path repoRoot, Path reportsDir, TestAgent.Mode testMode, CommandRunner runner,
                                  Set<String> failNodes) {

    public static Path defaultReportsDir(Path repoRoot) {
        return repoRoot.resolve("shortener-service/target/surefire-reports");
    }

    public static ScenarioEnvironment forRepo(Path repoRoot, TestAgent.Mode mode, CommandRunner runner, Set<String> failNodes) {
        return new ScenarioEnvironment(repoRoot, defaultReportsDir(repoRoot), mode, runner, failNodes);
    }

    public Agent wrap(String nodeId, Agent agent) {
        return failNodes.contains(nodeId)
                ? FaultInjectingAgent.permanentFailure(agent, "forced failure of " + nodeId + " (--fail)")
                : agent;
    }

    public TestAgent testAgent() {
        return new TestAgent(testMode, repoRoot, reportsDir, runner, TestAgent.DEFAULT_COMMAND);
    }
}
