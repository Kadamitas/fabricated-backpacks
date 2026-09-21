package com.kadamitas.fabricatedbackpacks.platform.neoforge;

import com.kadamitas.fabricatedbackpacks.platform.transaction.Transaction;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;
import net.minecraftforge.energy.IEnergyStorage;

public interface EnergyHandler extends IEnergyStorage {
    long getAmountAsLong();
    long getCapacityAsLong();
    int insert(int amount, TransactionContext tx);
    int extract(int amount, TransactionContext tx);
    @Override default int getEnergyStored() { return (int)Math.min(Integer.MAX_VALUE, getAmountAsLong()); }
    @Override default int getMaxEnergyStored() { return (int)Math.min(Integer.MAX_VALUE, getCapacityAsLong()); }
    @Override default boolean canExtract() { return true; }
    @Override default boolean canReceive() { return true; }
    @Override default int receiveEnergy(int maximum, boolean simulate) { try (var tx = Transaction.open(Transaction.current())) { int amount = insert(maximum, tx); if (!simulate) tx.commit(); return amount; } }
    @Override default int extractEnergy(int maximum, boolean simulate) { try (var tx = Transaction.open(Transaction.current())) { int amount = extract(maximum, tx); if (!simulate) tx.commit(); return amount; } }
}
