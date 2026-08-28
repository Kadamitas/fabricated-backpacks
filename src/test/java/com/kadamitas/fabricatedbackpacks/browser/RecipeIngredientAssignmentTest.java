package com.kadamitas.fabricatedbackpacks.browser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RecipeIngredientAssignmentTest {
    @Test void broadIngredientsYieldTheirOnlyChoiceToSpecificIngredients() {
        var assigned = RecipeIngredientAssignment.assign(List.of(new int[] {0, 1}, new int[] {0}), new long[] {1, 1}, 1).orElseThrow();
        assertArrayEquals(new int[] {1, 0}, assigned);
    }

    @Test void repeatedCellsShareSupplyWithoutMergingDifferentComponentVariants() {
        assertArrayEquals(new int[] {0, 0}, RecipeIngredientAssignment.assign(List.of(new int[] {0}, new int[] {0}), new long[] {6}, 3).orElseThrow());
        assertTrue(RecipeIngredientAssignment.assign(List.of(new int[] {0}, new int[] {0}), new long[] {5}, 3).isEmpty());
        assertTrue(RecipeIngredientAssignment.assign(List.of(new int[] {0, 1}), new long[] {3, 3}, 4).isEmpty());
        assertTrue(RecipeIngredientAssignment.assign(List.of(new int[] {0, 1}), new long[] {3, 3}, 3).isPresent());
        assertArrayEquals(new int[] {0}, RecipeIngredientAssignment.assign(List.of(new int[] {0}), new long[] {Long.MAX_VALUE}, 64).orElseThrow());
    }

    @Test void malformedOrUnboundedGraphInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredientAssignment.assign(List.of(), new long[0], 1));
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredientAssignment.assign(List.of(new int[] {0}), new long[] {1}, 0));
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredientAssignment.assign(List.of(new int[] {0}), new long[] {1}, 65));
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredientAssignment.assign(List.of(new int[] {1}), new long[] {1}, 1));
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredientAssignment.assign(List.of(new int[] {0}), new long[] {-1}, 1));
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredientAssignment.assign(List.of(new int[] {0}), new long[4_097], 1));
        assertTrue(RecipeIngredientAssignment.assign(List.of(new int[0]), new long[] {2}, 1).isEmpty());
    }

    @Test void boundedMatchingAgreesWithExhaustiveSearchAndDoesNotMutateInputs() {
        Random random = new Random(731_492L);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            int slots = random.nextInt(1, 6), variants = random.nextInt(1, 6), sets = random.nextInt(1, 5);
            List<int[]> candidates = new ArrayList<>();
            for (int slot = 0; slot < slots; slot++) {
                var allowed = new ArrayList<Integer>();
                for (int variant = 0; variant < variants; variant++) if (random.nextBoolean()) allowed.add(variant);
                candidates.add(allowed.stream().mapToInt(Integer::intValue).toArray());
            }
            long[] quantities = random.longs(variants, 0, 16).toArray();
            long[] before = quantities.clone();
            var graphBefore = candidates.stream().map(int[]::clone).toList();
            int[] capacity = Arrays.stream(quantities).mapToInt(count -> (int) (count / sets)).toArray();
            var result = RecipeIngredientAssignment.assign(candidates, quantities, sets);
            assertEquals(exists(candidates, capacity, 0), result.isPresent(), "Assignment " + iteration);
            assertArrayEquals(before, quantities);
            for (int slot = 0; slot < slots; slot++) assertArrayEquals(graphBefore.get(slot), candidates.get(slot));
            if (result.isPresent()) {
                long[] spent = new long[variants];
                for (int slot = 0; slot < slots; slot++) {
                    int variant = result.get()[slot];
                    assertTrue(Arrays.stream(candidates.get(slot)).anyMatch(candidate -> candidate == variant));
                    spent[variant] += sets;
                }
                for (int variant = 0; variant < variants; variant++) assertTrue(spent[variant] <= quantities[variant]);
            }
        }
    }

    private static boolean exists(List<int[]> candidates, int[] capacity, int slot) {
        if (slot == candidates.size()) return true;
        for (int variant : candidates.get(slot)) {
            if (capacity[variant] == 0) continue;
            capacity[variant]--;
            boolean found = exists(candidates, capacity, slot + 1);
            capacity[variant]++;
            if (found) return true;
        }
        return false;
    }
}
