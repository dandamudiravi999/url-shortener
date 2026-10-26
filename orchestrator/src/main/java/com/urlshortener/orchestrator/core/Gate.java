package com.urlshortener.orchestrator.core;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Entry gates guard preconditions before an agent runs; exit gates check an agent's
 * (still uncommitted) outputs before they are accepted into the shared context.
 */
@FunctionalInterface
public interface Gate {

    GateResult check(ArtifactLookup artifacts);

    static Gate requiresArtifacts(String... names) {
        String gate = "requires" + Arrays.toString(names);
        return artifacts -> {
            List<String> missing = Arrays.stream(names).filter(n -> !artifacts.has(n)).toList();
            return missing.isEmpty() ? GateResult.pass(gate) : GateResult.fail(gate, "missing artifacts " + missing);
        };
    }

    static Gate contentContains(String artifact, String expected) {
        String gate = artifact + " contains '" + expected + "'";
        return artifacts -> artifacts.contentOf(artifact).contains(expected)
                ? GateResult.pass(gate)
                : GateResult.fail(gate, artifact + " does not contain '" + expected + "'");
    }

    static Gate contentMatches(String description, String artifact, Predicate<String> predicate) {
        return artifacts -> predicate.test(artifacts.contentOf(artifact))
                ? GateResult.pass(description)
                : GateResult.fail(description, description + " not satisfied by " + artifact);
    }
}
