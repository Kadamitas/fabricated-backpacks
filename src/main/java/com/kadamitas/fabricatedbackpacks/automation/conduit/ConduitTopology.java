package com.kadamitas.fabricatedbackpacks.automation.conduit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/** Incremental, iterative component discovery; incomplete and oversized components never become routes. */
final class ConduitTopology {
    record Component(List<Long> nodes, int size, boolean oversized) {
        Component { nodes = List.copyOf(nodes); }
    }

    private static final class Group {
        final List<Long> sample = new ArrayList<>();
        int size;
        Component completed;
    }

    private final Set<Long> seeds = new LinkedHashSet<>();
    private final ArrayDeque<Long> frontier = new ArrayDeque<>();
    private final Map<Long, Group> seen = new HashMap<>();
    private Group current;
    private final LongPredicate present;
    private final LongFunction<long[]> adjacent;
    private final int maximumNodes;

    ConduitTopology(Collection<Long> roots, int maximumNodes, LongPredicate present, LongFunction<long[]> adjacent) {
        if (maximumNodes < 1) throw new IllegalArgumentException("A network must allow at least one node");
        for (Long root : Objects.requireNonNull(roots)) seeds.add(Objects.requireNonNull(root));
        this.maximumNodes = maximumNodes;
        this.present = Objects.requireNonNull(present);
        this.adjacent = Objects.requireNonNull(adjacent);
    }

    /** Each examined seed or expanded node consumes work, including duplicates and removed nodes. */
    List<Component> advance(int work) {
        if (work < 1) throw new IllegalArgumentException("Work must be positive");
        List<Component> completed = new ArrayList<>();
        while (work-- > 0) {
            if (frontier.isEmpty()) {
                complete(completed);
                if (seeds.isEmpty()) break;
                var iterator = seeds.iterator();
                long seed = iterator.next();
                iterator.remove();
                if (present.test(seed) && !seen.containsKey(seed)) {
                    current = new Group();
                    seen.put(seed, current);
                    frontier.add(seed);
                }
                continue;
            }
            long node = frontier.removeFirst();
            if (present.test(node)) {
                current.size++;
                if (current.sample.size() < maximumNodes) current.sample.add(node);
                for (long neighbor : adjacent.apply(node)) {
                    if (present.test(neighbor) && seen.putIfAbsent(neighbor, current) == null) frontier.addLast(neighbor);
                }
            }
            if (frontier.isEmpty()) complete(completed);
        }
        return completed;
    }

    private void complete(List<Component> completed) {
        if (current != null) {
            current.completed = new Component(current.sample, current.size, current.size > maximumNodes);
            current.sample.clear();
            if (current.size > 0) completed.add(current.completed);
            current = null;
        }
    }

    /**
     * Invalidates only groups touching a changed node or edge. Unrelated completed routes and the
     * current discovery keep their progress. All members of an oversized group are re-seeded too;
     * its capped display sample must never split it into apparently valid fragments.
     */
    List<Component> invalidate(Collection<Long> affected) {
        Set<Group> removed = new HashSet<>();
        for (long node : affected) {
            seeds.add(node);
            Group group = seen.get(node);
            if (group != null) removed.add(group);
        }
        if (removed.isEmpty()) return List.of();
        List<Component> result = new ArrayList<>();
        for (Group group : removed) if (group.completed != null) result.add(group.completed);
        if (removed.contains(current)) {
            frontier.clear();
            current = null;
        }
        var iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (removed.contains(entry.getValue())) {
                seeds.add(entry.getKey());
                iterator.remove();
            }
        }
        return List.copyOf(result);
    }

    Component component(long node) { Group group = seen.get(node); return group == null ? null : group.completed; }
    boolean complete() { return seeds.isEmpty() && frontier.isEmpty() && current == null; }
}
