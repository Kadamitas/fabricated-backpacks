package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConduitFilterTest {
    private static final ResourceLocation COBBLE = ResourceLocation.withDefaultNamespace("cobblestone");
    private static final ResourceLocation IRON = ResourceLocation.withDefaultNamespace("iron_ingot");

    @ParameterizedTest
    @EnumSource(ConduitFilterMode.class)
    void membershipAndEmptyListsHaveExplicitSemantics(ConduitFilterMode mode) {
        var filter = new ConduitFilter(mode, Map.of(8, COBBLE));
        assertEquals(mode != ConduitFilterMode.BLOCK, filter.matches(COBBLE));
        assertEquals(mode != ConduitFilterMode.ALLOW, filter.matches(IRON));
        assertEquals(mode != ConduitFilterMode.ALLOW, new ConduitFilter(mode, Map.of()).matches(COBBLE));
        assertSame(mode, mode.next().next().next());
    }

    @Test
    void ghostEditsPreserveSparsePositionsAndDoNotMutateEarlierPolicies() {
        Map<Integer, ResourceLocation> supplied = new LinkedHashMap<>();
        supplied.put(8, IRON);
        supplied.put(0, COBBLE);
        var original = new ConduitFilter(ConduitFilterMode.BLOCK, supplied);
        supplied.clear();
        assertEquals(Map.of(0, COBBLE, 8, IRON), original.entries());
        assertEquals(Optional.empty(), original.entry(4));
        assertThrows(UnsupportedOperationException.class, () -> original.entries().clear());
        var changed = original.withoutEntry(0).withEntry(4, COBBLE).withMode(ConduitFilterMode.ALLOW);
        assertEquals(Map.of(4, COBBLE, 8, IRON), changed.entries());
        assertEquals(Map.of(0, COBBLE, 8, IRON), original.entries());
        assertSame(changed, changed.withEntry(4, COBBLE));
        assertSame(changed, changed.withoutEntry(1));
        assertSame(changed, changed.withMode(ConduitFilterMode.ALLOW));
    }

    @Test
    void constructionRejectsAmbiguousEntriesAndEnforcesBothBoundaries() {
        for (int slot : new int[]{-1, 9, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new ConduitFilter(ConduitFilterMode.OFF, Map.of(slot, COBBLE)));
            assertThrows(IllegalArgumentException.class, () -> ConduitFilter.EMPTY.entry(slot));
            assertThrows(IllegalArgumentException.class, () -> ConduitFilter.EMPTY.withEntry(slot, COBBLE));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ConduitFilter(ConduitFilterMode.ALLOW, Map.of(0, COBBLE, 8, COBBLE)));
        assertThrows(IllegalArgumentException.class,
                () -> ConduitFilter.EMPTY.withEntry(0, COBBLE).withEntry(8, COBBLE));
        assertThrows(NullPointerException.class, () -> new ConduitFilter(null, Map.of()));
        assertThrows(NullPointerException.class, () -> ConduitFilter.EMPTY.withEntry(0, null));
        ResourceLocation maximum = ResourceLocation.fromNamespaceAndPath("m", "x".repeat(254));
        assertEquals(256, maximum.toString().length());
        assertEquals(Optional.of(maximum), ConduitFilter.EMPTY.withEntry(8, maximum).entry(8));
        ResourceLocation oversized = ResourceLocation.fromNamespaceAndPath("m", "x".repeat(255));
        assertThrows(IllegalArgumentException.class, () -> ConduitFilter.EMPTY.withEntry(0, oversized));
    }

    @ParameterizedTest
    @EnumSource(ConduitFilterMode.class)
    void diskCodecPreservesAllNinePositionsAndMissingModIdentities(ConduitFilterMode mode) {
        Map<Integer, ResourceLocation> entries = new LinkedHashMap<>();
        for (int slot = 8; slot >= 0; slot--)
            entries.put(slot, ResourceLocation.fromNamespaceAndPath("removed_mod", "fluid_or_item_" + slot));
        var expected = new ConduitFilter(mode, entries);
        var encoded = ConduitFilter.CODEC.encodeStart(JsonOps.INSTANCE, expected).getOrThrow();
        assertEquals(expected, ConduitFilter.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertEquals(0, encoded.getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().get("slot").getAsInt(),
                "Canonical serialization is deterministic regardless of caller map order");
    }

    @Test
    void malformedDiskPoliciesAreErrorsInsteadOfPartiallyAcceptedLists() {
        for (String json : new String[]{
                "{\"mode\":\"unknown\",\"entries\":[]}",
                "{\"mode\":\"allow\",\"entries\":[{\"slot\":9,\"id\":\"minecraft:stone\"}]}",
                "{\"mode\":\"allow\",\"entries\":[{\"slot\":0,\"id\":\"minecraft:stone\"},{\"slot\":0,\"id\":\"minecraft:dirt\"}]}",
                "{\"mode\":\"block\",\"entries\":[{\"slot\":0,\"id\":\"minecraft:stone\"},{\"slot\":8,\"id\":\"minecraft:stone\"}]}",
                "{\"mode\":\"allow\",\"entries\":[{\"slot\":0,\"id\":\"not an identifier\"}]}",
                "{\"mode\":\"allow\"}", "{\"entries\":[]}", "\"not a policy\""
        }) assertTrue(ConduitFilter.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).error().isPresent(), json);
        JsonObject oversized = new JsonObject();
        oversized.addProperty("mode", "block");
        JsonArray entries = new JsonArray();
        for (int index = 0; index < 10; index++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", index % 9);
            entry.addProperty("id", "fixture:item_" + index);
            entries.add(entry);
        }
        oversized.add("entries", entries);
        assertTrue(ConduitFilter.CODEC.parse(JsonOps.INSTANCE, oversized).error().isPresent());
    }
}
