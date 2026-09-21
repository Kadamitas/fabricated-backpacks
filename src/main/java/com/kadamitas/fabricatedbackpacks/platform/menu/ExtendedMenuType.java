package com.kadamitas.fabricatedbackpacks.platform.menu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.network.IContainerFactory;

public final class ExtendedMenuType<M extends AbstractContainerMenu, D> extends MenuType<M> {
    @FunctionalInterface public interface Factory<M, D> { M create(int id, Inventory inventory, D data); }
    private final StreamCodec<? super RegistryFriendlyByteBuf, D> codec;
    public ExtendedMenuType(Factory<M, D> factory, StreamCodec<? super RegistryFriendlyByteBuf, D> codec) {
        super((IContainerFactory<M>) (id, inventory, buffer) -> factory.create(id, inventory, codec.decode(buffer)), FeatureFlags.DEFAULT_FLAGS);
        this.codec = codec;
    }
    void write(RegistryFriendlyByteBuf buffer, D data) { codec.encode(buffer, data); }
}
