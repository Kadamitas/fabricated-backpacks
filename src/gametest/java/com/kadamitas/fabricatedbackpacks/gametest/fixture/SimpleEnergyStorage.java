package com.kadamitas.fabricatedbackpacks.gametest.fixture;

import com.kadamitas.fabricatedbackpacks.platform.transfer.EnergyStorage;
import com.kadamitas.fabricatedbackpacks.platform.transfer.SnapshotParticipant;
import com.kadamitas.fabricatedbackpacks.platform.transfer.StoragePreconditions;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Test-only native capability peer. Every mutation participates in the production transaction. */
public class SimpleEnergyStorage extends SnapshotParticipant<Long> implements EnergyStorage {
    public long amount;
    public final long capacity, maxInsert, maxExtract;
    public SimpleEnergyStorage(long capacity, long maxInsert, long maxExtract) { this.capacity = capacity; this.maxInsert = maxInsert; this.maxExtract = maxExtract; }
    @Override protected Long createSnapshot() { return amount; }
    @Override protected void readSnapshot(Long saved) { amount = saved; }
    @Override public long getAmount() { return amount; }
    @Override public long getCapacity() { return capacity; }
    @Override public boolean supportsInsertion() { return maxInsert > 0; }
    @Override public boolean supportsExtraction() { return maxExtract > 0; }
    @Override public long insert(long maximum, TransactionContext tx) { StoragePreconditions.notNegative(maximum); long moved = Math.min(Math.min(maximum, maxInsert), Math.max(0, capacity - amount)); if (moved > 0) { updateSnapshots(tx); amount += moved; } return moved; }
    @Override public long extract(long maximum, TransactionContext tx) { StoragePreconditions.notNegative(maximum); long moved = Math.min(Math.min(maximum, maxExtract), amount); if (moved > 0) { updateSnapshots(tx); amount -= moved; } return moved; }
}
