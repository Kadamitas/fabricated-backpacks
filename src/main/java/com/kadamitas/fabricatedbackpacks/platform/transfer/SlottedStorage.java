package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.List;
import java.util.stream.IntStream;

public interface SlottedStorage<T> extends Storage<T> {
    int getSlotCount();
    SingleSlotStorage<T> getSlot(int slot);
    default List<SingleSlotStorage<T>> getSlots() { return IntStream.range(0, getSlotCount()).mapToObj(this::getSlot).toList(); }
}
