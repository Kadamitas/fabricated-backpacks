package com.kadamitas.fabricatedbackpacks.config;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @Test void defaultsRetainEveryTierAndSeventeenDistinctLootMappings() {
        ServerConfig config = ServerConfig.defaults();
        for (BackpackTier tier : BackpackTier.values()) {
            assertEquals(tier.slots(), config.capacity(tier).slots());
            assertEquals(tier.upgradeSlots(), config.capacity(tier).upgrades());
        }
        assertEquals(17, config.carriers().lootTables().size());
        assertEquals("minecraft:chests/village/village_armorer", config.carriers().lootTables().get("minecraft:zombie_villager"));
        assertEquals(List.of(625, 250, 125, 25, 5, 1), config.carriers().tierWeights());
        assertThrows(UnsupportedOperationException.class, () -> config.capacities().clear());
        assertThrows(UnsupportedOperationException.class, () -> config.carriers().lootTables().clear());
        assertThrows(UnsupportedOperationException.class, () -> config.carriers().tierWeights().set(0, 0));
    }

    @ParameterizedTest @CsvSource({"1,0", "1,10", "144,0", "144,10"})
    void capacityEndpointsAreValid(int slots, int upgrades) { assertEquals(slots, new ServerConfig.Capacity(slots, upgrades).slots()); }

    @ParameterizedTest @CsvSource({"0,1", "145,1", "27,-1", "27,11"})
    void capacityOutsideEndpointsIsRejected(int slots, int upgrades) {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.Capacity(slots, upgrades));
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"carriers\":{\"spawnChance\":-0.001}}", "{\"carriers\":{\"spawnChance\":1.001}}",
            "{\"carriers\":{\"tierWeights\":[0,0,0,0,0,0]}}", "{\"carriers\":{\"tierWeights\":[1,2]}}",
            "{\"carriers\":{\"tierWeights\":[1,0,0,0,0,-1]}}", "{\"carriers\":{\"midMinimumTier\":4,\"highMinimumTier\":2}}",
            "{\"carriers\":{\"midDifficulty\":5,\"highDifficulty\":4}}", "{\"carriers\":{\"maximumDiscs\":13}}",
            "{\"carriers\":{\"lootTables\":{\"minecraft:zombie\":\"../secret\"}}}",
            "{\"storage\":{\"disallowedItems\":[\"#not a tag\"]}}", "{\"capture\":{\"hostileLimit\":121}}",
            "{\"format\":3}"
    })
    void malformedRulesCannotPartiallyApply(String json) { assertThrows(RuntimeException.class, () -> ConfigFile.decode(json)); }

    @ParameterizedTest @ValueSource(strings = {
            "{\"conduits\":{\"itemsPerOperation\":0}}", "{\"conduits\":{\"itemsPerOperation\":65}}",
            "{\"conduits\":{\"itemIntervalTicks\":0}}", "{\"conduits\":{\"fluidMbPerTick\":-1}}",
            "{\"conduits\":{\"energyPerTick\":1000000001}}", "{\"conduits\":{\"maximumNetworkNodes\":16385}}",
            "{\"conduits\":{\"maximumEndpointVisitsPerTick\":0}}", "{\"conduits\":{\"unknownRate\":5}}",
            "{\"engine\":{\"waterCapacityMb\":0}}", "{\"engine\":{\"waterMbPerTick\":4001}}",
            "{\"engine\":{\"energyCapacity\":39}}", "{\"engine\":{\"energyPerTick\":32001}}",
            "{\"engine\":{\"energyOutputPerTick\":0}}", "{\"engine\":{\"containerTransferMbPerTick\":0}}",
            "{\"engine\":{\"energyCapacity\":1000000000001}}", "{\"engine\":null}"
    })
    void invalidAutomationCannotStartOrPartiallyReplaceRules(String settings) {
        assertThrows(RuntimeException.class, () -> ConfigFile.decode("{\"automation\":" + settings + "}"));
    }

    @Test void dropProbabilitySaturatesWithoutLosingTheLootingIncrement() {
        var rules = ServerConfig.defaults().carriers();
        assertEquals(.5, rules.effectiveDropChance(BackpackTier.LEATHER, 0));
        assertEquals(.65, rules.effectiveDropChance(BackpackTier.LEATHER, 1), 1e-12);
        assertEquals(.8125, rules.effectiveDropChance(BackpackTier.COPPER, 1), 1e-12);
        assertEquals(1, rules.effectiveDropChance(BackpackTier.GOLD, 0));
        assertEquals(1, rules.effectiveDropChance(BackpackTier.NETHERITE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> rules.effectiveDropChance(BackpackTier.LEATHER, -1));
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"filters\":{\"advanced_filter_upgrade\":{\"slots\":65}}}",
            "{\"filters\":{\"pickup_upgrade\":{\"columns\":0}}}",
            "{\"itemLimits\":{\"smelting_upgrade\":11}}", "{\"groupLimits\":{\"not_registered\":1}}",
            "{\"cooking\":{\"speed\":0.249}}", "{\"cooking\":{\"fuelEfficiency\":4.001}}",
            "{\"cooking\":{\"inputFilters\":33}}", "{\"cooking\":{\"retryMinimum\":61,\"retryMaximum\":60}}",
            "{\"compacting\":{\"extraShapes\":[{\"width\":4,\"height\":1,\"pattern\":\"1111\"}]}}",
            "{\"compacting\":{\"itemOverrides\":{\"../file\":[]}}}",
            "{\"compacting\":{\"extraShapes\":[{\"width\":2,\"height\":2,\"pattern\":\"1000\"}]}}",
            "{\"stack\":{\"baseMultiplier\":0}}", "{\"stack\":{\"excludedItems\":[\"bad identifier\"]}}",
            "{\"tank\":{\"stackRatio\":1.01}}", "{\"battery\":{\"capacityPerRow\":499}}",
            "{\"magnet\":{\"range\":33}}", "{\"feeding\":{\"hungryTicks\":0}}",
            "{\"pump\":{\"worldRange\":17}}", "{\"experience\":{\"mendingPoints\":21}}",
            "{\"alchemy\":{\"interval\":0}}", "{\"jukebox\":{\"size\":257}}", "{\"jukebox\":{\"rowWidth\":7}}"
    })
    void upgradeBoundsAreRejectedBeforeApplication(String settings) {
        assertThrows(RuntimeException.class, () -> ConfigFile.decode("{\"upgrades\":" + settings + "}"));
    }

    @Test void configuredStackAndResourceRatiosKeepExactArithmeticAndHardConflicts() {
        var rules = ConfigFile.decode("""
                {"upgrades":{"stack":{"baseMultiplier":1.5,"multipliers":{"stack_upgrade_tier_1":4}},
                  "tank":{"capacityPerRow":500,"stackRatio":0.5,"transferPerRow":25,"minimumTransfer":10},
                  "battery":{"capacityPerRow":1000,"stackRatio":0,"transferPerRow":7},
                  "itemLimits":{"tank_upgrade":10,"battery_upgrade":10}}}
                """).upgrades();
        double factor = rules.stack().multiplier(List.of(UpgradeKind.STACK_UPGRADE_TIER_1, UpgradeKind.STACK_DOWNGRADE_TIER_1));
        assertEquals(.75, factor);
        assertEquals(1312, rules.tank().capacity(3, factor));
        assertEquals(65, rules.tank().transfer(3, factor));
        assertEquals(3000, rules.battery().capacity(3, factor));
        assertEquals(21, rules.battery().transfer(3, factor));
        assertEquals(2, rules.itemLimit(UpgradeKind.TANK));
        assertEquals(1, rules.itemLimit(UpgradeKind.BATTERY));
        assertEquals(Integer.MAX_VALUE, rules.tank().capacity(12, Double.MAX_VALUE));
        assertEquals(250, rules.tank().capacity(1, Double.MIN_VALUE));
    }
}
