package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class MenuGameTests {
    private MenuGameTests() {}

    static void shortcutTransfersOnlyFirstBackpack(GameTestHelper helper) {
        var player = player(helper);
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(4, 1, 4));
        helper.getLevel().setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        var chest = (net.minecraft.world.Container) helper.getLevel().getBlockEntity(pos);
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() - 2.5);
        player.setYRot(0);
        player.setXRot((float) Math.toDegrees(Math.atan2(player.getEyeY() - (pos.getY() + .5), 3)));
        helper.assertTrue(player.pick(player.blockInteractionRange(), 1F, false) instanceof net.minecraft.world.phys.BlockHitResult hit
                        && hit.getBlockPos().equals(pos),
                "The real current server pose looks at the chest within interaction range");
        var first = bag(BackpackTier.GOLD, UpgradeKind.DEPOSIT);
        first.setItem(0, new ItemStack(Items.COBBLESTONE, 11));
        var second = bag(BackpackTier.GOLD, UpgradeKind.DEPOSIT);
        second.setItem(0, new ItemStack(Items.DIAMOND, 7));
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(player, first.stack());
        player.getInventory().setItem(1, second.stack());
        send(player, new MenuAction(-1, "transfer", 0, 0, ""));
        helper.assertValueEqual(count(chest, Items.COBBLESTONE), 11, "The shortcut uses the first eligible provider's real deposit upgrade");
        helper.assertValueEqual(count(chest, Items.DIAMOND), 0, "The shortcut does not cascade through another carried backpack");
        helper.assertValueEqual(count(second, Items.DIAMOND), 7, "Lower-priority storage remains untouched");
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(player, bag(BackpackTier.LEATHER).stack());
        send(player, new MenuAction(-1, "deposit", 0, 0, ""));
        helper.assertValueEqual(count(second, Items.DIAMOND), 7, "A first backpack without an applicable upgrade does not authorize fallback transfers");
        var receiver = bag(BackpackTier.GOLD, UpgradeKind.RESTOCK);
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(player, receiver.stack());
        send(player, new MenuAction(-1, "transfer", 0, 0, ""));
        helper.assertValueEqual(count(com.kadamitas.fabricatedbackpacks.storage.BagInventory.of(
                com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.get(player)), Items.COBBLESTONE), 11,
                "The single context shortcut uses the real restock direction when that is the first transfer upgrade");
        helper.assertValueEqual(count(chest, Items.COBBLESTONE), 0, "Restocking removes the source items exactly once");
        var occluded = bag(BackpackTier.GOLD, UpgradeKind.DEPOSIT);
        occluded.setItem(0, new ItemStack(Items.EMERALD, 13));
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(player, occluded.stack());
        helper.getLevel().setBlockAndUpdate(pos.offset(0, 1, -1), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(pos.offset(0, 0, -1), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        send(player, new MenuAction(-1, "transfer", 0, 0, ""));
        helper.assertValueEqual(count(chest, Items.EMERALD), 0, "A container behind a solid block is not a shortcut target");
        helper.assertValueEqual(count(com.kadamitas.fabricatedbackpacks.storage.BagInventory.of(
                com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.get(player)), Items.EMERALD), 13,
                "Rejected line of sight preserves the original physical stack");
        helper.succeed();
    }

    static void filteredViewRejectsHiddenAndMalformedClicks(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.NETHERITE);
        bag.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        bag.setItem(110, new ItemStack(Items.EMERALD, 17));
        player.getInventory().setItem(9, bag.stack());
        BackpackMenus.openInventory(player, 9);
        var menu = (BackpackMenu) player.containerMenu;
        helper.assertFalse(menu.getSlot(110).isActive(), "A later physical page starts inactive");
        String mask = "0".repeat(110) + "1" + "0".repeat(bag.getContainerSize() - 111);
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, mask));
        helper.assertTrue(menu.getSlot(110).isActive() && !menu.getSlot(0).isActive(), "A bounded search mask selects the actual physical cell on the first filtered page");
        helper.assertValueEqual(menu.pages(), 1, "Filtered pagination counts results, not original cell addresses");
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "A hidden search result cannot be clicked through the server menu");
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, "1"));
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, "x".repeat(bag.getContainerSize())));
        helper.assertTrue(menu.getSlot(110).isActive() && !menu.getSlot(0).isActive(), "Malformed search masks cannot change the authorized view");
        menu.clicked(110, 0, ContainerInput.PICKUP, player);
        helper.assertValueEqual(menu.getCarried().getCount(), 17, "The selected cell transfers its real count exactly once");
        menu.clicked(110, 0, ContainerInput.PICKUP, player);
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, ""));
        helper.assertTrue(menu.getSlot(0).isActive() && !menu.getSlot(110).isActive(), "Clearing the view restores original physical pagination");
        helper.assertValueEqual(count(bag, Items.EMERALD), 17, "Searching and clearing do not move inventory contents");
        helper.succeed();
    }

    static void bulkSettingsPreservePhysicalContents(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.GOLD);
        bag.setItem(3, new ItemStack(Items.DIAMOND, 29));
        bag.remember(6, new ItemStack(Items.EMERALD));
        player.getInventory().setItem(0, bag.stack());
        BackpackMenus.openInventory(player, 0);
        var menu = (BackpackMenu) player.containerMenu;
        menu.clickMenuButton(player, 6);
        var memory = bag.stack().get(com.kadamitas.fabricatedbackpacks.storage.BagComponents.MEMORY);
        helper.assertValueEqual(memory.entries().size(), 2, "Remember occupied cells preserves existing depleted reservations");
        helper.assertTrue(memory.entries().stream().allMatch(entry -> entry.count() == 1), "Bulk memory stores normalized ghosts rather than physical quantities");
        menu.clickMenuButton(player, 8);
        helper.assertValueEqual(bag.settings().getIntArray("no_sort").orElseThrow().length, bag.getContainerSize(), "Select-all protects every accessible cell, including empty cells");
        send(player, new MenuAction(menu.containerId, "no_sort_color", 0, 0, "#12ABEF"));
        helper.assertValueEqual(bag.settings().getIntOr("no_sort_color", 0), 0x12abef, "The configured overlay color is server validated");
        send(player, new MenuAction(menu.containerId, "no_sort_color", 0, 0, "../secret"));
        helper.assertValueEqual(bag.settings().getIntOr("no_sort_color", 0), 0x12abef, "An invalid color is inert");
        var template = com.kadamitas.fabricatedbackpacks.settings.SettingsTemplate.capture(bag);
        var other = bag(BackpackTier.LEATHER);
        template.apply(other);
        helper.assertValueEqual(other.settings().getIntOr("no_sort_color", 0), 0x12abef, "Settings templates retain the overlay color");
        menu.clickMenuButton(player, 7);
        menu.clickMenuButton(player, 9);
        helper.assertTrue(bag.stack().get(com.kadamitas.fabricatedbackpacks.storage.BagComponents.MEMORY).entries().isEmpty(), "Clear-all removes memory only");
        helper.assertValueEqual(bag.settings().getIntArray("no_sort").orElseThrow().length, 0, "Clear-all removes exclusions");
        helper.assertValueEqual(count(bag, Items.DIAMOND), 29, "Bulk settings never consume, duplicate or move actual storage");
        helper.succeed();
    }

    static void bulkTransfersPreserveOwnerAndHotbar(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.GOLD);
        bag.setItem(0, new ItemStack(Items.DIAMOND, 60));
        bag.remember(2, new ItemStack(Items.EMERALD));
        player.getInventory().setItem(9, bag.stack());
        player.getInventory().setItem(10, new ItemStack(Items.DIAMOND, 12));
        player.getInventory().setItem(11, new ItemStack(Items.EMERALD, 7));
        player.getInventory().setItem(12, new ItemStack(Items.GOLD_INGOT, 4));
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        BackpackMenus.openInventory(player, 9);
        var menu = (BackpackMenu) player.containerMenu;
        send(player, new MenuAction(menu.containerId, "bulk_store", 0, 0, ""));
        helper.assertValueEqual(count(bag, Items.DIAMOND), 72, "Matching store merges and spills within real storage capacity");
        helper.assertValueEqual(count(bag, Items.EMERALD), 7, "Matching store recognizes an empty memory reservation");
        helper.assertValueEqual(player.getInventory().getItem(12).getCount(), 4, "Unmatched main-inventory items stay put");
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 5, "Bulk operations leave the player's hotbar untouched");
        player.getInventory().setItem(13, new ItemStack(Items.DIAMOND));
        send(player, new MenuAction(menu.containerId, "bulk_take", 0, 0, ""));
        helper.assertValueEqual(count(bag, Items.DIAMOND), 0, "Matching take exports all matching physical bag cells");
        helper.assertValueEqual(count(player.getInventory(), Items.DIAMOND), 78, "Taking preserves the exact total, including unchanged hotbar items");
        helper.assertTrue(player.getInventory().getItem(9) == bag.stack(), "Committing destination slots never restores a stale copy of the source backpack");
        bag.toggleNoSort(2);
        bag.setItem(4, new ItemStack(Items.GOLD_INGOT, 3));
        send(player, new MenuAction(menu.containerId, "bulk_take", 0, 1, ""));
        helper.assertValueEqual(count(player.getInventory(), Items.GOLD_INGOT), 7, "All-mode exports an otherwise unmatched item");
        helper.assertValueEqual(count(bag, Items.EMERALD), 7, "Sort exclusions also protect bulk extraction");
        menu.setCarried(new ItemStack(Items.PAPER));
        var before = bag.stack().copy();
        send(player, new MenuAction(menu.containerId, "bulk_store", 0, 1, ""));
        assertStack(helper, bag.stack(), before, "A nonempty cursor prevents bulk actions from changing state");
        helper.succeed();
    }

    static void slotConservationAndAuthorization(GameTestHelper helper) {
        var player = player(helper);
        var foreign = player(helper);
        BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.STACK_UPGRADE_TIER_4);
        source.setItem(0, new ItemStack(Items.COBBLESTONE, 900));
        source.setItem(90, new ItemStack(Items.DIAMOND, 7));
        player.getInventory().setSelectedSlot(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, source.stack());
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 64));
        player.getMainHandItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.containerMenu instanceof BackpackMenu, "Actual held item use opens the registered backpack menu");
        var menu = (BackpackMenu) player.containerMenu;
        BagInventory bag = menu.bag();
        int input = menuSlot(menu, player.getInventory(), 9);
        menu.clicked(input, 0, ContainerInput.QUICK_MOVE, player);
        helper.assertValueEqual(count(bag, Items.COBBLESTONE), 964, "Shift-click joins a physical oversized bag stack");
        helper.assertValueEqual(count(player.getInventory(), Items.COBBLESTONE), 0, "Shift-click removes only the transferred player source");
        menu.clicked(bag.getContainerSize(), 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "Unsafe capacity upgrade cannot be picked up through the real slot");
        helper.assertTrue(bag.has(UpgradeKind.STACK_UPGRADE_TIER_4), "Rejected upgrade removal preserves installed state");
        menu.clicked(90, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.quickMoveStack(player, 90).isEmpty(), "Hidden-page shift-click is rejected");
        helper.assertValueEqual(bag.getItem(90).getCount(), 7, "Inactive page content remains untouched");
        var beforeForeign = bag.stack().copy();
        menu.clicked(0, 0, ContainerInput.PICKUP, foreign);
        helper.assertTrue(menu.quickMoveStack(foreign, 0).isEmpty(), "Foreign quick move is rejected");
        assertStack(helper, bag.stack(), beforeForeign, "Both foreign-player slot paths leave all bag state unchanged");
        helper.assertTrue(foreign.getMainHandItem().isEmpty() && menu.getCarried().isEmpty(), "Unauthorized attempts create no cursor or hand items");
        int carrier = menuSlot(menu, player.getInventory(), 0);
        menu.clicked(carrier, 0, ContainerInput.PICKUP, player);
        menu.clicked(1, 0, ContainerInput.SWAP, player);
        helper.assertTrue(player.getMainHandItem() == source.stack(), "Neither pickup nor number-key swapping can move the owning bag");
        menu.clicked(Integer.MAX_VALUE, 0, ContainerInput.PICKUP, player);
        menu.clicked(-1000, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "Invalid indices are inert");
        menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
        helper.assertValueEqual(count(player.getInventory(), Items.COBBLESTONE) + count(bag, Items.COBBLESTONE), 964, "Exporting an oversized stack conserves every item");
        for (int slot = 0; slot < 36; slot++) helper.assertTrue(player.getInventory().getItem(slot).getCount() <= player.getInventory().getItem(slot).getMaxStackSize(), "Export never creates an oversized ordinary inventory stack");
        helper.assertTrue(menu.clickMenuButton(player, 1), "Page control is handled by the server menu");
        menu.clicked(90, 0, ContainerInput.QUICK_MOVE, player);
        helper.assertValueEqual(count(player.getInventory(), Items.DIAMOND), 7, "The same slot becomes accessible on its actual page");
        ItemStack prior = bag.stack().copy();
        player.getInventory().setItem(0, ItemStack.EMPTY);
        player.getInventory().setItem(1, source.stack());
        helper.assertFalse(menu.stillValid(player), "Moving the owning item invalidates the inventory session");
        menu.clicked(90, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.quickMoveStack(player, 90).isEmpty(), "Stale direct transfers are rejected");
        assertStack(helper, bag.stack(), prior, "A stale session cannot change the old bag snapshot");
        player.closeContainer();
        helper.succeed();
    }

    static void actualServerPayloads(GameTestHelper helper) {
        var player = player(helper);
        var foreign = player(helper);
        BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_PICKUP);
        player.getInventory().setItem(0, source.stack());
        player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 17));
        BackpackMenus.openInventory(player, 0);
        var menu = (BackpackMenu) player.containerMenu;
        menu.clickMenuButton(player, 100);
        menu.setCarried(new ItemStack(Items.DIAMOND, 12));
        send(player, new MenuAction(menu.containerId, "ghost", 0, 0, ""));
        send(player, new MenuAction(menu.containerId, "rename", 0, 0, "  Field bag  "));
        helper.runAfterDelay(2, () -> {
            var bag = menu.bag();
            var filter = upgrade(bag, 0);
            helper.assertTrue(bag.ghost(filter, 0).is(Items.DIAMOND), "The actual Fabric server receiver accepts a legitimate ghost edit");
            helper.assertValueEqual(menu.getCarried().getCount(), 12, "Ghost edit leaves the carried physical stack untouched");
            helper.assertValueEqual(bag.stack().getHoverName().getString(), "Field bag", "Server rename trims a legitimate requested name");
            ItemStack expected = bag.stack().copy();
            send(player, new MenuAction(menu.containerId, "ghost", -1, 0, ""));
            send(player, new MenuAction(menu.containerId, "ghost", 16, 0, ""));
            send(player, new MenuAction(menu.containerId, "ghost", Integer.MAX_VALUE, 1, ""));
            send(player, new MenuAction(menu.containerId, "ghost", 1, 0, ""));
            send(player, new MenuAction(menu.containerId, "ghost_registry", 0, 0, "not an identifier"));
            send(player, new MenuAction(menu.containerId + 1, "ghost", 0, 1, ""));
            send(player, new MenuAction(menu.containerId, "rename", 0, 0, "x".repeat(51)));
            send(player, new MenuAction(menu.containerId, "rename", 0, 0, "line\nbreak"));
            send(player, new MenuAction(menu.containerId, "unrecognized_action", 0, 0, ""));
            send(foreign, new MenuAction(menu.containerId, "ghost", 0, 1, ""));
            helper.runAfterDelay(2, () -> {
                assertStack(helper, bag.stack(), expected, "Malformed, duplicate, foreign, and stale-ID packets leave all bag data unchanged");
                helper.assertValueEqual(count(player.getInventory(), Items.DIAMOND) + menu.getCarried().getCount(), 29, "Every rejected payload preserves the source and cursor counts");
                player.closeContainer();
                helper.assertValueEqual(count(player.getInventory(), Items.DIAMOND), 29, "Closing the menu returns the exact carried items once");
                send(player, new MenuAction(menu.containerId, "ghost", 0, 1, ""));
                send(player, new MenuAction(menu.containerId, "rename", 0, 0, "Closed-menu replay"));
                BackpackMenus.openInventory(player, 0);
                var reopened = (BackpackMenu) player.containerMenu;
                reopened.clickMenuButton(player, 100);
                send(player, new MenuAction(menu.containerId, "ghost", 0, 1, ""));
                helper.runAfterDelay(2, () -> {
                    assertStack(helper, reopened.bag().stack(), expected, "Closed and superseded container IDs cannot replay changes into a new session");
                    player.getInventory().setItem(0, ItemStack.EMPTY);
                    player.getInventory().setItem(1, source.stack());
                    send(player, new MenuAction(reopened.containerId, "ghost", 0, 1, ""));
                    send(player, new MenuAction(reopened.containerId, "rename", 0, 0, "Moved-bag replay"));
                    helper.runAfterDelay(2, () -> {
                        assertStack(helper, source.stack(), expected, "A matching container ID still cannot mutate a moved source bag");
                        helper.assertValueEqual(count(player.getInventory(), Items.DIAMOND), 29, "No payload path creates or consumes real diamonds");
                        player.closeContainer();
                        helper.succeed();
                    });
                });
            });
        });
    }

    static void sharedEquipmentAuthority(GameTestHelper helper) {
        var owner = player(helper);
        var guest = player(helper);
        owner.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 1, 3.5)));
        guest.setPos(owner.getX() + 2, owner.getY(), owner.getZ());
        helper.assertTrue(guest.hasLineOfSight(owner), "Shared-view fixture has an unobstructed interaction path");
        var initial = bag(BackpackTier.GOLD, UpgradeKind.CRAFTING);
        initial.setItem(0, new ItemStack(Items.DIAMOND, 5));
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(owner, initial.stack());
        helper.assertFalse(BackpackMenus.openShared(guest, owner), "Worn storage is private until its wearer opts in");
        var live = com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.inventory(owner).orElseThrow();
        live.updateSettings(tag -> tag.putBoolean("share_access", true));
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.setFromInventory(owner, live);
        BackpackMenus.openEquipped(owner);
        var ownerMenu = (BackpackMenu) owner.containerMenu;
        helper.assertTrue(BackpackMenus.openShared(guest, owner), "An opted-in nearby wearer can share the real inventory");
        var guestMenu = (BackpackMenu) guest.containerMenu;
        helper.assertTrue(guestMenu.bag() == ownerMenu.bag(), "Both viewers use one authoritative inventory handle");
        guestMenu.setCarried(new ItemStack(Items.EMERALD, 3));
        guestMenu.clicked(1, 0, ContainerInput.PICKUP, guest);
        helper.assertTrue(guestMenu.getCarried().isEmpty(), "Shared insertion consumes the guest cursor exactly once");
        helper.assertValueEqual(ownerMenu.bag().getItem(1).getCount(), 3, "Owner immediately observes the shared insertion");
        helper.assertTrue(ownerMenu.stillValid(owner) && guestMenu.stillValid(guest), "Publishing attachment copies preserves the live shared handle");
        guest.closeContainer();
        BackpackMenus.openShared(guest, owner);
        helper.assertTrue(((BackpackMenu) guest.containerMenu).bag() == live, "Reopening does not create a competing equipment inventory");
        live.updateSettings(tag -> tag.putBoolean("share_access", false));
        helper.assertFalse(guest.containerMenu.stillValid(guest), "Revoking sharing invalidates an already-open guest session");
        guest.closeContainer();
        live.updateSettings(tag -> tag.putBoolean("share_access", true));
        BackpackMenus.openShared(guest, owner);
        var stale = (BackpackMenu) guest.containerMenu;
        ItemStack replacement = live.stack().copy();
        BagInventory.of(replacement).setItem(1, new ItemStack(Items.EMERALD, 29));
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(owner, replacement);
        helper.assertFalse(ownerMenu.stillValid(owner) || stale.stillValid(guest), "A real replacement invalidates both old viewers even when UUIDs match");
        stale.clicked(0, 0, ContainerInput.PICKUP, guest);
        helper.assertTrue(stale.getCarried().isEmpty(), "An old guest menu cannot extract from a replaced backpack");
        guest.closeContainer();
        owner.closeContainer();
        helper.assertValueEqual(com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.inventory(owner).orElseThrow().getItem(1).getCount(), 29,
                "Closing old views cannot overwrite a newly equipped backpack");
        helper.succeed();
    }

    static void equipmentMenuReadsLiveContents(GameTestHelper helper) {
        var player = player(helper);
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(player, bag(BackpackTier.GOLD).stack());
        BackpackMenus.openEquipment(player);
        var menu = player.containerMenu;
        var live = com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.inventory(player).orElseThrow();
        live.setItem(0, new ItemStack(Items.DIAMOND, 17));
        com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.setFromInventory(player, live);
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.get(player).isEmpty(), "Taking the native slot removes exactly its current equipped item");
        helper.assertValueEqual(count(BagInventory.of(menu.getCarried()), Items.DIAMOND), 17, "Equipment screen cannot return an old snapshot from before automation updated the bag");
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "Putting back the backpack clears its cursor");
        helper.assertValueEqual(count(com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.inventory(player).orElseThrow(), Items.DIAMOND), 17, "Re-equipping preserves the latest contents");
        player.closeContainer();
        helper.succeed();
    }

    static void nestedMenuLease(GameTestHelper helper) {
        var player = player(helper);
        var outer = bag(BackpackTier.GOLD, UpgradeKind.INCEPTION);
        var inner = bag(BackpackTier.LEATHER);
        outer.setItem(5, inner.stack());
        player.getInventory().setItem(0, outer.stack());
        BackpackMenus.openInventory(player, 0);
        helper.assertTrue(BackpackMenus.openSlot(player, 5), "Opening a real nested storage slot creates a child view");
        var menu = (BackpackMenu) player.containerMenu;
        helper.assertValueEqual(menu.nestedDepth(), 1, "Child view records its bounded nesting depth");
        menu.setCarried(new ItemStack(Items.EMERALD, 7));
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        int ancestorSlot = menuSlot(menu, player.getInventory(), 0);
        menu.clicked(ancestorSlot, 0, ContainerInput.THROW, player);
        helper.assertTrue(player.getInventory().getItem(0) == outer.stack(), "An active child view locks its physical ancestor against throwing");
        menu.setCarried(new ItemStack(BackpackRegistry.item(UpgradeKind.INCEPTION)));
        menu.clicked(menu.bag().getContainerSize(), 0, ContainerInput.PICKUP, player);
        helper.assertFalse(menu.bag().has(UpgradeKind.INCEPTION), "A nested view cannot install a second nesting level");
        helper.assertTrue(menu.getCarried().is(BackpackRegistry.item(UpgradeKind.INCEPTION)), "Denied nested upgrade installation retains the cursor item");
        menu.setCarried(ItemStack.EMPTY);
        player.closeContainer();
        var loaded = BagInventory.of(roundTrip(helper.getLevel(), outer.stack()));
        helper.assertValueEqual(count(BagInventory.of(loaded.getItem(5)), Items.EMERALD), 7, "Nested modifications write through the parent before save");
        BackpackMenus.openInventory(player, 0);
        BackpackMenus.openSlot(player, 5);
        var stale = (BackpackMenu) player.containerMenu;
        outer.setItem(5, outer.getItem(5).copy());
        helper.assertFalse(stale.stillValid(player), "Replacing a nested source with an identical copy invalidates its old view");
        stale.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(stale.getCarried().isEmpty(), "A stale child view cannot extract from a replacement");
        player.closeContainer();
        helper.succeed();
    }

    static void containerBackpackLease(GameTestHelper helper) {
        var player = player(helper);
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(4, 1, 4));
        helper.getLevel().setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getLevel().getBlockEntity(pos);
        chest.setItem(0, bag(BackpackTier.LEATHER).stack());
        player.openMenu(chest);
        helper.assertTrue(BackpackMenus.openSlot(player, 0), "A backpack in a real chest can be opened without taking it out");
        var menu = (BackpackMenu) player.containerMenu;
        menu.setCarried(new ItemStack(Items.IRON_INGOT, 4));
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        player.closeContainer();
        helper.assertValueEqual(count(BagInventory.of(chest.getItem(0)), Items.IRON_INGOT), 4, "External-container edits persist into the physical source item");
        player.openMenu(chest);
        BackpackMenus.openSlot(player, 0);
        var stale = (BackpackMenu) player.containerMenu;
        ItemStack moved = chest.removeItemNoUpdate(0);
        helper.assertFalse(stale.stillValid(player), "Removing the source item closes external-container access");
        stale.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(stale.getCarried().isEmpty(), "Moved-source access cannot create a duplicate cursor stack");
        player.closeContainer();
        helper.assertValueEqual(count(BagInventory.of(moved), Items.IRON_INGOT), 4, "The removed physical item retains all stored items");
        helper.succeed();
    }

    static void placedNestedViewLease(GameTestHelper helper) {
        var player = player(helper);
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(4, 1, 4));
        helper.getLevel().setBlockAndUpdate(pos, BackpackRegistry.block(BackpackTier.GOLD).defaultBlockState());
        var block = (com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity) helper.getLevel().getBlockEntity(pos);
        var outer = bag(BackpackTier.GOLD, UpgradeKind.INCEPTION);
        outer.setItem(0, bag(BackpackTier.LEATHER, UpgradeKind.CRAFTING).stack());
        block.setStack(outer.stack());
        BackpackMenus.openPlaced(player, block);
        BackpackMenus.openSlot(player, 0);
        var child = (BackpackMenu) player.containerMenu;
        helper.assertValueEqual(block.viewers(), 1, "A child view retains exactly one placed-backpack viewer lease");
        child.clickMenuButton(player, 100);
        com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus.open(player, child);
        helper.assertTrue(player.containerMenu instanceof com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus.PortableCrafting, "Nested crafting uses the actual portable workstation");
        helper.assertTrue(player.containerMenu.stillValid(player), "The nested source remains valid while its workstation retains the lease");
        helper.assertValueEqual(block.viewers(), 1, "Opening the child's workstation does not leak placed viewers");
        player.closeContainer();
        helper.assertValueEqual(block.viewers(), 0, "Closing the final child workstation releases its entire source lease chain");
        helper.succeed();
    }

    static void directStashBothDirections(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.LEATHER);
        player.getInventory().setItem(0, bag.stack());
        player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 32));
        var menu = player.inventoryMenu;
        int bagSlot = menuSlot(menu, player.getInventory(), 0), items = menuSlot(menu, player.getInventory(), 9);
        menu.clicked(bagSlot, 0, ContainerInput.PICKUP, player);
        menu.clicked(items, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(BackpackRegistry.isBackpack(menu.getCarried()), "Stashing with a held backpack keeps the backpack on the cursor");
        helper.assertTrue(player.getInventory().getItem(9).isEmpty(), "Cursor-backpack stash consumes exactly the actual source stack");
        helper.assertValueEqual(count(BagInventory.of(menu.getCarried()), Items.DIAMOND), 32, "Cursor-backpack stash inserts the entire fitting stack");
        menu.clicked(bagSlot, 0, ContainerInput.PICKUP, player);
        player.getInventory().setItem(9, new ItemStack(Items.EMERALD, 7));
        menu.clicked(items, 0, ContainerInput.PICKUP, player);
        menu.clicked(bagSlot, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "Cursor-item stash consumes its fitting carried stack");
        helper.assertValueEqual(count(BagInventory.of(player.getInventory().getItem(0)), Items.EMERALD), 7, "Cursor-item stash preserves the physical backpack in its slot");
        helper.assertValueEqual(count(BagInventory.of(player.getInventory().getItem(0)), Items.DIAMOND), 32, "Second-direction stash preserves prior contents");
        helper.succeed();
    }

    static void workstationBackpackLease(GameTestHelper helper) {
        var player = player(helper);
        var outer = bag(BackpackTier.GOLD, UpgradeKind.CRAFTING);
        var child = bag(BackpackTier.LEATHER);
        outer.upgradeInventory(upgrade(outer, 0)).setItem(0, child.stack());
        player.getInventory().setItem(0, outer.stack());
        BackpackMenus.openInventory(player, 0);
        var origin = (BackpackMenu) player.containerMenu;
        origin.clickMenuButton(player, 100);
        com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus.open(player, origin);
        helper.assertTrue(BackpackMenus.openSlot(player, 1), "A real backpack in a portable crafting input can be opened");
        var view = (BackpackMenu) player.containerMenu;
        helper.assertTrue(view.stillValid(player), "Closing the workstation preserves its input-source lease");
        helper.assertValueEqual(view.nestedDepth(), 1, "Portable input views retain containment depth");
        view.setCarried(new ItemStack(Items.EMERALD, 11));
        view.clicked(0, 0, ContainerInput.PICKUP, player);
        player.closeContainer();
        var restored = BagInventory.of(roundTrip(helper.getLevel(), outer.stack()));
        var storedChild = restored.upgradeInventory(upgrade(restored, 0)).getItem(0);
        helper.assertValueEqual(count(BagInventory.of(storedChild), Items.EMERALD), 11,
                "Child edits survive both workstation and ancestor component serialization");
        helper.succeed();
    }

    static void directStashPartialCapacity(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.LEATHER);
        for (int slot = 0; slot < bag.getContainerSize(); slot++) bag.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        bag.setItem(0, new ItemStack(Items.DIAMOND, 63));
        player.getInventory().setItem(0, bag.stack());
        player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND, 5));
        player.inventoryMenu.clicked(menuSlot(player.inventoryMenu, player.getInventory(), 0), 0, ContainerInput.PICKUP, player);
        helper.assertValueEqual(bag.getItem(0).getCount(), 64, "Partial stash fills only the one remaining unit of capacity");
        helper.assertValueEqual(player.inventoryMenu.getCarried().getCount(), 4, "Unaccepted items remain on the cursor");
        helper.assertValueEqual(bag.getItem(0).getCount() + player.inventoryMenu.getCarried().getCount(), 68, "Partial transfer conserves every item");
        helper.assertTrue(player.getInventory().getItem(0) == bag.stack(), "Partial stash never swaps away the target backpack");
        helper.succeed();
    }

    private static void send(ServerPlayer player, MenuAction request) {
        // Enters the live packet listener/Fabric receiver, including its server-thread dispatch.
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(request));
    }
}
