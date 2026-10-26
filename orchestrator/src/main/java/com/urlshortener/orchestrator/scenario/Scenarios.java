package com.urlshortener.orchestrator.scenario;

import java.util.List;
import java.util.Optional;

public final class Scenarios {

    private Scenarios() {
    }

    public static List<Scenario> all() {
        return List.of(new GreenfieldScenario(), new BrownfieldScenario(), new AmbiguousScenario());
    }

    public static Optional<Scenario> byName(String name) {
        return all().stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst();
    }
}
