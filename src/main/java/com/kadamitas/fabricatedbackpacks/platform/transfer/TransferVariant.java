package com.kadamitas.fabricatedbackpacks.platform.transfer;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;

/** Loader-local immutable resource identity; amounts belong to storages. */
public interface TransferVariant<T> extends com.kadamitas.fabricatedbackpacks.platform.neoforge.Resource {
    T getObject();
    DataComponentMap getComponents();
    DataComponentPatch getComponentsPatch();
    boolean isBlank();
    default boolean isEmpty() { return isBlank(); }
}
