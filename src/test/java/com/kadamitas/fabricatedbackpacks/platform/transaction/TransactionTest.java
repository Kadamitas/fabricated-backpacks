package com.kadamitas.fabricatedbackpacks.platform.transaction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {
    private static final class Value extends SnapshotJournal<Long> {
        long value; int publications; boolean failPreparation;
        void set(long next, TransactionContext tx) { updateSnapshots(tx); value = next; }
        @Override protected Long createSnapshot() { return value; }
        @Override protected void revertToSnapshot(Long value) { this.value = value; }
        @Override protected void prepareRootCommit(Long original) { if (failPreparation) throw new IllegalStateException("changed endpoint"); }
        @Override protected void onRootCommit(Long original) { publications++; }
    }
    @Test void abortRestoresFullWidthValue() {
        var value = new Value(); value.value = Long.MAX_VALUE - 4;
        try (var tx = Transaction.openRoot()) { value.set(5, tx); }
        assertEquals(Long.MAX_VALUE - 4, value.value); assertEquals(0, value.publications);
    }
    @Test void nestedCommitStillAbortsWithParent() {
        var value = new Value();
        try (var root = Transaction.openRoot()) { value.set(1, root); try (var child = Transaction.open(root)) { value.set(2, child); child.commit(); } assertEquals(2, value.value); }
        assertEquals(0, value.value); assertEquals(0, value.publications);
    }
    @Test void nestedAbortPreservesParentAndPublishesOnce() {
        var value = new Value();
        try (var root = Transaction.openRoot()) { value.set(1, root); try (var child = Transaction.open(root)) { value.set(2, child); } assertEquals(1, value.value); root.commit(); }
        assertEquals(1, value.value); assertEquals(1, value.publications);
    }
    @Test void nestedOnlyMutationParticipatesInRootAbort() {
        var value = new Value();
        try (var root = Transaction.openRoot()) { try (var child = Transaction.open(root)) { value.set(2, child); child.commit(); } }
        assertEquals(0, value.value);
    }
    @Test void failedPreparationRollsBackEveryJournal() {
        var first = new Value(); var second = new Value(); second.failPreparation = true;
        assertThrows(IllegalStateException.class, () -> { try (var tx = Transaction.openRoot()) { first.set(2, tx); second.set(3, tx); tx.commit(); } });
        assertEquals(0, first.value); assertEquals(0, second.value); assertEquals(0, first.publications);
        try (var tx = Transaction.openRoot()) { first.set(4, tx); tx.commit(); } assertEquals(4, first.value);
    }
    @Test void preventsOutOfOrderAndPostCommitMutation() {
        var value = new Value();
        try (var root = Transaction.openRoot()) { try (var child = Transaction.open(root)) { assertThrows(IllegalStateException.class, () -> value.set(1, root)); } root.commit(); assertThrows(IllegalStateException.class, () -> value.set(2, root)); }
    }
}
