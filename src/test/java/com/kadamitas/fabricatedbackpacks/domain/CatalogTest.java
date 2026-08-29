package com.kadamitas.fabricatedbackpacks.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTest {
    @ParameterizedTest
    @CsvSource({
            "LEATHER,backpack,27,1,9,3", "COPPER,copper_backpack,45,1,9,5",
            "IRON,iron_backpack,54,2,9,6", "GOLD,gold_backpack,81,3,9,9",
            "DIAMOND,diamond_backpack,108,5,12,9", "NETHERITE,netherite_backpack,120,7,12,10"
    })
    void tierDefaultsAndGrid(BackpackTier tier, String id, int slots, int upgradeSlots, int columns, int rows) {
        assertAll(() -> assertEquals(id, tier.id()), () -> assertEquals(slots, tier.slots()),
                () -> assertEquals(upgradeSlots, tier.upgradeSlots()),
                () -> assertEquals(columns, tier.columns()),
                () -> assertEquals(rows, tier.rows()),
                () -> assertEquals(tier, BackpackTier.byId(id).orElseThrow()));
    }

    @Test
    void catalogContainsExactlyTheFunctionalUpgradeIds() {
        Set<String> expected = Arrays.stream("""
                pickup_upgrade advanced_pickup_upgrade filter_upgrade advanced_filter_upgrade
                magnet_upgrade advanced_magnet_upgrade feeding_upgrade advanced_feeding_upgrade
                compacting_upgrade advanced_compacting_upgrade void_upgrade advanced_void_upgrade
                restock_upgrade advanced_restock_upgrade deposit_upgrade advanced_deposit_upgrade
                refill_upgrade advanced_refill_upgrade inception_upgrade everlasting_upgrade
                smelting_upgrade auto_smelting_upgrade smoking_upgrade auto_smoking_upgrade
                blasting_upgrade auto_blasting_upgrade crafting_upgrade stonecutter_upgrade
                jukebox_upgrade advanced_jukebox_upgrade tool_swapper_upgrade advanced_tool_swapper_upgrade
                tank_upgrade battery_upgrade pump_upgrade advanced_pump_upgrade xp_pump_upgrade
                anvil_upgrade smithing_upgrade infinity_upgrade survival_infinity_upgrade
                alchemy_upgrade advanced_alchemy_upgrade mob_catcher_upgrade advanced_mob_catcher_upgrade
                stack_upgrade_starter_tier stack_upgrade_tier_1 stack_upgrade_tier_2 stack_upgrade_tier_3
                stack_upgrade_tier_4 stack_upgrade_omega_tier stack_downgrade_tier_1
                stack_downgrade_tier_2 stack_downgrade_tier_3
                """.trim().split("\\s+")).collect(Collectors.toSet());
        Set<String> actual = Arrays.stream(UpgradeKind.values()).map(UpgradeKind::id).collect(Collectors.toSet());
        assertEquals(expected, actual);
        assertEquals(actual.size(), UpgradeKind.values().length, "Duplicate item registrations");
        for (UpgradeKind kind : UpgradeKind.values()) {
            assertEquals(kind, UpgradeKind.byId(kind.id()).orElseThrow());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "PICKUP,ADVANCED_PICKUP,9,16", "FILTER,ADVANCED_FILTER,9,16",
            "MAGNET,ADVANCED_MAGNET,9,16", "FEEDING,ADVANCED_FEEDING,9,16",
            "COMPACTING,ADVANCED_COMPACTING,9,16", "VOID,ADVANCED_VOID,9,16",
            "RESTOCK,ADVANCED_RESTOCK,9,16", "DEPOSIT,ADVANCED_DEPOSIT,9,16",
            "REFILL,ADVANCED_REFILL,6,12", "TOOL_SWAPPER,ADVANCED_TOOL_SWAPPER,0,8",
            "PUMP,ADVANCED_PUMP,0,4", "ALCHEMY,ADVANCED_ALCHEMY,4,8"
    })
    void advancedVariantsHaveTheirOwnFilterCapacity(UpgradeKind basic, UpgradeKind advanced, int basicSlots, int advancedSlots) {
        assertFalse(basic.advanced());
        assertTrue(advanced.advanced());
        assertEquals(basic.family(), advanced.family());
        assertEquals(basicSlots, basic.filterSlots());
        assertEquals(advancedSlots, advanced.filterSlots());
    }

    @ParameterizedTest
    @CsvSource({
            "JUKEBOX,2", "ADVANCED_JUKEBOX,24", "CRAFTING,9", "STONECUTTER,1",
            "ANVIL,2", "SMITHING,3", "TANK,4", "BATTERY,2",
            "SMELTING,3", "AUTO_SMELTING,3", "SMOKING,3", "AUTO_SMOKING,3",
            "BLASTING,3", "AUTO_BLASTING,3"
    })
    void physicalInventoriesExcludeGhostsAndDerivedWorkstationResults(UpgradeKind kind, int slots) {
        assertEquals(slots, kind.inventorySlots());
    }

    @Test
    void limitsApplyToSharedFamiliesAndLookupDoesNotInventItems() {
        assertEquals(3, UpgradeKind.STACK_DOWNGRADE_TIER_1.slotLimit());
        assertEquals(2, UpgradeKind.TANK.slotLimit());
        assertEquals(1, UpgradeKind.ADVANCED_JUKEBOX.slotLimit());
        assertEquals(UpgradeKind.SMELTING.family(), UpgradeKind.AUTO_BLASTING.family());
        assertTrue(UpgradeKind.byId("hopper_upgrade").isEmpty());
        assertTrue(UpgradeKind.byId("stack_upgrade_tier_1_to_tier_2").isEmpty());
        assertTrue(UpgradeKind.byId("other:pickup_upgrade").isEmpty());
        assertTrue(UpgradeKind.byId(null).isEmpty());
        assertTrue(BackpackTier.byId("BACKPACK").isEmpty());
    }
}
