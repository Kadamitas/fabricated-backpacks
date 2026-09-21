package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.kadamitas.fabricatedbackpacks.platform.neoforge.LongEnergyStorage;
import com.kadamitas.fabricatedbackpacks.platform.neoforge.NativeEnergyHandler;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import com.kadamitas.fabricatedbackpacks.platform.neoforge.EnergyHandler;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

public interface EnergyStorage extends LongEnergyStorage {
    EnergyStorage EMPTY = new EnergyStorage() {
        @Override public long getAmount() { return 0; }
        @Override public long getCapacity() { return 0; }
        @Override public long insert(long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return 0; }
        @Override public long extract(long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return 0; }
        @Override public boolean supportsInsertion() { return false; }
        @Override public boolean supportsExtraction() { return false; }
    };
    BlockLookup<EnergyStorage, IEnergyStorage> SIDED = new BlockLookup<>(ForgeCapabilities.ENERGY, handler -> wrap(ForgeResourceAdapters.energy(handler)), NativeEnergyHandler::new);
    ItemLookup<EnergyStorage, IEnergyStorage> ITEM = new ItemLookup<>(ForgeCapabilities.ENERGY, handler -> wrap(ForgeResourceAdapters.energy(handler)), NativeEnergyHandler::new);
    default boolean supportsInsertion() { return true; }
    default boolean supportsExtraction() { return true; }
    static EnergyStorage wrap(EnergyHandler nativeHandler) {
        return new EnergyStorage() {
            @Override public long getAmount() { return nativeHandler.getAmountAsLong(); }
            @Override public long getCapacity() { return nativeHandler.getCapacityAsLong(); }
            @Override public long insert(long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return nativeHandler.insert((int) Math.min(Integer.MAX_VALUE, maximum), transaction); }
            @Override public long extract(long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return nativeHandler.extract((int) Math.min(Integer.MAX_VALUE, maximum), transaction); }
        };
    }
}
