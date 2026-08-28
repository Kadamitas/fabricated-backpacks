package com.kadamitas.fabricatedbackpacks.storage;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** A live server inventory with a component snapshot written at each committed mutation. */
class ComponentInventory extends SimpleContainer {
    protected final ItemStack owner;
    private final DataComponentType<InventorySnapshot> component;
    private final Runnable changed;
    private boolean restoring;

    ComponentInventory(ItemStack owner, DataComponentType<InventorySnapshot> component, int size, Runnable changed) {
        super(size);
        this.owner = owner;
        this.component = component;
        this.changed = changed;
        restore();
    }

    private void restore() {
        restoring = true;
        var saved = owner.getOrDefault(component, InventorySnapshot.EMPTY);
        for (var entry : saved.entries()) {
            if (entry.slot() < getContainerSize()) getItems().set(entry.slot(), entry.create());
        }
        restoring = false;
    }

    @Override public int getMaxStackSize() { return Integer.MAX_VALUE; }
    @Override public int getMaxStackSize(ItemStack stack) { return stack.getMaxStackSize(); }

    @Override public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= getContainerSize()) return;
        getItems().set(slot, stack);
        setChanged();
    }

    @Override public void setChanged() {
        super.setChanged();
        if (restoring || owner == null) return;
        owner.set(component, InventorySnapshot.capture(this));
        if (changed != null) changed.run();
    }
}
