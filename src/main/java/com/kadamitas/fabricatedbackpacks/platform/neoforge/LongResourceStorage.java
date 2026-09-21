package com.kadamitas.fabricatedbackpacks.platform.neoforge;

import com.kadamitas.fabricatedbackpacks.platform.neoforge.Resource;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

/** Internal long-count storage boundary; all mutations must journal against the native transaction. */
public interface LongResourceStorage<R extends Resource> {
    int size();
    R resource(int slot);
    long amount(int slot);
    long capacity(int slot, R resource);
    boolean accepts(int slot, R resource);
    long insert(int slot, R resource, long amount, TransactionContext transaction);
    long extract(int slot, R resource, long amount, TransactionContext transaction);
    default long insert(R resource, long amount, TransactionContext transaction) {
        long moved = 0;
        for (int slot = 0; slot < size() && moved < amount; slot++) moved += insert(slot, resource, amount - moved, transaction);
        return moved;
    }
    default long extract(R resource, long amount, TransactionContext transaction) {
        long moved = 0;
        for (int slot = 0; slot < size() && moved < amount; slot++) moved += extract(slot, resource, amount - moved, transaction);
        return moved;
    }
}
