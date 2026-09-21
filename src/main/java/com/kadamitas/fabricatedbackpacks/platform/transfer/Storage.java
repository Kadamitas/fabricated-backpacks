package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.Collections;
import java.util.Iterator;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

public interface Storage<T> extends Iterable<StorageView<T>> {
    long insert(T resource, long maximum, TransactionContext transaction);
    long extract(T resource, long maximum, TransactionContext transaction);
    default boolean supportsInsertion() { return true; }
    default boolean supportsExtraction() { return true; }
    static <T> Storage<T> empty() {
        return new Storage<>() {
            @Override public long insert(T resource, long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return 0; }
            @Override public long extract(T resource, long maximum, TransactionContext transaction) { StoragePreconditions.notNegative(maximum); return 0; }
            @Override public Iterator<StorageView<T>> iterator() { return Collections.emptyIterator(); }
            @Override public boolean supportsInsertion() { return false; }
            @Override public boolean supportsExtraction() { return false; }
        };
    }
}
