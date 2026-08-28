package com.kadamitas.fabricatedbackpacks.upgrade;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompactingShapeTest {
    @Test void countsHollowAndAsymmetricPatternsExactly() {
        assertEquals(8, new CompactingRuntime.Shape(3, 3, "111101111").ingredients());
        assertEquals(3, new CompactingRuntime.Shape(2, 2, "1110").ingredients());
        assertEquals(2, new CompactingRuntime.Shape(1, 2, "11").ingredients());
    }

    @Test void rejectsMalformedOversizedAndSingleIngredientPatterns() {
        assertThrows(IllegalArgumentException.class, () -> new CompactingRuntime.Shape(4, 1, "1111"));
        assertThrows(IllegalArgumentException.class, () -> new CompactingRuntime.Shape(2, 2, "111"));
        assertThrows(IllegalArgumentException.class, () -> new CompactingRuntime.Shape(2, 2, "1x11"));
        assertThrows(IllegalArgumentException.class, () -> new CompactingRuntime.Shape(2, 2, "1000"));
        assertThrows(IllegalArgumentException.class, () -> new CompactingRuntime.Shape(0, 0, ""));
    }
}
