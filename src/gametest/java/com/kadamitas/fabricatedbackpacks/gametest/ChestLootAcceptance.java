package com.kadamitas.fabricatedbackpacks.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kadamitas.fabricatedbackpacks.world.ChestLoot;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The headless server enables experimental packs; real-client worlds also verify all nine default tables. */
final class ChestLootAcceptance {
    private ChestLootAcceptance() { }

    static List<String> verify(ServerLevel level, BlockPos origin, boolean requireAllVanilla) {
        var registries = level.getServer().reloadableRegistries();
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries.lookup());
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(origin)).create(LootContextParamSets.CHEST);
        List<String> evidence = new ArrayList<>();
        int builtin = 0;
        for (var entry : ChestLoot.ROLLS.entrySet()) {
            Identifier id = Identifier.withDefaultNamespace("chests/" + entry.getKey());
            var resource = level.getServer().getResourceManager().getResource(Identifier.withDefaultNamespace("loot_table/" + id.getPath() + ".json")).orElseThrow();
            var table = registries.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
            JsonObject encoded = LootTable.DIRECT_CODEC.encodeStart(ops, table).getOrThrow().getAsJsonObject();
            if (resource.getFabricPackSource() != PackSource.BUILT_IN) {
                check(!requireAllVanilla, "The default-world acceptance requires the vanilla source for " + id + "; actual=" + resource.sourcePackId());
                try (var reader = resource.openAsReader()) {
                    LootTable supplied = LootTable.DIRECT_CODEC.parse(ops, JsonParser.parseReader(reader)).getOrThrow();
                    var unmodified = LootTable.DIRECT_CODEC.encodeStart(ops, supplied).getOrThrow();
                    check(unmodified.equals(encoded), "Excluded datapack replacement must remain exactly as supplied: " + id + " from " + resource.sourcePackId());
                } catch (IOException exception) { throw new AssertionError("Could not audit the actual table source", exception); }
                evidence.add(id + ": preserved pack " + resource.sourcePackId());
                continue;
            }
            builtin++;
            check(encoded.toString().contains("fabricated_backpacks:"), "Loaded vanilla chest table includes the added pool: " + id + " = " + encoded);
            int backpackPools = 0;
            for (var value : encoded.getAsJsonArray("pools")) {
                if (!value.toString().contains("fabricated_backpacks:")) continue;
                backpackPools++;
                JsonObject pool = value.getAsJsonObject();
                check(pool.get("rolls").getAsInt() == 1, "The appended pool rolls exactly once");
                Map<String, Integer> actual = new HashMap<>();
                for (var choice : pool.getAsJsonArray("entries")) {
                    JsonObject item = choice.getAsJsonObject();
                    String itemId = item.has("name") ? item.get("name").getAsString() : "empty";
                    actual.merge(itemId, item.has("weight") ? item.get("weight").getAsInt() : 1, Integer::sum);
                }
                Map<String, Integer> expected = new HashMap<>();
                if (entry.getValue().emptyWeight() > 0) expected.put("empty", entry.getValue().emptyWeight());
                for (var choice : entry.getValue().outcomes()) expected.put("fabricated_backpacks:" + choice.item(), choice.weight());
                check(actual.equals(expected), "The actual table preserves every specified outcome weight: " + id);
            }
            check(backpackPools == 1, "Exactly one appended backpack pool is present: " + id);
            int observed = 0;
            RandomSource random = RandomSource.create(0xFABACCL);
            for (int sample = 0; sample < 256; sample++) {
                int added = table.getRandomItems(params, random).stream().filter(item -> BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace().equals("fabricated_backpacks"))
                        .mapToInt(ItemStack::getCount).sum();
                check(added <= 1, "A chest receives at most one added outcome per roll: " + id);
                if (entry.getKey().equals("spawn_bonus_chest")) check(added == 1, "Every actual bonus chest roll includes one leather backpack");
                observed += added;
            }
            check(observed > 0, "The actual vanilla table produces backpack loot: " + id);
            evidence.add(id + ": exact pool weights and 256 real rolls");
        }
        check(builtin > 0 && (!requireAllVanilla || builtin == 9), "The chest-loot acceptance exercised its required default tables");
        check(ChestLoot.ROLLS.get("abandoned_mineshaft").totalWeight() == 102, "Mineshaft values are weights, not percentages");
        return List.copyOf(evidence);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
