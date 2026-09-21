package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.Iterator;
import java.util.List;

public interface SingleSlotStorage<T> extends Storage<T>, StorageView<T>, SlottedStorage<T> {
    @Override default int getSlotCount() { return 1; }
    @Override default SingleSlotStorage<T> getSlot(int slot) { java.util.Objects.checkIndex(slot, 1); return this; }
    @Override default Iterator<StorageView<T>> iterator() { return List.<StorageView<T>>of(this).iterator(); }
}
