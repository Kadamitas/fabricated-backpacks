package com.kadamitas.fabricatedbackpacks.gameplay;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.BooleanSupplier;

/** Normal inventory clicks simulate capacity before consuming any physical source items. */
public final class BackpackStashing {
    private static BooleanSupplier clientScreenAllowed = () -> true;
    private BackpackStashing() {}
    public static void setClientScreenAllowed(BooleanSupplier allowed) { clientScreenAllowed = allowed; }
    private static boolean allowed(ItemStack bag, ClickAction action, Slot slot, Player player) {
        return bag.getCount() == 1 && action == ClickAction.PRIMARY && slot.allowModification(player)
                && (!player.level().isClientSide() || clientScreenAllowed.getAsBoolean());
    }
    private static BagInventory inventory(ItemStack stack, Player player) {
        return player.level().isClientSide() ? BagInventory.clientOf(stack) : BagInventory.of(stack);
    }
    public static boolean fromSlot(ItemStack carriedBag, Slot source, ClickAction action, Player player) {
        if (!allowed(carriedBag, action, source, player) || source.getItem().isEmpty()) return false;
        BagInventory bag = inventory(carriedBag, player);
        ItemStack offered = source.getItem();
        int accepted = offered.getCount() - UpgradeEngine.insert(bag, offered, true).getCount();
        if (accepted <= 0) return false;
        ItemStack taken = source.safeTake(accepted, accepted, player);
        ItemStack remainder = UpgradeEngine.insert(bag, taken, false);
        if (!remainder.isEmpty()) {
            remainder = source.safeInsert(remainder);
            if (!remainder.isEmpty() && !player.level().isClientSide()) player.getInventory().placeItemBackInInventory(remainder);
        }
        bag.save();
        return true;
    }
    public static boolean fromCursor(ItemStack bagStack, ItemStack carried, Slot target, ClickAction action, Player player, SlotAccess cursor) {
        if (!allowed(bagStack, action, target, player) || carried.isEmpty()) return false;
        BagInventory bag = inventory(bagStack, player);
        ItemStack predicted = UpgradeEngine.insert(bag, carried, true);
        if (predicted.getCount() == carried.getCount() || !cursor.set(predicted)) return false;
        ItemStack remainder = UpgradeEngine.insert(bag, carried, false);
        cursor.set(remainder);
        bag.save();
        target.setChanged();
        return true;
    }
}
