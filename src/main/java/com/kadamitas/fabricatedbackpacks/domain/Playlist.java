package com.kadamitas.fabricatedbackpacks.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/** Runtime playlist state. Persist discs/preferences; restore playback with {@link #stopped}. */
public record Playlist(int slotCount, List<Integer> occupiedSlots, int activeSlot,
                       List<Integer> queue, List<Integer> history, boolean shuffle, Repeat repeat) {
    public enum Repeat { OFF, ALL, ONE }
    public static final int MAX_SLOTS = 16;

    public Playlist {
        if (slotCount < 1 || slotCount > MAX_SLOTS) throw new IllegalArgumentException("Disc slot count must be 1..16");
        occupiedSlots = validatedSlots(occupiedSlots, slotCount);
        queue = List.copyOf(Objects.requireNonNull(queue, "queue"));
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        Objects.requireNonNull(repeat, "repeat");
        if (activeSlot < -1 || (activeSlot >= 0 && !occupiedSlots.contains(activeSlot))) {
            throw new IllegalArgumentException("Active slot must contain a disc");
        }
        if (queue.size() != new HashSet<>(queue).size() || queue.contains(activeSlot)
                || !occupiedSlots.containsAll(queue) || !occupiedSlots.containsAll(history)) {
            throw new IllegalArgumentException("Queue and history must refer to available discs");
        }
        if (history.size() > slotCount) throw new IllegalArgumentException("History exceeds disc slot count");
        if (activeSlot == -1 && (!queue.isEmpty() || !history.isEmpty())) {
            throw new IllegalArgumentException("Stopped playback has no queue or history");
        }
    }

    public static Playlist empty(int slots) {
        return stopped(slots, List.of(), false, Repeat.OFF);
    }

    /** Loading saved preferences never resumes an audio instance that no longer exists. */
    public static Playlist stopped(int slots, Collection<Integer> occupiedSlots, boolean shuffle, Repeat repeat) {
        return new Playlist(slots, List.copyOf(occupiedSlots), -1, List.of(), List.of(), shuffle, repeat);
    }

    public boolean playing() { return activeSlot >= 0; }

    public Playlist play(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        if (playing() || occupiedSlots.isEmpty()) return this;
        List<Integer> nextQueue = new ArrayList<>(occupiedSlots);
        if (shuffle) shuffle(nextQueue, random);
        int first = nextQueue.removeFirst();
        return new Playlist(slotCount, occupiedSlots, first, nextQueue, List.of(), shuffle, repeat);
    }

    public Playlist stop() {
        return playing() ? stopped(slotCount, occupiedSlots, shuffle, repeat) : this;
    }

    /** A natural completion under ONE keeps the active slot; the adapter starts its song again. */
    public Playlist finished(RandomGenerator random) {
        return advance(random, false);
    }

    /** Explicit next wraps even when repeat is OFF, but never starts stopped playback. */
    public Playlist next(RandomGenerator random) {
        return advance(random, true);
    }

    public Playlist previous() {
        if (!playing() || history.isEmpty()) return this;
        List<Integer> previousHistory = new ArrayList<>(history);
        int previous = previousHistory.removeLast();
        List<Integer> nextQueue = new ArrayList<>();
        if (activeSlot != previous) nextQueue.add(activeSlot);
        for (int queued : queue) {
            if (queued != previous && queued != activeSlot) nextQueue.add(queued);
        }
        return new Playlist(slotCount, occupiedSlots, previous, nextQueue, previousHistory, shuffle, repeat);
    }

    public Playlist withRepeat(Repeat mode) {
        Objects.requireNonNull(mode, "mode");
        return mode == repeat ? this : new Playlist(slotCount, occupiedSlots, activeSlot, queue, history, shuffle, mode);
    }

    /** Changing shuffle reconstructs the remaining order and excludes the active disc. */
    public Playlist setShuffle(boolean enabled, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        if (enabled == shuffle) return this;
        List<Integer> nextQueue = new ArrayList<>();
        if (playing()) {
            for (int slot : occupiedSlots) if (slot != activeSlot) nextQueue.add(slot);
            if (enabled) shuffle(nextQueue, random);
        }
        return new Playlist(slotCount, occupiedSlots, activeSlot, nextQueue, history, enabled, repeat);
    }

    /**
     * changedSlots includes a replacement even when the slot remains occupied. Changing the
     * active GUI slot stops audio; changes elsewhere cannot leave a stale queue/history entry.
     */
    public Playlist updateSlots(Collection<Integer> occupied, Set<Integer> changedSlots, RandomGenerator random) {
        return updateSlots(slotCount, occupied, changedSlots, random);
    }

    /** Resizing keeps unchanged active audio and valid history; removed/replaced active discs stop. */
    public Playlist updateSlots(int newSlotCount, Collection<Integer> occupied, Set<Integer> changedSlots, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        if (newSlotCount < 1 || newSlotCount > MAX_SLOTS) throw new IllegalArgumentException("Disc slot count must be 1..16");
        List<Integer> nextOccupied = validatedSlots(occupied, newSlotCount);
        Set<Integer> changed = Set.copyOf(Objects.requireNonNull(changedSlots, "changedSlots"));
        for (int slot : changed) Objects.checkIndex(slot, Math.max(slotCount, newSlotCount));
        if (!playing() || !nextOccupied.contains(activeSlot) || changed.contains(activeSlot)) {
            return stopped(newSlotCount, nextOccupied, shuffle, repeat);
        }
        List<Integer> nextQueue = new ArrayList<>(queue.stream()
                .filter(slot -> nextOccupied.contains(slot) && !changed.contains(slot)).toList());
        List<Integer> additions = new ArrayList<>();
        for (int slot : nextOccupied) {
            if (slot != activeSlot && (!occupiedSlots.contains(slot) || changed.contains(slot))) additions.add(slot);
        }
        if (shuffle) shuffle(additions, random);
        nextQueue.addAll(additions);
        List<Integer> nextHistory = history.stream()
                .filter(slot -> nextOccupied.contains(slot) && !changed.contains(slot)).toList();
        if (nextHistory.size() > newSlotCount) nextHistory = nextHistory.subList(nextHistory.size() - newSlotCount, nextHistory.size());
        return new Playlist(newSlotCount, nextOccupied, activeSlot, nextQueue, nextHistory, shuffle, repeat);
    }

    private Playlist advance(RandomGenerator random, boolean manual) {
        Objects.requireNonNull(random, "random");
        if (!playing() || (!manual && repeat == Repeat.ONE)) return this;
        List<Integer> nextQueue = new ArrayList<>(queue);
        if (nextQueue.isEmpty() && (manual || repeat == Repeat.ALL)) {
            nextQueue.addAll(occupiedSlots);
            if (shuffle) shuffle(nextQueue, random);
            if (nextQueue.size() > 1 && nextQueue.getFirst() == activeSlot) {
                nextQueue.add(nextQueue.removeFirst());
            }
        }
        if (nextQueue.isEmpty()) return stop();
        int next = nextQueue.removeFirst();
        List<Integer> nextHistory = new ArrayList<>(history);
        if (next != activeSlot) nextHistory.add(activeSlot);
        while (nextHistory.size() > slotCount) nextHistory.removeFirst();
        return new Playlist(slotCount, occupiedSlots, next, nextQueue, nextHistory, shuffle, repeat);
    }

    private static List<Integer> validatedSlots(Collection<Integer> slots, int slotCount) {
        Objects.requireNonNull(slots, "slots");
        Set<Integer> seen = new HashSet<>();
        for (int slot : slots) {
            Objects.checkIndex(slot, slotCount);
            if (!seen.add(slot)) throw new IllegalArgumentException("Duplicate occupied disc slot");
        }
        return seen.stream().sorted().toList();
    }

    private static void shuffle(List<Integer> slots, RandomGenerator random) {
        for (int index = slots.size() - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            Integer previous = slots.set(index, slots.get(other));
            slots.set(other, previous);
        }
    }
}
