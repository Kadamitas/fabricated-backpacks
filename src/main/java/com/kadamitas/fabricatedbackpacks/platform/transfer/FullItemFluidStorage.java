package com.kadamitas.fabricatedbackpacks.platform.transfer;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public final class FullItemFluidStorage implements SingleSlotStorage<FluidVariant> {
    private final ContainerItemContext context;
    private final ItemVariant full, empty;
    private final FluidVariant fluid;
    private final long perItem;
    public FullItemFluidStorage(ContainerItemContext context, Item empty, FluidVariant fluid, long perItem) {
        this.context = context; this.full = context.getItemVariant(); this.empty = ItemVariant.of(empty); this.fluid = fluid; this.perItem = perItem;
        if (perItem <= 0) throw new IllegalArgumentException("Invalid container fluid amount");
    }
    @Override public FluidVariant getResource() { return getAmount() == 0 ? FluidVariant.blank() : fluid; }
    @Override public boolean isResourceBlank() { return getAmount() == 0; }
    @Override public long getAmount() { return context.getItemVariant().equals(full) ? Math.multiplyExact(perItem, context.getMainSlot().getAmount()) : 0; }
    @Override public long getCapacity() { return getAmount(); }
    @Override public boolean supportsInsertion() { return false; }
    @Override public long insert(FluidVariant resource, long maximum, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(resource, maximum); return 0; }
    @Override public long extract(FluidVariant resource, long maximum, TransactionContext tx) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (!resource.equals(fluid) || !context.getItemVariant().equals(full) || maximum < perItem) return 0;
        return perItem * context.exchange(empty, Math.min(maximum / perItem, context.getMainSlot().getAmount()), tx);
    }
}
