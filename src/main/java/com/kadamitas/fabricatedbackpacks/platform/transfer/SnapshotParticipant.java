package com.kadamitas.fabricatedbackpacks.platform.transfer;

import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Fabric-compatible snapshot participant running inside NeoForge transactions.
 *
 * <p>NeoForge closes journals in registration order, whereas the storage code and the
 * bag aliases it opens over one shared item state rely on Fabric's last-in, first-out
 * reverts. Every participant therefore journals through the per-thread {@link TransactionLedger},
 * which records snapshots in one ordered frame per transaction depth and replays them in reverse.
 */
public abstract class SnapshotParticipant<T> {
    TransactionLedger.Frame lastFrame;

    protected abstract T createSnapshot();
    protected abstract void readSnapshot(T snapshot);
    protected void releaseSnapshot(T snapshot) {}
    protected void onFinalCommit() {}

    public final void updateSnapshots(TransactionContext transaction) {
        TransactionLedger.forThread().record(this, transaction);
    }

    final Object takeSnapshot() { return createSnapshot(); }

    @SuppressWarnings("unchecked")
    final void revert(Object snapshot) {
        readSnapshot((T) snapshot);
        releaseSnapshot((T) snapshot);
    }

    @SuppressWarnings("unchecked")
    final void release(Object snapshot) { releaseSnapshot((T) snapshot); }
}
