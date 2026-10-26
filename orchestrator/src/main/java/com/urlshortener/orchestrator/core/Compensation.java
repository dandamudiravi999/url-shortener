package com.urlshortener.orchestrator.core;

/** Undo action for a node's side effects, run in reverse completion order when a run fails. */
@FunctionalInterface
public interface Compensation {
    String rollback(WorkflowContext context) throws Exception;
}
