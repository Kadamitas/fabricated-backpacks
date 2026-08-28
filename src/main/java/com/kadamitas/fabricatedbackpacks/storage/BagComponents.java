package com.kadamitas.fabricatedbackpacks.storage;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.CustomData;

public final class BagComponents {
    public static final DataComponentType<com.kadamitas.fabricatedbackpacks.item.BackpackColors> COLORS = register("colors", com.kadamitas.fabricatedbackpacks.item.BackpackColors.CODEC);
    public static final DataComponentType<InventorySnapshot> CONTENTS = snapshot("contents");
    public static final DataComponentType<InventorySnapshot> UPGRADES = snapshot("upgrades");
    public static final DataComponentType<InventorySnapshot> FILTERS = snapshot("filters");
    public static final DataComponentType<InventorySnapshot> MEMORY = snapshot("memory");
    public static final DataComponentType<CustomData> SETTINGS = register("settings", CustomData.CODEC);
    public static final DataComponentType<String> IDENTITY = register("identity", Codec.STRING);

    private BagComponents() {}
    public static void initialize() { /* Forces registration before items are constructed. */ }

    private static DataComponentType<InventorySnapshot> snapshot(String path) {
        return register(path, InventorySnapshot.CODEC);
    }

    private static <T> DataComponentType<T> register(String path, Codec<T> codec) {
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
                ResourceLocation.fromNamespaceAndPath("fabricated_backpacks", path),
                DataComponentType.<T>builder().persistent(codec)
                        .networkSynchronized(ByteBufCodecs.fromCodecWithRegistries(codec)).cacheEncoding().build());
    }
}
