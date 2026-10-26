package com.urlshortener.orchestrator.core;

import java.time.Clock;

/** Lets tests outside the core package seed a context (publishing is engine-only in production code). */
public final class ContextFixtures {

    private ContextFixtures() {
    }

    public static WorkflowContext withArtifact(String requirement, String name, String content) {
        WorkflowContext ctx = new WorkflowContext("fixture", requirement, Clock.systemUTC());
        ctx.publish(name, content, "fixture");
        return ctx;
    }
}
