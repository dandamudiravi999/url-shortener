package com.urlshortener.orchestrator.core;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Autonomy boundaries for one run.
 *
 * @param maxParallelism    worker threads for independent nodes
 * @param maxTotalAttempts  attempt budget across all nodes; exceeding it triggers a safe stop
 * @param deadline          wall-clock budget for the run; exceeding it triggers a safe stop
 * @param maxReplans        guard against oscillating re-plans
 * @param killSwitch        if this file exists the run safe-stops (operators can `touch` it)
 * @param rollbackOnFailure compensate completed nodes when the run fails
 */
public record EngineConfig(int maxParallelism, int maxTotalAttempts, Duration deadline, int maxReplans,
                           Path killSwitch, boolean rollbackOnFailure) {

    public static EngineConfig defaults() {
        return new EngineConfig(4, 40, Duration.ofMinutes(15), 5, Path.of(".orchestrator", "STOP"), true);
    }

    public EngineConfig withMaxTotalAttempts(int attempts) {
        return new EngineConfig(maxParallelism, attempts, deadline, maxReplans, killSwitch, rollbackOnFailure);
    }

    public EngineConfig withKillSwitch(Path path) {
        return new EngineConfig(maxParallelism, maxTotalAttempts, deadline, maxReplans, path, rollbackOnFailure);
    }

    public EngineConfig withDeadline(Duration d) {
        return new EngineConfig(maxParallelism, maxTotalAttempts, d, maxReplans, killSwitch, rollbackOnFailure);
    }
}
