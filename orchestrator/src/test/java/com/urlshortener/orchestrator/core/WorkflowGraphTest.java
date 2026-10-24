package com.urlshortener.orchestrator.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.urlshortener.orchestrator.TestAgents.producing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowGraphTest {

    private static WorkflowNode node(String id, String... deps) {
        return WorkflowNode.builder(id, StageType.DESIGN, producing(id, id, id)).dependsOn(deps).build();
    }

    @Test
    void ordersTopologicallyAndGroupsParallelLevels() {
        WorkflowGraph g = WorkflowGraph.of(node("a"), node("b", "a"), node("c", "a"), node("d", "b", "c"));

        assertThat(g.topologicalOrder()).containsExactly("a", "b", "c", "d");
        assertThat(g.parallelLevels()).containsExactly(List.of("a"), List.of("b", "c"), List.of("d"));
        assertThat(g.downstreamOf("a")).containsExactlyInAnyOrder("b", "c", "d");
    }

    @Test
    void rejectsUnknownDependenciesAndDuplicates() {
        assertThatThrownBy(() -> WorkflowGraph.of(node("b", "missing"))).hasMessageContaining("unknown node");
        assertThatThrownBy(() -> WorkflowGraph.of(node("a"), node("a"))).hasMessageContaining("duplicate");
    }

    @Test
    void addingAnEdgeThatCreatesACycleIsRejectedAndRolledBack() {
        WorkflowGraph g = WorkflowGraph.of(node("a"), node("b", "a"));

        assertThatThrownBy(() -> g.addDependency("a", "b")).hasMessageContaining("cycle");
        assertThat(g.require("a").dependsOn()).isEmpty();
        assertThat(g.topologicalOrder()).containsExactly("a", "b");
    }
}
