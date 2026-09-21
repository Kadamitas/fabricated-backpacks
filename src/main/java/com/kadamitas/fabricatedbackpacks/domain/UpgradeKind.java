package com.kadamitas.fabricatedbackpacks.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Registered upgrade defaults; conversion ingredients are deliberately not functional upgrades. */
public enum UpgradeKind {
    PICKUP("pickup_upgrade", "pickup", false, 9, 0),
    ADVANCED_PICKUP("advanced_pickup_upgrade", "pickup", true, 16, 0),
    FILTER("filter_upgrade", "filter", false, 9, 0),
    ADVANCED_FILTER("advanced_filter_upgrade", "filter", true, 16, 0),
    MAGNET("magnet_upgrade", "magnet", false, 9, 0),
    ADVANCED_MAGNET("advanced_magnet_upgrade", "magnet", true, 16, 0),
    FEEDING("feeding_upgrade", "feeding", false, 9, 0),
    ADVANCED_FEEDING("advanced_feeding_upgrade", "feeding", true, 16, 0),
    COMPACTING("compacting_upgrade", "compacting", false, 9, 0),
    ADVANCED_COMPACTING("advanced_compacting_upgrade", "compacting", true, 16, 0),
    VOID("void_upgrade", "void", false, 9, 0),
    ADVANCED_VOID("advanced_void_upgrade", "void", true, 16, 0),
    RESTOCK("restock_upgrade", "restock", false, 9, 0),
    ADVANCED_RESTOCK("advanced_restock_upgrade", "restock", true, 16, 0),
    DEPOSIT("deposit_upgrade", "deposit", false, 9, 0),
    ADVANCED_DEPOSIT("advanced_deposit_upgrade", "deposit", true, 16, 0),
    REFILL("refill_upgrade", "refill", false, 6, 0),
    ADVANCED_REFILL("advanced_refill_upgrade", "refill", true, 12, 0),
    INCEPTION("inception_upgrade", "inception", false, 0, 0),
    EVERLASTING("everlasting_upgrade", "everlasting", false, 0, 0),
    SMELTING("smelting_upgrade", "cooking", false, 0, 3),
    AUTO_SMELTING("auto_smelting_upgrade", "cooking", false, 12, 3),
    SMOKING("smoking_upgrade", "cooking", false, 0, 3),
    AUTO_SMOKING("auto_smoking_upgrade", "cooking", false, 12, 3),
    BLASTING("blasting_upgrade", "cooking", false, 0, 3),
    AUTO_BLASTING("auto_blasting_upgrade", "cooking", false, 12, 3),
    CRAFTING("crafting_upgrade", "crafting", false, 0, 9),
    STONECUTTER("stonecutter_upgrade", "stonecutter", false, 0, 1),
    JUKEBOX("jukebox_upgrade", "jukebox", false, 0, 2),
    ADVANCED_JUKEBOX("advanced_jukebox_upgrade", "jukebox", true, 0, 24),
    TOOL_SWAPPER("tool_swapper_upgrade", "tool_swapper", false, 0, 0),
    ADVANCED_TOOL_SWAPPER("advanced_tool_swapper_upgrade", "tool_swapper", true, 8, 0),
    TANK("tank_upgrade", "tank", false, 0, 4, 2, 1),
    BATTERY("battery_upgrade", "battery", false, 0, 2),
    PUMP("pump_upgrade", "pump", false, 0, 0),
    ADVANCED_PUMP("advanced_pump_upgrade", "pump", true, 4, 0),
    XP_PUMP("xp_pump_upgrade", "xp_pump", false, 0, 0),
    ANVIL("anvil_upgrade", "anvil", false, 0, 2),
    SMITHING("smithing_upgrade", "smithing", false, 0, 3),
    INFINITY("infinity_upgrade", "infinity", false, 0, 0),
    SURVIVAL_INFINITY("survival_infinity_upgrade", "infinity", false, 0, 0),
    ALCHEMY("alchemy_upgrade", "alchemy", false, 4, 0),
    ADVANCED_ALCHEMY("advanced_alchemy_upgrade", "alchemy", true, 8, 0),
    MOB_CATCHER("mob_catcher_upgrade", "mob_catcher", false, 0, 0),
    ADVANCED_MOB_CATCHER("advanced_mob_catcher_upgrade", "mob_catcher", true, 0, 0),
    STACK_UPGRADE_STARTER_TIER("stack_upgrade_starter_tier", 1.5),
    STACK_UPGRADE_TIER_1("stack_upgrade_tier_1", 2),
    STACK_UPGRADE_TIER_2("stack_upgrade_tier_2", 4),
    STACK_UPGRADE_TIER_3("stack_upgrade_tier_3", 8),
    STACK_UPGRADE_TIER_4("stack_upgrade_tier_4", 16),
    STACK_DOWNGRADE_TIER_1("stack_downgrade_tier_1", 1.0 / 8),
    STACK_DOWNGRADE_TIER_2("stack_downgrade_tier_2", 1.0 / 16),
    STACK_DOWNGRADE_TIER_3("stack_downgrade_tier_3", 1.0 / 32),
    STACK_UPGRADE_OMEGA_TIER("stack_upgrade_omega_tier", Integer.MAX_VALUE);

    private static final Map<String, UpgradeKind> BY_ID;

    static {
        Map<String, UpgradeKind> byId = new HashMap<>();
        for (UpgradeKind kind : values()) {
            if (byId.put(kind.id, kind) != null) {
                throw new IllegalStateException("Duplicate upgrade identifier: " + kind.id);
            }
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private final String id;
    private final String family;
    private final boolean advanced;
    private final int filterSlots;
    private final int inventorySlots;
    private final int slotLimit;
    private final double stackMultiplier;

    UpgradeKind(String id, String family, boolean advanced, int filterSlots, int inventorySlots) {
        this(id, family, advanced, filterSlots, inventorySlots, 1, 1);
    }

    UpgradeKind(String id, double stackMultiplier) {
        this(id, "stack", false, 0, 0, 3, stackMultiplier);
    }

    UpgradeKind(String id, String family, boolean advanced, int filterSlots, int inventorySlots,
                int slotLimit, double stackMultiplier) {
        this.id = id;
        this.family = family;
        this.advanced = advanced;
        this.filterSlots = filterSlots;
        this.inventorySlots = inventorySlots;
        this.slotLimit = slotLimit;
        this.stackMultiplier = stackMultiplier;
    }

    public String id() { return id; }
    public String family() { return family; }
    public boolean advanced() { return advanced; }
    public int filterSlots() { return filterSlots; }
    public int inventorySlots() { return inventorySlots; }
    /** Default maximum number in the same family, not the physical inventory size. */
    public int slotLimit() { return slotLimit; }
    public double stackMultiplier() { return stackMultiplier; }

    /** Resolves a path identifier, without guessing another namespace or accepting conversions. */
    public static Optional<UpgradeKind> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
