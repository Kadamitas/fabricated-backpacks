package com.kadamitas.fabricatedbackpacks.platform.neoforge;

import java.util.Objects;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Exposes the mod's long-count inventories through NeoForge's native capability API.
 * Items use a unit scale of one. Fluids use 81 stored droplets per native millibucket,
 * preserving old save data's sub-millibucket remainder without duplicating or losing it.
 */
public final class NativeResourceHandler<R extends Resource> implements ResourceHandler<R> {
    public static final long DROPLETS_PER_MILLIBUCKET = 81;
    private final LongResourceStorage<R> storage;
    private final long units;
    private final Object origin;

    public NativeResourceHandler(LongResourceStorage<R> storage, long internalUnitsPerNativeUnit) {
        this(storage, internalUnitsPerNativeUnit, null);
    }

    /** @param origin the mod-side storage this handler exports, returned unchanged to the mod's own lookups */
    public NativeResourceHandler(LongResourceStorage<R> storage, long internalUnitsPerNativeUnit, Object origin) {
        this.storage = Objects.requireNonNull(storage);
        this.origin = origin;
        if (internalUnitsPerNativeUnit < 1 || internalUnitsPerNativeUnit > Long.MAX_VALUE / Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported native resource unit scale");
        }
        units = internalUnitsPerNativeUnit;
    }

    public Object origin() { return origin; }
    @Override public int size() { return storage.size(); }
    @Override public R getResource(int index) { checkIndex(index); return storage.resource(index); }
    @Override public long getAmountAsLong(int index) { checkIndex(index); return nonNegative(storage.amount(index)) / units; }
    @Override public long getCapacityAsLong(int index, R resource) {
        checkIndex(index);
        return resource.isEmpty() || storage.accepts(index, resource) ? nonNegative(storage.capacity(index, resource)) / units : 0;
    }
    @Override public boolean isValid(int index, R resource) {
        checkIndex(index);
        return !resource.isEmpty() && storage.accepts(index, resource);
    }
    @Override public int insert(int index, R resource, int amount, TransactionContext transaction) {
        checkIndex(index);
        return transfer(index, resource, amount, transaction, true);
    }
    @Override public int extract(int index, R resource, int amount, TransactionContext transaction) {
        checkIndex(index);
        return transfer(index, resource, amount, transaction, false);
    }
    @Override public int insert(R resource, int amount, TransactionContext transaction) { return transfer(-1, resource, amount, transaction, true); }
    @Override public int extract(R resource, int amount, TransactionContext transaction) { return transfer(-1, resource, amount, transaction, false); }

    private int transfer(int index, R resource, int amount, TransactionContext transaction, boolean insertion) {
        if (index != -1) checkIndex(index);
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        Objects.requireNonNull(transaction);
        if (amount == 0 || insertion && index != -1 && !storage.accepts(index, resource)) return 0;
        long requested = amount * units;
        long transferred;
        try (Transaction probe = Transaction.open(transaction)) {
            transferred = change(index, resource, requested, probe, insertion);
            validateTransfer(transferred, requested);
            if (transferred % units == 0) {
                probe.commit();
                return Math.toIntExact(transferred / units);
            }
        }
        // A tank's remaining capacity may not be a whole native unit. Roll back
        // the fractional attempt before trying the largest representable amount.
        long whole = transferred - transferred % units;
        if (whole == 0) return 0;
        try (Transaction exact = Transaction.open(transaction)) {
            long actual = change(index, resource, whole, exact, insertion);
            validateTransfer(actual, whole);
            if (actual != whole) return 0;
            exact.commit();
            return Math.toIntExact(actual / units);
        }
    }

    private long change(int index, R resource, long amount, TransactionContext transaction, boolean insertion) {
        if (index == -1) return insertion ? storage.insert(resource, amount, transaction) : storage.extract(resource, amount, transaction);
        return insertion ? storage.insert(index, resource, amount, transaction) : storage.extract(index, resource, amount, transaction);
    }
    private void checkIndex(int index) { Objects.checkIndex(index, storage.size()); }
    private static long nonNegative(long amount) {
        if (amount < 0) throw new IllegalStateException("Negative stored resource amount");
        return amount;
    }
    private static void validateTransfer(long actual, long requested) {
        if (actual < 0 || actual > requested) throw new IllegalStateException("Storage violated its transfer bounds");
    }
}
