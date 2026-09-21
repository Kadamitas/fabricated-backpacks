package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

public interface StorageView<T> {
    T getResource();
    boolean isResourceBlank();
    long getAmount();
    long getCapacity();
    long extract(T resource, long maximum, TransactionContext transaction);
}
