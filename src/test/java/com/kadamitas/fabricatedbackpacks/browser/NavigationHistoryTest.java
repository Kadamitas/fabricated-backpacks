package com.kadamitas.fabricatedbackpacks.browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavigationHistoryTest {
    @Test void backAndForwardRetainTheCurrentView() {
        NavigationHistory<String> history = new NavigationHistory<>(64);
        history.remember("items");
        history.remember("recipe");
        assertEquals("recipe", history.back("uses").orElseThrow());
        assertEquals("items", history.back("recipe").orElseThrow());
        assertEquals("recipe", history.forward("items").orElseThrow());
        assertEquals("uses", history.forward("recipe").orElseThrow());
        assertFalse(history.canGoForward());
    }

    @Test void aNewBranchInvalidatesForwardHistory() {
        NavigationHistory<String> history = new NavigationHistory<>(64);
        history.remember("first");
        history.back("second");
        assertTrue(history.canGoForward());
        history.remember("third");
        assertFalse(history.canGoForward());
        assertEquals("third", history.back("fourth").orElseThrow());
    }

    @Test void boundedHistoryEvictsOnlyTheOldestEntry() {
        NavigationHistory<Integer> history = new NavigationHistory<>(3);
        for (int index = 0; index < 100; index++) history.remember(index);
        assertEquals(99, history.back(100).orElseThrow());
        assertEquals(98, history.back(99).orElseThrow());
        assertEquals(97, history.back(98).orElseThrow());
        assertTrue(history.back(97).isEmpty());
    }

    @Test void emptyHistoryAndInvalidInputsAreSafe() {
        NavigationHistory<String> history = new NavigationHistory<>(1);
        assertTrue(history.back("now").isEmpty());
        assertTrue(history.forward("now").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new NavigationHistory<>(0));
        assertThrows(IllegalArgumentException.class, () -> new NavigationHistory<>(1025));
        assertThrows(NullPointerException.class, () -> history.remember(null));
    }
}
