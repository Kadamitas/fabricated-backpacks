package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

/** One journaled long-count resource slot, also useful for native integration fixtures. */
public abstract class SingleVariantStorage<T extends TransferVariant<?>> extends SnapshotParticipant<SingleVariantStorage.State<T>> implements SingleSlotStorage<T> {
    public record State<T>(T variant, long amount) {}
    public T variant = getBlankVariant();
    public long amount;
    protected abstract T getBlankVariant();
    protected abstract long getCapacity(T resource);
    @Override protected State<T> createSnapshot() { return new State<>(variant, amount); }
    @Override protected void readSnapshot(State<T> state) { variant = state.variant(); amount = state.amount(); }
    @Override public T getResource() { return variant; }
    @Override public boolean isResourceBlank() { return variant.isBlank(); }
    @Override public long getAmount() { return amount; }
    @Override public long getCapacity() { return getCapacity(variant); }
    @Override public long insert(T resource, long maximum, TransactionContext tx) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (!variant.isBlank() && !variant.equals(resource)) return 0;
        long moved = Math.min(maximum, Math.max(0, getCapacity(resource) - amount));
        if (moved > 0) { updateSnapshots(tx); variant = resource; amount += moved; }
        return moved;
    }
    @Override public long extract(T resource, long maximum, TransactionContext tx) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (!variant.equals(resource)) return 0;
        long moved = Math.min(maximum, amount);
        if (moved > 0) { updateSnapshots(tx); amount -= moved; if (amount == 0) variant = getBlankVariant(); }
        return moved;
    }
}
