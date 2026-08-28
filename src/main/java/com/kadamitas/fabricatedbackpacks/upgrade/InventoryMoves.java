package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Copy-first inventory operations; callers commit only complete recipe plans. */
public final class InventoryMoves {
    private InventoryMoves() { }

    public static List<ItemStack> snapshot(Container inventory) {
        List<ItemStack> result = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) result.add(inventory.getItem(slot).copy());
        return result;
    }

    public static int limit(Container inventory, ItemStack stack) {
        return inventory instanceof BagInventory bag ? bag.capacity(stack) : inventory.getMaxStackSize(stack);
    }

    public static int limit(Container inventory, int slot, ItemStack stack) {
        return inventory instanceof BackpackTraversal.ProcessingInventory view ? view.capacity(slot, stack) : limit(inventory, stack);
    }

    /** Never changes the input stack, including during simulation. */
    public static ItemStack insert(Container inventory, ItemStack incoming, boolean simulate) {
        List<ItemStack> slots = snapshot(inventory);
        ItemStack remainder = insertIntoPlan(inventory, slots, incoming, false);
        if (!simulate && remainder.getCount() != incoming.getCount()) commit(inventory, slots);
        return remainder;
    }

    public static ItemStack insertIntoPlan(Container inventory, List<ItemStack> slots, ItemStack incoming,
                                          boolean existingOnly) {
        ItemStack remaining = incoming.copy();
        if (remaining.isEmpty()) return ItemStack.EMPTY;
        int end = inventory instanceof net.minecraft.world.entity.player.Inventory
                ? Math.min(slots.size(), net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE) : slots.size();
        for (int begin = 0; begin < end && !remaining.isEmpty();) {
            int boundary = end;
            if (inventory instanceof BackpackTraversal.ProcessingInventory view) {
                var owner = view.node(begin);
                boundary = begin + 1;
                while (boundary < end && view.node(boundary) == owner) boundary++;
            }
            // Finish both merge/empty passes within a physical bag before the next
            // one: an existing outer stack cannot override an explicit child-first preference.
            for (int pass = 0; pass < (existingOnly ? 1 : 2); pass++) {
                for (int slot = begin; slot < boundary && !remaining.isEmpty(); slot++) {
                    ItemStack current = slots.get(slot);
                    if (!inventory.canPlaceItem(slot, remaining)) continue;
                    if (pass == 0 && (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, remaining))) continue;
                    if (pass == 1 && !current.isEmpty()) continue;
                    int room = Math.max(0, limit(inventory, slot, remaining) - current.getCount());
                    int moved = Math.min(room, remaining.getCount());
                    if (moved == 0) continue;
                    slots.set(slot, remaining.copyWithCount(current.getCount() + moved));
                    remaining.shrink(moved);
                }
            }
            begin = boundary;
        }
        return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
    }

    public static boolean removeExact(List<ItemStack> slots, ItemStack resource, int count) {
        return removeExact(null, slots, resource, count);
    }

    public static boolean removeExact(Container inventory, List<ItemStack> slots, ItemStack resource, int count) {
        if (count < 0) throw new IllegalArgumentException("Negative extraction");
        long available = 0;
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack stack = slots.get(slot);
            if (ItemStack.isSameItemSameComponents(stack, resource)
                    && (inventory == null || inventory.canTakeItem(null, slot, stack))) available += stack.getCount();
        }
        if (available < count) return false;
        int needed = count;
        for (int slot = 0; slot < slots.size() && needed > 0; slot++) {
            ItemStack stack = slots.get(slot);
            if (!ItemStack.isSameItemSameComponents(stack, resource)
                    || inventory != null && !inventory.canTakeItem(null, slot, stack)) continue;
            int removed = Math.min(needed, stack.getCount());
            slots.set(slot, stack.copyWithCount(stack.getCount() - removed));
            needed -= removed;
        }
        return true;
    }

    public static void commit(Container inventory, List<ItemStack> slots) {
        if (slots.size() != inventory.getContainerSize()) throw new IllegalArgumentException("Wrong snapshot size");
        if (inventory instanceof BackpackTraversal.ProcessingInventory view && !view.attached()) throw new IllegalStateException("Nested inventory changed during planning");
        for (int slot = 0; slot < slots.size(); slot++) {
            if (!ItemStack.matches(inventory.getItem(slot), slots.get(slot))) inventory.setItem(slot, slots.get(slot).copy());
        }
        inventory.setChanged();
    }

    public static int count(Container inventory, ItemStack exemplar) {
        long total = 0;
        for (ItemStack item : inventory) if (ItemStack.isSameItemSameComponents(item, exemplar)) total += item.getCount();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}
