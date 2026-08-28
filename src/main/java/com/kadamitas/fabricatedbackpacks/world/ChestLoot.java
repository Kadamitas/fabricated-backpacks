package com.kadamitas.fabricatedbackpacks.world;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

import java.util.List;
import java.util.Map;

/** One extra weighted roll per supported vanilla chest; other mods' pools remain intact. */
public final class ChestLoot {
    public record Outcome(String item, int weight) {
        public Outcome {
            if (item == null || item.isBlank() || weight < 1) throw new IllegalArgumentException("Invalid loot outcome");
        }
    }
    public record Roll(int emptyWeight, List<Outcome> outcomes) {
        public Roll {
            outcomes = List.copyOf(outcomes);
            if (emptyWeight < 0 || outcomes.isEmpty()) throw new IllegalArgumentException("Invalid chest roll");
        }
        public int totalWeight() { return emptyWeight + outcomes.stream().mapToInt(Outcome::weight).sum(); }
    }
    private static Outcome outcome(String id, int weight) { return new Outcome(id, weight); }
    public static final Map<String, Roll> ROLLS = Map.of(
            "spawn_bonus_chest", new Roll(0, List.of(outcome("backpack", 100))),
            "simple_dungeon", new Roll(90, List.of(outcome("backpack", 5), outcome("copper_backpack", 3), outcome("pickup_upgrade", 2))),
            "abandoned_mineshaft", new Roll(84, List.of(outcome("backpack", 7), outcome("copper_backpack", 5), outcome("iron_backpack", 3), outcome("gold_backpack", 1), outcome("magnet_upgrade", 2))),
            "desert_pyramid", new Roll(89, List.of(outcome("copper_backpack", 5), outcome("iron_backpack", 3), outcome("gold_backpack", 1), outcome("magnet_upgrade", 2))),
            "shipwreck_treasure", new Roll(92, List.of(outcome("iron_backpack", 4), outcome("gold_backpack", 2), outcome("advanced_magnet_upgrade", 2))),
            "woodland_mansion", new Roll(92, List.of(outcome("iron_backpack", 4), outcome("gold_backpack", 2), outcome("advanced_magnet_upgrade", 2))),
            "nether_bridge", new Roll(90, List.of(outcome("iron_backpack", 5), outcome("gold_backpack", 3), outcome("feeding_upgrade", 2))),
            "bastion_treasure", new Roll(90, List.of(outcome("iron_backpack", 3), outcome("gold_backpack", 5), outcome("feeding_upgrade", 2))),
            "end_city_treasure", new Roll(90, List.of(outcome("diamond_backpack", 3), outcome("gold_backpack", 5), outcome("advanced_magnet_upgrade", 2))));

    private ChestLoot() { }
    public static void initialize() {
        LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
            if (!key.identifier().getNamespace().equals("minecraft")) return;
            String path = key.identifier().getPath();
            if (!path.startsWith("chests/")) return;
            Roll roll = ROLLS.get(path.substring("chests/".length()));
            if (roll == null) return;
            // External and experimental datapack replacements keep control of their own tables.
            if (!BackpackConfig.get().chestLoot() || !source.isBuiltin()) return;
            LootPool.Builder pool = LootPool.lootPool().setRolls(ConstantValue.exactly(1));
            if (roll.emptyWeight() > 0) pool.add(EmptyLootItem.emptyItem().setWeight(roll.emptyWeight()));
            for (Outcome outcome : roll.outcomes()) pool.add(LootItem.lootTableItem(item(outcome.item())).setWeight(outcome.weight()));
            builder.withPool(pool);
        });
    }
    private static Item item(String id) {
        return BackpackTier.byId(id).map(BackpackRegistry::item)
                .orElseGet(() -> BackpackRegistry.item(UpgradeKind.byId(id).orElseThrow()));
    }
}
