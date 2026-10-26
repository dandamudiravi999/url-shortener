package com.urlshortener.orchestrator.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps an agent and fails its first {@code failures} attempts with a transient error.
 * Used by scenarios and the --fail CLI flag to exercise retries, fallback, MTTR and rollback.
 * Every injected fault is labelled as such in the audit trail.
 */
public final class FaultInjectingAgent implements Agent {

    private final Agent delegate;
    private final int failures;
    private final boolean retryable;
    private final String message;
    private final AtomicInteger calls = new AtomicInteger();

    public FaultInjectingAgent(Agent delegate, int failures, boolean retryable, String message) {
        this.delegate = delegate;
        this.failures = failures;
        this.retryable = retryable;
        this.message = message;
    }

    public static FaultInjectingAgent transientFailures(Agent delegate, int failures, String message) {
        return new FaultInjectingAgent(delegate, failures, true, message);
    }

    public static FaultInjectingAgent permanentFailure(Agent delegate, String message) {
        return new FaultInjectingAgent(delegate, Integer.MAX_VALUE, false, message);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public AgentResult execute(AgentTask task) throws Exception {
        if (calls.incrementAndGet() <= failures) {
            return AgentResult.failure("[injected fault] " + message, retryable);
        }
        return delegate.execute(task);
    }
}
