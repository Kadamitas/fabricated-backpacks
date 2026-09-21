package com.kadamitas.fabricatedbackpacks.platform.menu;

import java.util.OptionalInt;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;

public interface ExtendedMenuProvider<D> extends MenuProvider {
    D getScreenOpeningData(ServerPlayer player);
    static <D> OptionalInt open(ServerPlayer player, StreamCodec<? super RegistryFriendlyByteBuf, D> codec,
                               ExtendedMenuProvider<D> provider) {
        D opening = provider.getScreenOpeningData(player);
        player.openMenu(provider, buffer -> codec.encode(
                new RegistryFriendlyByteBuf(buffer, player.registryAccess()), opening));
        return player.containerMenu == player.inventoryMenu ? OptionalInt.empty() : OptionalInt.of(player.containerMenu.containerId);
    }
}
