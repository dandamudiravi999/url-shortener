package com.urlshortener.orchestrator.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit dependency graph (DAG). Every mutation is validated: unknown dependencies and
 * cycles are rejected, so a run-time re-plan can never leave the engine with an unschedulable plan.
 * Not thread-safe: only the engine's coordinator thread mutates it.
 */
public final class WorkflowGraph {

    private final Map<String, WorkflowNode> nodes = new LinkedHashMap<>();

    public static WorkflowGraph of(WorkflowNode... nodes) {
        WorkflowGraph graph = new WorkflowGraph();
        for (WorkflowNode node : nodes) {
            graph.add(node);
        }
        return graph;
    }

    public WorkflowGraph add(WorkflowNode node) {
        if (nodes.containsKey(node.id())) {
            throw new IllegalArgumentException("duplicate node id: " + node.id());
        }
        for (String dep : node.dependsOn()) {
            if (!nodes.containsKey(dep)) {
                throw new IllegalArgumentException(node.id() + " depends on unknown node " + dep);
            }
        }
        nodes.put(node.id(), node);
        return this;
    }

    /** Adds an edge upstream -> downstream; rolls back and throws if it would create a cycle. */
    public void addDependency(String downstreamId, String upstreamId) {
        WorkflowNode downstream = require(downstreamId);
        require(upstreamId);
        nodes.put(downstreamId, downstream.withDependency(upstreamId));
        if (hasCycle()) {
            nodes.put(downstreamId, downstream);
            throw new IllegalArgumentException("edge " + upstreamId + " -> " + downstreamId + " would create a cycle");
        }
    }

    public WorkflowNode require(String id) {
        WorkflowNode node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("unknown node " + id);
        }
        return node;
    }

    public Optional<WorkflowNode> find(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public boolean contains(String id) {
        return nodes.containsKey(id);
    }

    public Collection<WorkflowNode> nodes() {
        return List.copyOf(nodes.values());
    }

    public Set<String> ids() {
        return new LinkedHashSet<>(nodes.keySet());
    }

    /** Direct dependents of a node. */
    public List<String> dependentsOf(String id) {
        return nodes.values().stream().filter(n -> n.dependsOn().contains(id)).map(WorkflowNode::id).toList();
    }

    /** All transitive dependents of a node (not including itself). */
    public Set<String> downstreamOf(String id) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(dependentsOf(id));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (seen.add(next)) {
                queue.addAll(dependentsOf(next));
            }
        }
        return seen;
    }

    /** Kahn's algorithm; also the cycle check. Stable with respect to insertion order. */
    public List<String> topologicalOrder() {
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.values().forEach(n -> inDegree.put(n.id(), n.dependsOn().size()));
        Deque<String> ready = new ArrayDeque<>();
        nodes.keySet().stream().filter(id -> inDegree.get(id) == 0).forEach(ready::add);
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dependent : dependentsOf(id)) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != nodes.size()) {
            throw new IllegalStateException("workflow graph contains a cycle");
        }
        return order;
    }

    /** Groups nodes into levels that may run in parallel; used for plan rendering. */
    public List<List<String>> parallelLevels() {
        Map<String, Integer> level = new HashMap<>();
        for (String id : topologicalOrder()) {
            int l = require(id).dependsOn().stream().mapToInt(level::get).map(x -> x + 1).max().orElse(0);
            level.put(id, l);
        }
        List<List<String>> levels = new ArrayList<>();
        for (String id : topologicalOrder()) {
            int l = level.get(id);
            while (levels.size() <= l) {
                levels.add(new ArrayList<>());
            }
            levels.get(l).add(id);
        }
        return levels;
    }

    private boolean hasCycle() {
        try {
            topologicalOrder();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
