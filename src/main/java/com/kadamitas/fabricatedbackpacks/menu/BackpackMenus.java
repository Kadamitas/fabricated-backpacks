package com.kadamitas.fabricatedbackpacks.menu;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public final class BackpackMenus {
    public static final ExtendedMenuType<BackpackMenu, BagOpeningData> BACKPACK = Registry.register(BuiltInRegistries.MENU,
            BackpackRegistry.id("backpack"), new ExtendedMenuType<>(BackpackMenu::new, BagOpeningData.STREAM_CODEC));
    public static final ExtendedMenuType<EquipmentMenu, Boolean> EQUIPMENT = Registry.register(BuiltInRegistries.MENU,
            BackpackRegistry.id("equipment"), new ExtendedMenuType<>((id, inventory, ignored) -> new EquipmentMenu(id, inventory), ByteBufCodecs.BOOL));
    private BackpackMenus() {}
    public static void initialize() {
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown() && player instanceof ServerPlayer viewer
                    && entity instanceof ServerPlayer wearer && viewer != wearer && openShared(viewer, wearer))
                return net.minecraft.world.InteractionResult.SUCCESS;
            return net.minecraft.world.InteractionResult.PASS;
        });
    }

    public static void openHeld(ServerPlayer player, InteractionHand hand) {
        int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
        openInventory(player, slot);
    }
    public static void openInventory(ServerPlayer player, int slot) {
        if (slot < 0 || slot >= player.getInventory().getContainerSize()) return;
        ItemStack stack = player.getInventory().getItem(slot);
        if (BackpackRegistry.isBackpack(stack)) open(player, BagInventory.of(stack),
                new BagOpeningData(stack, slot, BlockPos.ZERO, BagOpeningData.INVENTORY), null);
    }
    public static void openEquipped(ServerPlayer player) {
        ItemStack stack = BackpackEquipment.get(player);
        if (BackpackRegistry.isBackpack(stack)) open(player, BackpackEquipment.inventory(player).orElseThrow(),
                new BagOpeningData(stack, -1, BlockPos.ZERO, BagOpeningData.EQUIPPED), null);
        else {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (BackpackRegistry.isBackpack(player.getInventory().getItem(slot))) { openInventory(player, slot); return; }
            }
            openEquipment(player);
        }
    }
    public static void openPlaced(ServerPlayer player, BackpackBlockEntity entity) {
        if (entity.stillValid(player)) open(player, entity.inventory(),
                new BagOpeningData(entity.stack(), -1, entity.getBlockPos(), BagOpeningData.PLACED), entity);
    }
    private static void open(ServerPlayer player, BagInventory bag, BagOpeningData data, BackpackBlockEntity placed) {
        open(player, bag, data, placed, null);
    }
    private static void open(ServerPlayer player, BagInventory bag, BagOpeningData data, BackpackBlockEntity placed, BagLease lease) {
        if (player.isSpectator()) return;
        com.kadamitas.fabricatedbackpacks.world.MobLoot.materialize(bag, player.level(), placed == null ? player.blockPosition() : placed.getBlockPos(), player);
        com.kadamitas.fabricatedbackpacks.admin.BackpackArchives.record(player.level(), bag, player);
        player.openMenu(new ExtendedMenuProvider<BagOpeningData>() {
            @Override public BagOpeningData getScreenOpeningData(ServerPlayer viewer) { return data; }
            @Override public Component getDisplayName() { return bag.stack().getHoverName(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player viewer) {
                return new BackpackMenu(id, inventory, data, bag, placed, lease);
            }
        });
        if (player.containerMenu instanceof BackpackMenu menu) com.kadamitas.fabricatedbackpacks.network.BackpackNetworking.sendSettings(player, menu);
    }
    public static boolean openSlot(ServerPlayer player, int index) {
        AbstractContainerMenu previous = player.containerMenu;
        if (!previous.stillValid(player) || !previous.getCarried().isEmpty() || index < 0 || index >= previous.slots.size()) return false;
        var slot = previous.slots.get(index);
        if (!slot.isActive() || !slot.mayPickup(player) || slot.container instanceof net.minecraft.world.inventory.ResultContainer) return false;
        ItemStack stack = slot.getItem();
        if (!BackpackRegistry.isBackpack(stack)) return false;
        if (previous instanceof EquipmentMenu && index == 0) { openEquipped(player); return true; }
        if (slot.container == player.getInventory()) { openInventory(player, slot.getContainerSlot()); return true; }
        BackpackMenu parent = previous instanceof BackpackMenu backpack ? backpack : WorkstationMenus.origin(previous);
        if (parent != null && (parent.nestedDepth() > 0 || parent.locks(stack))) return false;
        BagInventory bag = BagInventory.of(stack);
        if (parent != null) parent.retainView();
        BagLease lease = new BagLease(() -> previous.stillValid(player) && slot.getItem() == stack && slot.mayPickup(player),
                () -> { slot.setChanged(); WorkstationMenus.persistInputs(previous); if (parent != null) parent.persist(); },
                () -> { if (parent != null) parent.releaseView(); },
                item -> parent != null && parent.locks(item), parent == null ? 0 : parent.nestedDepth() + 1);
        open(player, bag, new BagOpeningData(stack, -1, BlockPos.ZERO, BagOpeningData.LEASED), null, lease);
        return player.containerMenu instanceof BackpackMenu menu && menu.bag() == bag;
    }
    public static boolean openShared(ServerPlayer viewer, ServerPlayer wearer) {
        BagInventory bag = BackpackEquipment.inventory(wearer).orElse(null);
        if (bag == null || !mayShare(viewer, wearer, bag) || !viewer.containerMenu.getCarried().isEmpty()) return false;
        BagLease lease = new BagLease(() -> mayShare(viewer, wearer, bag),
                () -> BackpackEquipment.setFromInventory(wearer, bag), () -> {}, item -> false, 0);
        open(viewer, bag, new BagOpeningData(bag.stack(), -1, BlockPos.ZERO, BagOpeningData.LEASED), null, lease);
        return viewer.containerMenu instanceof BackpackMenu menu && menu.bag() == bag;
    }
    private static boolean mayShare(ServerPlayer viewer, ServerPlayer wearer, BagInventory bag) {
        return viewer != wearer && viewer.isAlive() && wearer.isAlive() && !viewer.isSpectator() && !wearer.isSpectator()
                && viewer.level() == wearer.level() && viewer.distanceToSqr(wearer) <= 64 && viewer.hasLineOfSight(wearer)
                && BackpackEquipment.isCurrent(wearer, bag)
                && com.kadamitas.fabricatedbackpacks.config.BackpackConfig.get().storage().shareWornBackpacks()
                && com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime.effective(bag, wearer).getBooleanOr("share_access", false);
    }
    public static void openEquipment(ServerPlayer player) {
        if (player.isSpectator()) return;
        player.openMenu(new ExtendedMenuProvider<Boolean>() {
            @Override public Boolean getScreenOpeningData(ServerPlayer viewer) { return false; }
            @Override public Component getDisplayName() { return Component.translatable("screen.fabricated_backpacks.equipment"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player viewer) { return new EquipmentMenu(id, inventory); }
        });
    }
}
