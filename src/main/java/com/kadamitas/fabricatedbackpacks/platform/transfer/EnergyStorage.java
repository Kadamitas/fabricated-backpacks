package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.kadamitas.fabricatedbackpacks.platform.neoforge.LongEnergyStorage;
import com.kadamitas.fabricatedbackpacks.platform.neoforge.NativeEnergyHandler;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public interface EnergyStorage extends LongEnergyStorage {
    EnergyStorage EMPTY = new EnergyStorage() {
        @Override public long getAmount() { return 0; }
        @Override public long getCapacity() { return 0; }
        @Override public long insert(long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return 0; }
        @Override public long extract(long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return 0; }
        @Override public boolean supportsInsertion() { return false; }
        @Override public boolean supportsExtraction() { return false; }
    };
    BlockLookup<EnergyStorage, EnergyHandler> SIDED = new BlockLookup<>(Capabilities.Energy.BLOCK, EnergyStorage::wrap, NativeEnergyHandler::new);
    ItemLookup<EnergyStorage, EnergyHandler> ITEM = new ItemLookup<>(Capabilities.Energy.ITEM, EnergyStorage::wrap, NativeEnergyHandler::new);
    default boolean supportsInsertion() { return true; }
    default boolean supportsExtraction() { return true; }
    static EnergyStorage wrap(EnergyHandler nativeHandler) {
        if (nativeHandler instanceof NativeEnergyHandler exported && exported.storage() instanceof EnergyStorage origin) return origin;
        return new EnergyStorage() {
            @Override public long getAmount() { return nativeHandler.getAmountAsLong(); }
            @Override public long getCapacity() { return nativeHandler.getCapacityAsLong(); }
            @Override public long insert(long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return nativeHandler.insert((int) Math.min(Integer.MAX_VALUE, maximum), transaction); }
            @Override public long extract(long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return nativeHandler.extract((int) Math.min(Integer.MAX_VALUE, maximum), transaction); }
        };
    }
}
