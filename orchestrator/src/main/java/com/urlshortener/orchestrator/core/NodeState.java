package com.urlshortener.orchestrator.core;

public enum NodeState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** A human declined the node's outputs. Treated like a failure, but never retried. */
    REJECTED,
    /** Not run because an upstream node failed. */
    SKIPPED,
    /** Succeeded, then compensated because the run failed later. */
    ROLLED_BACK,
    /** Not run because the run was safe-stopped. */
    CANCELLED;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }
}
