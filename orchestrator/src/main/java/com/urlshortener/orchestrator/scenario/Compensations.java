package com.urlshortener.orchestrator.scenario;

import com.urlshortener.orchestrator.core.Compensation;

/** Undo actions for nodes with side effects. They record what was undone in the run's facts. */
final class Compensations {

    private Compensations() {
    }

    static Compensation discardBranch() {
        return ctx -> {
            String branch = "agent/" + ctx.runId();
            ctx.putFact("rollback.branch", branch + " discarded");
            return "discarded working branch " + branch + " and its change set";
        };
    }

    static Compensation withdrawReleaseCandidate() {
        return ctx -> {
            ctx.putFact("rollback.release", "rc-" + ctx.runId() + " withdrawn");
            return "withdrew release candidate rc-" + ctx.runId();
        };
    }
}
