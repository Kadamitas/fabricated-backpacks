package com.kadamitas.fabricatedbackpacks.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class BrowserQueryTest {
    private static final BrowserQuery.SearchText SAMPLE = new BrowserQuery.SearchText(
            "Advanced Alchemy Upgrade fabricated_backpacks:advanced_alchemy_upgrade", "fabricated_backpacks", "Applies potions. Expanded matching options. Résistance to fire.");

    @ParameterizedTest
    @CsvSource({
            "advanced,true", "ALCHEMY,true", "advanced alchemy,true", "@fabricated,true", "@minecraft,false",
            "#potions,true", "#resistance,true", "#magnet,false", "alchemy -magnet,true", "alchemy -advanced,false",
            "alchemy @fabricated #fire,true", "alchemy -@minecraft,true", "alchemy -#potions,false", "'\"advanced alchemy\"',true",
            "'#\"matching options\"',true", "'\"alchemy advanced\"',false", "fabricated_backpacks:advanced,true"
    })
    void literalTermsNamespacesTooltipsAndExclusions(String query, boolean expected) {
        assertEquals(expected, BrowserQuery.parse(query).matches(SAMPLE));
    }

    @Test void registryPickerQueriesSeparateItemTypesAndBucketlessFluidNames() {
        var catalog = Map.of(
                "minecraft:cobblestone", new BrowserQuery.SearchText("Cobblestone minecraft:cobblestone", "minecraft", "Building block"),
                "minecraft:iron_ingot", new BrowserQuery.SearchText("Iron Ingot minecraft:iron_ingot", "minecraft", "Metal"),
                "minecraft:water", new BrowserQuery.SearchText("Water minecraft:water", "minecraft", "Fluid"),
                "minecraft:lava", new BrowserQuery.SearchText("Lava minecraft:lava", "minecraft", "Fluid"),
                "example:steam", new BrowserQuery.SearchText("Pressurized Steam example:steam", "example", "Fluid without a bucket"),
                "example:resin", new BrowserQuery.SearchText("Liquid Resin example:resin", "example", "Fluid without a bucket"));
        Function<String, List<String>> matches = query -> {
            BrowserQuery parsed = BrowserQuery.parse(query);
            return catalog.entrySet().stream().filter(entry -> parsed.matches(entry.getValue()))
                    .map(Map.Entry::getKey).sorted().toList();
        };
        assertEquals(List.of("minecraft:cobblestone"), matches.apply("cobble -iron"));
        assertEquals(List.of("minecraft:water"), matches.apply("minecraft:water"));
        assertEquals(List.of("minecraft:water"), matches.apply("@minecraft #fluid -lava"));
        assertEquals(List.of("example:resin"), matches.apply("@example #fluid -steam"));
        assertEquals(List.of("example:steam"), matches.apply("\"pressurized steam\""));
        assertTrue(matches.apply("water #metal").isEmpty());
    }

    @Test void blankAndWhitespaceQueriesMatchEverything() {
        assertTrue(BrowserQuery.parse("").matches(SAMPLE));
        assertTrue(BrowserQuery.parse("    ").matches(SAMPLE));
        assertTrue(BrowserQuery.parse("\"\"").matches(SAMPLE));
    }

    @Test void unfinishedQuoteIsAUsableLiteralPhrase() {
        assertTrue(BrowserQuery.parse("\"advanced alchemy").matches(SAMPLE));
    }

    @Test void inputAndTokenBudgetsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> BrowserQuery.parse("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> BrowserQuery.parse("x ".repeat(65)));
        assertDoesNotThrow(() -> BrowserQuery.parse("x".repeat(256)));
    }

    @Test void termsAreDefensivelyCopied() {
        var terms = new java.util.ArrayList<>(List.of(new BrowserQuery.Term(BrowserQuery.Field.NAME, false, "alchemy")));
        BrowserQuery query = new BrowserQuery(terms);
        terms.clear();
        assertEquals(1, query.terms().size());
        assertThrows(UnsupportedOperationException.class, () -> query.terms().clear());
    }
}
