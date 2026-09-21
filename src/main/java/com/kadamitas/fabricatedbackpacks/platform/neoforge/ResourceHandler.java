package com.kadamitas.fabricatedbackpacks.platform.neoforge;

import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

/** Long-accounting transactional side of Forge's int-count resource boundary. */
public interface ResourceHandler<R extends Resource> {
    int size();
    R getResource(int slot);
    long getAmountAsLong(int slot);
    long getCapacityAsLong(int slot, R resource);
    boolean isValid(int slot, R resource);
    int insert(int slot, R resource, int amount, TransactionContext tx);
    int extract(int slot, R resource, int amount, TransactionContext tx);
    default int insert(R resource, int amount, TransactionContext tx) { int moved = 0; for (int slot = 0; slot < size() && moved < amount; slot++) moved += insert(slot, resource, amount - moved, tx); return moved; }
    default int extract(R resource, int amount, TransactionContext tx) { int moved = 0; for (int slot = 0; slot < size() && moved < amount; slot++) moved += extract(slot, resource, amount - moved, tx); return moved; }
}
