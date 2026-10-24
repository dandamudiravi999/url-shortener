package com.urlshortener.orchestrator.agent;

import com.urlshortener.orchestrator.core.WorkflowNode;

import java.util.List;

/**
 * An agent's request to extend the plan at run time: insert {@code node} and make each of
 * {@code gates} (existing, not yet started nodes) wait for it. Validated by the engine.
 */
public record PlanChange(WorkflowNode node, List<String> gates, String reason) {
}
