package com.kadamitas.fabricatedbackpacks.platform.transaction;

/** Snapshot hooks for the loader-independent, nested rollback boundary. */
public abstract class SnapshotJournal<T> {
    public final void updateSnapshots(TransactionContext transaction) { transaction.enlist(this); }
    protected abstract T createSnapshot();
    protected abstract void revertToSnapshot(T snapshot);
    protected void prepareRootCommit(T original) {}
    protected void onRootCommit(T original) {}
    final Object capture() { return createSnapshot(); }
    @SuppressWarnings("unchecked") final void restore(Object snapshot) { revertToSnapshot((T) snapshot); }
    @SuppressWarnings("unchecked") final void prepare(Object snapshot) { prepareRootCommit((T) snapshot); }
    @SuppressWarnings("unchecked") final void committed(Object snapshot) { onRootCommit((T) snapshot); }
}
