package com.kadamitas.fabricatedbackpacks.platform.transfer;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.neoforged.neoforge.transfer.resource.Resource;

/** Loader-local immutable resource identity; amounts belong to storages. */
public interface TransferVariant<T> extends Resource {
    T getObject();
    DataComponentMap getComponents();
    DataComponentPatch getComponentsPatch();
    boolean isBlank();
    @Override default boolean isEmpty() { return isBlank(); }
}
