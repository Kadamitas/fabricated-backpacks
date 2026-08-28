package com.kadamitas.fabricatedbackpacks.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolRuleTest {
    @Test void exactItemsTagsAndSafeDefaults() {
        ToolRule rule = ToolRule.decode("""
                {"items":["example:custom_axe","#minecraft:axes"],"blocks":["#minecraft:logs"]}
                """);
        assertEquals(Set.of("example:custom_axe", "#minecraft:axes"), rule.items());
        assertEquals(Set.of("#minecraft:logs"), rule.blocks());
        assertTrue(rule.entities().isEmpty());
        assertEquals(0, rule.priority());
        assertFalse(rule.manualOnly());
        assertTrue(rule.requireCorrectTool());
    }

    @Test void explicitUtilityRulesAndImmutableInputs() {
        ToolRule rule = ToolRule.decode("""
                {"items":["minecraft:shears"],"entities":["minecraft:sheep"],
                 "priority":1000,"manual_only":true,"require_correct_tool":false}
                """);
        assertEquals(1000, rule.priority());
        assertTrue(rule.manualOnly());
        assertFalse(rule.requireCorrectTool());
        assertThrows(UnsupportedOperationException.class, () -> rule.items().clear());
        Set<String> selectors = new HashSet<>(Set.of("minecraft:brush"));
        ToolRule copy = new ToolRule(selectors, Set.of("minecraft:suspicious_sand"), Set.of(), 0, true, false);
        selectors.clear();
        assertEquals(Set.of("minecraft:brush"), copy.items());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]", "null", "{}",
            "{\"items\":[],\"blocks\":[\"minecraft:stone\"]}",
            "{\"items\":[\"minecraft:brush\"]}",
            "{\"items\":[\"Bad:brush\"],\"blocks\":[\"minecraft:stone\"]}",
            "{\"items\":[\"brush\"],\"blocks\":[\"minecraft:stone\"]}",
            "{\"items\":[\"minecraft:brush\",\"minecraft:brush\"],\"blocks\":[\"minecraft:stone\"]}",
            "{\"items\":[false],\"blocks\":[\"minecraft:stone\"]}",
            "{\"items\":[\"minecraft:brush\"],\"blocks\":null}",
            "{\"items\":[\"minecraft:brush\"],\"blocks\":[\"minecraft:stone\"],\"prioriy\":1}",
            "{\"items\":[\"minecraft:brush\"],\"blocks\":[\"minecraft:stone\"],\"priority\":-1}",
            "{\"items\":[\"minecraft:brush\"],\"blocks\":[\"minecraft:stone\"],\"priority\":1001}",
            "{\"items\":[\"minecraft:brush\"],\"blocks\":[\"minecraft:stone\"],\"priority\":1.2}",
            "{\"items\":[\"minecraft:brush\"],\"blocks\":[\"minecraft:stone\"],\"priority\":\"1\"}",
            "{\"items\":[\"minecraft:brush\"],\"blocks\":[\"minecraft:stone\"],\"priority\":1e100}",
            "{\"items\":[\"minecraft:brush\"],\"blocks\":[\"minecraft:stone\"],\"manual_only\":\"true\"}",
            "{\"items\":[\"minecraft:brush\"],\"blocks\":[\"minecraft:stone\"],\"require_correct_tool\":null}"
    })
    void invalidDataCannotBecomeAnUnboundedOrAmbiguousRule(String json) {
        assertThrows(RuntimeException.class, () -> ToolRule.decode(json));
    }

    @Test void boundsResourceSizeAndSelectorCount() {
        String selectors = java.util.stream.IntStream.range(0, 65).mapToObj(index -> "\"example:tool_" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        assertThrows(IllegalArgumentException.class, () -> ToolRule.decode("{\"items\":[" + selectors + "],\"blocks\":[\"minecraft:stone\"]}"));
        assertThrows(IllegalArgumentException.class, () -> ToolRule.decode(" ".repeat(ToolRule.MAX_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> new ToolRule(Set.of("example:" + "a".repeat(257)), Set.of("minecraft:stone"), Set.of(), 0, false, true));
    }
}
