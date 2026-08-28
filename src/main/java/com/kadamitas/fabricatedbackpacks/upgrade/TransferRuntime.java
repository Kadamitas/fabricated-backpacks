package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class TransferRuntime {
    private TransferRuntime() { }

    /** Returns the number of source stacks from which at least one item moved. */
    public static int transfer(BagInventory bag, Container other, boolean deposit) {
        if (other == bag || (other instanceof BagInventory target && target.identity().equals(bag.identity()))) return 0;
        InstalledUpgrade upgrade = bag.installedUpgrades().stream().filter(item -> item.kind().family().equals(deposit ? "deposit" : "restock")
                && UpgradeFilters.enabled(bag, item)).findFirst().orElse(null);
        if (upgrade == null) return 0;
        Container storage = BackpackTraversal.processingInventory(bag);
        if (other instanceof BagInventory target && BackpackTraversal.inventoryBags(bag).stream()
                .anyMatch(node -> node.inventory() == target || node.inventory().identity().equals(target.identity()))) return 0;
        Container source = deposit ? storage : other;
        Container destination = deposit ? other : storage;
        int movedStacks = 0;
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            ItemStack stack = source.getItem(slot);
            if (stack.isEmpty() || !source.canTakeItem(destination, slot, stack)
                    || !UpgradeFilters.matches(bag, upgrade, stack, "", 0, bag.filterSlots(upgrade), false, deposit ? other : null)) continue;
            ItemStack remainder = deposit ? InventoryMoves.insert(destination, stack, false) : UpgradeEngine.insert(bag, stack, false);
            if (remainder.getCount() == stack.getCount()) continue;
            source.setItem(slot, remainder);
            movedStacks++;
        }
        return movedStacks;
    }

    public static void refill(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position, LivingEntity carrier) {
        var rules = BackpackConfig.get().upgrades().refill();
        if (level.getGameTime() % rules.interval() != 0) return;
        Container storage = BackpackTraversal.processingInventory(bag);
        for (ServerPlayer player : ConsumptionRuntime.players(level, position, carrier, rules.range())) {
            for (int row = 0; row < bag.filterSlots(upgrade); row++) {
                ItemStack desired = bag.ghost(upgrade, row);
                if (desired.isEmpty()) continue;
                Inventory inventory = player.getInventory();
                ItemStack cursor = player.containerMenu.getCarried();
                int cursorCount = ItemStack.isSameItemSameComponents(cursor, desired) ? cursor.getCount() : 0;
                int owned = InventoryMoves.count(inventory, desired);
                int needed = Math.max(0, desired.getMaxStackSize() - Math.min(desired.getMaxStackSize(), owned) - cursorCount);
                if (needed == 0) continue;
                String target = upgrade.kind().advanced() ? NbtAccess.getStringOr(bag.settings(upgrade), "refill_target_" + row, "ANY") : "ANY";
                int destination = targetSlot(inventory, desired, target);
                if (destination < 0) continue;
                ItemStack present = inventory.getItem(destination);
                if (!present.isEmpty() && !ItemStack.isSameItemSameComponents(present, desired)) continue;
                needed = Math.min(needed, Math.max(0, desired.getMaxStackSize() - present.getCount()));
                for (int source = 0; source < storage.getContainerSize() && needed > 0; source++) {
                    ItemStack stack = storage.getItem(source);
                    if (!ItemStack.isSameItemSameComponents(stack, desired) || !storage.canTakeItem(inventory, source, stack)) continue;
                    int moved = Math.min(needed, stack.getCount());
                    inventory.setItem(destination, stack.copyWithCount(inventory.getItem(destination).getCount() + moved));
                    storage.setItem(source, stack.copyWithCount(stack.getCount() - moved));
                    needed -= moved;
                }
            }
        }
    }

    private static int targetSlot(Inventory inventory, ItemStack desired, String target) {
        if (target.equals("MAIN_HAND")) return inventory.selected;
        if (target.equals("OFF_HAND")) return Inventory.SLOT_OFFHAND;
        if (target.startsWith("HOTBAR_")) {
            try {
                int number = Integer.parseInt(target.substring(7));
                return number >= 1 && number <= 9 ? number - 1 : -1;
            } catch (NumberFormatException invalid) { return -1; }
        }
        if (!target.equals("ANY")) return -1;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack present = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(present, desired) && present.getCount() < desired.getMaxStackSize()) return slot;
        }
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        return -1;
    }

    /** Advanced pick-block moves an owned item to the selected hand; replacement must fit before either stack changes. */
    public static boolean pickBlock(BagInventory bag, ServerPlayer player, ItemStack desired) {
        if (desired.isEmpty() || bag.installedUpgrades().stream().noneMatch(upgrade -> upgrade.kind().family().equals("refill")
                && upgrade.kind().advanced() && UpgradeFilters.enabled(bag, upgrade))) return false;
        Container storage = BackpackTraversal.processingInventory(bag, player);
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            ItemStack source = storage.getItem(slot);
            if (!ItemStack.isSameItemSameComponents(source, desired)
                    || !storage.canTakeItem(player.getInventory(), slot, source)) continue;
            if (ToolRuntime.swapToHand(bag, player, slot, Math.min(source.getCount(), source.getMaxStackSize()))) return true;
        }
        return false;
    }
}
