package com.urlshortener.orchestrator;

import com.urlshortener.orchestrator.agent.Agent;
import com.urlshortener.orchestrator.agent.AgentResult;
import com.urlshortener.orchestrator.agent.AgentTask;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Small stub agents for engine tests. */
public final class TestAgents {

    private TestAgents() {
    }

    public static Agent producing(String name, String artifact, String content) {
        return of(name, t -> AgentResult.success(name + " done").artifact(artifact, content));
    }

    public static Agent of(String name, Function<AgentTask, AgentResult> body) {
        return new Agent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentResult execute(AgentTask task) {
                return body.apply(task);
            }
        };
    }

    /** Fails with a retryable error the first {@code failures} times. */
    public static Agent flaky(String name, int failures, String artifact, String content, AtomicInteger calls) {
        return of(name, t -> calls.incrementAndGet() <= failures
                ? AgentResult.failure("transient", true)
                : AgentResult.success("ok").artifact(artifact, content));
    }
}
