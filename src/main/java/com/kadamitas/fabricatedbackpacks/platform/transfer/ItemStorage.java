package com.kadamitas.fabricatedbackpacks.platform.transfer;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

public final class ItemStorage {
    public static final BlockLookup<Storage<ItemVariant>, ResourceHandler<ItemResource>> SIDED = new BlockLookup<>(Capabilities.Item.BLOCK,
            handler -> NativeStorage.adopt(handler, ItemVariant::new, ItemVariant::nativeResource, 1),
            storage -> NativeStorage.export(storage, ItemVariant::new, ItemVariant::nativeResource, 1));
    public static final ItemLookup<Storage<ItemVariant>, ResourceHandler<ItemResource>> ITEM = new ItemLookup<>(Capabilities.Item.ITEM,
            handler -> NativeStorage.adopt(handler, ItemVariant::new, ItemVariant::nativeResource, 1),
            storage -> NativeStorage.export(storage, ItemVariant::new, ItemVariant::nativeResource, 1));
    private ItemStorage() {}
}
