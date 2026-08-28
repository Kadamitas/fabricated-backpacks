package com.kadamitas.fabricatedbackpacks.menu;

import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class EquipmentMenu extends AbstractContainerMenu {
    private final Player player;
    private final SimpleContainer equipment;
    public EquipmentMenu(int id, Inventory inventory) {
        super(BackpackMenus.EQUIPMENT, id);
        player = inventory.player;
        equipment = new SimpleContainer(1) {
            @Override public ItemStack getItem(int slot) {
                return slot != 0 ? ItemStack.EMPTY : player.level().isClientSide ? super.getItem(0) : BackpackEquipment.get(player);
            }
            @Override public void setItem(int slot, ItemStack stack) {
                if (slot != 0) return;
                if (player.level().isClientSide) super.setItem(0, stack);
                else BackpackEquipment.set(player, stack);
            }
            @Override public ItemStack removeItem(int slot, int amount) {
                if (player.level().isClientSide) return super.removeItem(slot, amount);
                if (slot != 0 || amount <= 0) return ItemStack.EMPTY;
                ItemStack result = getItem(0).split(amount);
                setChanged();
                return result;
            }
            @Override public ItemStack removeItemNoUpdate(int slot) {
                if (player.level().isClientSide) return super.removeItemNoUpdate(slot);
                if (slot != 0) return ItemStack.EMPTY;
                ItemStack result = getItem(0);
                BackpackEquipment.set(player, ItemStack.EMPTY);
                return result;
            }
            @Override public boolean isEmpty() { return getItem(0).isEmpty(); }
            @Override public void clearContent() { setItem(0, ItemStack.EMPTY); }
            @Override public void setChanged() {
                super.setChanged();
                if (!player.level().isClientSide) BackpackEquipment.set(player, getItem(0));
            }
        };
        if (player.level().isClientSide) equipment.setItem(0, BackpackEquipment.get(player).copy());
        addSlot(new Slot(equipment, 0, 80, 35) {
            @Override public boolean mayPlace(ItemStack stack) { return BackpackRegistry.isBackpack(stack); }
            @Override public int getMaxStackSize() { return 1; }
        });
        MenuSlots.addInventory(this::addSlot, inventory, 8, 84);
    }
    @Override public boolean stillValid(Player viewer) { return viewer == player && player.isAlive(); }
    @Override public ItemStack quickMoveStack(Player viewer, int index) {
        if (!stillValid(viewer) || index < 0 || index >= slots.size() || !slots.get(index).isActive()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack original = slot.getItem().copy();
        boolean moved = index == 0 ? moveItemStackTo(slot.getItem(), 1, slots.size(), true)
                : BackpackRegistry.isBackpack(slot.getItem()) && moveItemStackTo(slot.getItem(), 0, 1, false);
        if (!moved) return ItemStack.EMPTY;
        if (slot.getItem().isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }
}
