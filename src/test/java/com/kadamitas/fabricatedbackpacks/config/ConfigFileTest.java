package com.kadamitas.fabricatedbackpacks.config;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFileTest {
    @TempDir Path directory;

    @Test void createAndRoundTripAnEntireFileIncludingEmptyMapsAndZeroUpgradeSlots() throws Exception {
        Path file = directory.resolve("nested/config.json");
        assertEquals(ServerConfig.defaults(), ConfigFile.loadOrCreate(file));
        assertEquals(ServerConfig.defaults(), ConfigFile.decode(Files.readString(file)));
        ServerConfig changed = ConfigFile.decode("""
                {"capacities":{"backpack":{"slots":1,"upgrades":0}},
                 "carriers":{"spawnChance":0,"lootTables":{},"colors":{}},
                 "storage":{"blockedConnections":["#minecraft:logs","minecraft:chest"]}}
                """);
        assertEquals(1, changed.capacity(BackpackTier.LEATHER).slots());
        assertEquals(0, changed.capacity(BackpackTier.LEATHER).upgrades());
        assertEquals(120, changed.capacity(BackpackTier.NETHERITE).slots());
        assertTrue(changed.carriers().lootTables().isEmpty());
        assertEquals(changed, ConfigFile.decode(ConfigFile.encode(changed)));
    }

    @Test void aBadFileIsNeverRewrittenAsDefaults() throws Exception {
        Path file = directory.resolve("broken.json");
        String malformed = "{\"capacities\":{\"backpack\":{\"slots\":999}}}";
        Files.writeString(file, malformed);
        assertThrows(RuntimeException.class, () -> ConfigFile.loadOrCreate(file));
        assertEquals(malformed, Files.readString(file));
    }

    @Test void upgradePartialOverridesRetainOtherFamiliesAndRoundTripCustomShapes() {
        ServerConfig changed = ConfigFile.decode("""
                {"upgrades":{"filters":{"advanced_pickup_upgrade":{"slots":64,"columns":6}},
                  "groupLimits":{"cooking":2},"itemLimits":{"smelting_upgrade":0},
                  "cooking":{"speed":0.25,"fuelEfficiency":4,"inputFilters":32,"fuelFilters":32},
                  "compacting":{"extraShapes":[],"itemOverrides":{"minecraft:clay_ball":[{"width":2,"height":2,"pattern":"1110"}]}},
                  "jukebox":{"size":16,"rowWidth":6},"allowAlwaysVoid":false}}
                """);
        var rules = changed.upgrades();
        assertEquals(64, rules.filterSlots(UpgradeKind.ADVANCED_PICKUP));
        assertEquals(9, rules.filterSlots(UpgradeKind.PICKUP));
        assertEquals(64, rules.filterSlots(UpgradeKind.AUTO_SMELTING));
        assertEquals(1, rules.inventorySlots(UpgradeKind.JUKEBOX));
        assertEquals(16, rules.inventorySlots(UpgradeKind.ADVANCED_JUKEBOX));
        assertEquals(0, rules.itemLimit(UpgradeKind.SMELTING));
        assertEquals(2, rules.groupLimit(UpgradeKind.SMOKING));
        assertEquals("1110", rules.compacting().itemOverrides().get("minecraft:clay_ball").getFirst().pattern());
        assertTrue(rules.compacting().extraShapes().isEmpty());
        assertFalse(rules.allowAlwaysVoid());
        assertEquals(changed, ConfigFile.decode(ConfigFile.encode(changed)));
        assertThrows(UnsupportedOperationException.class, () -> rules.compacting().itemOverrides().get("minecraft:clay_ball").clear());
        assertThrows(UnsupportedOperationException.class, () -> rules.filters().clear());
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "null", "{\"typo\":true}", "{\"storage\":{\"typo\":true}}",
            "{\"storage\":null}", "{\"capacities\":{\"missing_backpack\":{\"slots\":3,\"upgrades\":1}}}"})
    void unknownAndNullFieldsAreReportedRatherThanSilentlyIgnored(String text) {
        assertThrows(RuntimeException.class, () -> ConfigFile.decode(text));
    }

    @Test void oversizedConfigIsRejectedBeforeParsing() {
        assertThrows(IllegalArgumentException.class, () -> ConfigFile.decode(" ".repeat(1_048_577)));
    }

    @Test void automationPartialOverridesRetainExistingRulesAndExactLongCapacities() {
        ServerConfig legacy = ConfigFile.decode("{\"upgrades\":{\"allowAlwaysVoid\":false}}");
        assertEquals(AutomationConfig.defaults(), legacy.automation());
        ServerConfig changed = ConfigFile.decode("""
                {"automation":{"engine":{"energyCapacity":1000000000000,"energyOutputPerTick":123456789},
                  "conduits":{"itemsPerOperation":3,"maximumEndpointVisitsPerTick":1}}}
                """);
        assertEquals(1_000_000_000_000L, changed.automation().engine().energyCapacity());
        assertEquals(123_456_789, changed.automation().engine().energyOutputPerTick());
        assertEquals(3, changed.automation().conduits().itemsPerOperation());
        assertEquals(AutomationConfig.Engine.defaults().waterCapacityMb(), changed.automation().engine().waterCapacityMb());
        assertEquals(ServerConfig.defaults().upgrades(), changed.upgrades());
        assertEquals(changed, ConfigFile.decode(ConfigFile.encode(changed)));
    }
}
