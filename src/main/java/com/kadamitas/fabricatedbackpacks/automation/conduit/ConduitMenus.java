package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public final class ConduitMenus {
    public static final ExtendedMenuType<ConduitMenu, BlockPos> CONDUIT = Registry.register(BuiltInRegistries.MENU,
            BackpackRegistry.id("conduit"), new ExtendedMenuType<>(ConduitMenu::new, BlockPos.STREAM_CODEC));
    private static boolean initialized;
    private ConduitMenus() {}
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        PayloadTypeRegistry.serverboundPlay().register(ConduitFilterAction.TYPE, ConduitFilterAction.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ConduitFilterState.TYPE, ConduitFilterState.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ConduitFilterAction.TYPE, (action, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player.containerMenu instanceof ConduitMenu menu) menu.applyFilterAction(player, action);
                }));
    }
    public static void open(ServerPlayer player, ConduitBundleBlockEntity bundle, Direction face) {
        if (!bundle.stillValid(player)) return;
        player.openMenu(new ExtendedMenuProvider<BlockPos>() {
            @Override public BlockPos getScreenOpeningData(ServerPlayer viewer) { return bundle.getBlockPos(); }
            @Override public Component getDisplayName() { return Component.translatable("screen.fabricated_backpacks.conduit"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player viewer) {
                return new ConduitMenu(id, inventory, bundle, face);
            }
        });
    }
}
