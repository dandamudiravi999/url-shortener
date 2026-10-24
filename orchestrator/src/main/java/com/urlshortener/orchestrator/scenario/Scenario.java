package com.urlshortener.orchestrator.scenario;

import com.urlshortener.orchestrator.core.ScriptedApprovalGateway;
import com.urlshortener.orchestrator.core.WorkflowGraph;

/** A requirement plus the plan the orchestrator starts from and the scripted human for unattended runs. */
public interface Scenario {

    String name();

    String title();

    String requirement();

    WorkflowGraph plan(ScenarioEnvironment env);

    ScriptedApprovalGateway.Builder scriptedHuman();
}
