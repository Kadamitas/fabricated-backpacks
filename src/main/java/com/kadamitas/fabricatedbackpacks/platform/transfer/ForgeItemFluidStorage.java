package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.kadamitas.fabricatedbackpacks.platform.transaction.Transaction;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

/** Mutate a detached item copy, then exchange its complete resulting container atomically. */
final class ForgeItemFluidStorage implements SlottedStorage<FluidVariant> {
    private final ContainerItemContext context;
    private ForgeItemFluidStorage(ContainerItemContext context) { this.context = context; }
    static Storage<FluidVariant> create(ContainerItemContext context) { var storage = new ForgeItemFluidStorage(context); return storage.handler() == null ? null : storage; }
    private IFluidHandlerItem handler() {
        var stack = context.getItemVariant().toStack();
        var nativeHandler = stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null);
        if (nativeHandler != null) return nativeHandler;
        // Forge 66 leaves BucketItem.initCapabilities disabled. Use its supported bucket wrapper
        // on our detached copy while retaining the same atomic container exchange below.
        return stack.getItem() instanceof net.minecraft.world.item.BucketItem
                ? new net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper(stack) : null;
    }
    @Override public int getSlotCount() { var handler = handler(); return handler == null ? 0 : handler.getTanks(); }
    @Override public java.util.Iterator<StorageView<FluidVariant>> iterator() { return getSlots().stream().map(slot -> (StorageView<FluidVariant>)slot).iterator(); }
    @Override public SingleSlotStorage<FluidVariant> getSlot(int slot) {
        java.util.Objects.checkIndex(slot, getSlotCount());
        return new SingleSlotStorage<>() {
            @Override public FluidVariant getResource() { var handler = handler(); return handler == null ? FluidVariant.blank() : ForgeResourceAdapters.variant(handler.getFluidInTank(slot)); }
            @Override public boolean isResourceBlank() { return getResource().isBlank(); }
            @Override public long getAmount() { var handler = handler(); return handler == null ? 0 : handler.getFluidInTank(slot).getAmount() * 81L; }
            @Override public long getCapacity() { var handler = handler(); return handler == null ? 0 : handler.getTankCapacity(slot) * 81L; }
            @Override public long insert(FluidVariant resource, long maximum, TransactionContext tx) { return ForgeItemFluidStorage.this.insert(resource, maximum, tx); }
            @Override public long extract(FluidVariant resource, long maximum, TransactionContext tx) { return getResource().equals(resource) ? ForgeItemFluidStorage.this.extract(resource, maximum, tx) : 0; }
        };
    }
    private long change(FluidVariant resource, long maximum, TransactionContext parent, boolean insert) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        int requested = (int)Math.min(Integer.MAX_VALUE, maximum / 81);
        if (requested == 0) return 0;
        var handler = handler(); if (handler == null) return 0;
        var stack = ForgeResourceAdapters.fluid(resource, requested);
        int moved = insert ? handler.fill(stack, IFluidHandler.FluidAction.EXECUTE) : handler.drain(stack, IFluidHandler.FluidAction.EXECUTE).getAmount();
        if (moved == 0) return 0;
        try (var tx = Transaction.open(parent)) {
            if (context.exchange(ItemVariant.of(handler.getContainer()), 1, tx) != 1) return 0;
            tx.commit(); return moved * 81L;
        }
    }
    @Override public long insert(FluidVariant resource, long maximum, TransactionContext tx) { return change(resource, maximum, tx, true); }
    @Override public long extract(FluidVariant resource, long maximum, TransactionContext tx) { return change(resource, maximum, tx, false); }
}
