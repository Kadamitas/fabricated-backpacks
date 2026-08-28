package com.kadamitas.fabricatedbackpacks.domain;

import java.util.Arrays;
import java.util.Optional;

/** Default capacities for new backpacks. Existing inventories must not be shrunk implicitly. */
public enum BackpackTier {
    LEATHER("backpack", 27, 1),
    COPPER("copper_backpack", 45, 1),
    IRON("iron_backpack", 54, 2),
    GOLD("gold_backpack", 81, 3),
    DIAMOND("diamond_backpack", 108, 5),
    NETHERITE("netherite_backpack", 120, 7);

    private final String id;
    private final int slots;
    private final int upgradeSlots;

    BackpackTier(String id, int slots, int upgradeSlots) {
        this.id = id;
        this.slots = slots;
        this.upgradeSlots = upgradeSlots;
    }

    public String id() { return id; }
    public int slots() { return slots; }
    public int upgradeSlots() { return upgradeSlots; }
    public int columns() { return slots > 81 ? 12 : 9; }
    public int rows() { return Math.ceilDiv(slots, columns()); }

    public static Optional<BackpackTier> byId(String id) {
        return Arrays.stream(values()).filter(tier -> tier.id.equals(id)).findFirst();
    }
}
