package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackSettingsScreen;
import com.kadamitas.fabricatedbackpacks.client.screen.StorageToolsScreen;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.*;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Actual storage search, bulk settings and modifier-key transfer acceptance in the rendered client. */
final class StorageClientAcceptance {
    private StorageClientAcceptance() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var bag = bag(BackpackTier.NETHERITE);
            bag.setItem(110, new ItemStack(Items.EMERALD, 37));
            bag.setItem(0, new ItemStack(Items.COBBLESTONE, 12));
            bag.remember(3, new ItemStack(Items.DIAMOND));
            var player = player(world);
            player.getInventory().setItem(5, bag.stack());
            for (int slot = 9; slot < 36; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
            player.getInventory().setItem(10, new ItemStack(Items.EMERALD, 10));
            player.getInventory().setItem(11, new ItemStack(Items.GOLD_INGOT, 4));
            player.getInventory().setItem(12, new ItemStack(Items.DIAMOND));
            player.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
        BrowserClientAcceptance.openHovered(context, 5);
        searchBrowser(context, "@minecraft \"emerald\"");
        context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().getSlot(110).isActive());
        world.getServer().waitFor(server -> player(world).containerMenu.getSlot(110).isActive());
        check(context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().storageRank(110)) == 0,
                "A later physical slot reflows into the first visible search cell");
        check(!storagePageEnabled(context), "A one-page search disables the storage page arrow");
        check(!noResultsVisible(context), "A matching search does not show the empty-state label");
        context.takeScreenshot("storage-search-reflow");
        clickSlot(context, 110);
        clickPlayerSlot(context, 14);
        world.getServer().waitFor(server -> player(world).getInventory().getItem(14).getCount() == 37);
        searchBrowser(context, "no_such_item_qa");
        context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().filteredSize() == 0);
        check(context.computeOnClient(client -> client.player.containerMenu.slots.subList(0, 120).stream().noneMatch(slot -> slot.isActive())),
                "No-result search hides every physical storage click target");
        check(noResultsVisible(context), "A no-result search explicitly labels its empty storage area");
        check(!storagePageEnabled(context), "A no-result search disables meaningless page navigation");
        context.takeScreenshot("storage-search-no-results");
        searchBrowser(context, "");
        context.waitFor(client -> !((BackpackScreen) client.gui.screen()).getMenu().filtering());
        check(storagePageEnabled(context), "Clearing search restores multi-page storage navigation");
        check(!noResultsVisible(context), "Clearing search removes the empty-state label");
        clickButton(context, "Prefs");
        context.waitForScreen(BackpackSettingsScreen.class);
        clickButton(context, "Slot tools");
        context.waitForScreen(StorageToolsScreen.class);
        clickButton(context, "Remember occupied");
        world.getServer().waitFor(server -> storage(world).stack().get(BagComponents.MEMORY).entries().size() == 2);
        clickButton(context, "Exclude all from sort");
        world.getServer().waitFor(server -> storage(world).settings().getIntArray("no_sort").orElseThrow().length == 120);
        clickButton(context, "Clear sort exclusions");
        searchBrowser(context, "#25BC8A");
        clickButton(context, "Set overlay color");
        world.getServer().waitFor(server -> storage(world).settings().getIntOr("no_sort_color", 0) == 0x25bc8a);
        clickButton(context, "Store matching");
        world.getServer().waitFor(server -> count(storage(world), Items.DIAMOND) == 1);
        check(world.getServer().computeOnServer(server -> count(storage(world), Items.GOLD_INGOT)) == 0, "Ordinary bulk clicks transfer matching contents and memory only");
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        clickButton(context, "Store matching");
        context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        world.getServer().waitFor(server -> count(storage(world), Items.GOLD_INGOT) == 4);
        check(world.getServer().computeOnServer(server -> count(storage(world), Items.EMERALD)) == 47, "Shift bulk-store preserves all emeralds across multiple input stacks");
        clickButton(context, "Exclude all from sort");
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        clickButton(context, "Take matching");
        context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        check(world.getServer().computeOnServer(server -> count(storage(world), Items.EMERALD)) == 47, "Excluded cells remain protected even in all-transfer mode");
        clickButton(context, "Clear sort exclusions");
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        clickButton(context, "Take matching");
        context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        world.getServer().waitFor(server -> storage(world).isEmpty());
        check(world.getServer().computeOnServer(server -> count(player(world).getInventory(), Items.EMERALD)) == 47, "Actual all-transfer output conserves the full quantity");
        context.takeScreenshot("storage-bulk-controls-and-color");
        clickButton(context, "Back");
        clickButton(context, "Back to backpack");
        context.waitForScreen(BackpackScreen.class);
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null);
    }

    private static boolean storagePageEnabled(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.gui.screen().children().stream().filter(Button.class::isInstance)
                .map(Button.class::cast).filter(button -> button.getMessage().getString().equals(">")).findFirst().orElseThrow().active);
    }
    private static boolean noResultsVisible(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.gui.screen().children().stream().filter(StringWidget.class::isInstance)
                .map(StringWidget.class::cast).filter(label -> label.getMessage().getString().equals("No matching items"))
                .findFirst().orElseThrow().visible);
    }
    private static BagInventory storage(TestSingleplayerContext world) { return BagInventory.of(player(world).getInventory().getItem(5)); }
}
