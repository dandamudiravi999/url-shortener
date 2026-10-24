package com.urlshortener.orchestrator.core;

import java.util.List;
import java.util.Map;

public record NodeReport(String id, StageType stage, String description, List<String> dependsOn, Impact impact,
                         NodeState state, int attempts, int executions, String agent, String message,
                         long durationMillis, Map<String, Integer> consumed) {
}
