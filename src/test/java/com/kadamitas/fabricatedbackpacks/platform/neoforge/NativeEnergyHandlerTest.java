package com.kadamitas.fabricatedbackpacks.platform.neoforge;

import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeEnergyHandlerTest {
    @Test void longStoredAmountsSurviveNativeIntegerTransfers() {
        Battery battery = new Battery(Long.MAX_VALUE, 6_000_000_000L);
        var nativeHandler = new NativeEnergyHandler(battery);
        assertEquals(6_000_000_000L, nativeHandler.getAmountAsLong());
        assertEquals(Long.MAX_VALUE, nativeHandler.getCapacityAsLong());
        try (var tx = Transaction.openRoot()) {
            assertEquals(Integer.MAX_VALUE, nativeHandler.extract(Integer.MAX_VALUE, tx));
            tx.commit();
        }
        assertEquals(6_000_000_000L - Integer.MAX_VALUE, battery.amount);
        assertEquals(1, battery.commits);
    }

    @Test void rootAbortRestoresCommittedNestedTransfer() {
        Battery battery = new Battery(1000, 400);
        var nativeHandler = new NativeEnergyHandler(battery);
        try (var tx = Transaction.openRoot()) {
            assertEquals(100, nativeHandler.extract(100, tx));
            assertEquals(700, nativeHandler.insert(700, tx));
        }
        assertEquals(400, battery.amount);
        assertEquals(0, battery.commits);
    }

    @Test void badDelegatesCannotCommitExcessEnergy() {
        Battery battery = new Battery(1000, 400) {
            @Override public long extract(long maximum, TransactionContext tx) {
                return super.extract(maximum + 1, tx);
            }
        };
        var nativeHandler = new NativeEnergyHandler(battery);
        try (var tx = Transaction.openRoot()) {
            assertThrows(IllegalStateException.class, () -> nativeHandler.extract(100, tx));
            tx.commit();
        }
        assertEquals(400, battery.amount);
        assertEquals(0, battery.commits);
    }

    @Test void negativeAndZeroRequestsDoNotChangeTheStore() {
        Battery battery = new Battery(1000, 400);
        var nativeHandler = new NativeEnergyHandler(battery);
        try (var tx = Transaction.openRoot()) {
            assertThrows(IllegalArgumentException.class, () -> nativeHandler.insert(-1, tx));
            assertThrows(IllegalArgumentException.class, () -> nativeHandler.extract(-1, tx));
            assertEquals(0, nativeHandler.insert(0, tx));
            assertEquals(0, nativeHandler.extract(0, tx));
            tx.commit();
        }
        assertEquals(400, battery.amount);
        assertEquals(0, battery.commits);
    }

    private static class Battery extends SnapshotJournal<Long> implements LongEnergyStorage {
        private final long capacity;
        private long amount;
        private int commits;
        Battery(long capacity, long amount) { this.capacity = capacity; this.amount = amount; }
        @Override public long getAmount() { return amount; }
        @Override public long getCapacity() { return capacity; }
        @Override public long insert(long maximum, TransactionContext tx) {
            long actual = Math.min(maximum, capacity - amount);
            if (actual > 0) { updateSnapshots(tx); amount += actual; }
            return actual;
        }
        @Override public long extract(long maximum, TransactionContext tx) {
            long actual = Math.min(maximum, amount);
            if (actual > 0) { updateSnapshots(tx); amount -= actual; }
            return actual;
        }
        @Override protected Long createSnapshot() { return amount; }
        @Override protected void revertToSnapshot(Long snapshot) { amount = snapshot; }
        @Override protected void onRootCommit(Long original) { commits++; }
    }
}
