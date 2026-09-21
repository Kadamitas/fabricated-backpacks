package com.kadamitas.fabricatedbackpacks.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 12, 16})
    void naturalPlaybackVisitsEveryDiscOnceThenStops(int slots) {
        List<Integer> occupied = IntStream.range(0, slots).boxed().toList();
        Playlist playlist = Playlist.stopped(slots, occupied, false, Playlist.Repeat.OFF);
        Random random = new Random(7);
        playlist = playlist.play(random);
        for (int expected = 0; expected < slots; expected++) {
            assertEquals(expected, playlist.activeSlot());
            assertEquals(occupied, playlist.occupiedSlots());
            playlist = playlist.finished(random);
        }
        assertFalse(playlist.playing());
        assertTrue(playlist.queue().isEmpty());
        assertTrue(playlist.history().isEmpty());
        assertEquals(occupied, playlist.occupiedSlots());
    }

    @Test
    void emptyAndAlreadyPlayingControlsAreNoOps() {
        Random random = new Random(1);
        Playlist empty = Playlist.empty(12);
        assertSame(empty, empty.play(random));
        assertSame(empty, empty.next(random));
        assertSame(empty, empty.previous());
        Playlist active = Playlist.stopped(12, List.of(1, 4, 10), false, Playlist.Repeat.OFF).play(random);
        assertSame(active, active.play(random));
        assertEquals(1, active.activeSlot());
        assertEquals(4, active.finished(random).activeSlot());
        assertEquals(10, active.finished(random).finished(random).activeSlot());
    }

    @Test
    void repeatAllRebuildsCompleteCycles() {
        Random random = new Random(2);
        Playlist playlist = Playlist.stopped(3, List.of(0, 1, 2), false, Playlist.Repeat.ALL).play(random);
        for (int step = 0; step < 30; step++) {
            assertEquals(step % 3, playlist.activeSlot());
            assertTrue(playlist.history().size() <= 3);
            playlist = playlist.finished(random);
        }
    }

    @Test
    void repeatOneReplaysNaturallyButNextStillAdvances() {
        Random random = new Random(3);
        Playlist active = Playlist.stopped(3, List.of(0, 2), false, Playlist.Repeat.ONE).play(random);
        assertSame(active, active.finished(random));
        assertEquals(2, active.next(random).activeSlot());
        Playlist off = active.withRepeat(Playlist.Repeat.OFF).next(random);
        assertFalse(off.finished(random).playing());
        assertEquals(0, off.next(random).activeSlot());
    }

    @Test
    void previousUsesHistoryAndReturnsCurrentTrackToQueue() {
        Random random = new Random(4);
        Playlist start = Playlist.stopped(3, List.of(0, 1, 2), false, Playlist.Repeat.OFF).play(random);
        Playlist last = start.next(random).next(random);
        Playlist previous = last.previous();
        assertEquals(1, previous.activeSlot());
        assertEquals(List.of(2), previous.queue());
        Playlist first = previous.previous();
        assertEquals(0, first.activeSlot());
        assertEquals(List.of(1, 2), first.queue());
        assertSame(first, first.previous());
        assertEquals(1, first.next(random).activeSlot());
    }

    @Test
    void shuffleIsReproducibleAndExcludesTheActiveDisc() {
        List<Integer> occupied = IntStream.range(0, 12).boxed().toList();
        Playlist playing = Playlist.stopped(12, occupied, false, Playlist.Repeat.OFF).play(new Random(0));
        Playlist shuffled = playing.setShuffle(true, new Random(99));
        assertEquals(shuffled, playing.setShuffle(true, new Random(99)));
        assertFalse(shuffled.queue().contains(playing.activeSlot()));
        assertEquals(new HashSet<>(occupied.subList(1, 12)), new HashSet<>(shuffled.queue()));
        assertEquals(11, shuffled.queue().size());
        assertEquals(occupied.subList(1, 12), shuffled.setShuffle(false, new Random(99)).queue());
    }

    @Test
    void insertionsAndRemovalsUpdateQueueAndHistory() {
        Random random = new Random(5);
        Playlist first = Playlist.stopped(12, List.of(0, 2, 4), false, Playlist.Repeat.OFF).play(random);
        Playlist second = first.next(random);
        Playlist changed = second.updateSlots(List.of(2, 6), Set.of(0, 4, 6), random);
        assertEquals(2, changed.activeSlot());
        assertEquals(List.of(6), changed.queue());
        assertTrue(changed.history().isEmpty());
        assertEquals(List.of(0, 2, 4), first.occupiedSlots());
        assertEquals(List.of(4), second.queue());
    }

    @Test
    void replacingTheActiveDiscStopsWithoutLosingPreferences() {
        Random random = new Random(6);
        Playlist active = Playlist.stopped(12, List.of(0, 3), true, Playlist.Repeat.ALL).play(random);
        Playlist stopped = active.updateSlots(List.of(0, 3), Set.of(active.activeSlot()), random);
        assertFalse(stopped.playing());
        assertTrue(stopped.queue().isEmpty());
        assertTrue(stopped.history().isEmpty());
        assertEquals(List.of(0, 3), stopped.occupiedSlots());
        assertTrue(stopped.shuffle());
        assertEquals(Playlist.Repeat.ALL, stopped.repeat());
    }

    @Test
    void repeatedControlsKeepBoundedValidState() {
        List<Integer> occupied = IntStream.range(0, 12).boxed().toList();
        Random random = new Random(0xBACC);
        Playlist state = Playlist.stopped(12, occupied, false, Playlist.Repeat.ALL).play(random);
        for (int action = 0; action < 2_000; action++) {
            state = switch (random.nextInt(5)) {
                case 0 -> state.previous();
                case 1 -> state.setShuffle(!state.shuffle(), random);
                case 2 -> state.finished(random);
                case 3 -> state.stop().play(random);
                default -> state.next(random);
            };
            assertEquals(occupied, state.occupiedSlots());
            assertTrue(occupied.contains(state.activeSlot()));
            assertTrue(state.history().size() <= 12);
            assertTrue(occupied.containsAll(state.history()));
            assertEquals(state.queue().size(), new HashSet<>(state.queue()).size());
            assertFalse(state.queue().contains(state.activeSlot()));
        }
    }

    @Test
    void growingAPlayingPlaylistPreservesActiveOrderAndHistory() {
        Random random = new Random(8);
        Playlist before = Playlist.stopped(12, List.of(0, 3, 11), false, Playlist.Repeat.ALL).play(random).next(random);
        Playlist grown = before.updateSlots(16, List.of(0, 3, 11, 14, 15), Set.of(12, 13, 14, 15), random);
        assertEquals(16, grown.slotCount());
        assertEquals(3, grown.activeSlot());
        assertEquals(List.of(11, 14, 15), grown.queue());
        assertEquals(List.of(0), grown.history());
        assertEquals(Playlist.Repeat.ALL, grown.repeat());
        assertEquals(0, grown.previous().activeSlot());
        assertEquals(14, grown.next(random).next(random).activeSlot());
        assertEquals(12, before.slotCount());
    }

    @Test
    void twoHundredSlotLibraryPlaysHighIndexDiscsAndKeepsHistory() {
        Random random = new Random(10);
        Playlist first = Playlist.stopped(200, List.of(0, 99, 199), false, Playlist.Repeat.ALL).play(random);
        assertEquals(0, first.activeSlot());
        Playlist last = first.next(random).next(random);
        assertEquals(199, last.activeSlot());
        assertEquals(List.of(0, 99), last.history());
        assertEquals(99, last.previous().activeSlot());
        assertEquals(200, last.slotCount());
    }

    @Test
    void shrinkingTrimsHistoryAndStopsOnlyForAnAffectedActiveDisc() {
        Random random = new Random(9);
        Playlist repeated = Playlist.stopped(16, List.of(0, 1, 15), true, Playlist.Repeat.ALL).play(random);
        // Repeated history may contain more entries than the new geometry even
        // when every remaining entry still names a valid occupied slot.
        repeated = new Playlist(16, List.of(0, 1, 15), 1, List.of(15), List.of(0, 1, 0, 1, 0), true, Playlist.Repeat.ALL);
        Playlist smaller = repeated.updateSlots(2, List.of(0, 1), Set.of(15), random);
        assertEquals(1, smaller.activeSlot());
        assertEquals(List.of(1, 0), smaller.history());
        assertTrue(smaller.queue().isEmpty());
        assertTrue(smaller.shuffle());
        assertEquals(Playlist.Repeat.ALL, smaller.repeat());
        Playlist removed = repeated.updateSlots(1, List.of(0), Set.of(1, 15), random);
        assertFalse(removed.playing());
        assertEquals(1, removed.slotCount());
        assertEquals(List.of(0), removed.occupiedSlots());
        assertTrue(removed.history().isEmpty());
        assertTrue(removed.shuffle());
        assertEquals(Playlist.Repeat.ALL, removed.repeat());
        assertThrows(IllegalArgumentException.class, () -> smaller.updateSlots(257, List.of(0), Set.of(), random));
    }

    @Test
    void inputCollectionsAreCopied() {
        List<Integer> occupied = new ArrayList<>(List.of(0, 1));
        Playlist playlist = Playlist.stopped(2, occupied, false, Playlist.Repeat.OFF);
        occupied.clear();
        assertEquals(List.of(0, 1), playlist.occupiedSlots());
        assertThrows(UnsupportedOperationException.class, () -> playlist.occupiedSlots().clear());
    }

    @Test
    void invalidSavedAndRuntimeStatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Playlist.empty(0));
        assertThrows(IllegalArgumentException.class, () -> Playlist.empty(257));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Playlist.stopped(12, List.of(12), false, Playlist.Repeat.OFF));
        assertThrows(IllegalArgumentException.class,
                () -> Playlist.stopped(12, List.of(0, 0), false, Playlist.Repeat.OFF));
        assertThrows(IllegalArgumentException.class,
                () -> new Playlist(2, List.of(0, 1), 0, List.of(0), List.of(), false, Playlist.Repeat.OFF));
        assertThrows(IllegalArgumentException.class,
                () -> new Playlist(2, List.of(0, 1), -1, List.of(1), List.of(), false, Playlist.Repeat.OFF));
        assertThrows(IllegalArgumentException.class,
                () -> new Playlist(2, List.of(0, 1), 1, List.of(), List.of(0, 1, 0), false, Playlist.Repeat.OFF));
        assertThrows(IllegalArgumentException.class,
                () -> new Playlist(2, List.of(0), 1, List.of(), List.of(), false, Playlist.Repeat.OFF));
    }
}
