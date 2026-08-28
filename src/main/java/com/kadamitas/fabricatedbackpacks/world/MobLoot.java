package com.kadamitas.fabricatedbackpacks.world;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Deferred loot is rolled once; an explicit overflow queue preserves every unaccepted item. */
public final class MobLoot {
    private MobLoot() { }

    public static void queue(BagInventory bag, List<ItemStack> supplied) {
        List<ItemStack> items = new ArrayList<>(bag.stack().getOrDefault(WorldComponents.EXTRA_ITEMS, InventorySnapshot.EMPTY).items());
        for (ItemStack item : supplied) if (!item.isEmpty()) items.add(item.copy());
        bag.stack().set(WorldComponents.EXTRA_ITEMS, snapshot(items));
        bag.save();
    }

    private static InventorySnapshot snapshot(List<ItemStack> items) {
        List<ItemStack> compacted = new ArrayList<>();
        for (ItemStack source : items) {
            ItemStack remaining = source.copy();
            for (ItemStack present : compacted) {
                if (!ItemStack.isSameItemSameComponents(remaining, present)) continue;
                int amount = (int)Math.min(remaining.getCount(), (long)Integer.MAX_VALUE - present.getCount());
                present.grow(amount); remaining.shrink(amount);
                if (remaining.isEmpty()) break;
            }
            if (!remaining.isEmpty()) compacted.add(remaining);
        }
        if (compacted.size() > InventorySnapshot.MAX_SLOTS) throw new IllegalArgumentException("Generated loot has too many distinct stacks to preserve");
        List<InventorySnapshot.Entry> entries = new ArrayList<>();
        for (int index = 0; index < compacted.size(); index++) {
            ItemStack item = compacted.get(index);
            entries.add(new InventorySnapshot.Entry(index, ItemStackTemplate.fromNonEmptyStack(item.copyWithCount(1)), item.getCount()));
        }
        return new InventorySnapshot(compacted.size(), entries);
    }

    /** Call before opening/using storage, with a recipient when loose overflow can be handed over. */
    public static boolean materialize(BagInventory bag, ServerLevel level, BlockPos position, ServerPlayer recipient) {
        var plan = bag.stack().get(WorldComponents.DEFERRED_LOOT);
        List<ItemStack> pending = new ArrayList<>(bag.stack().getOrDefault(WorldComponents.EXTRA_ITEMS, InventorySnapshot.EMPTY).items());
        if (plan != null) {
            var table = level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, plan.table()));
            var params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(position))
                    .withOptionalParameter(LootContextParams.THIS_ENTITY, recipient).withLuck(plan.luck()).create(LootContextParamSets.CHEST);
            for (int roll = 0; roll < plan.rolls(); roll++) pending.addAll(table.getRandomItems(params, mixedSeed(plan.seed(), roll)));
            // Validate the complete queue before consuming the plan or mutating storage.
            InventorySnapshot all = snapshot(pending);
            bag.stack().set(WorldComponents.EXTRA_ITEMS, all);
            bag.stack().remove(WorldComponents.DEFERRED_LOOT);
        }
        if (pending.isEmpty()) {
            if (plan != null) bag.save();
            return plan != null;
        }
        List<ItemStack> leftovers = new ArrayList<>();
        boolean changed = plan != null;
        for (ItemStack source : pending) {
            ItemStack remainder = bag.insert(source, false);
            changed |= remainder.getCount() != source.getCount();
            if (!remainder.isEmpty() && recipient != null && recipient.isAlive()) {
                // Player/container stacks stay within their ordinary limits even if a queue merged them.
                while (!remainder.isEmpty()) {
                    ItemStack batch = remainder.copyWithCount(Math.min(remainder.getCount(), remainder.getMaxStackSize()));
                    int offered = batch.getCount();
                    recipient.getInventory().add(batch);
                    int given = offered - batch.getCount();
                    if (!batch.isEmpty() && recipient.drop(batch.copy(), false) != null) given += batch.getCount();
                    if (given == 0) break;
                    remainder.shrink(given);
                    changed = true;
                }
            }
            if (!remainder.isEmpty()) leftovers.add(remainder);
        }
        if (leftovers.isEmpty()) bag.stack().remove(WorldComponents.EXTRA_ITEMS);
        else bag.stack().set(WorldComponents.EXTRA_ITEMS, snapshot(leftovers));
        if (changed) bag.save();
        return changed;
    }

    private static long mixedSeed(long seed, int roll) {
        long value = seed + 0x9E3779B97F4A7C15L * (roll + 1L);
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value == 0 ? 1 : value; // Vanilla reserves zero for a fresh random stream.
    }
}
