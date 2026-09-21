package com.kadamitas.fabricatedbackpacks.platform.neoforge;

import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

/** Internal 64-bit energy amounts, mutated through the same native transaction as external capabilities. */
public interface LongEnergyStorage {
    long getAmount();
    long getCapacity();
    long insert(long maximum, TransactionContext transaction);
    long extract(long maximum, TransactionContext transaction);
}
