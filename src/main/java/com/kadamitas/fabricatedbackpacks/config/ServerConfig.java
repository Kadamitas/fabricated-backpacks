package com.kadamitas.fabricatedbackpacks.config;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, validated server rules. Files are read at startup; geometry changes need a restart. */
public record ServerConfig(int format, Map<String, Capacity> capacities, Storage storage,
                           Capture capture, Carriers carriers, boolean chestLoot, UpgradeConfig upgrades,
                           AutomationConfig automation) {
    public static final int CURRENT_FORMAT = 2;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public ServerConfig {
        if (format != CURRENT_FORMAT) throw new IllegalArgumentException("Unsupported configuration format: " + format);
        capacities = Map.copyOf(capacities);
        Set<String> expected = java.util.Arrays.stream(BackpackTier.values()).map(BackpackTier::id).collect(java.util.stream.Collectors.toSet());
        if (!capacities.keySet().equals(expected)) throw new IllegalArgumentException("capacities must contain the six backpack item ids");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(carriers, "carriers");
        Objects.requireNonNull(upgrades, "upgrades");
        Objects.requireNonNull(automation, "automation");
    }

    /** Retains the original source API while old files inherit the added upgrade defaults. */
    public ServerConfig(int format, Map<String, Capacity> capacities, Storage storage,
                        Capture capture, Carriers carriers, boolean chestLoot) {
        this(format, capacities, storage, capture, carriers, chestLoot, UpgradeConfig.defaults(), AutomationConfig.defaults());
    }

    public ServerConfig(int format, Map<String, Capacity> capacities, Storage storage,
                        Capture capture, Carriers carriers, boolean chestLoot, UpgradeConfig upgrades) {
        this(format, capacities, storage, capture, carriers, chestLoot, upgrades, AutomationConfig.defaults());
    }

    public Capacity capacity(BackpackTier tier) { return capacities.get(tier.id()); }

    public static ServerConfig defaults() {
        Map<String, Capacity> capacities = new LinkedHashMap<>();
        for (BackpackTier tier : BackpackTier.values()) capacities.put(tier.id(), new Capacity(tier.slots(), tier.upgradeSlots()));
        return new ServerConfig(CURRENT_FORMAT, capacities, Storage.defaults(), Capture.defaults(), Carriers.defaults(), true,
                UpgradeConfig.defaults(), AutomationConfig.defaults());
    }

    public record Capacity(int slots, int upgrades) {
        public Capacity {
            range(slots, 1, 144, "inventory slots");
            range(upgrades, 0, 10, "upgrade slots");
        }
    }

    public record Storage(boolean itemFluidAccess, boolean shareWornBackpacks, boolean displayItems,
                          boolean onlyWornUpgrades, boolean outerUsesChildren, boolean childUpgrades,
                          boolean allowBagInContainerItems, boolean disallowContainerItems,
                          Set<String> disallowedItems, Set<String> blockedInteractions,
                          Set<String> blockedConnections, boolean disableConnections, boolean disableDuplicateChecks,
                          Burden burden) {
        public Storage {
            disallowedItems = rules(disallowedItems, "disallowedItems");
            blockedInteractions = rules(blockedInteractions, "blockedInteractions");
            blockedConnections = rules(blockedConnections, "blockedConnections");
            Objects.requireNonNull(burden, "burden");
        }
        public static Storage defaults() {
            return new Storage(true, true, true, false, true, true, false, false,
                    Set.of(), Set.of(), Set.of(), false, false, new Burden(false, 3, 1, "minecraft:slowness"));
        }
    }

    public record Burden(boolean enabled, int freeBackpacks, int levelsPerExtra, String effect) {
        public Burden {
            range(freeBackpacks, 0, 144, "freeBackpacks");
            range(levelsPerExtra, 1, 10, "levelsPerExtra");
            identifier(effect, "burden effect");
        }
    }

    public record Capture(int passiveLimit, int hostileLimit, boolean excludeInventories,
                          Set<String> blockedEntities, Set<String> passiveEntities, Set<String> hostileEntities) {
        public Capture {
            range(passiveLimit, 1, 120, "passive capture limit");
            range(hostileLimit, 1, 120, "hostile capture limit");
            blockedEntities = rules(blockedEntities, "blockedEntities");
            passiveEntities = rules(passiveEntities, "passiveEntities");
            hostileEntities = rules(hostileEntities, "hostileEntities");
        }
        public static Capture defaults() { return new Capture(18, 72, false, Set.of(), Set.of(), Set.of()); }
    }

    public record Colors(int body, int trim) {
        public Colors { range(body, 0, 0xffffff, "body color"); range(trim, 0, 0xffffff, "trim color"); }
    }

    public record Carriers(double spawnChance, List<Integer> tierWeights, int midMinimumTier, int highMinimumTier,
                           double midDifficulty, double highDifficulty, boolean loot, boolean effects,
                           boolean health, boolean armor, boolean enchantments, boolean music,
                           boolean fakePlayerDrops, double dropChance, double lootingBonus,
                           List<Double> dropMultipliers, double healthPerTier,
                           double musicChance, double advancedMusicChance, int maximumDiscs,
                           Set<String> blockedDiscs, Map<String, String> lootTables, Map<String, Colors> colors) {
        public Carriers {
            probability(spawnChance, "spawnChance");
            tierWeights = List.copyOf(tierWeights);
            if (tierWeights.size() != 6) throw new IllegalArgumentException("tierWeights must have six entries");
            long sum = 0;
            for (int weight : tierWeights) { range(weight, 0, 1_000_000, "tier weight"); sum += weight; }
            if (sum == 0) throw new IllegalArgumentException("At least one tier weight must be positive");
            range(midMinimumTier, 0, 5, "midMinimumTier");
            range(highMinimumTier, midMinimumTier, 5, "highMinimumTier");
            decimal(midDifficulty, 0, 100, "midDifficulty");
            decimal(highDifficulty, midDifficulty, 100, "highDifficulty");
            probability(dropChance, "dropChance"); probability(lootingBonus, "lootingBonus");
            dropMultipliers = List.copyOf(dropMultipliers);
            if (dropMultipliers.size() != 6) throw new IllegalArgumentException("dropMultipliers must have six entries");
            for (double factor : dropMultipliers) decimal(factor, 0, 64, "drop multiplier");
            decimal(healthPerTier, 0, 1024, "healthPerTier");
            probability(musicChance, "musicChance"); probability(advancedMusicChance, "advancedMusicChance");
            range(maximumDiscs, 1, 12, "maximumDiscs");
            blockedDiscs = rules(blockedDiscs, "blockedDiscs");
            lootTables = Map.copyOf(lootTables);
            lootTables.forEach((entity, table) -> { identifier(entity, "loot entity"); identifier(table, "loot table"); });
            colors = Map.copyOf(colors);
            colors.keySet().forEach(entity -> identifier(entity, "color entity"));
        }

        /** Below-minimum tiers are excluded from the weighted draw; zero eligible weight means no bag. */
        public int minimumTier(double difficulty) {
            decimal(difficulty, 0, 100, "local difficulty");
            return difficulty >= highDifficulty ? highMinimumTier : difficulty >= midDifficulty ? midMinimumTier : 0;
        }

        public double effectiveDropChance(BackpackTier tier, int lootingLevel) {
            if (lootingLevel < 0) throw new IllegalArgumentException("Negative Looting level");
            return Math.min(1, (dropChance + lootingBonus * lootingLevel) * dropMultipliers.get(tier.ordinal()));
        }

        public static Carriers defaults() {
            Map<String, String> loot = new LinkedHashMap<>();
            addLoot(loot, "desert_pyramid", "creeper", "husk");
            addLoot(loot, "shipwreck_treasure", "drowned");
            addLoot(loot, "end_city_treasure", "enderman");
            addLoot(loot, "woodland_mansion", "evoker", "vex", "vindicator");
            addLoot(loot, "bastion_bridge", "piglin"); addLoot(loot, "bastion_treasure", "piglin_brute");
            addLoot(loot, "pillager_outpost", "pillager"); addLoot(loot, "simple_dungeon", "skeleton", "zombie");
            addLoot(loot, "igloo_chest", "stray"); addLoot(loot, "buried_treasure", "witch");
            addLoot(loot, "nether_bridge", "wither_skeleton"); addLoot(loot, "village/village_armorer", "zombie_villager");
            addLoot(loot, "bastion_other", "zombified_piglin");
            Map<String, Colors> colors = new LinkedHashMap<>();
            palette(colors, 0x568C45, 0x303C2D, "creeper"); palette(colors, 0xA89462, 0x554334, "husk");
            palette(colors, 0x458E80, 0xB79C69, "drowned"); palette(colors, 0x282335, 0x9B61C2, "enderman");
            palette(colors, 0x6B7079, 0x44404A, "evoker", "vindicator", "pillager");
            palette(colors, 0xA9CBD5, 0x778B9E, "vex"); palette(colors, 0xB18063, 0x6C452F, "piglin", "piglin_brute");
            palette(colors, 0xC7C4B3, 0x666761, "skeleton"); palette(colors, 0x60824B, 0x41495D, "zombie", "zombie_villager");
            palette(colors, 0x9DAEAE, 0x455B60, "stray"); palette(colors, 0x654B80, 0x3D302E, "witch");
            palette(colors, 0x454642, 0x24251F, "wither_skeleton"); palette(colors, 0x84906A, 0xA16C5C, "zombified_piglin");
            return new Carriers(.01, List.of(625, 250, 125, 25, 5, 1), 1, 2, 2, 4,
                    true, true, true, true, true, true, false, .5, .15,
                    List.of(1d, 1.25, 1.5, 3d, 4.5, 6d), 5, .25, .25, 4,
                    Set.of("botania:record_gaia_1", "botania:record_gaia_2"), loot, colors);
        }
    }

    private static void addLoot(Map<String, String> target, String table, String... entities) {
        for (String entity : entities) target.put("minecraft:" + entity, "minecraft:chests/" + table);
    }
    private static void palette(Map<String, Colors> target, int body, int trim, String... entities) {
        for (String entity : entities) target.put("minecraft:" + entity, new Colors(body, trim));
    }
    private static Set<String> rules(Set<String> values, String field) {
        Set<String> result = Set.copyOf(values);
        result.forEach(rule -> identifier(rule.startsWith("#") ? rule.substring(1) : rule, field));
        return result;
    }
    private static void identifier(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + field + ": " + value);
    }
    private static void probability(double value, String field) { decimal(value, 0, 1, field); }
    private static void range(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be in " + minimum + ".." + maximum);
    }
    private static void decimal(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be in " + minimum + ".." + maximum);
    }
}
