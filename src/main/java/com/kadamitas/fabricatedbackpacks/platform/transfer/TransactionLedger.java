package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * One NeoForge journal per thread that owns every {@link SnapshotParticipant} snapshot.
 *
 * <p>Frames are kept from the outermost to the innermost open transaction. NeoForge may move a
 * committed frame one depth up without a callback, which never changes that order, so the
 * innermost frame is always the one for the current transaction. Aborts replay a frame's
 * snapshots newest-first; a commit into a depth that already has a frame merges into it.
 */
final class TransactionLedger extends SnapshotJournal<TransactionLedger.Frame> {
    private static final ThreadLocal<TransactionLedger> THREAD = ThreadLocal.withInitial(TransactionLedger::new);

    static TransactionLedger forThread() { return THREAD.get(); }

    static final class Frame {
        final List<SnapshotParticipant<?>> participants = new ArrayList<>();
        final List<Object> snapshots = new ArrayList<>();
        boolean closed;
    }

    private final ArrayList<Frame> frames = new ArrayList<>();

    private TransactionLedger() {}

    void record(SnapshotParticipant<?> participant, TransactionContext transaction) {
        updateSnapshots(transaction);
        if (frames.isEmpty()) throw new IllegalStateException("Transaction ledger lost its frame for " + transaction);
        Frame frame = frames.getLast();
        if (participant.lastFrame == frame) return;
        frame.participants.add(participant);
        frame.snapshots.add(participant.takeSnapshot());
        participant.lastFrame = frame;
    }

    @Override protected Frame createSnapshot() {
        Frame frame = new Frame();
        frames.add(frame);
        return frame;
    }

    @Override protected void revertToSnapshot(Frame frame) {
        pop(frame);
        frame.closed = true;
        for (int index = frame.participants.size() - 1; index >= 0; index--) {
            frame.participants.get(index).revert(frame.snapshots.get(index));
        }
    }

    @Override protected void releaseSnapshot(Frame frame) {
        if (frame.closed) return;
        // A nested commit into a depth that already journaled: keep the older frame's
        // snapshots and append the newer ones so a later abort still replays newest-first.
        pop(frame);
        frame.closed = true;
        if (frames.isEmpty()) throw new IllegalStateException("A committed transaction frame has no parent frame");
        Frame parent = frames.getLast();
        parent.participants.addAll(frame.participants);
        parent.snapshots.addAll(frame.snapshots);
    }

    @Override protected void onRootCommit(Frame frame) {
        pop(frame);
        frame.closed = true;
        for (int index = frame.participants.size() - 1; index >= 0; index--) {
            frame.participants.get(index).release(frame.snapshots.get(index));
        }
        Set<SnapshotParticipant<?>> committed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SnapshotParticipant<?> participant : frame.participants) {
            if (committed.add(participant)) participant.onFinalCommit();
        }
    }

    private void pop(Frame frame) {
        if (frames.isEmpty() || frames.getLast() != frame) throw new IllegalStateException("Transaction frames closed out of order");
        frames.removeLast();
    }
}
