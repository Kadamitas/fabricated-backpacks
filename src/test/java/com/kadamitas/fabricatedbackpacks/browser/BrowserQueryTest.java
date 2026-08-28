package com.kadamitas.fabricatedbackpacks.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

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
