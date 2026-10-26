package com.urlshortener.orchestrator.agent;

/**
 * A unit of autonomous work for one SDLC stage. Agents read the context through the task and
 * return proposed outputs; they never write shared state directly. That keeps every change
 * behind the engine's gates, policy checks and approvals.
 */
public interface Agent {

    String name();

    AgentResult execute(AgentTask task) throws Exception;
}
