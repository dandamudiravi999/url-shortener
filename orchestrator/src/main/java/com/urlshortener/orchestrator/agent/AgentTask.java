package com.urlshortener.orchestrator.agent;

import com.urlshortener.orchestrator.core.ApprovalGateway;
import com.urlshortener.orchestrator.core.StageType;
import com.urlshortener.orchestrator.core.WorkflowContext;

/** Everything an agent may use for one attempt. {@code human} is audited by the engine. */
public record AgentTask(String runId, String nodeId, StageType stage, int attempt,
                        WorkflowContext context, ApprovalGateway human) {
}
