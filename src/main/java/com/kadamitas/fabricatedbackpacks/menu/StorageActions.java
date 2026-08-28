package com.kadamitas.fabricatedbackpacks.menu;

import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.upgrade.InventoryMoves;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/** Bounded bulk actions on an already-authorized menu. The hotbar and every owning bag stay put. */
public final class StorageActions {
    private StorageActions() {}

    public static void memory(BagInventory bag, boolean remember) {
        if (!remember) {
            bag.stack().set(BagComponents.MEMORY, InventorySnapshot.EMPTY);
            bag.save();
            return;
        }
        var entries = new java.util.TreeMap<Integer, InventorySnapshot.Entry>();
        for (var entry : bag.stack().getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY).entries())
            if (entry.slot() < bag.getContainerSize()) entries.put(entry.slot(), entry);
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            ItemStack item = bag.getItem(slot);
            if (!bag.blocked(slot) && !item.isEmpty()) entries.put(slot, new InventorySnapshot.Entry(slot,
                    net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(item.copyWithCount(1)), 1));
        }
        bag.stack().set(BagComponents.MEMORY, new InventorySnapshot(bag.getContainerSize(), List.copyOf(entries.values())));
        bag.save();
    }

    public static void noSort(BagInventory bag, boolean select) {
        int[] slots = select ? IntStream.range(0, bag.getContainerSize()).filter(slot -> !bag.blocked(slot)).toArray() : new int[0];
        bag.updateSettings(tag -> tag.putIntArray("no_sort", slots));
    }

    public static int transfer(BackpackMenu menu, Player player, boolean toBackpack, boolean all) {
        if (!menu.stillValid(player) || !menu.getCarried().isEmpty()) return 0;
        BagInventory bag = menu.bag();
        var inventory = player.getInventory();
        // A separate 27-cell destination keeps armor, the hotbar and offhand outside bulk operations.
        var main = new SimpleContainer(27);
        for (int slot = 0; slot < 27; slot++) main.setItem(slot, inventory.getItem(slot + 9).copy());
        var originalMain = InventoryMoves.snapshot(main);
        List<ItemStack> matches = new ArrayList<>();
        if (!all) {
            for (ItemStack item : toBackpack ? bag : main) if (!item.isEmpty()) matches.add(item.copyWithCount(1));
            if (toBackpack) for (var memory : bag.stack().getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY).entries())
                matches.add(memory.create());
        }
        int moved = 0;
        if (toBackpack) {
            for (int slot = 9; slot < 36; slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item.isEmpty() || menu.locks(item) || !all && !matches(matches, item)) continue;
                ItemStack remaining = bag.insert(item, false, player);
                if (remaining.getCount() == item.getCount()) continue;
                inventory.setItem(slot, remaining);
                moved++;
            }
            for (int slot = 0; slot < bag.getContainerSize(); slot++) UpgradeEngine.onManualSlotChanged(bag, slot);
        } else {
            int[] excluded = bag.settings().getIntArray("no_sort").orElseGet(() -> new int[0]);
            for (int slot = 0; slot < bag.getContainerSize(); slot++) {
                ItemStack item = bag.getItem(slot);
                final int index = slot;
                if (item.isEmpty() || menu.locks(item) || bag.isInfiniteSlot(slot) || !bag.canTakeItem(inventory, slot, item)
                        || Arrays.stream(excluded).anyMatch(value -> value == index) || !all && !matches(matches, item)) continue;
                ItemStack remaining = InventoryMoves.insert(main, item, false);
                if (remaining.getCount() == item.getCount()) continue;
                bag.setItem(slot, remaining);
                moved++;
            }
            for (int slot = 0; slot < 27; slot++) if (!ItemStack.matches(main.getItem(slot), originalMain.get(slot)))
                inventory.setItem(slot + 9, main.getItem(slot));
        }
        menu.persist();
        menu.broadcastChanges();
        return moved;
    }

    private static boolean matches(List<ItemStack> candidates, ItemStack item) {
        return candidates.stream().anyMatch(candidate -> ItemStack.isSameItem(candidate, item));
    }
}
