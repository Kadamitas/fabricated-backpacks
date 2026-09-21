package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.function.Function;
import net.minecraft.world.level.material.Fluid;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

public final class EmptyItemFluidStorage implements SingleSlotStorage<FluidVariant> {
    private final ContainerItemContext context;
    private final ItemVariant empty;
    private final Function<ItemVariant, ItemVariant> filled;
    private final FluidVariant fluid;
    private final long perItem;
    public EmptyItemFluidStorage(ContainerItemContext context, Function<ItemVariant, ItemVariant> filled, Fluid fluid, long perItem) {
        this.context = context; this.empty = context.getItemVariant(); this.filled = filled; this.fluid = FluidVariant.of(fluid); this.perItem = perItem;
        if (perItem <= 0) throw new IllegalArgumentException("Invalid container fluid amount");
    }
    @Override public FluidVariant getResource() { return FluidVariant.blank(); }
    @Override public boolean isResourceBlank() { return true; }
    @Override public long getAmount() { return 0; }
    @Override public long getCapacity() { return context.getItemVariant().equals(empty) ? Math.multiplyExact(perItem, context.getMainSlot().getAmount()) : 0; }
    @Override public boolean supportsExtraction() { return false; }
    @Override public long extract(FluidVariant resource, long maximum, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(resource, maximum); return 0; }
    @Override public long insert(FluidVariant resource, long maximum, TransactionContext tx) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (!resource.equals(fluid) || !context.getItemVariant().equals(empty) || maximum < perItem) return 0;
        return perItem * context.exchange(filled.apply(empty), Math.min(maximum / perItem, context.getMainSlot().getAmount()), tx);
    }
}
