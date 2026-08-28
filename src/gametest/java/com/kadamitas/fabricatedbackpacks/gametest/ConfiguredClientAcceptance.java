package com.kadamitas.fabricatedbackpacks.gametest;

import com.google.gson.JsonParser;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.ConfigFile;
import com.kadamitas.fabricatedbackpacks.config.ServerConfig;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.network.ServerRules;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.*;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Tests configured geometry and real mapped keyboard/mouse input at a small GUI viewport. */
final class ConfiguredClientAcceptance {
    private static final String LONG_FILTER_TITLE = "Advanced Filter with Sixty-four Configured Ghost Cells";
    private ConfiguredClientAcceptance() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        checkItemModels(context);
        ServerConfig previous = BackpackConfig.get();
        try {
            var json = JsonParser.parseString(ConfigFile.encode(previous)).getAsJsonObject();
            var capacity = json.getAsJsonObject("capacities").getAsJsonObject(BackpackTier.NETHERITE.id());
            capacity.addProperty("slots", 144);
            capacity.addProperty("upgrades", 10);
            var upgrades = json.getAsJsonObject("upgrades");
            upgrades.getAsJsonObject("jukebox").addProperty("size", 16);
            upgrades.getAsJsonObject("jukebox").addProperty("rowWidth", 6);
            var filters = upgrades.getAsJsonObject("filters").getAsJsonObject(UpgradeKind.ADVANCED_FILTER.id());
            filters.addProperty("slots", 64);
            filters.addProperty("columns", 6);
            ServerConfig configured = ConfigFile.decode(json.toString());
            world.getServer().runOnServer(server -> {
                BackpackConfig.configure(configured);
                var backpack = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_JUKEBOX, UpgradeKind.ADVANCED_FILTER);
                backpack.upgrades().getItem(1).set(DataComponents.CUSTOM_NAME, Component.literal(LONG_FILTER_TITLE));
                backpack.upgrades().setChanged();
                backpack.updateSettings(upgrade(backpack, 1), state -> state.putBoolean("enabled", false));
                var player = player(world);
                player.getInventory().setItem(4, backpack.stack());
                for (int index = 0; index < 16; index++) player.getInventory().setItem(9 + index, new ItemStack(Items.MUSIC_DISC_13));
                player.getInventory().setItem(31, new ItemStack(Items.EMERALD, 37));
                player.getInventory().setItem(32, new ItemStack(Items.DIAMOND));
                ServerPlayNetworking.send(player, new ServerRules(ConfigFile.encode(configured)));
                player.inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            BrowserClientAcceptance.openHovered(context, 4);
            check(context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().bag().getContainerSize()) == 144,
                    "Configured storage geometry reaches the real client");
            clickButton(context, "1");
            context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 0);
            context.waitTicks(3);
            checkHeading(context, false);
            for (int index = 0; index < 16; index++) {
                clickPlayerSlot(context, 9 + index);
                int auxiliary = context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().auxiliaryStart());
                clickSlot(context, auxiliary + index);
            }
            world.getServer().waitFor(server -> {
                var bag = BagInventory.of(player(world).getInventory().getItem(4));
                return count(bag.upgradeInventory(upgrade(bag, 0)), Items.MUSIC_DISC_13) == 16;
            });
            context.takeScreenshot("configured-six-column-sixteen-records");
            clickButton(context, "2");
            context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 1);
            context.waitTicks(3);
            checkHeading(context, true);
            for (int page = 1; page < 4; page++) clickButton(context, "Filters " + page + "/4");
            clickPlayerSlot(context, 32);
            double[] ghost = context.computeOnClient(client -> {
                var screen = (BackpackScreen) client.gui.screen();
                var origin = (com.kadamitas.fabricatedbackpacks.client.mixin.ContainerScreenAccess) (Object) screen;
                return new double[]{origin.fabricatedBackpacks$left() + screen.getMenu().storageWidth() + 51 + 3 * 18 + 8,
                        origin.fabricatedBackpacks$top() + 54 + 18 + 8};
            });
            clickAt(context, ghost[0], ghost[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
            world.getServer().waitFor(server -> {
                var bag = BagInventory.of(player(world).getInventory().getItem(4));
                return bag.ghost(upgrade(bag, 1), 63).is(Items.DIAMOND);
            });
            clickPlayerSlot(context, 32);
            clickButton(context, ">");
            context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().page() == 1);
            clickPlayerSlot(context, 31);
            clickSlot(context, 143);
            world.getServer().waitFor(server -> BagInventory.of(player(world).getInventory().getItem(4)).getItem(143).getCount() == 37);
            check(context.computeOnClient(client -> client.gui.screen().children().stream().filter(AbstractWidget.class::isInstance)
                    .map(AbstractWidget.class::cast).filter(widget -> widget.visible).allMatch(widget -> widget.getX() >= 0 && widget.getY() >= 0
                            && widget.getRight() <= client.gui.screen().width && widget.getBottom() <= client.gui.screen().height)),
                    "Every visible control fits the small configured GUI viewport");
            context.takeScreenshot("configured-filter64-storage144-small-gui");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);
            mappedInput(context, GLFW.GLFW_KEY_H, -1);
            mappedInput(context, -1, GLFW.GLFW_MOUSE_BUTTON_4);
        } catch (Exception exception) { throw new AssertionError("Configured client acceptance failed", exception); }
        finally {
            context.runOnClient(client -> {
                KeyMapping.get("key.fabricated_backpacks.open").setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_B));
                KeyMapping.resetMapping();
                client.options.guiScale().set(2);
                client.resizeGui();
            });
            world.getServer().runOnServer(server -> {
                BackpackConfig.configure(previous);
                ServerPlayNetworking.send(player(world), new ServerRules(ConfigFile.encode(previous)));
            });
            world.getConnection().waitForClientboundPackets();
        }
        check(world.getServer().computeOnServer(server -> BagInventory.of(player(world).getInventory().getItem(4)).getItem(143).getCount()) == 37,
                "Returning to smaller defaults preserves the existing larger backpack contents");
    }

    private static void checkItemModels(ClientGameTestContext context) {
        context.runOnClient(client -> {
            Identifier fixture = Identifier.fromNamespaceAndPath("fabricated_backpacks_tests", "energy_cell");
            boolean foundFixture = false;
            for (var item : BuiltInRegistries.ITEM) {
                var id = BuiltInRegistries.ITEM.getKey(item);
                if (!id.getNamespace().equals("fabricated_backpacks") && !id.equals(fixture)) continue;
                var model = item.getDefaultInstance().get(DataComponents.ITEM_MODEL);
                var baked = model == null ? null : client.getModelManager().getItemModel(model);
                check(baked != null && !(baked instanceof MissingItemModel),
                        "The loaded client resolves a real item model for " + id + " via " + model);
                if (id.equals(fixture)) {
                    foundFixture = true;
                    check(model.equals(fixture), "The energy fixture uses its test-only item definition");
                }
            }
            check(foundFixture, "The model audit includes the registered test energy cell, without hiding catalog entries");
        });
    }

    private static void checkHeading(ClientGameTestContext context, boolean requireClipping) {
        context.runOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            String full = screen.getMenu().selected().orElseThrow().stack().getHoverName().getString();
            var label = screen.children().stream().filter(StringWidget.class::isInstance).map(StringWidget.class::cast)
                    .filter(widget -> widget.getMessage().getString().equals(full)).findFirst().orElseThrow();
            int available = screen.getMenu().imageWidth() - screen.getMenu().storageWidth() - 58;
            check(label.getWidth() == Math.min(client.font.width(label.getMessage()), available),
                    "The upgrade heading uses the configured panel width");
            String rendered = renderedText(label);
            boolean clipped = client.font.width(label.getMessage()) > available;
            if (requireClipping) check(clipped, "The long-name fixture exercises heading overflow");
            if (clipped) {
                check(rendered.endsWith("..."),
                        "An overflowing heading uses an explicit ellipsis");
                check(client.font.width(rendered) <= available, "The ellipsis stays within the measured heading width");
            } else check(rendered.equals(full), "A heading that fits the widened panel remains complete");
            var title = screen.children().stream().filter(StringWidget.class::isInstance).map(StringWidget.class::cast)
                    .filter(widget -> widget.getMessage().getString().equals(screen.getTitle().getString())).findFirst().orElseThrow();
            check(renderedText(title).equals(screen.getTitle().getString()), "The short backpack title stays complete");
            var actualTooltip = ((com.kadamitas.fabricatedbackpacks.gametest.mixin.TestWidgetTooltipAccess) (Object) label)
                    .fabricatedBackpacksTests$tooltip().get();
            check(actualTooltip != null, "The heading exposes its full name in a tooltip");
            String tooltip = actualTooltip.toCharSequence(client).stream().map(ConfiguredClientAcceptance::plain)
                    .collect(java.util.stream.Collectors.joining());
            check(tooltip.replaceAll("\\s", "").equals(full.replaceAll("\\s", "")),
                    "The heading tooltip retains the full name, including its clipped suffix");
        });
    }

    private static String renderedText(StringWidget label) {
        StringBuilder text = new StringBuilder();
        label.visitLines(new net.minecraft.client.gui.ActiveTextCollector() {
            @Override public Parameters defaultParameters() { return null; }
            @Override public void defaultParameters(Parameters value) {}
            @Override public void accept(net.minecraft.client.gui.TextAlignment alignment, int x, int y,
                                         Parameters parameters, FormattedCharSequence sequence) { text.append(plain(sequence)); }
            @Override public void acceptScrolling(Component message, int x, int y, int width, int height, int padding,
                                                  Parameters parameters) { throw new AssertionError("The heading must use a static ellipsis"); }
        });
        return text.toString();
    }
    private static String plain(FormattedCharSequence sequence) {
        StringBuilder text = new StringBuilder();
        sequence.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
        return text.toString();
    }

    private static void mappedInput(ClientGameTestContext context, int keyboard, int mouse) {
        context.runOnClient(client -> {
            KeyMapping.get("key.fabricated_backpacks.open").setKey((keyboard >= 0 ? InputConstants.Type.KEYSYM : InputConstants.Type.MOUSE)
                    .getOrCreate(keyboard >= 0 ? keyboard : mouse));
            KeyMapping.resetMapping();
        });
        context.getInput().pressKey(GLFW.GLFW_KEY_E);
        context.waitForScreen(InventoryScreen.class);
        hoverPlayerSlot(context, 4);
        if (keyboard >= 0) context.getInput().pressKey(keyboard); else context.getInput().pressMouse(mouse);
        context.waitForScreen(BackpackScreen.class);
        check(context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().source().inventorySlot()) == 4,
                "A rebound " + (keyboard >= 0 ? "keyboard" : "mouse") + " shortcut opens the hovered backpack");
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null);
    }
}
