package com.kadamitas.fabricatedbackpacks.platform.transfer;

import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

public final class ItemStorage {
    public static final BlockLookup<Storage<ItemVariant>, IItemHandler> SIDED = new BlockLookup<>(ForgeCapabilities.ITEM_HANDLER,
            handler -> new NativeStorage<>(ForgeResourceAdapters.items(handler), value -> value, value -> value, 1),
            ItemStorage::export);
    public static final ItemLookup<Storage<ItemVariant>, IItemHandler> ITEM = new ItemLookup<>(ForgeCapabilities.ITEM_HANDLER,
            handler -> new NativeStorage<>(ForgeResourceAdapters.items(handler), value -> value, value -> value, 1),
            ItemStorage::export);
    private static IItemHandler export(Storage<ItemVariant> storage) {
        return ForgeResourceAdapters.items(NativeStorage.export(storage, value -> value, value -> value, 1),
                () -> storage instanceof com.kadamitas.fabricatedbackpacks.resource.VoidItemStorage voided && voided.hasAggregateAdmission());
    }
    private ItemStorage() {}
}
