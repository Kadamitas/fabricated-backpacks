package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.client.mixin.ContainerScreenAccess;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackSettingsScreen;
import com.kadamitas.fabricatedbackpacks.client.screen.StorageToolsScreen;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Set;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.*;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Actual storage search, bulk settings and modifier-key transfer acceptance in the rendered client. */
final class StorageClientAcceptance {
    private StorageClientAcceptance() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            searchAndTransfers(context, world);
            largeStorageDrag(context, world);
        } finally {
            context.runOnClient(client -> { client.options.guiScale().set(previousScale); client.resizeGui(); });
        }
    }

    private static void searchAndTransfers(ClientGameTestContext context, TestSingleplayerContext world) {
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
        ConfiguredClientAcceptance.awaitLayout(context);
        check(context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().visibleRows()
                        < ((BackpackScreen) client.gui.screen()).getMenu().bag().rows()),
                "The small viewport deliberately exercises storage paging rather than the full-height layout");
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
        checkSettingsTooltips(context);
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

    static void checkSettingsTooltips(ClientGameTestContext context) {
        context.waitTicks(2);
        checkSettingsTooltips(context, false);
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        try {
            context.waitTicks(2);
            checkSettingsTooltips(context, true);
        } finally {
            context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        }
        context.waitTicks(2);
        checkSettingsTooltips(context, false);
    }

    private static void checkSettingsTooltips(ClientGameTestContext context, boolean expected) {
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof BackpackSettingsScreen, "Context-help checks run on the real backpack settings screen");
            List<AbstractWidget> widgets = client.gui.screen().children().stream()
                    .filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                    .filter(widget -> widget instanceof Button || widget instanceof EditBox).filter(widget -> widget.visible).toList();
            Set<String> expectedLabels = Set.of("Edit slots", "Equipment", "Slot tools", "Backpack name", "Rename",
                    "Keep search", "Keep tab", "Exact memory", "Shift into tab", "Share worn bag", "Use my defaults",
                    "Displayed storage slot", "Display slot", "Rotate45°", "Depth -", "Depth +", "Settings template name",
                    "<", ">", "Save", "Preview", "Load", "Delete", "Export pack", "Save as my defaults", "Back to backpack");
            Set<String> actualLabels = widgets.stream().map(StorageClientAcceptance::canonicalSettingLabel)
                    .collect(java.util.stream.Collectors.toSet());
            check(widgets.size() == 26 && actualLabels.equals(expectedLabels),
                    "Every settings control is included in contextual-help coverage: " + actualLabels);
            for (AbstractWidget widget : widgets) {
                String label = canonicalSettingLabel(widget);
                var tooltip = ((com.kadamitas.fabricatedbackpacks.gametest.mixin.TestWidgetTooltipAccess) (Object) widget)
                        .fabricatedBackpacksTests$tooltip().get();
                if (!expected) {
                    check(tooltip == null, "Settings context help stays hidden without Shift: " + label);
                    continue;
                }
                check(tooltip != null, "Holding Shift attaches context help to settings control: " + label);
                String help = tooltip.toCharSequence(client).stream().map(ConfiguredClientAcceptance::plain)
                        .collect(java.util.stream.Collectors.joining(" ")).replaceAll("\\s+", " ").strip();
                check(!help.isBlank() && !help.equalsIgnoreCase(widget.getMessage().getString()),
                        "Settings help explains the control instead of repeating its label: " + label + " -> " + help);
                if (label.equals("Depth -")) check(help.contains("outward") && help.contains("1/16") && help.contains("-16") && help.contains("16"),
                        "Depth - explains direction, step size, and bounded range: " + help);
                if (label.equals("Depth +")) check(help.contains("deeper") && help.contains("1/16") && help.contains("-16") && help.contains("16"),
                        "Depth + explains direction, step size, and bounded range: " + help);
            }
        });
    }

    private static String canonicalSettingLabel(AbstractWidget widget) {
        return widget.getMessage().getString().replaceFirst(": (?:On|Off)$", "");
    }

    private static void largeStorageDrag(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().waitFor(server -> player(world).containerMenu == player(world).inventoryMenu);
        ItemStack original = world.getServer().computeOnServer(server -> player(world).getInventory().getItem(5).copy());
        int otherCobble = world.getServer().computeOnServer(server -> count(player(world).getInventory(), Items.COBBLESTONE));
        int[] window = context.computeOnClient(client -> new int[]{client.getWindow().getScreenWidth(),
                client.getWindow().getScreenHeight(), client.options.guiScale().get()});
        try {
            world.getServer().runOnServer(server -> {
                check(player(world).containerMenu.getCarried().isEmpty(), "The drag fixture starts with a clear cursor");
                ItemStack stack = new ItemStack(BackpackRegistry.item(BackpackTier.NETHERITE));
                // A legitimately retained larger snapshot, independent of the restored server defaults.
                stack.set(BagComponents.CONTENTS, new InventorySnapshot(144, List.of()));
                var bag = BagInventory.of(stack);
                bag.upgrades().setItem(0, new ItemStack(BackpackRegistry.item(UpgradeKind.STACK_UPGRADE_TIER_4)));
                check(bag.capacity(new ItemStack(Items.COBBLESTONE)) >= 1000, "The fixture permits the enlarged physical stack");
                bag.setItem(143, new ItemStack(Items.COBBLESTONE, 1000));
                player(world).getInventory().setItem(5, bag.stack());
                player(world).inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
            BrowserClientAcceptance.openHovered(context, 5);
            ConfiguredClientAcceptance.awaitLayout(context);
            world.getServer().waitFor(server -> player(world).containerMenu instanceof BackpackMenu menu && menu.visibleRows() == 12);
            int menuId = context.computeOnClient(client -> client.player.containerMenu.containerId);
            clickSlot(context, 143);
            world.getServer().waitFor(server -> player(world).containerMenu.getCarried().getCount() == 1000);
            context.waitFor(client -> client.player.containerMenu.getCarried().getCount() == 1000);
            check(world.getServer().computeOnServer(server -> storage(world).isEmpty()), "A real pickup moves all 1000 items to the cursor");
            cancelHeldDragOnResize(context, world, menuId, otherCobble);
            dragAcrossStorage(context, dragSlotPositions(context));
            context.waitTicks(3);
            checkConnectedDrag(context, menuId);
            world.getServer().waitFor(server -> count(storage(world), Items.COBBLESTONE) == 960
                    && player(world).containerMenu.getCarried().getCount() == 40);
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.player.containerMenu instanceof BackpackMenu menu
                    && count(menu.bag(), Items.COBBLESTONE) == 960 && menu.getCarried().getCount() == 40);
            world.getServer().runOnServer(server -> {
                var menu = (BackpackMenu) player(world).containerMenu;
                check(menu.containerId == menuId && menu.stillValid(player(world)), "The real server menu remains valid after drag release");
                assertBoundedDrag(menu);
                check(count(player(world).getInventory(), Items.COBBLESTONE) == otherCobble, "Dragging never leaks items into player inventory");
            });
            context.runOnClient(client -> assertBoundedDrag((BackpackMenu) client.player.containerMenu));
            context.takeScreenshot("storage-drag144-bounded-result");
            clickSlot(context, 143);
            world.getServer().waitFor(server -> player(world).containerMenu.getCarried().isEmpty()
                    && count(storage(world), Items.COBBLESTONE) == 1000);
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.player.containerMenu.getCarried().isEmpty()
                    && count(((BackpackMenu) client.player.containerMenu).bag(), Items.COBBLESTONE) == 1000);
            check(world.getServer().computeOnServer(server -> storage(world).getItem(143).getCount()) == 40,
                    "The remainder returns through a real click into an untouched cell");
            checkConnectedDrag(context, menuId);
        } finally {
            restoreDragFixture(context, world, original, window);
        }
    }

    private static void cancelHeldDragOnResize(ClientGameTestContext context, TestSingleplayerContext world, int menuId, int otherCobble) {
        var expectedCursor = new ItemStack(Items.COBBLESTONE, 1000);
        long originalDrops = world.getServer().computeOnServer(server -> nearbyDroppedCobble(world));
        for (boolean releaseOutside : new boolean[]{false, true}) {
            int oldRows = context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().visibleRows());
            int firstIndex = releaseOutside ? 2 : 0; // Distinct starts avoid vanilla's time-based double-click path.
            double[] first = storageSlotPosition(context, firstIndex);
            context.getInput().setCursorPos(first[0] + 1, first[1]);
            context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            try {
                context.getInput().setCursorPos(first[0], first[1]);
                context.waitTick();
                double[] second = storageSlotPosition(context, firstIndex + 1);
                context.getInput().setCursorPos(second[0], second[1]);
                context.waitTick();
                context.runOnClient(client -> assertNativeHeldDrag((BackpackScreen) client.gui.screen(), firstIndex));
                int scale = releaseOutside ? 2 : 3;
                context.runOnClient(client -> { client.options.guiScale().set(scale); client.resizeGui(); });
                ConfiguredClientAcceptance.awaitLayout(context);
                int newRows = context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().visibleRows());
                check(newRows != oldRows, "The held drag crosses an actual viewport-row layout change");
                world.getServer().waitFor(server -> player(world).containerMenu instanceof BackpackMenu menu && menu.visibleRows() == newRows);
                double[] release = releaseOutside ? new double[]{1, 1} : storageSlotPosition(context, 0);
                if (releaseOutside) context.runOnClient(client -> {
                    var origin = (ContainerScreenAccess) (Object) client.gui.screen();
                    check(origin.fabricatedBackpacks$left() > 1 && origin.fabricatedBackpacks$top() > 1,
                            "The second release is genuinely outside the resized container");
                });
                context.getInput().setCursorPos(release[0], release[1]);
                context.waitTick();
            } finally { context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT); }
            world.getConnection().waitForServerboundPackets();
            world.getConnection().waitForClientboundPackets();
            context.waitTicks(2);
            checkConnectedDrag(context, menuId);
            check(world.getServer().computeOnServer(server -> {
                var menu = (BackpackMenu) player(world).containerMenu;
                return menu.containerId == menuId && menu.stillValid(player(world)) && menu.bag().isEmpty()
                        && ItemStack.matches(menu.getCarried(), expectedCursor)
                        && count(player(world).getInventory(), Items.COBBLESTONE) == otherCobble
                        && nearbyDroppedCobble(world) == originalDrops;
            }), "A resized held drag cannot place or drop any of the1000 cursor items; outside=" + releaseOutside);
            check(context.computeOnClient(client -> {
                var menu = (BackpackMenu) client.player.containerMenu;
                return menu.bag().isEmpty() && ItemStack.matches(menu.getCarried(), expectedCursor)
                        && count(client.player.getInventory(), Items.COBBLESTONE) == otherCobble;
            }), "The resized client keeps the exact cursor and empty storage after release; outside=" + releaseOutside);
            context.takeScreenshot("storage-drag-resize-cancel-" + (releaseOutside ? "outside" : "on-slot"));
        }
    }

    private static long nearbyDroppedCobble(TestSingleplayerContext world) {
        var player = player(world);
        return player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(32)).stream()
                .filter(entity -> entity.getItem().is(Items.COBBLESTONE)).mapToLong(entity -> entity.getItem().getCount()).sum();
    }

    private static void assertNativeHeldDrag(BackpackScreen screen, int first) {
        try {
            var active = AbstractContainerScreen.class.getDeclaredField("isQuickCrafting");
            var targets = AbstractContainerScreen.class.getDeclaredField("quickCraftSlots");
            active.setAccessible(true);
            targets.setAccessible(true);
            var actualTargets = (java.util.Set<?>) targets.get(screen);
            check(active.getBoolean(screen) && actualTargets.size() == 2
                            && actualTargets.contains(screen.getMenu().getSlot(first)) && actualTargets.contains(screen.getMenu().getSlot(first + 1)),
                    "Actual held mouse input must enter native quick-crafting across both real cells before resize");
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot observe native held drag state", failure); }
    }

    private static double[] storageSlotPosition(ClientGameTestContext context, int index) {
        return context.computeOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var slot = screen.getMenu().getSlot(index);
            var origin = (ContainerScreenAccess) (Object) screen;
            check(slot.isActive() && slot.container == screen.getMenu().bag(), "The drag uses an active physical storage cell: " + index);
            return new double[]{(origin.fabricatedBackpacks$left() + slot.x + 8.0) * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
                    (origin.fabricatedBackpacks$top() + slot.y + 8.0) * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight()};
        });
    }

    private static double[][] dragSlotPositions(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var menu = screen.getMenu();
            check(client.getWindow().getGuiScale() == 2, "The real drag runs at GUI scale 2");
            check(menu.bag().getContainerSize() == 144 && menu.bag().columns() == 12 && menu.visibleRows() == 12
                    && menu.pages() == 1 && menu.page() == 0, "All 144 storage cells are visible without paging");
            var origin = (ContainerScreenAccess) (Object) screen;
            var distinct = new java.util.HashSet<String>();
            double[][] points = new double[144][2];
            for (int index = 0; index < points.length; index++) {
                var slot = menu.getSlot(index);
                check(slot.isActive() && slot.container == menu.bag() && slot.getContainerSlot() == index,
                        "The drag target is the actual active physical storage cell " + index);
                int x = origin.fabricatedBackpacks$left() + slot.x;
                int y = origin.fabricatedBackpacks$top() + slot.y;
                check(x >= 0 && y >= 0 && x + 16 <= screen.width && y + 16 <= screen.height,
                        "Every drag target fits the rendered viewport: " + index);
                check(distinct.add(x + "," + y), "Distinct physical storage cells have distinct mouse targets");
                points[index][0] = (x + 8.0) * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth();
                points[index][1] = (y + 8.0) * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight();
            }
            return points;
        });
    }

    private static void dragAcrossStorage(ClientGameTestContext context, double[][] points) {
        context.getInput().setCursorPos(points[0][0] + 1, points[0][1]);
        context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        try {
            for (int row = 0; row < 12; row++) for (int column = 0; column < 12; column++) {
                int slot = row * 12 + (row % 2 == 0 ? column : 11 - column);
                context.getInput().setCursorPos(points[slot][0], points[slot][1]);
                context.waitTick();
            }
            context.takeScreenshot("storage-drag144-held-preview");
        } finally {
            context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
    }

    private static void assertBoundedDrag(BackpackMenu menu) {
        int occupied = 0;
        for (int slot = 0; slot < 144; slot++) {
            ItemStack stack = menu.bag().getItem(slot);
            if (!stack.isEmpty()) occupied++;
            if (slot < 120) check(stack.is(Items.COBBLESTONE) && stack.getCount() == 8,
                    "The first 120 drag targets each receive exactly eight items: " + slot);
            else check(stack.isEmpty(), "Crossing additional cells cannot exceed the 120-target drag limit: " + slot);
        }
        check(occupied == 120, "The real drag reaches exactly 120 targets, not a smaller incidental subset");
        check(menu.getCarried().is(Items.COBBLESTONE) && menu.getCarried().getCount() == 40,
                "The forty undistributed items stay on the enlarged cursor");
        check(count(menu.bag(), Items.COBBLESTONE) + menu.getCarried().getCount() == 1000,
                "Stored plus cursor contents conserve all 1000 items");
    }

    private static void checkConnectedDrag(ClientGameTestContext context, int menuId) {
        context.runOnClient(client -> check(client.player != null && client.getConnection() != null
                        && client.getConnection().getConnection().isConnected()
                        && client.gui.screen() instanceof BackpackScreen screen
                        && screen.getMenu() == client.player.containerMenu && screen.getMenu().containerId == menuId,
                "The connected client keeps the same live backpack menu after the actual drag packet is sent"));
    }

    private static void restoreDragFixture(ClientGameTestContext context, TestSingleplayerContext world,
                                            ItemStack original, int[] window) {
        boolean connected = context.computeOnClient(client -> client.player != null && client.getConnection() != null
                && client.getConnection().getConnection().isConnected());
        if (connected) {
            if (context.computeOnClient(client -> client.gui.screen() instanceof BackpackScreen)) {
                context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                context.waitFor(client -> client.gui.screen() == null);
            }
            world.getServer().waitFor(server -> player(world).containerMenu == player(world).inventoryMenu);
            world.getServer().runOnServer(server -> {
                player(world).getInventory().setItem(5, original.copy());
                player(world).inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> ItemStack.matches(client.player.getInventory().getItem(5), original));
        }
        context.getInput().resizeWindow(window[0], window[1]);
        context.runOnClient(client -> { client.options.guiScale().set(window[2]); client.resizeGui(); });
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
