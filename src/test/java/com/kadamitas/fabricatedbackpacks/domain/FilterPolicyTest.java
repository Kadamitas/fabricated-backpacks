package com.kadamitas.fabricatedbackpacks.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.kadamitas.fabricatedbackpacks.domain.FilterPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class FilterPolicyTest {
    private static final ItemDescriptor IRON = new ItemDescriptor("minecraft:iron_ingot");
    private static final ItemDescriptor GOLD = new ItemDescriptor("minecraft:gold_ingot");

    @Test
    void emptyListsAreDeliberateAndNeverMatchAnEmptyStack() {
        assertTrue(blockList(List.of()).matches(IRON));
        assertFalse(allowList(List.of()).matches(IRON));
        assertTrue(allowList(List.of(IRON)).matches(IRON));
        assertFalse(allowList(List.of(IRON)).matches(GOLD));
        assertFalse(blockList(List.of(IRON)).matches(IRON));
        assertTrue(blockList(List.of(IRON)).matches(GOLD));
        assertFalse(blockList(List.of()).matches(ItemDescriptor.EMPTY));
        assertFalse(allowList(List.of()).matches(ItemDescriptor.EMPTY));
    }

    @ParameterizedTest(name = "{0}: damage={1}, components={2}, changedDamage={3}, changedComponents={4}")
    @MethodSource("secondaryCases")
    void damageAndComponentsAreIndependent(Match primary, boolean checkDamage, boolean checkComponents,
                                           boolean changedDamage, boolean changedComponents) {
        ItemDescriptor exemplar = new ItemDescriptor("minecraft:iron_sword", 3,
                Map.of("minecraft:custom_name", "Alpha", "minecraft:damage", "3"), Set.of());
        ItemDescriptor candidate = new ItemDescriptor(
                primary == Match.ITEM ? "minecraft:iron_sword" : "minecraft:diamond_sword",
                changedDamage ? 4 : 3,
                Map.of("minecraft:custom_name", changedComponents ? "Beta" : "Alpha",
                        "minecraft:damage", changedDamage ? "4" : "3"), Set.of());
        FilterPolicy policy = new FilterPolicy(Mode.ALLOW, primary, checkDamage, checkComponents,
                TagMatch.ANY, Set.of(), List.of(exemplar));
        assertEquals((!checkDamage || !changedDamage) && (!checkComponents || !changedComponents),
                policy.matches(candidate));
    }

    static Stream<Arguments> secondaryCases() {
        return Stream.of(Match.ITEM, Match.NAMESPACE).flatMap(primary -> IntStream.range(0, 16)
                .mapToObj(bits -> Arguments.of(primary, (bits & 1) != 0, (bits & 2) != 0,
                        (bits & 4) != 0, (bits & 8) != 0)));
    }

    @ParameterizedTest
    @CsvSource({
            "ALLOW,ANY,true,false", "ALLOW,ALL,true,true",
            "BLOCK,ANY,true,true", "BLOCK,ALL,true,false",
            "ALLOW,ANY,false,true", "ALLOW,ALL,false,false",
            "BLOCK,ANY,false,false", "BLOCK,ALL,false,true"
    })
    void tagQuantifiersAndBlockInversion(Mode mode, TagMatch tagMatch, boolean emptyTags, boolean expected) {
        Set<String> selected = emptyTags ? Set.of() : Set.of("c:ingots", "c:gold_ingots");
        ItemDescriptor candidate = new ItemDescriptor(IRON.id(), 0, Map.of(), Set.of("c:ingots"));
        FilterPolicy policy = new FilterPolicy(mode, Match.TAGS, false, false, tagMatch, selected, List.of());
        assertEquals(expected, policy.matches(candidate));
        assertFalse(policy.matches(ItemDescriptor.EMPTY));
    }

    @Test
    void tagsWithSecondaryChecksRequireAnExemplar() {
        ItemDescriptor named = new ItemDescriptor(IRON.id(), 0,
                Map.of("minecraft:custom_name", "Token"), Set.of("c:ingots"));
        FilterPolicy missingExemplar = new FilterPolicy(Mode.ALLOW, Match.TAGS, false, true,
                TagMatch.ANY, Set.of("c:ingots"), List.of());
        FilterPolicy withExemplar = new FilterPolicy(Mode.ALLOW, Match.TAGS, false, true,
                TagMatch.ANY, Set.of("c:ingots"), List.of(named));
        assertFalse(missingExemplar.matches(named));
        assertTrue(withExemplar.matches(named));
        assertFalse(withExemplar.matches(new ItemDescriptor(IRON.id(), 0, Map.of(), Set.of("c:ingots"))));
    }

    @Test
    void contentsIncludeDepletedMemoryAndIgnoreUnrelatedGhosts() {
        FilterPolicy policy = new FilterPolicy(Mode.CONTENTS, Match.ITEM, false, false,
                TagMatch.ANY, Set.of(), List.of(new ItemDescriptor("minecraft:diamond")));
        assertTrue(policy.matches(IRON, List.of(IRON, ItemDescriptor.EMPTY), List.of()));
        assertTrue(policy.matches(GOLD, List.of(), List.of(GOLD)));
        assertFalse(policy.matches(new ItemDescriptor("minecraft:diamond"), List.of(IRON), List.of(GOLD)));
        assertFalse(policy.matches(IRON, List.of(), List.of()));
    }

    @Test
    void namespaceMatchDoesNotAcceptARegistryNamePrefix() {
        FilterPolicy policy = new FilterPolicy(Mode.ALLOW, Match.NAMESPACE, false, false,
                TagMatch.ANY, Set.of(), List.of(IRON));
        assertTrue(policy.matches(GOLD));
        assertFalse(policy.matches(new ItemDescriptor("minecraft_extra:iron_ingot")));
        assertFalse(allowList(List.of(IRON)).matches(GOLD));
    }

    @Test
    void emptyFuelExceptionMustBeExplicitAndDoesNotOverrideTags() {
        FilterPolicy fuel = new FilterPolicy(Mode.ALLOW, Match.ITEM, false, false,
                TagMatch.ANY, Set.of(), List.of(), true);
        FilterPolicy emptyTag = new FilterPolicy(Mode.ALLOW, Match.TAGS, false, false,
                TagMatch.ANY, Set.of(), List.of(), true);
        assertTrue(fuel.matches(new ItemDescriptor("minecraft:coal")));
        assertFalse(fuel.matches(ItemDescriptor.EMPTY));
        assertFalse(emptyTag.matches(IRON));
    }

    @Test
    void descriptorsAndFiltersSnapshotTheirCollections() {
        Map<String, String> components = new HashMap<>(Map.of("minecraft:custom_name", "Named"));
        Set<String> tags = new HashSet<>(Set.of("c:ingots"));
        ItemDescriptor item = new ItemDescriptor(IRON.id(), 2, components, tags);
        List<ItemDescriptor> entries = new ArrayList<>(List.of(item));
        FilterPolicy policy = allowList(entries);
        components.clear();
        tags.clear();
        entries.clear();
        assertEquals("Named", item.components().get("minecraft:custom_name"));
        assertEquals(Set.of("c:ingots"), item.tags());
        assertTrue(policy.matches(item));
        assertThrows(UnsupportedOperationException.class, () -> item.components().clear());
        assertThrows(UnsupportedOperationException.class, () -> policy.entries().clear());
    }

    @Test
    void malformedStateCannotEnterRuleEvaluation() {
        assertThrows(IllegalArgumentException.class, () -> new ItemDescriptor("NoNamespace"));
        assertThrows(IllegalArgumentException.class, () -> new ItemDescriptor("Minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> new ItemDescriptor(IRON.id(), -1, Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ItemDescriptor(IRON.id(), 0, Map.of("name", "x"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> allowList(List.of(ItemDescriptor.EMPTY)));
        assertThrows(IllegalArgumentException.class, () -> new FilterPolicy(Mode.CONTENTS, Match.TAGS,
                false, false, TagMatch.ANY, Set.of(), List.of()));
        assertThrows(NullPointerException.class, () -> allowList(List.of()).matches(null));
    }
}
