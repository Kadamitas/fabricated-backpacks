package com.kadamitas.fabricatedbackpacks.platform.menu;

import java.util.OptionalInt;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public interface ExtendedMenuProvider<D> extends MenuProvider {
    D getScreenOpeningData(ServerPlayer player);
    static <D> OptionalInt open(ServerPlayer player, ExtendedMenuProvider<D> provider) {
        D opening = provider.getScreenOpeningData(player);
        return player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return provider.getDisplayName(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player viewer) { return provider.createMenu(id, inventory, viewer); }
            @Override @SuppressWarnings("unchecked") public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buffer) {
                ((ExtendedMenuType<?, D>) menu.getType()).write(buffer, opening);
            }
        });
    }
}
