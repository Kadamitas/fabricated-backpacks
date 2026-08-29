package com.kadamitas.fabricatedbackpacks.automation.conduit;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

/** One allowance per physical endpoint, shared by its faces, network components and API callers. */
final class ConduitBudget extends SnapshotParticipant<ConduitBudget.State> {
    record State(long start, long used, long received) {}
    private long start = Long.MIN_VALUE;
    private long used;
    private long received = Long.MIN_VALUE;
    long touched;

    long available(long now, long limit, int interval) {
        if (limit < 0 || interval < 1) throw new IllegalArgumentException("Invalid conduit allowance");
        return expired(now, interval) ? limit : Math.max(0, limit - used);
    }

    boolean receivedThisTick(long now) { return received == now; }

    void charge(long now, long amount, int interval, boolean input, TransactionContext transaction) {
        if (amount < 0 || interval < 1) throw new IllegalArgumentException("Invalid conduit charge");
        if (amount == 0) return;
        updateSnapshots(transaction);
        if (expired(now, interval)) { start = now; used = 0; }
        used = Math.addExact(used, amount);
        if (input) received = now;
        touched = now;
    }

    private boolean expired(long now, int interval) {
        return start == Long.MIN_VALUE || now < start || now - start >= interval;
    }
    @Override protected State createSnapshot() { return new State(start, used, received); }
    @Override protected void readSnapshot(State previous) { start = previous.start; used = previous.used; received = previous.received; }
}
