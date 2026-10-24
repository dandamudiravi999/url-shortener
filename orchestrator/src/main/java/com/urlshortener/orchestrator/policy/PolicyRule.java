package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.core.ArtifactLookup;
import com.urlshortener.orchestrator.core.WorkflowNode;

import java.util.List;
import java.util.Map;

/**
 * A guardrail evaluated on every node's proposed outputs before they are committed.
 *
 * @param proposed artifacts this attempt wants to publish (name to content)
 * @param view     the context as it would look after commit (proposed overlaid on committed)
 */
public interface PolicyRule {

    String id();

    PolicyCategory category();

    List<PolicyFinding> evaluate(WorkflowNode node, Map<String, String> proposed, ArtifactLookup view);
}
