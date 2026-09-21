package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public class CombinedStorage<T, S extends Storage<T>> implements Storage<T> {
    protected final List<S> parts;
    public CombinedStorage(List<S> parts) { this.parts = List.copyOf(parts); }
    @Override public long insert(T resource, long maximum, TransactionContext tx) {
        StoragePreconditions.notNegative(maximum);
        long moved = 0;
        for (var part : parts) { moved += part.insert(resource, maximum - moved, tx); if (moved == maximum) break; }
        return moved;
    }
    @Override public long extract(T resource, long maximum, TransactionContext tx) {
        StoragePreconditions.notNegative(maximum);
        long moved = 0;
        for (var part : parts) { moved += part.extract(resource, maximum - moved, tx); if (moved == maximum) break; }
        return moved;
    }
    @Override public boolean supportsInsertion() { for (var part : parts) if (part.supportsInsertion()) return true; return false; }
    @Override public boolean supportsExtraction() { for (var part : parts) if (part.supportsExtraction()) return true; return false; }
    @Override public Iterator<StorageView<T>> iterator() {
        List<StorageView<T>> views = new ArrayList<>();
        for (var part : parts) part.forEach(views::add);
        return views.iterator();
    }
}
