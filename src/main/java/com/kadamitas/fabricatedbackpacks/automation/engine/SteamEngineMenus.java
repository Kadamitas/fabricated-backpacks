package com.kadamitas.fabricatedbackpacks.automation.engine;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public final class SteamEngineMenus {
    public static final ExtendedMenuType<SteamEngineMenu, BlockPos> STEAM_ENGINE = Registry.register(BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath("fabricated_backpacks", "steam_engine"),
            new ExtendedMenuType<>(SteamEngineMenu::new, BlockPos.STREAM_CODEC));
    public static final ExtendedMenuType<SteamEngineSideMenu, BlockPos> SIDES = Registry.register(BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath("fabricated_backpacks", "steam_engine_sides"),
            new ExtendedMenuType<>(SteamEngineSideMenu::new, BlockPos.STREAM_CODEC));

    private SteamEngineMenus() { }
    public static void initialize() { }
    public static boolean open(ServerPlayer player, SteamEngineBlockEntity engine) {
        return engine.stillValid(player) && player.openMenu(engine).isPresent();
    }
    public static boolean openSides(ServerPlayer player, SteamEngineBlockEntity engine, Direction face) {
        if (face == null || !engine.stillValid(player)) return false;
        return player.openMenu(new ExtendedMenuProvider<BlockPos>() {
            @Override public BlockPos getScreenOpeningData(ServerPlayer viewer) { return engine.getBlockPos(); }
            @Override public Component getDisplayName() {
                return engine.hasCustomName() ? engine.getName() : Component.translatable("screen.fabricated_backpacks.steam_engine_sides");
            }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player viewer) {
                return new SteamEngineSideMenu(id, inventory, engine, face);
            }
        }).isPresent();
    }
}
