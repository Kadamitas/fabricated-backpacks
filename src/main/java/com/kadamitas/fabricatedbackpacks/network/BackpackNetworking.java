package com.kadamitas.fabricatedbackpacks.network;

import com.kadamitas.fabricatedbackpacks.gameplay.BackpackRuntime;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;

public final class BackpackNetworking {
    private BackpackNetworking() {}
    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(MenuAction.TYPE, MenuAction.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JukeboxAudio.TYPE, JukeboxAudio.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BagSettings.TYPE, BagSettings.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorkstationState.TYPE, WorkstationState.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ServerRules.TYPE, ServerRules.STREAM_CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sender.sendPacket(new ServerRules(com.kadamitas.fabricatedbackpacks.config.ConfigFile.encode(
                        com.kadamitas.fabricatedbackpacks.config.BackpackConfig.get()))));
        com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus.setStateListener((player, state) ->
                ServerPlayNetworking.send(player, new WorkstationState(player.containerMenu.containerId, CustomData.of(state))));
        ServerPlayNetworking.registerGlobalReceiver(MenuAction.TYPE, (payload, context) -> context.server().execute(() -> handle(context.player(), payload)));
        UpgradeEngine.setSoundBridge(new JukeboxRuntime.SoundBridge() {
            @Override public void start(ServerLevel level, String identity, int entityId, BlockPos pos, Holder<JukeboxSong> song) {
                JukeboxAudio.playback(level.registryAccess(), identity, song, entityId, pos, Integer.MAX_VALUE)
                        .ifPresent(packet -> {
                            for (ServerPlayer listener : JukeboxRuntime.listeners(level, pos, entityId))
                                ServerPlayNetworking.send(listener, packet);
                        });
            }
            @Override public void stop(ServerLevel level, String identity) {
                for (ServerPlayer listener : level.players()) leave(listener, identity);
            }
            @Override public void join(ServerPlayer listener, String identity, int entityId, BlockPos position, Holder<JukeboxSong> song, int remainingTicks) {
                JukeboxAudio.playback(listener.level().registryAccess(), identity, song, entityId, position, remainingTicks)
                        .ifPresent(packet -> ServerPlayNetworking.send(listener, packet));
            }
            @Override public void leave(ServerPlayer listener, String identity) {
                ServerPlayNetworking.send(listener, JukeboxAudio.stop(identity));
            }
        });
    }

    private static void handle(ServerPlayer player, MenuAction packet) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (packet.containerId() == -1) {
            switch (packet.action()) {
                case "open" -> BackpackMenus.openEquipped(player);
                case "equipment" -> BackpackMenus.openEquipment(player);
                case "toggle_upgrade" -> {
                    if (packet.index() < 0 || packet.index() >= 5) return;
                    var bags = BackpackRuntime.carried(player);
                    if (bags.isEmpty()) return;
                    var bag = bags.getFirst();
                    if (UpgradeEngine.action(bag, packet.index(), "toggle", player)) {
                        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.setFromInventory(player, bag);
                        player.inventoryMenu.broadcastChanges();
                    }
                }
                case "deposit", "restock", "transfer" -> transferLookedAt(player, packet.action());
                case "tool_cycle" -> {
                    var hit = player.pick(player.blockInteractionRange(), 1F, false);
                    if (hit instanceof BlockHitResult blockHit) for (var bag : BackpackRuntime.carried(player))
                        if (UpgradeEngine.blockAttack(bag, player, player.level().getBlockState(blockHit.getBlockPos()), true)) break;
                }
                default -> { }
            }
            return;
        }
        if (packet.action().equals("open_slot")) {
            if (player.containerMenu.containerId == packet.containerId()) BackpackMenus.openSlot(player, packet.index());
            return;
        }
        if (packet.action().equals("workstation_choice")) {
            Identifier recipe = Identifier.tryParse(packet.text());
            if (recipe != null && player.containerMenu.containerId == packet.containerId())
                com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus.selectRecipe(player, recipe);
            return;
        }
        if (!(player.containerMenu instanceof BackpackMenu menu) || menu.containerId != packet.containerId() || !menu.stillValid(player)) return;
        var upgrade = menu.selected().orElse(null);
        switch (packet.action()) {
            case "storage_view" -> { if (!menu.storageView(packet.text())) return; }
            case "bulk_store", "bulk_take" -> com.kadamitas.fabricatedbackpacks.menu.StorageActions.transfer(menu, player,
                    packet.action().equals("bulk_store"), packet.value() == 1);
            case "workstation" -> { com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus.open(player, menu); return; }
            case "resource_container" -> com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime.action(menu.bag(), packet.index(), "container", player);
            case "release_mob" -> com.kadamitas.fabricatedbackpacks.gameplay.MobCapture.release(menu.bag(), packet.index(), player,
                    player.position().add(player.getLookAngle().multiply(2.5, 0, 2.5)).add(0, 0.2, 0));
            case "ghost", "ghost_registry" -> {
                if (upgrade == null || packet.index() < 0 || packet.index() >= menu.bag().filterSlots(upgrade)) return;
                ItemStack ghost = packet.value() == 1 ? ItemStack.EMPTY : menu.getCarried().copyWithCount(1);
                if (packet.action().equals("ghost_registry")) {
                    Identifier itemId = Identifier.tryParse(packet.text());
                    if (itemId == null) return;
                    ghost = BuiltInRegistries.ITEM.getOptional(itemId).map(ItemStack::new).orElse(ItemStack.EMPTY);
                }
                menu.bag().setFilter(upgrade, packet.index(), ghost);
            }
            case "upgrade" -> {
                if (upgrade == null) return;
                if (!UpgradeEngine.action(menu.bag(), upgrade.slot(), packet.text(), player))
                    com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime.action(menu.bag(), upgrade.slot(), packet.text(), player);
            }
            case "rename" -> {
                String name = packet.text().strip();
                if (name.length() > 50 || name.codePoints().anyMatch(Character::isISOControl)) return;
                if (name.isEmpty()) menu.bag().stack().remove(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
                else menu.bag().stack().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name));
            }
            case "memory_components" -> menu.bag().updateSettings(tag -> tag.putBoolean("memory_components", !tag.getBooleanOr("memory_components", false)));
            default -> {
                if (!com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime.action(menu.bag(), player, packet.action(), packet.value(), packet.text())) return;
            }
        }
        menu.persist();
        menu.broadcastChanges();
        sendSettings(player, menu);
    }
    public static void sendSettings(ServerPlayer player, BackpackMenu menu) {
        ServerPlayNetworking.send(player, new BagSettings(menu.containerId,
                com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime.view(menu.bag(), player),
                menu.bag().stack().getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY)));
    }
    private static void transferLookedAt(ServerPlayer player, String action) {
        if (!(player.pick(player.blockInteractionRange(), 1F, false) instanceof BlockHitResult hit)
                || !(player.level().getBlockEntity(hit.getBlockPos()) instanceof Container target)) return;
        if (!target.stillValid(player) || !player.level().mayInteract(player, hit.getBlockPos())) return;
        if (com.kadamitas.fabricatedbackpacks.config.RuleMatchers.block(player.level().getBlockState(hit.getBlockPos()),
                com.kadamitas.fabricatedbackpacks.config.BackpackConfig.get().storage().blockedInteractions())) return;
        var bags = BackpackRuntime.carried(player);
        if (bags.isEmpty()) return;
        var bag = bags.getFirst();
        String selectedAction = action;
        if (action.equals("transfer")) {
            selectedAction = com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal.upgradeBags(bag).stream()
                    .filter(com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal.Node::attached)
                    .flatMap(node -> node.inventory().installedUpgrades().stream()
                            .filter(upgrade -> com.kadamitas.fabricatedbackpacks.upgrade.UpgradeFilters.enabled(node.inventory(), upgrade)))
                    .map(upgrade -> upgrade.kind().family()).filter(family -> family.equals("deposit") || family.equals("restock"))
                    .findFirst().orElse("");
        }
        boolean deposit = selectedAction.equals("deposit");
        if (!deposit && !selectedAction.equals("restock")) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("No active deposit or restock upgrade"), true);
            return;
        }
        int moved = UpgradeEngine.transfer(bag, target, deposit);
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.setFromInventory(player, bag);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(moved == 0 ? "No matching items transferred"
                : "Transferred " + moved + " stack" + (moved == 1 ? "" : "s")), true);
        player.inventoryMenu.broadcastChanges();
    }
}
