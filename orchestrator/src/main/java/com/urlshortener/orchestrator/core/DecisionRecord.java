package com.urlshortener.orchestrator.core;

import java.time.Instant;
import java.util.List;

/**
 * One entry of decision lineage: what was decided, why, and which artifact versions it was based on.
 * Lets a reviewer walk from a release back to the requirement wording that caused it.
 */
public record DecisionRecord(String id, String nodeId, String summary, String rationale,
                             List<String> basedOn, int planRevision, Instant at) {
}
