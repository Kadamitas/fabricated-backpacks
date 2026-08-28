package com.kadamitas.fabricatedbackpacks.browser;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** Bounded navigation history; storing a new branch discards obsolete forward views. */
public final class NavigationHistory<T> {
    private final int limit;
    private final ArrayDeque<T> past = new ArrayDeque<>();
    private final ArrayDeque<T> future = new ArrayDeque<>();

    public NavigationHistory(int limit) {
        if (limit < 1 || limit > 1024) throw new IllegalArgumentException("Invalid navigation history limit");
        this.limit = limit;
    }
    public boolean canGoBack() { return !past.isEmpty(); }
    public boolean canGoForward() { return !future.isEmpty(); }
    public void remember(T current) {
        append(past, current);
        future.clear();
    }
    public Optional<T> back(T current) { return move(past, future, current); }
    public Optional<T> forward(T current) { return move(future, past, current); }
    private Optional<T> move(ArrayDeque<T> source, ArrayDeque<T> destination, T current) {
        Objects.requireNonNull(current);
        if (source.isEmpty()) return Optional.empty();
        append(destination, current);
        return Optional.of(source.removeLast());
    }
    private void append(ArrayDeque<T> destination, T value) {
        Objects.requireNonNull(value);
        if (destination.size() == limit) destination.removeFirst();
        destination.addLast(value);
    }
}
