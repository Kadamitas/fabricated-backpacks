package com.kadamitas.fabricatedbackpacks.platform.neoforge;

import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeResourceHandlerTest {
    private record TestResource(boolean isEmpty) implements Resource {}
    private static final TestResource WATER = new TestResource(false);
    private static final TestResource EMPTY = new TestResource(true);

    @Test void fractionalCapacityNeverConsumesAnUnreportedDroplet() {
        Tank tank = new Tank(100, 0);
        var handler = new NativeResourceHandler<>(tank, 81);
        try (var tx = Transaction.openRoot()) {
            assertEquals(1, handler.insert(0, WATER, 2, tx));
            assertEquals(81, tank.amount);
            tx.commit();
        }
        assertEquals(81, tank.amount);
        assertEquals(1, tank.commits);
    }

    @Test void fractionalStoredAmountSurvivesExtraction() {
        Tank tank = new Tank(1000, 200);
        var handler = new NativeResourceHandler<>(tank, 81);
        try (var tx = Transaction.openRoot()) {
            assertEquals(2, handler.extract(0, WATER, 3, tx));
            assertEquals(38, tank.amount);
            tx.commit();
        }
        assertEquals(38, tank.amount);
    }

    @Test void lessThanOneNativeUnitIsUntouched() {
        Tank tank = new Tank(80, 70);
        var handler = new NativeResourceHandler<>(tank, 81);
        try (var tx = Transaction.openRoot()) {
            assertEquals(0, handler.insert(0, WATER, 1, tx));
            assertEquals(0, handler.extract(0, WATER, 1, tx));
            tx.commit();
        }
        assertEquals(70, tank.amount);
        assertEquals(0, tank.commits);
    }

    @Test void committedNestedNativeOperationStillRollsBackOnRootAbort() {
        Tank tank = new Tank(1000, 400);
        var handler = new NativeResourceHandler<>(tank, 81);
        try (var root = Transaction.openRoot()) {
            try (var child = Transaction.open(root)) {
                assertEquals(2, handler.extract(0, WATER, 2, child));
                child.commit();
            }
            assertEquals(238, tank.amount);
        }
        assertEquals(400, tank.amount);
        assertEquals(0, tank.commits);
    }

    @Test void successfulNativeTransferIsConservedAcrossTwoHandlers() {
        Tank source = new Tank(1000, 400), destination = new Tank(100, 0);
        var from = new NativeResourceHandler<>(source, 81);
        var to = new NativeResourceHandler<>(destination, 81);
        try (var tx = Transaction.openRoot()) {
            int accepted = to.insert(0, WATER, 3, tx);
            assertEquals(accepted, from.extract(0, WATER, accepted, tx));
            tx.commit();
        }
        assertEquals(400, source.amount + destination.amount);
        assertEquals(81, destination.amount);
    }

    @Test void longCapacityIsNotTruncatedToAnInteger() {
        Tank tank = new Tank(Long.MAX_VALUE, 5_000_000_000L);
        var handler = new NativeResourceHandler<>(tank, 1);
        assertEquals(5_000_000_000L, handler.getAmountAsLong(0));
        assertEquals(Long.MAX_VALUE, handler.getCapacityAsLong(0, WATER));
        try (var tx = Transaction.openRoot()) {
            assertEquals(Integer.MAX_VALUE, handler.extract(0, WATER, Integer.MAX_VALUE, tx));
            tx.commit();
        }
        assertEquals(5_000_000_000L - Integer.MAX_VALUE, tank.amount);
    }

    @Test void invalidInputsFailBeforeMutation() {
        Tank tank = new Tank(1000, 400);
        var handler = new NativeResourceHandler<>(tank, 81);
        assertThrows(IllegalArgumentException.class, () -> new NativeResourceHandler<>(tank, 0));
        assertThrows(IllegalArgumentException.class, () -> new NativeResourceHandler<>(tank, Long.MAX_VALUE));
        try (var tx = Transaction.openRoot()) {
            assertThrows(IndexOutOfBoundsException.class, () -> handler.insert(1, WATER, 1, tx));
            assertThrows(IllegalArgumentException.class, () -> handler.insert(0, EMPTY, 1, tx));
            assertThrows(IllegalArgumentException.class, () -> handler.extract(0, WATER, -1, tx));
        }
        assertEquals(400, tank.amount);
    }

    private static final class Tank extends SnapshotJournal<Long> implements LongResourceStorage<TestResource> {
        private final long capacity;
        private long amount;
        private int commits;
        Tank(long capacity, long amount) { this.capacity = capacity; this.amount = amount; }
        @Override public int size() { return 1; }
        @Override public TestResource resource(int slot) { return amount == 0 ? EMPTY : WATER; }
        @Override public long amount(int slot) { return amount; }
        @Override public long capacity(int slot, TestResource resource) { return capacity; }
        @Override public boolean accepts(int slot, TestResource resource) { return resource.equals(WATER); }
        @Override public long insert(int slot, TestResource resource, long maximum, TransactionContext tx) {
            long actual = Math.min(maximum, capacity - amount);
            if (actual > 0) { updateSnapshots(tx); amount += actual; }
            return actual;
        }
        @Override public long extract(int slot, TestResource resource, long maximum, TransactionContext tx) {
            long actual = Math.min(maximum, amount);
            if (actual > 0) { updateSnapshots(tx); amount -= actual; }
            return actual;
        }
        @Override protected Long createSnapshot() { return amount; }
        @Override protected void revertToSnapshot(Long snapshot) { amount = snapshot; }
        @Override protected void onRootCommit(Long original) { commits++; }
    }
}
