package com.kadamitas.fabricatedbackpacks.storage;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** A live server inventory with a component snapshot written at each committed mutation. */
class ComponentInventory extends SimpleContainer {
    protected final ItemStack owner;
    private final DataComponentType<InventorySnapshot> component;
    private final Runnable changed;
    private InventorySnapshot observed;
    private boolean synchronizing;

    ComponentInventory(ItemStack owner, DataComponentType<InventorySnapshot> component, int size, Runnable changed) {
        super(size);
        this.owner = owner;
        this.component = component;
        this.changed = changed;
        refreshFromOwner();
    }

    /** Auxiliary owners may themselves be refreshed from an outer upgrade snapshot. */
    protected void beforeRefresh() {}

    protected final boolean synchronizing() { return synchronizing; }

    /**
     * Fabric can commit new components onto the same physical ItemStack. Immutable snapshot
     * identity makes ordinary reads cheap while distinguishing that exchange from our own writes.
     */
    protected final void refreshFromOwner() {
        if (synchronizing || owner == null) return;
        synchronizing = true;
        try {
            beforeRefresh();
            InventorySnapshot saved = owner.getOrDefault(component, InventorySnapshot.EMPTY);
            if (saved == observed) return;
            var loaded = new ItemStack[getContainerSize()];
            java.util.Arrays.fill(loaded, ItemStack.EMPTY);
            for (var entry : saved.entries()) {
                if (entry.slot() >= 0 && entry.slot() < loaded.length) loaded[entry.slot()] = entry.create();
            }
            var physical = super.getItems();
            for (int slot = 0; slot < loaded.length; slot++)
                physical.set(slot, retainOwnedStack(physical.get(slot), loaded[slot]));
            observed = saved;
        } finally {
            synchronizing = false;
        }
    }

    private static ItemStack retainOwnedStack(ItemStack previous, ItemStack replacement) {
        if (previous.isEmpty() || replacement.isEmpty() || !ItemStack.isSameItem(previous, replacement)) return replacement;
        if (BackpackRegistry.isBackpack(previous)) {
            String identity = previous.getOrDefault(BagComponents.IDENTITY, "");
            if (identity.isEmpty() || !identity.equals(replacement.getOrDefault(BagComponents.IDENTITY, ""))) return replacement;
        }
        if (ItemStack.matches(previous, replacement)) return previous;
        // Keep live upgrade/child owners attached, but replace components exactly, including removals.
        var components = replacement.getComponents();
        for (var type : List.copyOf(previous.getComponents().keySet()))
            if (components.get(type) == null) previous.remove(type);
        previous.applyComponents(components);
        previous.setCount(replacement.getCount());
        return previous;
    }

    @Override public ItemStack getItem(int slot) { refreshFromOwner(); return super.getItem(slot); }
    @Override public NonNullList<ItemStack> getItems() { refreshFromOwner(); return super.getItems(); }
    @Override public boolean isEmpty() { refreshFromOwner(); return super.isEmpty(); }
    @Override public ItemStack removeItem(int slot, int count) { refreshFromOwner(); return super.removeItem(slot, count); }
    @Override public ItemStack removeItemNoUpdate(int slot) { refreshFromOwner(); return super.removeItemNoUpdate(slot); }
    @Override public List<ItemStack> removeAllItems() { refreshFromOwner(); return super.removeAllItems(); }
    @Override public boolean canAddItem(ItemStack stack) { refreshFromOwner(); return super.canAddItem(stack); }
    @Override public void clearContent() { refreshFromOwner(); super.clearContent(); }
    @Override public void fillStackedContents(StackedContents contents) { refreshFromOwner(); super.fillStackedContents(contents); }
    @Override public String toString() { refreshFromOwner(); return super.toString(); }
    @Override public int getMaxStackSize() { return Integer.MAX_VALUE; }
    @Override public int getMaxStackSize(ItemStack stack) { return stack.getMaxStackSize(); }

    @Override public void setItem(int slot, ItemStack stack) {
        refreshFromOwner();
        if (slot < 0 || slot >= getContainerSize()) return;
        super.getItems().set(slot, stack);
        setChanged();
    }

    @Override public void setChanged() {
        if (synchronizing || owner == null) return;
        refreshFromOwner();
        super.setChanged();
        synchronizing = true;
        try {
            InventorySnapshot saved = InventorySnapshot.capture(this);
            // Existing views have a fixed physical slot extent. Preserve an external larger
            // snapshot's unaddressed tail when that older view next saves.
            if (observed.size() > saved.size()) {
                var entries = new ArrayList<>(saved.entries());
                for (var entry : observed.entries()) if (entry.slot() >= saved.size()) entries.add(entry);
                saved = new InventorySnapshot(observed.size(), entries);
            }
            observed = saved;
            owner.set(component, saved);
        } finally {
            synchronizing = false;
        }
        if (changed != null) changed.run();
    }
}
