package com.kadamitas.fabricatedbackpacks.platform.neoforge;

import java.util.Objects;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Native capability boundary that keeps stored energy at full 64-bit precision. */
public final class NativeEnergyHandler implements EnergyHandler {
    private final LongEnergyStorage storage;
    public NativeEnergyHandler(LongEnergyStorage storage) { this.storage = Objects.requireNonNull(storage); }
    public LongEnergyStorage storage() { return storage; }
    @Override public long getAmountAsLong() { return nonNegative(storage.getAmount()); }
    @Override public long getCapacityAsLong() { return nonNegative(storage.getCapacity()); }
    @Override public int insert(int amount, TransactionContext transaction) { return transfer(amount, transaction, true); }
    @Override public int extract(int amount, TransactionContext transaction) { return transfer(amount, transaction, false); }
    private int transfer(int amount, TransactionContext transaction, boolean insertion) {
        TransferPreconditions.checkNonNegative(amount);
        Objects.requireNonNull(transaction);
        if (amount == 0) return 0;
        try (var nested = Transaction.open(transaction)) {
            long moved = insertion ? storage.insert(amount, nested) : storage.extract(amount, nested);
            if (moved < 0 || moved > amount) throw new IllegalStateException("Storage violated its energy transfer bounds");
            nested.commit();
            return Math.toIntExact(moved);
        }
    }
    private static long nonNegative(long amount) {
        if (amount < 0) throw new IllegalStateException("Negative stored energy amount");
        return amount;
    }
}
