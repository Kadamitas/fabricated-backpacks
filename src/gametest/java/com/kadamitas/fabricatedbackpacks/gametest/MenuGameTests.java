package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class MenuGameTests {
    private MenuGameTests() {}

    static void adaptiveRowsPreserveAuthority(GameTestHelper helper) {
        var player = player(helper);
        var foreign = player(helper);
        var bag = bag(BackpackTier.NETHERITE, UpgradeKind.STACK_UPGRADE_TIER_4);
        bag.setItem(0, new ItemStack(Items.COBBLESTONE, 900));
        bag.setItem(72, new ItemStack(Items.DIAMOND, 11));
        bag.setItem(119, new ItemStack(Items.EMERALD, 7));
        player.getInventory().setItem(0, bag.stack());
        BackpackMenus.openInventory(player, 0);
        var menu = (BackpackMenu) player.containerMenu;
        var physicalSlots = List.copyOf(menu.slots);
        ItemStack expected = bag.stack().copy();
        int[] synchronizedRows = {-1};
        menu.addSlotListener(new ContainerListener() {
            @Override public void slotChanged(AbstractContainerMenu source, int index, ItemStack item) {}
            @Override public void dataChanged(AbstractContainerMenu source, int index, int value) {
                if (index == 3) synchronizedRows[0] = value;
            }
        });

        helper.assertValueEqual(menu.visibleRows(), 6, "The server initially authorizes six actual storage rows");
        helper.assertValueEqual(menu.pages(), 2, "A ten-row backpack initially has two pages");
        helper.assertValueEqual(menu.getSlot(0).x, 29, "Physical storage starts after the left upgrade rail");
        helper.assertValueEqual(menu.getSlot(0).y, 18, "Storage uses the shared layout's vertical origin");
        helper.assertValueEqual(menu.getSlot(menu.upgradeSlotStart() + 1).x, 6, "Physical upgrades remain on the left rail");
        helper.assertValueEqual(menu.getSlot(menu.upgradeSlotStart() + 1).y, 22, "Upgrade slots use sixteen-pixel spacing");
        helper.assertValueEqual(menu.getSlot(menuSlot(menu, player.getInventory(), 9)).x, 56, "The nine-column player inventory is centered below twelve-column storage");
        helper.assertValueEqual(menu.getSlot(menuSlot(menu, player.getInventory(), 9)).y, 140, "Player slots start at the shared six-row inventory position");
        menu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));

        for (int action : new int[]{200, 201, 202, 213, 299, Integer.MIN_VALUE, Integer.MAX_VALUE})
            button(player, menu.containerId, action);
        button(player, menu.containerId + 1, 203);
        helper.assertFalse(menu.clickMenuButton(foreign, 203), "A different actor cannot resize this menu");
        helper.assertValueEqual(menu.visibleRows(), 6, "Invalid row counts and stale container IDs cannot change the view");

        startDrag(menu, player, 60);
        button(player, menu.containerId, 203);
        finishDrag(menu, player);
        helper.assertTrue(bag.getItem(60).isEmpty() && menu.getCarried().getCount() == 3,
                "A row change cancels a retained one-slot drag before vanilla can write to its hidden target");
        helper.assertValueEqual(synchronizedRows[0], 3, "The fourth data slot broadcasts the accepted row request");
        helper.assertValueEqual(menu.visibleRows(), 3, "Three requested rows become authoritative");
        helper.assertValueEqual(menu.pages(), 4, "The same physical inventory now spans four pages");
        helper.assertTrue(menu.getSlot(35).isActive() && !menu.getSlot(36).isActive(), "The first page boundary follows three rows");
        startDrag(menu, player, 1, 2);
        button(player, menu.containerId, 1);
        finishDrag(menu, player);
        helper.assertTrue(bag.getItem(1).isEmpty() && bag.getItem(2).isEmpty() && menu.getCarried().getCount() == 3,
                "Changing pages cancels a multi-slot drag without distributing any cursor items to hidden cells");
        button(player, menu.containerId, 1);
        helper.assertValueEqual(menu.page(), 2, "Real page buttons reach the third three-row page");
        helper.assertTrue(menu.getSlot(72).isActive(), "The selected page owns its actual first physical cell");
        button(player, menu.containerId, 205);
        helper.assertValueEqual(menu.page(), 1, "Growing the view retains the prior first cell within the new page");
        helper.assertTrue(menu.getSlot(60).isActive() && menu.getSlot(72).isActive() && !menu.getSlot(59).isActive(), "Resizing preserves rank authority without retaining the wrong page number");
        assertStack(helper, bag.stack(), expected, "Changing row count and pages changes no stored data");
        helper.assertValueEqual(menu.getCarried().getCount(), 3, "Resizing leaves every carried item untouched");

        button(player, menu.containerId, 203);
        helper.assertTrue(menu.getSlot(60).isActive(), "Shrinking also retains the previous first visible cell");
        button(player, menu.containerId, 1);
        button(player, menu.containerId, 1);
        helper.assertValueEqual(menu.page(), 3, "The partially filled last page remains reachable");
        menu.clicked(menuSlot(menu, player.getInventory(), 9), 0, ClickType.PICKUP, player);
        menu.clicked(119, 0, ClickType.PICKUP, player);
        helper.assertValueEqual(menu.getCarried().getCount(), 7, "The active last-page slot transfers its real items");
        button(player, menu.containerId, 205);
        button(player, menu.containerId, 206);
        helper.assertFalse(menu.getSlot(119).isActive(), "A later resize can make that physical cell inactive");
        menu.clicked(119, 0, ClickType.PICKUP, player);
        helper.assertTrue(bag.getItem(119).isEmpty() && menu.getCarried().getCount() == 7, "A stale hidden-slot placement is rejected without losing the cursor");
        button(player, menu.containerId, 212);
        helper.assertValueEqual(synchronizedRows[0], 12, "The authoritative data retains the accepted request");
        helper.assertValueEqual(menu.visibleRows(), 10, "Actual visible rows cannot exceed the bag's ten rows");
        helper.assertValueEqual(menu.pages(), 1, "A full-height view clamps back to its only page");
        helper.assertValueEqual(menu.imageHeight(), 294, "A ten-row view has the agreed complete inventory height");
        menu.clicked(119, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "Returning to the now-active slot consumes the cursor exactly once");
        assertStack(helper, bag.stack(), expected, "The full resize and transfer sequence conserves the original backpack snapshot");
        helper.assertValueEqual(count(player.getInventory(), Items.GOLD_INGOT), 3, "The original carried gold is returned intact to player inventory");
        helper.assertTrue(physicalSlots.equals(menu.slots), "Row changes never replace or reorder slot ownership");

        menu.clicked(menuSlot(menu, player.getInventory(), 9), 0, ClickType.PICKUP, player);
        startDrag(menu, player, 60);
        button(player, menu.containerId, 210);
        button(player, menu.containerId, 1);
        finishDrag(menu, player);
        assertStack(helper, bag.getItem(60), Items.GOLD_INGOT, 3,
                "A clamped row request and a one-page cycle keep an unchanged view's valid drag intact");
        helper.assertTrue(menu.getCarried().isEmpty(), "The unchanged-view drag consumes exactly its three items");
        menu.clicked(60, 0, ClickType.PICKUP, player);
        menu.clicked(menuSlot(menu, player.getInventory(), 9), 0, ClickType.PICKUP, player);
        assertStack(helper, bag.stack(), expected, "Returning a valid drag's items restores the exact backpack snapshot");
        helper.assertValueEqual(count(player.getInventory(), Items.GOLD_INGOT), 3, "Both cancelled and completed drags conserve the original gold");

        player.getInventory().setItem(0, ItemStack.EMPTY);
        player.getInventory().setItem(1, bag.stack());
        button(player, menu.containerId, 203);
        helper.assertValueEqual(menu.visibleRows(), 10, "Even a matching button packet cannot resize a moved-source session");
        helper.assertFalse(menu.clickMenuButton(player, 203), "The direct menu path also rejects a stale source");
        player.closeContainer();
        helper.succeed();
    }

    static void adaptiveRowsRespectFilteredRanks(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.NETHERITE);
        bag.setItem(0, new ItemStack(Items.COBBLESTONE, 9));
        bag.setItem(83, new ItemStack(Items.GOLD_INGOT, 5));
        bag.setItem(95, new ItemStack(Items.COAL, 11));
        bag.setItem(96, new ItemStack(Items.DIAMOND, 13));
        bag.setItem(119, new ItemStack(Items.EMERALD, 17));
        player.getInventory().setItem(0, bag.stack());
        BackpackMenus.openInventory(player, 0);
        var menu = (BackpackMenu) player.containerMenu;
        ItemStack expected = bag.stack().copy();
        String mask = "0".repeat(24) + "1".repeat(96);
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, mask));
        button(player, menu.containerId, 203);
        helper.assertValueEqual(menu.pages(), 3, "Filtered pages use accepted row count and result ranks");
        button(player, menu.containerId, 1);
        button(player, menu.containerId, 1);
        helper.assertTrue(menu.getSlot(96).isActive() && !menu.getSlot(95).isActive(), "The last filtered page maps to the correct physical cells");
        menu.clicked(95, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "A hidden but matching cell cannot be picked up through its old address");
        button(player, menu.containerId, 205);
        helper.assertValueEqual(menu.page(), 1, "Resizing anchors the previous first filtered rank");
        helper.assertTrue(menu.getSlot(84).isActive() && menu.getSlot(96).isActive() && !menu.getSlot(83).isActive(), "The new filtered range does not confuse ranks with storage indices");
        menu.clicked(96, 0, ClickType.PICKUP, player);
        helper.assertValueEqual(menu.getCarried().getCount(), 13, "An active filtered address transfers the actual matching stack");
        startDrag(menu, player, 96);
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, "0".repeat(120)));
        finishDrag(menu, player);
        helper.assertTrue(bag.getItem(96).isEmpty() && menu.getCarried().getCount() == 13,
                "Changing the search mask cancels a retained drag before its target disappears");
        button(player, menu.containerId, 212);
        helper.assertValueEqual(menu.pages(), 1, "Zero filtered results still have one safe page");
        helper.assertValueEqual(menu.page(), 0, "An empty filtered view cannot retain an out-of-bounds page");
        helper.assertTrue(menu.slots.subList(0, bag.getContainerSize()).stream().noneMatch(slot -> slot.isActive()), "Growing an empty view cannot authorize any storage cell");
        menu.clicked(96, 0, ClickType.PICKUP, player);
        helper.assertValueEqual(menu.getCarried().getCount(), 13, "A hidden filtered destination cannot consume carried items");
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, mask));
        startDrag(menu, player, 96);
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, mask));
        finishDrag(menu, player);
        assertStack(helper, bag.getItem(96), Items.DIAMOND, 13, "Repeating the same search mask leaves a valid drag intact");
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, ""));
        helper.assertTrue(menu.getCarried().isEmpty(), "Restoring an authorized view permits the exact return transfer");
        assertStack(helper, bag.stack(), expected, "Filtered resizing preserves all physical items and their original locations");
        player.closeContainer();
        helper.succeed();
    }

    static void retainedUpgradeSelectionDoesNotOverlapRows(GameTestHelper helper) {
        var player = player(helper);
        ItemStack carrier = new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER));
        carrier.set(BagComponents.CONTENTS, new InventorySnapshot(256, List.of()));
        carrier.set(BagComponents.UPGRADES, new InventorySnapshot(128, List.of()));
        var bag = BagInventory.of(carrier);
        bag.upgrades().setItem(0, new ItemStack(BackpackRegistry.item(UpgradeKind.TANK)));
        bag.upgrades().setItem(1, new ItemStack(BackpackRegistry.item(UpgradeKind.TANK)));
        bag.setItem(255, new ItemStack(Items.IRON_INGOT, 5));
        player.getInventory().setItem(0, carrier);
        BackpackMenus.openInventory(player, 0);
        var menu = (BackpackMenu) player.containerMenu;
        helper.assertValueEqual(bag.getContainerSize(), 256, "A valid retained storage extent is not reduced to configured defaults");
        helper.assertValueEqual(menu.pages(), 4, "Twenty-two saved rows initially span four six-row pages");
        helper.assertValueEqual(menu.imageHeight(), 222, "A retained upgrade extent does not create a multi-thousand-pixel screen");
        menu.setUpgradeWindow(0, 0);
        helper.assertTrue(menu.getSlot(menu.upgradeSlotStart() + 127).isActive(), "Client rail-window hints cannot restrict authoritative server upgrade slots");
        button(player, menu.containerId, 1127);
        helper.assertValueEqual(menu.selectedSlot(), 127, "The new selection range reaches retained slots beyond the old row-action collision");
        for (int action : new int[]{200, 202, 213, 299, 1128}) button(player, menu.containerId, action);
        helper.assertValueEqual(menu.selectedSlot(), 127, "Malformed row requests and out-of-range selections cannot select another retained slot");
        helper.assertValueEqual(menu.visibleRows(), 6, "Invalid retained-slot requests do not change the row authority");
        button(player, menu.containerId, 1000);
        helper.assertValueEqual(menu.selectedSlot(), 0, "The new selection range also reaches the first upgrade slot");
        menu.setCarried(new ItemStack(Items.WATER_BUCKET));
        startDrag(menu, player, menu.auxiliaryStart());
        button(player, menu.containerId, 101);
        finishDrag(menu, player);
        helper.assertValueEqual(menu.selectedSlot(), 1, "The original normal-slot selection action remains supported");
        helper.assertTrue(bag.upgradeInventory(upgrade(bag, 0)).getItem(0).isEmpty()
                        && bag.upgradeInventory(upgrade(bag, 1)).getItem(0).isEmpty()
                        && menu.getCarried().is(Items.WATER_BUCKET) && menu.getCarried().getCount() == 1,
                "Selecting another upgrade cancels the old auxiliary drag instead of retargeting its physical item");
        startDrag(menu, player, menu.auxiliaryStart());
        button(player, menu.containerId, 101);
        finishDrag(menu, player);
        assertStack(helper, bag.upgradeInventory(upgrade(bag, 1)).getItem(0), Items.WATER_BUCKET, 1,
                "Selecting the same upgrade does not cancel its valid auxiliary drag");
        helper.assertTrue(menu.getCarried().isEmpty(), "The accepted auxiliary drag stores its bucket exactly once");
        button(player, menu.containerId, 212);
        helper.assertValueEqual(menu.visibleRows(), 12, "Retained storage is bounded to twelve visible rows per page");
        helper.assertValueEqual(menu.pages(), 2, "Rows beyond the viewport remain paged rather than discarded");
        button(player, menu.containerId, 1);
        helper.assertTrue(menu.getSlot(255).isActive(), "The final retained storage cell remains accessible");
        menu.clicked(255, 0, ClickType.QUICK_MOVE, player);
        helper.assertValueEqual(count(player.getInventory(), Items.IRON_INGOT), 5, "A retained last-page cell transfers its exact physical stack");
        helper.assertValueEqual(count(bag, Items.IRON_INGOT), 0, "The original retained source is consumed exactly once");
        helper.assertValueEqual(bag.upgrades().getContainerSize(), 128, "Rail paging and selections preserve the saved upgrade extent");
        ItemStack retainedPlayer = new ItemStack(BackpackRegistry.item(UpgradeKind.ADVANCED_JUKEBOX));
        var savedRecords = new net.minecraft.world.SimpleContainer(64);
        savedRecords.setItem(63, new ItemStack(Items.MUSIC_DISC_CAT));
        retainedPlayer.set(BagComponents.CONTENTS, InventorySnapshot.capture(savedRecords));
        menu.setCarried(retainedPlayer);
        menu.clicked(menu.upgradeSlotStart() + 2, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "A saved larger upgrade installs through the already-open physical rail");
        button(player, menu.containerId, 1002);
        helper.assertTrue(menu.getSlot(menu.auxiliaryStart() + 63).isActive(),
                "A newly installed retained inventory exposes its final physical slot without reopening");
        menu.clicked(menu.auxiliaryStart() + 63, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.MUSIC_DISC_CAT) && menu.getCarried().getCount() == 1
                        && bag.upgradeInventory(upgrade(bag, 2)).getItem(63).isEmpty(),
                "The final retained record can be picked up exactly once from the unchanged menu");
        menu.clicked(menu.auxiliaryStart() + 63, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty() && bag.upgradeInventory(upgrade(bag, 2)).getItem(63).is(Items.MUSIC_DISC_CAT),
                "Returning the final retained record preserves its original slot and count");
        ItemStack sameUpgrade = bag.upgrades().getItem(2);
        helper.assertTrue(menu.selected().isPresent(), "The installed upgrade is selected before an in-place count transition");
        sameUpgrade.setCount(0);
        helper.assertTrue(menu.selected().isEmpty(), "The cached selection never exposes an emptied stack");
        sameUpgrade.setCount(1);
        helper.assertTrue(menu.selected().filter(selected -> selected.stack() == sameUpgrade).isPresent(),
                "Restoring the same stack reference invalidates the empty cached selection");
        player.closeContainer();
        helper.succeed();
    }

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
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "A hidden search result cannot be clicked through the server menu");
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, "1"));
        send(player, new MenuAction(menu.containerId, "storage_view", 0, 0, "x".repeat(bag.getContainerSize())));
        helper.assertTrue(menu.getSlot(110).isActive() && !menu.getSlot(0).isActive(), "Malformed search masks cannot change the authorized view");
        menu.clicked(110, 0, ClickType.PICKUP, player);
        helper.assertValueEqual(menu.getCarried().getCount(), 17, "The selected cell transfers its real count exactly once");
        menu.clicked(110, 0, ClickType.PICKUP, player);
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
        helper.assertValueEqual(NbtAccess.getIntArray(bag.settings(), "no_sort").orElseThrow().length, bag.getContainerSize(), "Select-all protects every accessible cell, including empty cells");
        send(player, new MenuAction(menu.containerId, "no_sort_color", 0, 0, "#12ABEF"));
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(), "no_sort_color", 0), 0x12abef, "The configured overlay color is server validated");
        send(player, new MenuAction(menu.containerId, "no_sort_color", 0, 0, "../secret"));
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(), "no_sort_color", 0), 0x12abef, "An invalid color is inert");
        var template = com.kadamitas.fabricatedbackpacks.settings.SettingsTemplate.capture(bag);
        var other = bag(BackpackTier.LEATHER);
        template.apply(other);
        helper.assertValueEqual(NbtAccess.getIntOr(other.settings(), "no_sort_color", 0), 0x12abef, "Settings templates retain the overlay color");
        menu.clickMenuButton(player, 7);
        menu.clickMenuButton(player, 9);
        helper.assertTrue(bag.stack().get(com.kadamitas.fabricatedbackpacks.storage.BagComponents.MEMORY).entries().isEmpty(), "Clear-all removes memory only");
        helper.assertValueEqual(NbtAccess.getIntArray(bag.settings(), "no_sort").orElseThrow().length, 0, "Clear-all removes exclusions");
        helper.assertValueEqual(count(bag, Items.DIAMOND), 29, "Bulk settings never consume, duplicate or move actual storage");

        var defaults = bag(BackpackTier.LEATHER);
        defaults.updateSettings(tag -> tag.putString("sort_order", "tags"));
        helper.assertTrue(SettingsRuntime.action(defaults, player, "defaults_save", 0, ""),
                "The sort-order control can start from an inherited player preference");
        helper.assertFalse(bag.settings().contains("sort_order"), "The open bag has not overridden the inherited order");
        var physical = bag.stack().get(BagComponents.CONTENTS);
        var upgrades = bag.stack().get(BagComponents.UPGRADES);
        ItemStack cursor = new ItemStack(Items.PAPER, 7);
        menu.setCarried(cursor.copy());
        for (String expected : List.of("name", "count", "mod", "tags", "name")) {
            button(player, menu.containerId, 10);
            helper.assertValueEqual(NbtAccess.getStringOr(bag.settings(), "sort_order", ""), expected,
                    "The native button cycles the effective sort order and persists a bag override");
            helper.assertTrue(physical.equals(bag.stack().get(BagComponents.CONTENTS))
                            && upgrades.equals(bag.stack().get(BagComponents.UPGRADES)),
                    "Selecting an order changes no physical cell or upgrade");
            assertStack(helper, menu.getCarried(), cursor, "Selecting an order preserves the exact cursor stack");
        }
        var saved = BagInventory.of(roundTrip(helper.getLevel(), bag.stack()));
        helper.assertValueEqual(NbtAccess.getStringOr(saved.settings(), "sort_order", ""), "name",
                "The selected sort order survives the real item codec");
        helper.assertTrue(physical.equals(saved.stack().get(BagComponents.CONTENTS)),
                "Saving the order does not reorder physical storage");
        helper.assertValueEqual(NbtAccess.getStringOr(SettingsRuntime.effective(bag(BackpackTier.LEATHER), player), "sort_order", ""), "tags",
                "A bag-specific order change leaves the player's defaults intact");
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
        player.getInventory().selected = 0;
        player.setItemInHand(InteractionHand.MAIN_HAND, source.stack());
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 64));
        player.getMainHandItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.containerMenu instanceof BackpackMenu, "Actual held item use opens the registered backpack menu");
        var menu = (BackpackMenu) player.containerMenu;
        BagInventory bag = menu.bag();
        int input = menuSlot(menu, player.getInventory(), 9);
        menu.clicked(input, 0, ClickType.QUICK_MOVE, player);
        helper.assertValueEqual(count(bag, Items.COBBLESTONE), 964, "Shift-click joins a physical oversized bag stack");
        helper.assertValueEqual(count(player.getInventory(), Items.COBBLESTONE), 0, "Shift-click removes only the transferred player source");
        menu.clicked(bag.getContainerSize(), 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "Unsafe capacity upgrade cannot be picked up through the real slot");
        helper.assertTrue(bag.has(UpgradeKind.STACK_UPGRADE_TIER_4), "Rejected upgrade removal preserves installed state");
        menu.clicked(90, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.quickMoveStack(player, 90).isEmpty(), "Hidden-page shift-click is rejected");
        helper.assertValueEqual(bag.getItem(90).getCount(), 7, "Inactive page content remains untouched");
        var beforeForeign = bag.stack().copy();
        menu.clicked(0, 0, ClickType.PICKUP, foreign);
        helper.assertTrue(menu.quickMoveStack(foreign, 0).isEmpty(), "Foreign quick move is rejected");
        assertStack(helper, bag.stack(), beforeForeign, "Both foreign-player slot paths leave all bag state unchanged");
        helper.assertTrue(foreign.getMainHandItem().isEmpty() && menu.getCarried().isEmpty(), "Unauthorized attempts create no cursor or hand items");
        int carrier = menuSlot(menu, player.getInventory(), 0);
        menu.clicked(carrier, 0, ClickType.PICKUP, player);
        menu.clicked(1, 0, ClickType.SWAP, player);
        helper.assertTrue(player.getMainHandItem() == source.stack(), "Neither pickup nor number-key swapping can move the owning bag");
        menu.clicked(Integer.MAX_VALUE, 0, ClickType.PICKUP, player);
        menu.clicked(-1000, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "Invalid indices are inert");
        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertValueEqual(count(player.getInventory(), Items.COBBLESTONE) + count(bag, Items.COBBLESTONE), 964, "Exporting an oversized stack conserves every item");
        for (int slot = 0; slot < 36; slot++) helper.assertTrue(player.getInventory().getItem(slot).getCount() <= player.getInventory().getItem(slot).getMaxStackSize(), "Export never creates an oversized ordinary inventory stack");
        helper.assertTrue(menu.clickMenuButton(player, 1), "Page control is handled by the server menu");
        menu.clicked(90, 0, ClickType.QUICK_MOVE, player);
        helper.assertValueEqual(count(player.getInventory(), Items.DIAMOND), 7, "The same slot becomes accessible on its actual page");
        ItemStack prior = bag.stack().copy();
        player.getInventory().setItem(0, ItemStack.EMPTY);
        player.getInventory().setItem(1, source.stack());
        helper.assertFalse(menu.stillValid(player), "Moving the owning item invalidates the inventory session");
        menu.clicked(90, 0, ClickType.PICKUP, player);
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
        guestMenu.clicked(1, 0, ClickType.PICKUP, guest);
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
        stale.clicked(0, 0, ClickType.PICKUP, guest);
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
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.get(player).isEmpty(), "Taking the native slot removes exactly its current equipped item");
        helper.assertValueEqual(count(BagInventory.of(menu.getCarried()), Items.DIAMOND), 17, "Equipment screen cannot return an old snapshot from before automation updated the bag");
        menu.clicked(0, 0, ClickType.PICKUP, player);
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
        menu.clicked(0, 0, ClickType.PICKUP, player);
        int ancestorSlot = menuSlot(menu, player.getInventory(), 0);
        menu.clicked(ancestorSlot, 0, ClickType.THROW, player);
        helper.assertTrue(player.getInventory().getItem(0) == outer.stack(), "An active child view locks its physical ancestor against throwing");
        menu.setCarried(new ItemStack(BackpackRegistry.item(UpgradeKind.INCEPTION)));
        menu.clicked(menu.bag().getContainerSize(), 0, ClickType.PICKUP, player);
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
        stale.clicked(0, 0, ClickType.PICKUP, player);
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
        menu.clicked(0, 0, ClickType.PICKUP, player);
        player.closeContainer();
        helper.assertValueEqual(count(BagInventory.of(chest.getItem(0)), Items.IRON_INGOT), 4, "External-container edits persist into the physical source item");
        player.openMenu(chest);
        BackpackMenus.openSlot(player, 0);
        var stale = (BackpackMenu) player.containerMenu;
        ItemStack moved = chest.removeItemNoUpdate(0);
        helper.assertFalse(stale.stillValid(player), "Removing the source item closes external-container access");
        stale.clicked(0, 0, ClickType.PICKUP, player);
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
        menu.clicked(bagSlot, 0, ClickType.PICKUP, player);
        menu.clicked(items, 0, ClickType.PICKUP, player);
        helper.assertTrue(BackpackRegistry.isBackpack(menu.getCarried()), "Stashing with a held backpack keeps the backpack on the cursor");
        helper.assertTrue(player.getInventory().getItem(9).isEmpty(), "Cursor-backpack stash consumes exactly the actual source stack");
        helper.assertValueEqual(count(BagInventory.of(menu.getCarried()), Items.DIAMOND), 32, "Cursor-backpack stash inserts the entire fitting stack");
        menu.clicked(bagSlot, 0, ClickType.PICKUP, player);
        player.getInventory().setItem(9, new ItemStack(Items.EMERALD, 7));
        menu.clicked(items, 0, ClickType.PICKUP, player);
        menu.clicked(bagSlot, 0, ClickType.PICKUP, player);
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
        view.clicked(0, 0, ClickType.PICKUP, player);
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
        player.inventoryMenu.clicked(menuSlot(player.inventoryMenu, player.getInventory(), 0), 0, ClickType.PICKUP, player);
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

    private static void button(ServerPlayer player, int containerId, int action) {
        // Real vanilla packet handler with an embedded recipient; no rendered client is implied.
        player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(containerId, action));
    }

    private static void startDrag(BackpackMenu menu, ServerPlayer player, int... slots) {
        // Exercise the real server menu drag protocol; input/display timing remains a client-test concern.
        menu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(0, 0), ClickType.QUICK_CRAFT, player);
        for (int slot : slots)
            menu.clicked(slot, AbstractContainerMenu.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT, player);
    }

    private static void finishDrag(BackpackMenu menu, ServerPlayer player) {
        menu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(2, 0), ClickType.QUICK_CRAFT, player);
    }
}
