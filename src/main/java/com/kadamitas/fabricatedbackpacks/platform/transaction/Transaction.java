package com.kadamitas.fabricatedbackpacks.platform.transaction;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Thread-confined transactions with nested savepoints and exactly-once root publication. */
public final class Transaction implements TransactionContext, AutoCloseable {
    private static final ThreadLocal<Transaction> CURRENT = new ThreadLocal<>();
    private final Transaction parent;
    private final Thread owner = Thread.currentThread();
    private final Map<SnapshotJournal<?>, Object> snapshots = new IdentityHashMap<>();
    private final List<SnapshotJournal<?>> order = new ArrayList<>();
    private boolean committed, closed;

    private Transaction(Transaction parent) { this.parent = parent; CURRENT.set(this); }
    public static Transaction openRoot() {
        if (CURRENT.get() != null) throw new IllegalStateException("A root transaction is already open");
        return new Transaction(null);
    }
    public static Transaction open(TransactionContext context) {
        if (context == null) return openRoot();
        if (!(context instanceof Transaction parent)) throw new IllegalArgumentException("Unknown transaction context");
        parent.checkActive();
        return new Transaction(parent);
    }
    public static TransactionContext current() { return CURRENT.get(); }
    private void checkActive() {
        if (owner != Thread.currentThread() || closed || CURRENT.get() != this)
            throw new IllegalStateException("Transaction is closed, not innermost, or accessed from another thread");
    }
    @Override public void enlist(SnapshotJournal<?> journal) {
        checkActive();
        if (committed) throw new IllegalStateException("Transaction is already committed");
        if (!snapshots.containsKey(journal)) { snapshots.put(journal, journal.capture()); order.add(journal); }
    }
    public void commit() { checkActive(); committed = true; }
    @Override public void close() {
        checkActive();
        closed = true;
        try {
            if (!committed) rollback();
            else if (parent != null) {
                for (var journal : order) if (!parent.snapshots.containsKey(journal)) {
                    parent.snapshots.put(journal, snapshots.get(journal)); parent.order.add(journal);
                }
            } else {
                try { for (var journal : order) journal.prepare(snapshots.get(journal)); }
                catch (RuntimeException | Error failure) { rollback(); throw failure; }
                // No new transfers may start until the complete root state is published.
                for (var journal : order) journal.committed(snapshots.get(journal));
            }
        } finally { CURRENT.set(parent); }
    }
    private void rollback() {
        Throwable first = null;
        for (var journal : order.reversed()) try { journal.restore(snapshots.get(journal)); }
        catch (RuntimeException | Error failure) { if (first == null) first = failure; else first.addSuppressed(failure); }
        if (first instanceof RuntimeException failure) throw failure;
        if (first instanceof Error failure) throw failure;
    }
}
