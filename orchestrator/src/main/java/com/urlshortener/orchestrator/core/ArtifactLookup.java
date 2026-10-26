package com.urlshortener.orchestrator.core;

import java.util.Optional;

/** Read access to artifacts; implemented by the committed context and by staged (uncommitted) views. */
@FunctionalInterface
public interface ArtifactLookup {

    Optional<Artifact> find(String name);

    default String contentOf(String name) {
        return find(name).map(Artifact::content).orElse("");
    }

    default boolean has(String name) {
        return find(name).isPresent();
    }
}
