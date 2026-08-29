package com.kadamitas.fabricatedbackpacks.automation.conduit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class ConduitTopologyTest {
    @Test
    void incrementalWorkNeverPublishesAnIncompleteLoop() {
        Map<Long, long[]> graph = Map.of(1L, new long[]{2, 4}, 2L, new long[]{1, 3},
                3L, new long[]{2, 4}, 4L, new long[]{1, 3});
        var expansions = new AtomicInteger();
        var topology = new ConduitTopology(graph.keySet(), 4, graph::containsKey, key -> { expansions.incrementAndGet(); return graph.get(key); });
        List<ConduitTopology.Component> result = new ArrayList<>();
        while (!topology.complete()) {
            int previous = expansions.get();
            result.addAll(topology.advance(1));
            assertTrue(expansions.get() - previous <= 1, "one unit cannot expand multiple nodes");
            if (expansions.get() < 4) assertNull(topology.component(1));
        }
        assertEquals(1, result.size());
        assertEquals(4, result.getFirst().size());
        assertFalse(result.getFirst().oversized());
        assertEquals(graph.keySet(), new java.util.HashSet<>(result.getFirst().nodes()));
    }

    @Test
    void oversizedComponentsRetainABoundedSampleWithoutCreatingValidFragments() {
        var topology = new ConduitTopology(LongStream.range(0, 20_000).boxed().toList(), 8,
                key -> key >= 0 && key < 20_000, key -> new long[]{key - 1, key + 1});
        List<ConduitTopology.Component> results = finish(topology, 31);
        assertEquals(1, results.size());
        assertEquals(20_000, results.getFirst().size());
        assertEquals(8, results.getFirst().nodes().size());
        assertTrue(results.getFirst().oversized());
        assertSame(results.getFirst(), topology.component(0));
        assertSame(results.getFirst(), topology.component(19_999), "every truncated member still belongs to the rejected component");
        assertEquals(results, topology.invalidate(List.of(19_999L)), "An unsampled member invalidates its entire oversized group");
        List<ConduitTopology.Component> rebuilt = finish(topology, 31);
        assertEquals(1, rebuilt.size());
        assertEquals(20_000, rebuilt.getFirst().size());
        assertTrue(rebuilt.getFirst().oversized(), "Rebuilding must not turn discarded sample entries into valid fragments");
    }

    @Test
    void MissingAndDisconnectedNodesAreNotSilentlyJoined() {
        Map<Long, long[]> graph = new HashMap<>();
        graph.put(1L, new long[]{2});
        graph.put(2L, new long[]{1, 3});
        graph.put(3L, new long[]{2, 4});
        graph.put(4L, new long[]{3});
        var topology = new ConduitTopology(graph.keySet(), 8, key -> key != 3 && graph.containsKey(key), graph::get);
        var results = finish(topology, 2);
        assertEquals(List.of(1, 2), results.stream().map(ConduitTopology.Component::size).sorted().toList());
        assertNull(topology.component(3));
        assertNotSame(topology.component(2), topology.component(4));
    }

    @Test
    void EmptyAndInvalidInputsHaveExplicitBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> new ConduitTopology(List.of(), 0, key -> false, key -> new long[0]));
        var topology = new ConduitTopology(List.of(), 1, key -> false, key -> new long[0]);
        assertTrue(topology.complete());
        assertTrue(topology.advance(1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> topology.advance(0));
        assertThrows(IllegalArgumentException.class, () -> topology.advance(-1));
    }

    @Test
    void repeatedLocalChangesPreserveAnUnrelatedIncompleteDiscovery() {
        Map<Long, long[]> graph = new HashMap<>();
        graph.put(0L, new long[0]);
        for (long node = 1_000; node < 1_240; node++) graph.put(node, new long[]{node - 1, node + 1});
        AtomicInteger largeExpansions = new AtomicInteger();
        var topology = new ConduitTopology(graph.keySet().stream().sorted().toList(), 256, graph::containsKey,
                key -> { if (key >= 1_000) largeExpansions.incrementAndGet(); return graph.get(key); });
        topology.advance(8);
        assertNull(topology.component(1_000), "The large route is still being discovered");
        for (int tick = 0; tick < 40 && topology.component(1_000) == null; tick++) {
            topology.invalidate(List.of(0L));
            topology.advance(8);
        }
        assertNotNull(topology.component(1_000), "A changing disconnected group must not reset the active frontier");
        assertEquals(240, topology.component(1_000).size());
        assertEquals(240, largeExpansions.get(), "Unrelated changes do not redo completed discovery work");
    }

    @Test
    void targetedBridgeChangesRebuildBothSidesWithoutDroppingOtherGroups() {
        Map<Long, long[]> graph = new HashMap<>(Map.of(1L, new long[]{2}, 2L, new long[]{1},
                3L, new long[]{4}, 4L, new long[]{3}, 10L, new long[0]));
        var topology = new ConduitTopology(graph.keySet(), 8, graph::containsKey, graph::get);
        finish(topology, 3);
        ConduitTopology.Component independent = topology.component(10);
        graph.put(2L, new long[]{1, 3}); graph.put(3L, new long[]{2, 4});
        assertEquals(2, topology.invalidate(List.of(2L, 3L)).size());
        assertNull(topology.component(1)); assertNull(topology.component(4));
        assertSame(independent, topology.component(10));
        assertEquals(4, finish(topology, 2).getFirst().size());
        assertSame(topology.component(1), topology.component(4));
        graph.put(2L, new long[]{1}); graph.put(3L, new long[]{4});
        assertEquals(1, topology.invalidate(List.of(2L, 3L)).size());
        assertEquals(List.of(2, 2), finish(topology, 2).stream().map(ConduitTopology.Component::size).sorted().toList());
        assertSame(independent, topology.component(10));
    }

    private static List<ConduitTopology.Component> finish(ConduitTopology topology, int work) {
        List<ConduitTopology.Component> result = new ArrayList<>();
        for (int iterations = 0; !topology.complete(); iterations++) {
            assertTrue(iterations < 100_000, "bounded iterative traversal must terminate");
            result.addAll(topology.advance(work));
        }
        return result;
    }
}
