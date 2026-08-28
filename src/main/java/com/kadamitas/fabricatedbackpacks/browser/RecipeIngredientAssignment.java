package com.kadamitas.fabricatedbackpacks.browser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Capacitated matching keeps a broad ingredient from consuming a later ingredient's only choice. */
public final class RecipeIngredientAssignment {
    public static final int MAX_SLOTS = 9;
    public static final int MAX_VARIANTS = 4_096;
    public static final int MAX_SETS = 64;

    private RecipeIngredientAssignment() {}

    /** Each input cell receives one component-identical variant, repeated {@code sets} times. */
    public static Optional<int[]> assign(List<int[]> candidates, long[] quantities, int sets) {
        if (candidates.isEmpty() || candidates.size() > MAX_SLOTS || quantities.length > MAX_VARIANTS
                || sets < 1 || sets > MAX_SETS) throw new IllegalArgumentException("Invalid recipe assignment bounds");
        List<List<Integer>> owners = new ArrayList<>(quantities.length);
        int[] capacities = new int[quantities.length];
        for (int variant = 0; variant < quantities.length; variant++) {
            if (quantities[variant] < 0) throw new IllegalArgumentException("Negative ingredient count");
            capacities[variant] = (int) Math.min(candidates.size(), quantities[variant] / sets);
            owners.add(new ArrayList<>());
        }
        for (int[] choices : candidates) for (int variant : choices) {
            if (variant < 0 || variant >= quantities.length) throw new IllegalArgumentException("Invalid ingredient variant");
        }
        int[] result = new int[candidates.size()];
        Arrays.fill(result, -1);
        for (int slot = 0; slot < candidates.size(); slot++) {
            if (!augment(slot, candidates, capacities, owners, result, new boolean[quantities.length])) return Optional.empty();
        }
        return Optional.of(result);
    }

    private static boolean augment(int slot, List<int[]> candidates, int[] capacities, List<List<Integer>> owners,
                                   int[] result, boolean[] visited) {
        for (int variant : candidates.get(slot)) {
            if (visited[variant] || capacities[variant] == 0) continue;
            visited[variant] = true;
            List<Integer> assigned = owners.get(variant);
            if (assigned.size() < capacities[variant]) {
                assigned.add(slot);
                result[slot] = variant;
                return true;
            }
            for (int index = 0; index < assigned.size(); index++) {
                if (!augment(assigned.get(index), candidates, capacities, owners, result, visited)) continue;
                assigned.set(index, slot);
                result[slot] = variant;
                return true;
            }
        }
        return false;
    }
}
