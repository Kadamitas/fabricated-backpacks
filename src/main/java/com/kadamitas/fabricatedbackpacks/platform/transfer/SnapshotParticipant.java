package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.kadamitas.fabricatedbackpacks.platform.transaction.SnapshotJournal;

/** The shared persistence hooks run inside NeoForge's transaction implementation. */
public abstract class SnapshotParticipant<T> extends SnapshotJournal<T> {
    protected abstract void readSnapshot(T snapshot);
    protected void onFinalCommit() {}
    @Override protected final void revertToSnapshot(T snapshot) { readSnapshot(snapshot); }
    @Override protected final void onRootCommit(T original) { onFinalCommit(); }
}
