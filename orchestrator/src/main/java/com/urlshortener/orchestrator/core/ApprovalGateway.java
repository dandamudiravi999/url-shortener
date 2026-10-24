package com.urlshortener.orchestrator.core;

/**
 * The human-in-the-loop channel. Implementations: console prompt (interactive), scripted
 * decisions (demos, CI, tests). Anything that cannot reach a human must reject: the safe default.
 */
@FunctionalInterface
public interface ApprovalGateway {
    ApprovalDecision decide(ApprovalRequest request);
}
