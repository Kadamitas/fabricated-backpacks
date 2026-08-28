package com.kadamitas.fabricatedbackpacks.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GhostFiltersTest {
    private static final ItemDescriptor STONE = new ItemDescriptor("minecraft:stone");

    @Test
    void editsLeaveOriginalStateUntouched() {
        GhostFilters empty = GhostFilters.empty(9);
        GhostFilters edited = empty.with(4, STONE);
        assertTrue(empty.entries().isEmpty());
        assertEquals(List.of(STONE), edited.entries());
        assertTrue(edited.clear(4).entries().isEmpty());
        assertEquals(List.of(STONE), edited.entries());
        assertThrows(UnsupportedOperationException.class, () -> edited.slots().clear());
    }

    @Test
    void duplicateIdentityIgnoresReloadableTags() {
        GhostFilters filters = GhostFilters.empty(3).with(0, STONE);
        ItemDescriptor reloaded = new ItemDescriptor(STONE.id(), 0, Map.of(), Set.of("c:stones"));
        assertSame(filters, filters.with(1, reloaded));
        assertEquals(Optional.empty(), filters.slots().get(1));
        ItemDescriptor named = new ItemDescriptor(STONE.id(), 0,
                Map.of("minecraft:custom_name", "Named stone"), Set.of());
        assertEquals(2, filters.with(1, named).entries().size());
    }

    @Test
    void smallerDefaultsRetainExistingSlots() {
        GhostFilters original = GhostFilters.empty(16).with(15, STONE);
        GhostFilters smaller = original.withConfiguredSlots(9);
        assertEquals(9, smaller.configuredSlots());
        assertEquals(16, smaller.slots().size());
        assertEquals(STONE, smaller.slots().get(15).orElseThrow());
        assertEquals(20, smaller.withConfiguredSlots(20).slots().size());
    }

    @Test
    void invalidBoundsAndDuplicateSavedEntriesFail() {
        assertThrows(IllegalArgumentException.class, () -> GhostFilters.empty(-1));
        assertThrows(IllegalArgumentException.class, () -> GhostFilters.empty(65));
        assertThrows(IndexOutOfBoundsException.class, () -> GhostFilters.empty(3).with(3, STONE));
        assertThrows(IllegalArgumentException.class, () -> new GhostFilters(2,
                List.of(Optional.of(STONE), Optional.of(STONE))));
        assertTrue(GhostFilters.empty(0).entries().isEmpty());
        assertTrue(new GhostFilters(1, List.of(Optional.of(ItemDescriptor.EMPTY))).entries().isEmpty());
    }
}
