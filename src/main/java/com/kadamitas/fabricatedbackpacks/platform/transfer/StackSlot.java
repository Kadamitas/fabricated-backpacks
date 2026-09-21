package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

/** One real stack location, journaled together with backpack contents and overflow. */
public final class StackSlot extends com.kadamitas.fabricatedbackpacks.platform.transaction.SnapshotJournal<ItemStack> implements SingleSlotStorage<ItemVariant> {
    private final Supplier<ItemStack> get;
    private final Consumer<ItemStack> set;
    public StackSlot(Supplier<ItemStack> get, Consumer<ItemStack> set) { this.get = get; this.set = set; }
    @Override protected ItemStack createSnapshot() { ItemStack original = get.get(); set.accept(original.copy()); return original; }
    @Override protected void revertToSnapshot(ItemStack stack) { set.accept(stack); }
    @Override protected void onRootCommit(ItemStack original) { set.accept(preserveIdentity(original, get.get())); }
    /** Same-item exchanges retain the physical handle that an open menu and its cached bag own. */
    static ItemStack preserveIdentity(ItemStack original, ItemStack updated) {
        if (original.isEmpty() || updated.isEmpty() || original.getItem() != updated.getItem()) return updated;
        for (var type : java.util.Set.copyOf(original.getComponents().keySet()))
            if (!updated.getComponents().has(type)) original.remove(type);
        original.applyComponents(updated.getComponents());
        original.setCount(updated.getCount());
        return original;
    }
    @Override public ItemVariant getResource() { return ItemVariant.of(get.get()); }
    @Override public boolean isResourceBlank() { return get.get().isEmpty(); }
    @Override public long getAmount() { return get.get().getCount(); }
    @Override public long getCapacity() { return get.get().isEmpty() ? 64 : get.get().getMaxStackSize(); }
    @Override public long insert(ItemVariant resource, long maximum, TransactionContext tx) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        var stack = get.get(); if (!stack.isEmpty() && !resource.matches(stack)) return 0;
        int amount = (int)Math.min(maximum, Math.max(0, resource.toStack().getMaxStackSize() - stack.getCount()));
        if (amount > 0) { updateSnapshots(tx); set.accept(resource.toStack(stack.getCount() + amount)); }
        return amount;
    }
    @Override public long extract(ItemVariant resource, long maximum, TransactionContext tx) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        var stack = get.get(); if (!resource.matches(stack)) return 0;
        int amount = (int)Math.min(maximum, stack.getCount());
        if (amount > 0) { updateSnapshots(tx); set.accept(stack.copyWithCount(stack.getCount() - amount)); }
        return amount;
    }
}
