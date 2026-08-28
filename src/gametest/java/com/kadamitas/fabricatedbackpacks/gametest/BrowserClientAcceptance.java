package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.*;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** End-user browser actions operate the server's real recipe, cursor, and ghost-filter handlers. */
final class BrowserClientAcceptance {
    private BrowserClientAcceptance() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var bag = bag(BackpackTier.DIAMOND, UpgradeKind.CRAFTING, UpgradeKind.STONECUTTER, UpgradeKind.ADVANCED_FILTER, UpgradeKind.SMELTING);
            bag.setItem(0, new ItemStack(Items.OAK_PLANKS, 12));
            bag.setItem(1, new ItemStack(Items.STONE, 10));
            bag.setItem(2, new ItemStack(Items.RAW_IRON, 5));
            bag.updateSettings(upgrade(bag, 2), settings -> settings.putBoolean("enabled", false));
            bag.updateSettings(upgrade(bag, 3), settings -> settings.putBoolean("enabled", false));
            bag.upgradeInventory(upgrade(bag, 3)).setItem(1, new ItemStack(Items.COAL, 3));
            bag.upgradeInventory(upgrade(bag, 3)).setItem(2, new ItemStack(Items.GOLD_NUGGET, 5));
            player(world).getInventory().setItem(26, new ItemStack(Items.NAUTILUS_SHELL));
            player(world).getInventory().setItem(3, bag.stack());
            player(world).inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
        openHovered(context, 3);
        clickButton(context, "1");
        context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 0);
        context.waitFor(client -> client.gui.screen().children().stream().anyMatch(widget -> widget instanceof AbstractWidget button && button.getMessage().getString().equals("Open station")));
        clickButton(context, "Open station");
        context.waitForScreen(CraftingScreen.class);
        int originalMenu = context.computeOnClient(client -> client.player.containerMenu.containerId);
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            context.getInput().pressKey(GLFW.GLFW_KEY_O);
            waitBrowser(context);
            context.waitTicks(3);
            context.takeScreenshot("browser-empty-search-gui-three");
            checkEmptySearch(context);
        } finally {
            context.runOnClient(client -> { client.options.guiScale().set(originalScale); client.resizeGui(); });
        }
        searchBrowser(context, "@minecraft \"crafting table\"");
        context.waitTicks(40);
        clickButton(context, "Crafting Table");
        context.waitTicks(3);
        clickButton(context, "☆");
        clickButton(context, "Save recipe");
        clickButton(context, "Uses");
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_ALT);
        context.getInput().pressKey(GLFW.GLFW_KEY_LEFT);
        context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_ALT);
        context.waitTicks(3);
        clickButton(context, "Recipes");
        context.takeScreenshot("browser-recipe-transfer-before");
        clickButton(context, "Transfer recipe");
        context.waitForScreen(CraftingScreen.class);
        world.getServer().waitFor(server -> player(world).containerMenu.slots.get(0).getItem().is(Items.CRAFTING_TABLE));
        check(context.computeOnClient(client -> client.player.containerMenu.containerId) == originalMenu, "Recipe browser preserves the actual portable container");
        check(world.getServer().computeOnServer(server -> count(((WorkstationMenus.PortableCrafting) player(world).containerMenu).grid(), Items.OAK_PLANKS)) == 4,
                "Transfer places exactly four real ingredients");
        clickSlot(context, 0);
        clickPlayerSlot(context, 25);
        world.getServer().waitFor(server -> player(world).getInventory().getItem(25).is(Items.CRAFTING_TABLE));
        check(world.getServer().computeOnServer(server -> count(((WorkstationMenus.PortableCrafting) player(world).containerMenu).grid(), Items.OAK_PLANKS)) == 0,
                "Taking the result consumes all four ingredients once");
        context.takeScreenshot("browser-recipe-transfer-crafted");

        context.getInput().pressKey(GLFW.GLFW_KEY_O);
        waitBrowser(context);
        searchBrowser(context, "@minecraft \"crafting table\"");
        clickButton(context, "Crafting Table");
        awaitButton(context, "Transfer max");
        clickButton(context, "Transfer max");
        context.waitForScreen(CraftingScreen.class);
        world.getServer().waitFor(server -> count(((WorkstationMenus.PortableCrafting) player(world).containerMenu).grid(), Items.OAK_PLANKS) == 8);
        check(world.getServer().computeOnServer(server -> count(((WorkstationMenus.PortableCrafting) player(world).containerMenu).backpack(), Items.OAK_PLANKS)) == 0,
                "The maximum button moves the two remaining complete sets without inventing ingredients");
        context.takeScreenshot("browser-maximum-crafting-inputs");
        for (int batch = 0; batch < 2; batch++) { clickSlot(context, 0); clickPlayerSlot(context, 25); }
        world.getServer().waitFor(server -> player(world).getInventory().getItem(25).is(Items.CRAFTING_TABLE)
                && player(world).getInventory().getItem(25).getCount() == 3);
        check(world.getServer().computeOnServer(server -> count(((WorkstationMenus.PortableCrafting) player(world).containerMenu).grid(), Items.OAK_PLANKS)) == 0,
                "Three actual result takes consume the twelve original planks once");

        clickPlayerSlot(context, 26);
        clickSlot(context, 5);
        awaitButton(context, "Choose recipe");
        clickButton(context, "Choose recipe");
        context.waitForScreen(com.kadamitas.fabricatedbackpacks.client.screen.WorkstationChoiceScreen.class);
        clickButton(context, "Copper Ingot");
        context.waitForScreen(CraftingScreen.class);
        world.getServer().waitFor(server -> player(world).containerMenu.slots.get(0).getItem().is(Items.COPPER_INGOT));
        clickSlot(context, 0);
        clickPlayerSlot(context, 27);
        world.getServer().waitFor(server -> player(world).containerMenu.slots.get(5).getItem().is(Items.IRON_NUGGET));
        clickSlot(context, 5);
        clickPlayerSlot(context, 26);
        check(world.getServer().computeOnServer(server -> player(world).getInventory().getItem(26).is(Items.IRON_NUGGET)),
                "The selected conflict recipe uses its own real remainder");
        context.takeScreenshot("crafting-conflict-picked-and-crafted");
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null);

        openHovered(context, 3);
        clickSlot(context, 1);
        clickPlayerSlot(context, 29);
        clickButton(context, "2");
        context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 1);
        awaitButton(context, "Open station");
        clickButton(context, "Open station");
        context.waitForScreen(net.minecraft.client.gui.screens.inventory.StonecutterScreen.class);
        context.getInput().pressKey(GLFW.GLFW_KEY_O);
        waitBrowser(context);
        searchBrowser(context, "@minecraft \"stone slab\"");
        clickButton(context, "Stone Slab");
        selectCategory(context, "Stonecutting");
        awaitButton(context, "Transfer max");
        clickButton(context, "Transfer max");
        context.waitForScreen(net.minecraft.client.gui.screens.inventory.StonecutterScreen.class);
        world.getServer().waitFor(server -> player(world).containerMenu.getSlot(0).getItem().is(Items.STONE)
                && player(world).containerMenu.getSlot(0).getItem().getCount() == 10);
        check(world.getServer().computeOnServer(server -> player(world).getInventory().getItem(29).isEmpty()),
                "The stonecutting browser moves the ten physical player-owned inputs");
        context.takeScreenshot("browser-stonecutting-maximum-inputs");
        awaitButton(context, "All recipes");
        clickButton(context, "All recipes");
        context.waitForScreen(com.kadamitas.fabricatedbackpacks.client.screen.WorkstationChoiceScreen.class);
        context.takeScreenshot("stonecutter-large-result-grid");
        searchBrowser(context, "fixture_stone_12");
        clickButton(context, "Cracked Stone Bricks");
        context.waitForScreen(net.minecraft.client.gui.screens.inventory.StonecutterScreen.class);
        world.getServer().waitFor(server -> player(world).containerMenu.slots.get(1).getItem().is(Items.CRACKED_STONE_BRICKS));
        clickSlot(context, 1);
        clickPlayerSlot(context, 29);
        world.getServer().waitFor(server -> player(world).getInventory().getItem(29).is(Items.CRACKED_STONE_BRICKS));
        check(world.getServer().computeOnServer(server -> player(world).containerMenu.slots.get(0).getItem().getCount()) == 9,
                "Picking a searched stonecutter recipe consumes exactly one real input");
        awaitButton(context, "Recent 1");
        context.takeScreenshot("stonecutter-search-and-recent-choice");
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null);

        openHovered(context, 3);
        clickButton(context, "3");
        context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 2);
        double[] ghost = context.computeOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            return new double[]{(screen.width - screen.getMenu().imageWidth()) / 2.0 + screen.getMenu().storageWidth() + 59,
                    (screen.height - screen.getMenu().imageHeight()) / 2.0 + 62};
        });
        clickAt(context, ghost[0], ghost[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        waitBrowser(context);
        searchBrowser(context, "@minecraft \"diamond\" -ore -block -sword -axe -pickaxe -shovel -hoe -helmet -chestplate -leggings -boots -horse");
        context.waitTicks(12);
        clickButton(context, "Diamond");
        clickButton(context, "Set filter");
        context.waitForScreen(BackpackScreen.class);
        world.getServer().waitFor(server -> {
            var bag = BagInventory.of(player(world).getInventory().getItem(3));
            return bag.ghost(upgrade(bag, 2), 0).is(Items.DIAMOND);
        });
        check(world.getServer().computeOnServer(server -> player(world).containerMenu.getCarried().isEmpty()), "Selecting a ghost never grants a cursor item");
        check(world.getServer().computeOnServer(server -> count(BagInventory.of(player(world).getInventory().getItem(3)), Items.DIAMOND)) == 0,
                "Selecting a ghost does not insert an actual diamond");
        context.takeScreenshot("browser-ghost-filter");
        clickButton(context, "Items");
        waitBrowser(context);
        var openBrowser = context.computeOnClient(client -> client.gui.screen());
        int browserMenu = context.computeOnClient(client -> client.player.containerMenu.containerId);
        var serverRecipes = world.getServer().computeOnServer(server -> server.getRecipeManager());
        var clientRecipes = context.computeOnClient(client -> client.player.getRecipeBook().getCollections());
        world.getServer().runCommand("reload");
        world.getServer().waitFor(server -> server.getRecipeManager() != serverRecipes);
        context.waitFor(client -> client.player.getRecipeBook().getCollections() != clientRecipes);
        check(context.computeOnClient(client -> client.gui.screen() == openBrowser
                        && client.player.containerMenu.containerId == browserMenu),
                "A data reload preserves the same valid browser screen and authoritative container");
        String reloadLabel = context.computeOnClient(client -> net.minecraft.network.chat.Component
                .translatable("browser.fabricated_backpacks.refresh").getString());
        clickButton(context, reloadLabel);
        context.waitTicks(30);
        searchBrowser(context, "@minecraft \"crafting table\"");
        clickButton(context, "Crafting Table");
        context.waitFor(client -> client.gui.screen().children().stream().anyMatch(widget -> widget instanceof AbstractWidget button && button.getMessage().getString().equals("★")));
        context.takeScreenshot("browser-reload-and-bookmarks");
        world.getServer().runOnServer(server -> player(world).closeContainer());
        context.waitFor(client -> client.gui.screen() == null);

        openHovered(context, 3);
        clickButton(context, "4");
        context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 3);
        clickButton(context, "Items");
        waitBrowser(context);
        searchBrowser(context, "@minecraft \"raw iron\" -block");
        clickButton(context, "Raw Iron");
        clickButton(context, "Uses");
        selectCategory(context, "Smelting");
        awaitButton(context, "Transfer max");
        clickButton(context, "Transfer max");
        context.waitForScreen(BackpackScreen.class);
        world.getServer().waitFor(server -> {
            var bag = BagInventory.of(player(world).getInventory().getItem(3));
            return bag.upgradeInventory(upgrade(bag, 3)).getItem(0).getCount() == 5;
        });
        check(world.getServer().computeOnServer(server -> {
            var bag = BagInventory.of(player(world).getInventory().getItem(3));
            var cooking = bag.upgradeInventory(upgrade(bag, 3));
            return count(bag, Items.RAW_IRON) == 0 && cooking.getItem(0).is(Items.RAW_IRON)
                    && cooking.getItem(1).is(Items.COAL) && cooking.getItem(1).getCount() == 3
                    && cooking.getItem(2).is(Items.GOLD_NUGGET) && cooking.getItem(2).getCount() == 5;
        }), "The cooking browser changes only owned recipe inputs, preserving real fuel and old output");
        context.takeScreenshot("browser-cooking-maximum-inputs");
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null);
    }

    private static void checkEmptySearch(ClientGameTestContext context) {
        context.runOnClient(client -> {
            check(client.getWindow().getGuiScale() == 3, "The empty browser is checked at actual GUI scale three");
            var search = client.gui.screen().children().stream().filter(EditBox.class::isInstance)
                    .map(EditBox.class::cast).findFirst().orElseThrow();
            check(search.getValue().isEmpty() && !search.isFocused(), "The newly opened browser displays its empty search hint");
            var help = Component.translatable("browser.fabricated_backpacks.search_hint");
            check(client.font.width(help) > search.getInnerWidth(), "The real syntax help exercises search hint overflow");

            // Observe the actual widget's extracted text, using the loaded game font.
            var state = new GuiRenderState();
            search.extractWidgetRenderState(new GuiGraphicsExtractor(client, state, -1, -1), -1, -1, 0);
            var rendered = new ArrayList<GuiTextRenderState>();
            state.forEachText(rendered::add);
            check(rendered.size() == 1, "The empty search draws one visible hint instead of hiding it");
            var bounds = rendered.getFirst().bounds();
            check(bounds != null && bounds.left() >= search.getX() + 4
                            && bounds.right() <= search.getX() + 4 + search.getInnerWidth(),
                    "The rendered hint, including its shadow, stays inside the input instead of overlapping the recipe title: " + bounds);

            var tooltip = ((com.kadamitas.fabricatedbackpacks.gametest.mixin.TestWidgetTooltipAccess) (Object) search)
                    .fabricatedBackpacksTests$tooltip().get();
            check(tooltip != null, "The search provides its complete syntax help as a tooltip");
            String tooltipText = tooltip.toCharSequence(client).stream().map(BrowserClientAcceptance::plain)
                    .collect(java.util.stream.Collectors.joining());
            check(tooltipText.replaceAll("\\s", "").equals(help.getString().replaceAll("\\s", "")),
                    "Search tooltip retains the full quoted-phrase and exclusion syntax");
            StringBuilder narration = new StringBuilder();
            search.updateWidgetNarration(new NarrationElementOutput() {
                @Override public void add(NarratedElementType type, NarrationThunk<?> value) { value.getText(narration::append); }
                @Override public NarrationElementOutput nest() { return this; }
            });
            check(narration.toString().contains(help.getString()), "The native edit-box narration retains the complete syntax help");
        });
    }

    private static String plain(FormattedCharSequence sequence) {
        StringBuilder text = new StringBuilder();
        sequence.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
        return text.toString();
    }

    private static void selectCategory(ClientGameTestContext context, String desired) {
        var labels = java.util.List.of("All categories", "Crafting", "Stonecutting", "Smithing", "Smelting", "Smoking", "Blasting", "Campfire cooking");
        for (int attempt = 0; attempt < labels.size(); attempt++) {
            String current = context.computeOnClient(client -> client.gui.screen().children().stream()
                    .filter(widget -> widget instanceof AbstractWidget button && button.visible && labels.contains(button.getMessage().getString()))
                    .map(widget -> ((AbstractWidget) widget).getMessage().getString()).findFirst().orElseThrow());
            if (current.equals(desired)) return;
            clickButton(context, current);
        }
        throw new AssertionError("The actual browser never offered category " + desired);
    }

    private static void awaitButton(ClientGameTestContext context, String label) {
        context.waitFor(client -> client.gui.screen().children().stream().anyMatch(widget -> widget instanceof AbstractWidget button
                && button.visible && button.active && button.getMessage().getString().equals(label)));
    }

    static void openHovered(ClientGameTestContext context, int inventorySlot) {
        context.getInput().pressKey(GLFW.GLFW_KEY_E);
        context.waitForScreen(InventoryScreen.class);
        hoverPlayerSlot(context, inventorySlot);
        check(context.computeOnClient(client -> ((com.kadamitas.fabricatedbackpacks.client.mixin.ContainerScreenAccess) client.gui.screen())
                .fabricatedBackpacks$hoveredSlot().getItem() == client.player.getInventory().getItem(inventorySlot)),
                "The rendered inventory cursor targets the requested physical backpack slot");
        context.getInput().pressKey(GLFW.GLFW_KEY_B);
        context.waitForScreen(BackpackScreen.class);
        check(context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().source().inventorySlot()) == inventorySlot,
                "The inventory shortcut opens the hovered backpack, not another equipped item");
    }
}
