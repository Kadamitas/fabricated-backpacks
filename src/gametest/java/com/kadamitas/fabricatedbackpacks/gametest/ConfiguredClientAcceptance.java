package com.kadamitas.fabricatedbackpacks.gametest;

import com.google.gson.JsonParser;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.client.mixin.ContainerScreenAccess;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.ConfigFile;
import com.kadamitas.fabricatedbackpacks.config.ServerConfig;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.BackpackLayout;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.network.ServerRules;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.narration.ScreenNarrationCollector;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import team.reborn.energy.api.EnergyStorage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.*;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Tests configured geometry and real mapped keyboard/mouse input at a small GUI viewport. */
final class ConfiguredClientAcceptance {
    private static final String LONG_FILTER_TITLE = "Advanced Filter with Sixty-four Configured Ghost Cells";
    private ConfiguredClientAcceptance() {}

    static void referenceLayout(ClientGameTestContext context, TestSingleplayerContext world) {
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            for (int scale : new int[]{2, 3}) {
                resizeLayout(context, scale);
                selectUpgrade(context, 0);
                int rows = context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().visibleRows());
                world.getServer().waitFor(server -> player(world).containerMenu instanceof BackpackMenu menu && menu.visibleRows() == rows);
                checkCompactFrame(context, scale == 2);
                check(context.computeOnClient(client -> client.gui.screen().children().stream()
                                .filter(EditBox.class::isInstance).map(EditBox.class::cast).noneMatch(box -> box.visible)),
                        "An empty storage search stays collapsed until its icon is used");
                check(context.computeOnClient(client -> {
                    var menu = ((BackpackScreen) client.gui.screen()).getMenu();
                    return menu.selected().orElseThrow().kind() == UpgradeKind.ADVANCED_JUKEBOX
                            && count(menu.bag().upgradeInventory(menu.selected().orElseThrow()), Items.MUSIC_DISC_13) == 1;
                }), "The compact jukebox panel still reads its physical disc inventory");
                context.takeScreenshot("ui-netherite-jukebox-scale-" + scale);
            }
            narrowJukebox(context, world);
        } finally {
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> { client.options.guiScale().set(previousScale); client.resizeGui(); });
        }
        awaitLayout(context);
    }

    private static void narrowJukebox(ClientGameTestContext context, TestSingleplayerContext world) {
        context.getInput().resizeWindow(684, 480);
        resizeLayout(context, 2);
        context.runOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            check(screen.width == 342 && screen.height == 240, "Narrow acceptance uses the actual 342 by 240 GUI viewport");
            var panel = screen.upgradePanelBounds().orElseThrow();
            check(panel.width() < screen.getMenu().panelWidth() && inside(panel, screen.width, screen.height),
                    "The side panel reflows to fewer columns when its ordinary width would clip");
            var origin = (ContainerScreenAccess) (Object) screen;
            for (var slot : screen.getMenu().slots) if (slot.isActive())
                check(inside(new ScreenRectangle(origin.fabricatedBackpacks$left() + slot.x,
                                origin.fabricatedBackpacks$top() + slot.y, 16, 16), screen.width, screen.height),
                        "The narrow viewport keeps every active slot reachable");
            for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.visible)
                check(inside(new ScreenRectangle(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()), screen.width, screen.height),
                        "The narrow viewport keeps every visible control reachable");
            checkHeadingRenderOutput(screen);
        });
        clickButton(context, "Slots 1/2");
        int finalRecord = context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().auxiliaryStart() + 11);
        String before = auxiliaryClickState(context, world, finalRecord);
        context.takeScreenshot("ui-narrow342-before-record-pickup");
        clickSlot(context, finalRecord);
        context.waitTicks(5);
        world.getConnection().waitForServerboundPackets();
        world.getConnection().waitForClientboundPackets();
        context.takeScreenshot("ui-narrow342-after-record-pickup");
        String after = auxiliaryClickState(context, world, finalRecord);
        check(world.getServer().computeOnServer(server -> player(world).containerMenu.getCarried().is(Items.MUSIC_DISC_WAIT)
                        && player(world).containerMenu.getCarried().getCount() == 1 && player(world).containerMenu.getSlot(finalRecord).getItem().isEmpty())
                        && context.computeOnClient(client -> client.player.containerMenu.getCarried().is(Items.MUSIC_DISC_WAIT)
                        && client.player.containerMenu.getCarried().getCount() == 1 && client.player.containerMenu.getSlot(finalRecord).getItem().isEmpty()),
                "The real narrow-viewport click picks up exactly the final WAIT record. Before: " + before + "; after: " + after);
        clickSlot(context, finalRecord);
        world.getServer().waitFor(server -> player(world).containerMenu.getCarried().isEmpty()
                && ((BackpackMenu) player(world).containerMenu).bag().upgradeInventory(
                        ((BackpackMenu) player(world).containerMenu).selected().orElseThrow()).getItem(11).is(Items.MUSIC_DISC_WAIT));
        context.takeScreenshot("ui-narrow342-jukebox-page-two");
    }

    private static String auxiliaryClickState(ClientGameTestContext context, TestSingleplayerContext world, int index) {
        String clientState = context.computeOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var menu = screen.getMenu();
            var origin = (ContainerScreenAccess) (Object) screen;
            var slot = menu.getSlot(index);
            var hovered = origin.fabricatedBackpacks$hoveredSlot();
            int x = origin.fabricatedBackpacks$left() + slot.x + 8, y = origin.fabricatedBackpacks$top() + slot.y + 8;
            var widgets = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                    .filter(widget -> widget.visible && widget.isMouseOver(x, y)).map(widget -> widget.getMessage().getString()).toList();
            return "client[id=" + menu.containerId + ",selected=" + menu.selectedSlot() + ",slot=" + index + ":" + slot.getItem()
                    + ",active=" + slot.isActive() + ",cursor=" + menu.getCarried() + ",click=" + x + "," + y
                    + ",slotXY=" + slot.x + "," + slot.y + ",origin=" + origin.fabricatedBackpacks$left() + "," + origin.fabricatedBackpacks$top()
                    + ",gui=" + screen.width + "x" + screen.height + ",window=" + client.getWindow().getScreenWidth() + "x" + client.getWindow().getScreenHeight()
                    + ",hovered=" + (hovered == null ? "none" : menu.slots.indexOf(hovered) + ":" + hovered.getItem())
                    + ",slotComponents=" + slot.getItem().getComponents() + ",cursorComponents=" + menu.getCarried().getComponents()
                    + ",panel=" + screen.upgradePanelBounds() + ",widgetsAtClick=" + widgets + "]";
        });
        String serverState = world.getServer().computeOnServer(server -> {
            var menu = (BackpackMenu) player(world).containerMenu;
            return "server[id=" + menu.containerId + ",selected=" + menu.selectedSlot() + ",slot=" + index + ":" + menu.getSlot(index).getItem()
                    + ",active=" + menu.getSlot(index).isActive() + ",cursor=" + menu.getCarried()
                    + ",slotComponents=" + menu.getSlot(index).getItem().getComponents()
                    + ",cursorComponents=" + menu.getCarried().getComponents() + "]";
        });
        return clientState + " " + serverState;
    }

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        checkItemModels(context);
        basicReferenceLayout(context, world);
        placedEnergyCapabilities(context, world);
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
            awaitLayout(context);
            check(context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().bag().getContainerSize()) == 144,
                    "Configured storage geometry reaches the real client");
            selectUpgrade(context, 0);
            context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 0);
            context.waitTicks(3);
            checkHeading(context, false);
            checkCompactFrame(context, false);
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
            selectUpgrade(context, 1);
            context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 1);
            context.waitTicks(3);
            checkHeading(context, true);
            checkCompactFrame(context, false);
            for (int page = 1; page < 4; page++) clickButton(context, "Filters " + page + "/4");
            clickPlayerSlot(context, 32);
            clickGhost(context, 63);
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
            resizeLayout(context, 2);
            checkCompactFrame(context, true);
            check(context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().getSlot(143).isActive()),
                    "The larger viewport exposes the configured final physical row without paging");
            context.takeScreenshot("ui-configured144-full-rows-scale-2");
            resizeLayout(context, 3);
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
        cookingPanel(context, world);
        auxiliarySwitchingAndRetainedInventory(context, world);
    }

    private static void placedEnergyCapabilities(ClientGameTestContext context, TestSingleplayerContext world) {
        BlockPos position = new BlockPos(5, 80, 4);
        ItemStack original = world.getServer().computeOnServer(server -> {
            var placed = (BackpackBlockEntity) server.overworld().getBlockEntity(position);
            check(placed != null && placed.inventory().installedUpgrades().isEmpty(),
                    "The existing Netherite display sample starts without an energy upgrade");
            for (Direction side : Direction.values()) {
                EnergyStorage neighbor = EnergyStorage.SIDED.find(server.overworld(), position.relative(side), side.getOpposite());
                check(neighbor == null || !neighbor.supportsInsertion(), "The capability fixture has no adjacent energy receiver: " + side);
            }
            return placed.stack().copy();
        });
        final List<EnergyStorage> ports;
        try {
            world.getServer().runOnServer(server -> {
                var charged = BagInventory.of(original.copy());
                charged.upgrades().setItem(0, new ItemStack(BackpackRegistry.item(UpgradeKind.BATTERY)));
                charged.updateSettings(upgrade(charged, 0), state -> state.putLong("amount", 3210));
                ((BackpackBlockEntity) server.overworld().getBlockEntity(position)).setStack(charged.stack());
            });
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level != null && Arrays.stream(Direction.values()).allMatch(side -> {
                EnergyStorage port = EnergyStorage.SIDED.find(client.level, position, side);
                return port != null && port.supportsInsertion() && port.supportsExtraction();
            }));
            ports = context.computeOnClient(client -> Arrays.stream(Direction.values())
                    .map(side -> EnergyStorage.SIDED.find(client.level, position, side)).toList());
            context.runOnClient(client -> checkClientEnergyPorts(ports, true, true, "Output enabled"));
            world.getServer().runOnServer(server -> {
                var placed = (BackpackBlockEntity) server.overworld().getBlockEntity(position);
                check(ResourceRuntime.energyStorage(placed.inventory()).getAmount() == 3210,
                        "Client capability queries and committed writes cannot change the server's 3210 energy");
                check(UpgradeEngine.action(placed.inventory(), 0, "external_output", player(world)),
                        "The actual server action switches the placed battery to input-only");
            });
            context.waitFor(client -> ports.stream().allMatch(port -> port.supportsInsertion() && !port.supportsExtraction()));
            context.runOnClient(client -> checkClientEnergyPorts(ports, true, false, "Output disabled"));
            world.getServer().runOnServer(server -> {
                var placed = (BackpackBlockEntity) server.overworld().getBlockEntity(position);
                check(!placed.inventory().settings(upgrade(placed.inventory(), 0)).getBooleanOr("external_output", true)
                                && ResourceRuntime.energyStorage(placed.inventory()).getAmount() == 3210,
                        "Updating the cached client flags leaves the authoritative setting Off and all 3210 energy intact");
            });
        } finally {
            world.getServer().runOnServer(server -> {
                var placed = (BackpackBlockEntity) server.overworld().getBlockEntity(position);
                placed.setStack(original);
                check(ItemStack.matches(placed.stack(), original), "The placed sample's exact original stack is restored");
            });
            world.getConnection().waitForClientboundPackets();
        }
        context.waitFor(client -> ports.stream().allMatch(port -> !port.supportsInsertion() && !port.supportsExtraction()));
        context.runOnClient(client -> checkClientEnergyPorts(ports, false, false, "Original sample restored"));
        world.getServer().runOnServer(server -> check(ItemStack.matches(
                ((BackpackBlockEntity) server.overworld().getBlockEntity(position)).stack(), original),
                "Late client capability calls preserve the restored sample's items and components"));
    }

    private static void checkClientEnergyPorts(List<EnergyStorage> ports, boolean input, boolean output, String phase) {
        check(ports.size() == 6, "Energy capabilities are checked on all six physical sides");
        for (int index = 0; index < ports.size(); index++) {
            EnergyStorage port = ports.get(index);
            String label = phase + ", " + Direction.values()[index];
            check(port != null && port.supportsInsertion() == input && port.supportsExtraction() == output,
                    label + ": the retained API handle reports the current synchronized capabilities");
            check(port.getAmount() == 0 && port.getCapacity() == 0, label + ": public capability flags reveal no quantities");
            try (Transaction transaction = Transaction.openOuter()) {
                check(port.insert(17, transaction) == 0 && port.extract(17, transaction) == 0,
                        label + ": client-side API insertion and extraction both return zero");
                transaction.commit();
            }
            check(port.getAmount() == 0 && port.getCapacity() == 0, label + ": committing a client query creates no energy");
        }
    }

    private static void basicReferenceLayout(ClientGameTestContext context, TestSingleplayerContext world) {
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        world.getServer().waitFor(server -> player(world).containerMenu == player(world).inventoryMenu);
        var original = world.getServer().computeOnServer(server -> player(world).getInventory().getItem(7).copy());
        try {
            world.getServer().runOnServer(server -> {
                var basic = bag(BackpackTier.LEATHER);
                check(basic.getContainerSize() == 27 && basic.isEmpty(), "The basic reference fixture is a real empty27 backpack");
                player(world).getInventory().setItem(7, basic.stack());
                player(world).inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            BrowserClientAcceptance.openHovered(context, 7);
            for (int scale : new int[]{2, 3}) {
                resizeLayout(context, scale);
                check(context.computeOnClient(client -> {
                    var screen = (BackpackScreen) client.gui.screen();
                    return screen.getMenu().bag().getContainerSize() == 27 && screen.getMenu().bag().isEmpty()
                            && screen.getMenu().selected().isEmpty() && screen.upgradePanelBounds().isEmpty();
                }), "An empty basic backpack shows its compact three-row body without an unused upgrade panel");
                checkCompactFrame(context, true);
                context.takeScreenshot("ui-basic27-empty-scale-" + scale);
            }
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);
        } finally {
            world.getServer().runOnServer(server -> {
                player(world).closeContainer();
                player(world).getInventory().setItem(7, original);
                player(world).inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            context.runOnClient(client -> { client.options.guiScale().set(previousScale); client.resizeGui(); });
        }
    }

    private static void auxiliarySwitchingAndRetainedInventory(ClientGameTestContext context, TestSingleplayerContext world) {
        var fixture = world.getServer().computeOnServer(server -> {
            var player = player(world);
            ItemStack previousBag = player.getInventory().getItem(7).copy();
            ItemStack previousLoose = player.getInventory().getItem(9).copy();
            ItemStack previousSmall = player.getInventory().getItem(10).copy();
            var bag = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.TANK);
            // Slot2 stays empty for the retained-jukebox installation below. The charged
            // battery has no container inputs or external receiver, so ticking cannot drain it.
            bag.upgrades().setItem(3, new ItemStack(BackpackRegistry.item(UpgradeKind.BATTERY)));
            bag.updateSettings(upgrade(bag, 3), settings -> settings.putLong("amount", 12_345));
            var first = bag.upgradeInventory(upgrade(bag, 0));
            var buckets = new ItemStack(Items.BUCKET, 3);
            buckets.set(DataComponents.CUSTOM_NAME, Component.literal("First tank buckets"));
            first.setItem(0, buckets);
            first.setItem(1, new ItemStack(Items.WATER_BUCKET));
            var second = bag.upgradeInventory(upgrade(bag, 1));
            var bottles = new ItemStack(Items.GLASS_BOTTLE, 7);
            bottles.set(DataComponents.CUSTOM_NAME, Component.literal("Second tank bottles"));
            second.setItem(0, bottles);
            second.setItem(1, new ItemStack(Items.LAVA_BUCKET));
            // Both tanks are empty: empty drain inputs and already-full fill inputs cannot process.
            // That leaves ordinary server resource ticking enabled while these exact snapshots stay stable.
            var records = new net.minecraft.world.SimpleContainer(64);
            records.setItem(0, new ItemStack(Items.MUSIC_DISC_13));
            records.setItem(31, new ItemStack(Items.MUSIC_DISC_BLOCKS));
            var finalDisc = new ItemStack(Items.MUSIC_DISC_CAT);
            finalDisc.set(DataComponents.CUSTOM_NAME, Component.literal("Retained final record"));
            records.setItem(63, finalDisc);
            InventorySnapshot retained = InventorySnapshot.capture(records);
            var loose = new ItemStack(BackpackRegistry.item(UpgradeKind.ADVANCED_JUKEBOX));
            loose.set(BagComponents.CONTENTS, retained);
            var small = new ItemStack(BackpackRegistry.item(UpgradeKind.ADVANCED_JUKEBOX));
            small.set(BagComponents.CONTENTS, new InventorySnapshot(12, java.util.List.of()));
            player.getInventory().setItem(7, bag.stack());
            player.getInventory().setItem(9, loose);
            player.getInventory().setItem(10, small);
            player.inventoryMenu.broadcastChanges();
            return new AuxiliaryFixture(previousBag, previousLoose, previousSmall, InventorySnapshot.capture(first), InventorySnapshot.capture(second), retained);
        });
        try {
            world.getConnection().waitForClientboundPackets();
            BrowserClientAcceptance.openHovered(context, 7);
            awaitLayout(context);
            var clientMenu = context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu());
            var serverMenu = world.getServer().computeOnServer(server -> player(world).containerMenu);
            int auxiliary = context.computeOnClient(client -> clientMenu.auxiliaryStart());
            for (int selected : new int[]{0, 1, 0}) {
                selectUpgrade(context, selected);
                assertTankPair(context, world, fixture.first(), fixture.second(), ItemStack.EMPTY, selected);
            }
            context.takeScreenshot("ui-two-tank-tab-switching");
            for (int selected : new int[]{0, 1}) {
                selectUpgrade(context, selected);
                ItemStack expectedCursor = (selected == 0 ? fixture.first() : fixture.second()).entries().getFirst().create();
                clickSlot(context, auxiliary);
                world.getServer().waitFor(server -> ItemStack.matches(player(world).containerMenu.getCarried(), expectedCursor));
                assertTankPair(context, world, selected == 0 ? withoutSlot(fixture.first(), 0) : fixture.first(),
                        selected == 1 ? withoutSlot(fixture.second(), 0) : fixture.second(), expectedCursor, selected);
                clickSlot(context, auxiliary);
                world.getServer().waitFor(server -> player(world).containerMenu.getCarried().isEmpty());
                assertTankPair(context, world, fixture.first(), fixture.second(), ItemStack.EMPTY, selected);
            }

            selectUpgrade(context, 3);
            ItemStack batteryCursor = context.computeOnClient(client -> clientMenu.getCarried().copy());
            for (boolean enabled : new boolean[]{false, true}) {
                clickButton(context, "External energy output: " + (enabled ? "Off" : "On"));
                world.getServer().waitFor(server -> {
                    var bag = ((BackpackMenu) player(world).containerMenu).bag();
                    return bag.settings(upgrade(bag, 3)).getBooleanOr("external_output", true) == enabled;
                });
                check(world.getServer().computeOnServer(server -> {
                    var menu = (BackpackMenu) player(world).containerMenu;
                    return ResourceRuntime.batteryStored(menu.bag(), 3) == 12_345 && ItemStack.matches(menu.getCarried(), batteryCursor);
                }), "The real battery output toggle preserves every stored energy unit and the server cursor");
                world.getConnection().waitForClientboundPackets();
                context.waitFor(client -> client.gui.screen() instanceof BackpackScreen screen
                        && screen.getMenu().bag().settings(upgrade(screen.getMenu().bag(), 3)).getBooleanOr("external_output", true) == enabled
                        && screen.children().stream().anyMatch(child -> child instanceof BackpackIconButton button
                        && button.getMessage().getString().equals("External energy output: " + (enabled ? "On" : "Off"))));
                check(context.computeOnClient(client -> ResourceRuntime.batteryStored(clientMenu.bag(), 3) == 12_345
                                && ItemStack.matches(clientMenu.getCarried(), batteryCursor)),
                        "Battery output settings synchronize without consuming charge or cursor items on the actual client");
                assertTankPair(context, world, fixture.first(), fixture.second(), batteryCursor, 3);
            }
            context.takeScreenshot("ui-battery-external-output-restored");

            // First install into the empty physical slot of this already-open menu. Then replace
            // the selected empty12 with retained64 using a real swap, without another tab action.
            clickPlayerSlot(context, 10);
            clickSlot(context, context.computeOnClient(client -> clientMenu.upgradeSlotStart() + 2));
            world.getServer().waitFor(server -> player(world).containerMenu.getCarried().isEmpty()
                    && BackpackRegistry.kind(((BackpackMenu) player(world).containerMenu).bag().upgrades().getItem(2)).orElse(null) == UpgradeKind.ADVANCED_JUKEBOX);
            selectUpgrade(context, 2);
            check(context.computeOnClient(client -> clientMenu.bag().inventorySlots(upgrade(clientMenu.bag(), 2)) == 12
                            && clientMenu.bag().upgradeInventory(upgrade(clientMenu.bag(), 2)).isEmpty()),
                    "The same-kind swap begins with the selected real empty12 inventory");
            var previousPages = context.computeOnClient(client -> slotPageLabels((BackpackScreen) client.gui.screen()));
            context.takeScreenshot("ui-selected12-before-retained64-swap");
            clickPlayerSlot(context, 9);
            clickSlot(context, context.computeOnClient(client -> clientMenu.upgradeSlotStart() + 2));
            world.getServer().waitFor(server -> {
                var menu = (BackpackMenu) player(world).containerMenu;
                return menu.selectedSlot() == 2 && emptyTwelveRecordUpgrade(menu.getCarried())
                        && snapshotMatches(menu.bag().upgradeInventory(upgrade(menu.bag(), 2)), fixture.records());
            });
            clickPlayerSlot(context, 9);
            world.getServer().waitFor(server -> player(world).containerMenu.getCarried().isEmpty()
                    && emptyTwelveRecordUpgrade(player(world).getInventory().getItem(9)));
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.gui.screen() instanceof BackpackScreen screen && screen.getMenu() == clientMenu
                    && clientMenu.selectedSlot() == 2 && clientMenu.bag().inventorySlots(upgrade(clientMenu.bag(), 2)) == 64
                    && !slotPageLabels(screen).equals(previousPages));
            check(context.computeOnClient(client -> client.player.containerMenu == clientMenu)
                            && world.getServer().computeOnServer(server -> player(world).containerMenu == serverMenu),
                    "The saved larger jukebox swaps into the same selected slot of the same already-open client and server menu");
            check(context.computeOnClient(client -> emptyTwelveRecordUpgrade(client.player.getInventory().getItem(9))
                            && clientMenu.getCarried().isEmpty()),
                    "The real same-kind swap returns exactly the displaced empty12 upgrade to its vacated source slot");
            check(context.computeOnClient(client -> snapshotMatches(clientMenu.bag().upgradeInventory(upgrade(clientMenu.bag(), 2)), fixture.records())),
                    "Installing a retained64 inventory delivers every saved record and its exact component data");
            var seenPages = new java.util.HashSet<String>();
            while (!context.computeOnClient(client -> clientMenu.getSlot(auxiliary + 63).isActive())) {
                String page = context.computeOnClient(client -> client.gui.screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.visible && button.active && button.getMessage().getString().startsWith("Slots "))
                        .map(button -> button.getMessage().getString()).findFirst().orElseThrow());
                check(seenPages.add(page) && seenPages.size() <= 64, "Real slot-page controls must reach physical63 without cycling: " + seenPages);
                clickButton(context, page);
            }
            check(!seenPages.isEmpty(), "The retained64 fixture really exercises auxiliary paging");
            context.takeScreenshot("ui-retained64-jukebox-final-page");
            ItemStack finalDisc = fixture.records().entries().stream().filter(entry -> entry.slot() == 63).findFirst().orElseThrow().create();
            String beforePickup = auxiliaryClickState(context, world, auxiliary + 63);
            clickSlot(context, auxiliary + 63);
            context.waitTicks(5);
            world.getConnection().waitForServerboundPackets();
            world.getConnection().waitForClientboundPackets();
            context.takeScreenshot("ui-retained64-after-record-pickup");
            String afterPickup = auxiliaryClickState(context, world, auxiliary + 63);
            check(context.computeOnClient(client -> ItemStack.matches(clientMenu.getCarried(), finalDisc)
                            && snapshotMatches(clientMenu.bag().upgradeInventory(upgrade(clientMenu.bag(), 2)), withoutSlot(fixture.records(), 63)))
                            && world.getServer().computeOnServer(server -> ItemStack.matches(player(world).containerMenu.getCarried(), finalDisc)
                            && snapshotMatches(((BackpackMenu) player(world).containerMenu).bag()
                            .upgradeInventory(upgrade(((BackpackMenu) player(world).containerMenu).bag(), 2)), withoutSlot(fixture.records(), 63))),
                    "A real click picks up physical record63 exactly once, leaving the other retained records unchanged. Before: "
                            + beforePickup + "; after: " + afterPickup);
            // These are two separate clicks, not vanilla's same-slot double-click collection.
            // Accelerated test ticks do not establish the native MouseHandler wall-clock threshold.
            separateSingleClicks(context);
            String beforeReturn = auxiliaryClickState(context, world, auxiliary + 63);
            clickSlot(context, auxiliary + 63);
            context.waitTicks(5);
            world.getConnection().waitForServerboundPackets();
            world.getConnection().waitForClientboundPackets();
            context.takeScreenshot("ui-retained64-after-record-return-click");
            String afterReturn = auxiliaryClickState(context, world, auxiliary + 63);
            check(context.computeOnClient(client -> clientMenu.getCarried().isEmpty()
                            && snapshotMatches(clientMenu.bag().upgradeInventory(upgrade(clientMenu.bag(), 2)), fixture.records()))
                            && world.getServer().computeOnServer(server -> player(world).containerMenu.getCarried().isEmpty()
                            && snapshotMatches(((BackpackMenu) player(world).containerMenu).bag()
                            .upgradeInventory(upgrade(((BackpackMenu) player(world).containerMenu).bag(), 2)), fixture.records())),
                    "The separate real return click empties both cursors and restores every exact record component/count. Before: "
                            + beforeReturn + "; after: " + afterReturn);
            assertTankPair(context, world, fixture.first(), fixture.second(), ItemStack.EMPTY, 2);
            check(context.computeOnClient(client -> snapshotMatches(clientMenu.bag().upgradeInventory(upgrade(clientMenu.bag(), 2)), fixture.records()))
                            && world.getServer().computeOnServer(server -> snapshotMatches(((BackpackMenu) player(world).containerMenu).bag()
                            .upgradeInventory(upgrade(((BackpackMenu) player(world).containerMenu).bag(), 2)), fixture.records())),
                    "Returning the retained final record preserves all64 physical addresses, counts and names on both sides");
            context.takeScreenshot("ui-retained64-jukebox-record-returned");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);
        } finally {
            world.getServer().runOnServer(server -> {
                var player = player(world);
                player.closeContainer();
                player.getInventory().setItem(7, fixture.previousBag());
                player.getInventory().setItem(9, fixture.previousLoose());
                player.getInventory().setItem(10, fixture.previousSmall());
                player.inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
        }
    }

    private static void separateSingleClicks(ClientGameTestContext context) {
        long before = context.computeOnClient(client -> net.minecraft.util.Util.getMillis());
        try {
            Thread.sleep(net.minecraft.client.MouseHandler.DOUBLE_CLICK_THRESHOLD_MS + 1);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while separating two ordinary mouse clicks", failure);
        }
        long elapsed = context.computeOnClient(client -> net.minecraft.util.Util.getMillis()) - before;
        check(elapsed >= net.minecraft.client.MouseHandler.DOUBLE_CLICK_THRESHOLD_MS,
                "The return press must occur outside the native double-click window; elapsed=" + elapsed);
    }

    private static void assertTankPair(ClientGameTestContext context, TestSingleplayerContext world,
                                       InventorySnapshot first, InventorySnapshot second, ItemStack cursor, int selected) {
        check(world.getServer().computeOnServer(server -> {
            var menu = (BackpackMenu) player(world).containerMenu;
            return menu.selectedSlot() == selected && ItemStack.matches(menu.getCarried(), cursor)
                    && snapshotMatches(menu.bag().upgradeInventory(upgrade(menu.bag(), 0)), first)
                    && snapshotMatches(menu.bag().upgradeInventory(upgrade(menu.bag(), 1)), second);
        }), "Real tank tab interaction preserves both exact server inventories and the cursor");
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(2);
        check(context.computeOnClient(client -> {
            var menu = ((BackpackScreen) client.gui.screen()).getMenu();
            return menu.selectedSlot() == selected && ItemStack.matches(menu.getCarried(), cursor)
                    && snapshotMatches(menu.bag().upgradeInventory(upgrade(menu.bag(), 0)), first)
                    && snapshotMatches(menu.bag().upgradeInventory(upgrade(menu.bag(), 1)), second);
        }), "Native tab/data synchronization must not copy the selected tank's cells into another client upgrade");
    }

    private static boolean snapshotMatches(net.minecraft.world.Container actual, InventorySnapshot expected) {
        if (actual.getContainerSize() != expected.size()) return false;
        for (int slot = 0; slot < actual.getContainerSize(); slot++) {
            ItemStack wanted = ItemStack.EMPTY;
            for (var entry : expected.entries()) if (entry.slot() == slot) { wanted = entry.create(); break; }
            if (!ItemStack.matches(actual.getItem(slot), wanted)) return false;
        }
        return true;
    }

    private static InventorySnapshot withoutSlot(InventorySnapshot inventory, int slot) {
        return new InventorySnapshot(inventory.size(), inventory.entries().stream().filter(entry -> entry.slot() != slot).toList());
    }

    private static java.util.List<String> slotPageLabels(BackpackScreen screen) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.active && button.getMessage().getString().startsWith("Slots "))
                .map(button -> button.getMessage().getString()).toList();
    }

    private static boolean emptyTwelveRecordUpgrade(ItemStack stack) {
        var inventory = stack.get(BagComponents.CONTENTS);
        return stack.getCount() == 1 && BackpackRegistry.kind(stack).orElse(null) == UpgradeKind.ADVANCED_JUKEBOX
                && inventory != null && inventory.size() == 12 && inventory.entries().isEmpty();
    }

    private record AuxiliaryFixture(ItemStack previousBag, ItemStack previousLoose, ItemStack previousSmall, InventorySnapshot first,
                                    InventorySnapshot second, InventorySnapshot records) {}

    static void awaitLayout(ClientGameTestContext context) {
        context.waitFor(client -> client.gui.screen() instanceof BackpackScreen screen
                && screen.getMenu().visibleRows() == Math.min(screen.getMenu().bag().rows(), BackpackLayout.rowsForViewport(
                        screen.getMenu().bag().rows(), screen.height, screen.getMenu().bag().upgrades().getContainerSize())));
        context.waitTicks(2);
    }

    private static void resizeLayout(ClientGameTestContext context, int scale) {
        context.runOnClient(client -> { client.options.guiScale().set(scale); client.resizeGui(); });
        awaitLayout(context);
        check(context.computeOnClient(client -> client.getWindow().getGuiScale()) == scale,
                "Layout acceptance uses the requested actual GUI scale " + scale);
    }

    private static void checkCompactFrame(ClientGameTestContext context, boolean fullRows) {
        context.runOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var menu = screen.getMenu();
            var layout = menu.layout();
            var origin = (ContainerScreenAccess) (Object) screen;
            int left = origin.fabricatedBackpacks$left();
            int top = origin.fabricatedBackpacks$top();
            var panel = screen.upgradePanelBounds();
            var visibleUpgrades = menu.bag().installedUpgrades().stream()
                    .filter(upgrade -> screen.upgradeTabBounds(upgrade.slot()).isPresent()).toList();
            int contentWidth = panel.map(bounds -> bounds.right() - left).orElse(layout.tabX() + 22);
            int contentHeight = Math.max(menu.imageHeight(), 35 + visibleUpgrades.size() * 20);
            check(left == (screen.width - contentWidth) / 2 && top == (screen.height - contentHeight) / 2
                            && left >= 0 && top >= 0 && left + contentWidth <= screen.width && top + contentHeight <= screen.height,
                    "The complete visible backpack body, settings and selected panel are centered and fit the scaled viewport");
            check(layout.storagePanelX() == 21 && layout.storageX() == 29 && layout.storageY() == 18,
                    "Storage starts inside the main body, separate from the left physical upgrade rail");
            if (fullRows) check(menu.visibleRows() == menu.bag().rows(), "Every physical storage row is visible when the viewport has room");
            else check(menu.visibleRows() < menu.bag().rows(), "The smaller viewport uses actual paging without squeezing slot hit targets");
            for (int index = 0; index < layout.visibleUpgradeSlots(); index++) {
                var slot = menu.getSlot(menu.upgradeSlotStart() + index);
                check(slot.x == 6 && slot.y == 6 + index * 16 && slot.x + 16 < layout.storageX(),
                        "Physical upgrade slot " + index + " remains on the left at the specified spacing");
                if (slot.isActive()) {
                    for (int edge : new int[]{0, 15}) {
                        int mouseX = left + slot.x + 8, mouseY = top + slot.y + edge;
                        screen.extractRenderState(new GuiGraphicsExtractor(client, new GuiRenderState(), mouseX, mouseY), mouseX, mouseY, 0);
                        check(origin.fabricatedBackpacks$hoveredSlot() == slot,
                                "The native hit test selects the correct compact upgrade slot at both vertical edges: " + index);
                    }
                }
            }
            var playerStart = menu.slots.stream().filter(slot -> slot.container == client.player.getInventory()
                    && slot.getContainerSlot() == 9).findFirst().orElseThrow();
            check(playerStart.x == layout.inventoryX() && playerStart.y == layout.inventoryY()
                            && layout.inventoryX() + 80 == layout.storagePanelX() + layout.storageWidth() / 2,
                    "The176-pixel player inventory frame is centered below storage");
            for (var slot : menu.slots) if (slot.isActive()) {
                check(inside(new ScreenRectangle(left + slot.x, top + slot.y, 16, 16), screen.width, screen.height),
                        "Every active physical slot fits the viewport: " + slot.index);
                if (slot.container == menu.bag()) {
                    int rank = menu.storageRank(slot.getContainerSlot());
                    check(rank >= 0 && slot.x == layout.storageX() + rank % menu.bag().columns() * 18
                                    && slot.y == layout.storageY() + rank / menu.bag().columns() % menu.visibleRows() * 18,
                            "Actual storage hit targets use the body grid and current visible row count: " + slot.index);
                }
            }
            for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.visible) {
                check(inside(new ScreenRectangle(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()), screen.width, screen.height),
                        "Every visible control fits the viewport: " + widget.getMessage().getString());
                if (widget instanceof Button button) {
                    check(button instanceof BackpackIconButton && button.getWidth() <= 22 && button.getHeight() <= 22,
                            "Backpack controls are compact icon buttons, not wide text buttons: " + button.getMessage().getString());
                    String tooltip = switch (button.getMessage().getString()) {
                        case ">" -> "Page " + (menu.page() + 1) + "/" + menu.pages();
                        case "Mode" -> switch (menu.editMode()) {
                            case 1 -> "Memory slots: click a storage slot to remember its item";
                            case 2 -> "No-sort slots: click a storage slot to exclude it from sorting";
                            default -> "Slot editing: off — click to edit memory slots";
                        };
                        default -> button.getMessage().getString();
                    };
                    checkIcon(button, false, tooltip);
                }
            }
            var settings = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals("Prefs")).findFirst().orElseThrow();
            check(settings.getX() == left + layout.tabX() && settings.getY() == top + 4
                            && settings.getWidth() == 22 && settings.getHeight() == 22,
                    "The separate22-pixel Settings tab starts at the upper-right body edge");
            check(panel.isPresent() == menu.selected().isPresent(), "Only a selected installed upgrade owns an expanded panel");
            boolean compactTabs = panel.isPresent()
                    ? panel.orElseThrow().left() == left + layout.tabX() + 22
                    : 29 + Math.max(0, visibleUpgrades.size() - 1) * 25 + 22 > contentHeight;
            if (panel.isPresent()) {
                var bounds = panel.orElseThrow();
                if (visibleUpgrades.stream().noneMatch(upgrade -> upgrade.slot() == menu.selectedSlot())) {
                    check(compactTabs, "A selected upgrade outside the visible rail page keeps its panel beside the compact tabs");
                }
                int expectedX = layout.tabX() + (compactTabs ? 22 : 0);
                int available = screen.width - 8 - expectedX;
                int expectedWidth = available >= layout.panelWidth() ? layout.panelWidth() : Math.max(1, (available - 12) / 18) * 18 + 12;
                check(bounds.left() == left + expectedX && bounds.width() == expectedWidth && inside(bounds, screen.width, screen.height),
                        "A selected panel uses either the normal body-edge position or the exact22-pixel compact-tab offset");
                int earlier = (int) visibleUpgrades.stream().filter(upgrade -> upgrade.slot() < menu.selectedSlot()).count();
                int expectedY = compactTabs ? Math.clamp(29 + earlier * 20, 29, Math.max(29, contentHeight - bounds.height() - 6)) : 29 + earlier * 25;
                check(bounds.top() == top + expectedY, "The panel follows its selected tab, with only the documented compact-layout clamp");
            }
            int tabY = 29;
            for (var upgrade : visibleUpgrades) {
                var bounds = screen.upgradeTabBounds(upgrade.slot()).orElseThrow();
                boolean embedded = !compactTabs && upgrade.slot() == menu.selectedSlot();
                check(bounds.left() == left + layout.tabX() + (embedded ? 3 : 0)
                                && bounds.top() == top + tabY + (embedded ? 3 : 0)
                                && bounds.width() == (embedded ? 20 : 22) && bounds.height() == (embedded || compactTabs ? 20 : 22),
                        "Each upgrade icon follows the exact normal25-pitch expanded chain or compact20-pitch tab geometry");
                if (embedded) check(bounds.left() == panel.orElseThrow().left() + 3 && bounds.top() == panel.orElseThrow().top() + 3,
                        "The selected20-pixel icon is embedded in the normal expanded panel");
                tabY += embedded ? panel.orElseThrow().height() + 3 : compactTabs ? 20 : 25;
                String label = "Upgrade " + (upgrade.slot() + 1) + ": " + upgrade.stack().getHoverName().getString();
                var tab = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow();
                check(tab.visible && tab.active && tab.getX() == bounds.left() && tab.getY() == bounds.top()
                                && tab.getWidth() == bounds.width() && tab.getHeight() == bounds.height(),
                        "The accessible tab names and hit bounds describe the real clickable upgrade icon");
                checkIcon(tab, true, label);
            }
            var resources = menu.bag().installedUpgrades().stream().filter(upgrade -> upgrade.kind() == UpgradeKind.TANK
                    || upgrade.kind() == UpgradeKind.BATTERY).toList();
            for (int index = 0; index < resources.size(); index++) {
                var resource = resources.get(index);
                if (menu.filtering()) check(screen.resourceBounds(resource.slot()).isEmpty(), "Filtering hides resource hit targets");
                else {
                    var bounds = screen.resourceBounds(resource.slot()).orElseThrow();
                    int footprint = menu.bag().columns() - resources.size() * 2 + index * 2;
                    check(bounds.left() == left + menu.storageX() + footprint * 18 + 9 && bounds.top() == top + menu.storageY()
                                    && bounds.width() == 16 && bounds.height() == menu.visibleRows() * 18 - 2
                                    && inside(bounds, screen.width, screen.height),
                            "The slim16-pixel resource gauge stays at offset9 inside its reserved two-column footprint");
                }
            }
            checkInventoryStrip(screen);
            checkHeadingRenderOutput(screen);
        });
    }

    private static void checkInventoryStrip(BackpackScreen screen) {
        var client = net.minecraft.client.Minecraft.getInstance();
        var origin = (ContainerScreenAccess) (Object) screen;
        var layout = screen.getMenu().layout();
        var expected = new ScreenRectangle(origin.fabricatedBackpacks$left() + layout.storagePanelX() + 3,
                origin.fabricatedBackpacks$top() + layout.inventoryTitleY(), layout.storageWidth() - 6, 12);
        var state = new GuiRenderState();
        screen.extractBackground(new GuiGraphicsExtractor(client, state, -1, -1), -1, -1, 0);
        int[] bars = {0};
        state.forEachElement(element -> {
            if (element instanceof ColoredRectangleRenderState rectangle && expected.equals(rectangle.bounds())) bars[0]++;
        }, GuiRenderState.TraverseRange.ALL);
        check(bars[0] == 1, "The actual background draws one full-body-width12-pixel Inventory strip: " + expected);
    }

    private static boolean inside(ScreenRectangle bounds, int width, int height) {
        return bounds.left() >= 0 && bounds.top() >= 0 && bounds.right() <= width && bounds.bottom() <= height;
    }

    private static void checkIcon(Button button, boolean requireItem, String expectedTooltip) {
        // Called on the client thread: inspect the native render output without replacing input or rendering.
        var client = net.minecraft.client.Minecraft.getInstance();
        var state = new GuiRenderState();
        button.extractRenderState(new GuiGraphicsExtractor(client, state, -1, -1), -1, -1, 0);
        var text = new ArrayList<GuiTextRenderState>();
        state.forEachText(text::add);
        check(text.isEmpty(), "An icon control never paints its accessible label as on-screen text: " + button.getMessage().getString());
        String label = button.getMessage().getString();
        check(!label.isBlank(), "Icon-only controls retain a complete accessible label");
        var tooltip = ((com.kadamitas.fabricatedbackpacks.gametest.mixin.TestWidgetTooltipAccess) (Object) button)
                .fabricatedBackpacksTests$tooltip().get();
        check(tooltip != null && tooltip.toCharSequence(client).stream().map(ConfiguredClientAcceptance::plain)
                        .collect(java.util.stream.Collectors.joining()).replaceAll("\\s", "").contains(expectedTooltip.replaceAll("\\s", "")),
                "The complete icon label or current-state explanation remains available on hover: " + label);
        if (requireItem) {
            var items = new ArrayList<GuiItemRenderState>();
            state.forEachItem(items::add);
            check(items.size() == 1, "An upgrade tab draws its actual item icon instead of a numeric text placeholder");
            var bounds = items.getFirst().bounds();
            check(bounds != null && bounds.left() >= button.getX() && bounds.top() >= button.getY()
                            && bounds.right() <= button.getRight() && bounds.bottom() <= button.getBottom(),
                    "The actual upgrade item icon stays inside its clickable tab");
        }
    }

    private static void cookingPanel(ClientGameTestContext context, TestSingleplayerContext world) {
        var original = world.getServer().computeOnServer(server -> new ItemStack[]{player(world).getInventory().getItem(7).copy(),
                player(world).getInventory().getItem(9).copy(), player(world).getInventory().getItem(10).copy()});
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            world.getServer().runOnServer(server -> {
                var bag = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_JUKEBOX, UpgradeKind.AUTO_SMELTING);
                bag.updateSettings(upgrade(bag, 1), settings -> settings.putBoolean("enabled", false));
                player(world).getInventory().setItem(7, bag.stack());
                player(world).getInventory().setItem(9, new ItemStack(Items.RAW_IRON, 2));
                player(world).getInventory().setItem(10, new ItemStack(Items.COAL));
                player(world).inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            BrowserClientAcceptance.openHovered(context, 7);
            selectUpgrade(context, 1);
            awaitLayout(context);
            int auxiliary = context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().auxiliaryStart());
            clickPlayerSlot(context, 9);
            clickCookingGhost(context, 0);
            clickSlot(context, auxiliary);
            clickPlayerSlot(context, 10);
            clickCookingGhost(context, 8);
            clickSlot(context, auxiliary + 1);
            world.getServer().waitFor(server -> {
                var bag = BagInventory.of(player(world).getInventory().getItem(7));
                var input = bag.upgradeInventory(upgrade(bag, 1));
                return input.getItem(0).is(Items.RAW_IRON) && input.getItem(0).getCount() == 2 && input.getItem(1).is(Items.COAL);
            });
            CookingGuard guard = world.getServer().computeOnServer(server -> cookingGuard(
                    BagInventory.of(player(world).getInventory().getItem(7)), player(world)));
            Map<BackpackIconButton.Icon, List<Integer>> glyphs = new EnumMap<>(BackpackIconButton.Icon.class);
            for (int scale : new int[]{2, 3}) {
                resizeLayout(context, scale);
                checkCompactFrame(context, scale == 2);
                checkCookingFilterGeometry(context);
                cookingControlStates(context, world, guard, glyphs, scale);
                context.takeScreenshot("ui-auto-smelting-paused-scale-" + scale);
            }
            check(glyphs.size() == 10, "Real clicks exercise all ten cooking filter glyph states, not just their enum names");
            clickButton(context, "More 1/2");
            clickButton(context, "Enabled: Off");
            context.waitFor(client -> {
                var menu = ((BackpackScreen) client.gui.screen()).getMenu();
                var state = menu.bag().settings(menu.selected().orElseThrow());
                return state.getBooleanOr("burning", false) && state.getIntOr("cook_progress", 0) > 0;
            });
            checkCompactFrame(context, false);
            checkCookingFilterGeometry(context);
            clickButton(context, "More 2/2");
            context.takeScreenshot("ui-auto-smelting-running");
            world.getServer().waitFor(server -> {
                var bag = BagInventory.of(player(world).getInventory().getItem(7));
                return count(bag, Items.IRON_INGOT) + count(bag.upgradeInventory(upgrade(bag, 1)), Items.IRON_INGOT) == 2;
            }, 800);
            clickButton(context, "More 1/2");
            clickButton(context, "Enabled: On");
            world.getServer().waitFor(server -> {
                var bag = BagInventory.of(player(world).getInventory().getItem(7));
                return !bag.settings(upgrade(bag, 1)).getBooleanOr("enabled", true);
            });
            check(world.getServer().computeOnServer(server -> {
                var bag = BagInventory.of(player(world).getInventory().getItem(7));
                var inventory = bag.upgradeInventory(upgrade(bag, 1));
                return count(bag, Items.RAW_IRON) + count(inventory, Items.RAW_IRON) == 0
                        && count(bag, Items.COAL) + count(inventory, Items.COAL) == 0
                        && bag.settings(upgrade(bag, 1)).getIntOr("burn_total", 0) > 0
                        && player(world).containerMenu.getCarried().isEmpty();
            }), "Compact cooker slots and enable controls preserve two-item recipe output and one consumed fuel item");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);
        } finally {
            world.getServer().runOnServer(server -> {
                player(world).closeContainer();
                player(world).getInventory().setItem(7, original[0]);
                player(world).getInventory().setItem(9, original[1]);
                player(world).getInventory().setItem(10, original[2]);
                player(world).inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            context.runOnClient(client -> { client.options.guiScale().set(previousScale); client.resizeGui(); });
        }
    }

    private record CookingControls(String mode, String match, boolean damage, boolean components) {
        static CookingControls defaults() { return new CookingControls("ALLOW", "ITEM", false, false); }
        String[] labels() {
            return new String[]{"Input filter mode: " + mode, "Input filter match: " + match,
                    "Input match damage: " + (damage ? "On" : "Off"), "Input match components: " + (components ? "On" : "Off")};
        }
        BackpackIconButton.Icon[] icons() {
            return new BackpackIconButton.Icon[]{switch (mode) {
                case "ALLOW" -> BackpackIconButton.Icon.FILTER_ALLOW;
                case "BLOCK" -> BackpackIconButton.Icon.FILTER_BLOCK;
                case "CONTENTS" -> BackpackIconButton.Icon.FILTER_CONTENTS;
                default -> throw new AssertionError("Unknown tested filter mode: " + mode);
            }, switch (match) {
                case "ITEM" -> BackpackIconButton.Icon.MATCH_ITEM;
                case "NAMESPACE" -> BackpackIconButton.Icon.MATCH_MOD;
                case "TAGS" -> BackpackIconButton.Icon.MATCH_TAGS;
                default -> throw new AssertionError("Unknown tested primary match: " + match);
            }, damage ? BackpackIconButton.Icon.MATCH_DAMAGE : BackpackIconButton.Icon.IGNORE_DAMAGE,
                    components ? BackpackIconButton.Icon.MATCH_COMPONENTS : BackpackIconButton.Icon.IGNORE_COMPONENTS};
        }
        boolean matches(CompoundTag settings) {
            return settings.getStringOr("input_filter_mode", "ALLOW").equals(mode)
                    && settings.getStringOr("input_filter_match", "ITEM").equals(match)
                    && settings.getBooleanOr("input_match_damage", false) == damage
                    && settings.getBooleanOr("input_match_components", false) == components;
        }
    }
    private record CookingClick(String label, CookingControls result, String capture) {}
    private record CookingGuard(String identity, InventorySnapshot storage, InventorySnapshot auxiliary,
                                InventorySnapshot filters, InventorySnapshot player, ItemStack cursor, CompoundTag otherSettings) {}

    private static void clickCookingGhost(ClientGameTestContext context, int index) {
        double[] position = context.computeOnClient(client -> {
            var bounds = ((BackpackScreen) client.gui.screen()).ghostBounds(index).orElseThrow();
            return new double[]{bounds.left() + 8, bounds.top() + 8};
        });
        clickAt(context, position[0], position[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static void cookingControlStates(ClientGameTestContext context, TestSingleplayerContext world, CookingGuard guard,
                                             Map<BackpackIconButton.Icon, List<Integer>> glyphs, int scale) {
        check(guard.filters().entries().size() == 2
                        && guard.filters().entries().stream().anyMatch(entry -> entry.slot() == 0 && entry.count() == 1 && entry.create().is(Items.RAW_IRON))
                        && guard.filters().entries().stream().anyMatch(entry -> entry.slot() == 8 && entry.count() == 1 && entry.create().is(Items.COAL)),
                "Real ghost clicks remember one input and one fuel without consuming the physical furnace stacks");
        checkCookingState(context, world, guard, CookingControls.defaults(), "ALLOW", "ANY");
        checkCookingRow(context, CookingControls.defaults().labels(), CookingControls.defaults().icons(), glyphs, "More 1/2");
        var clicks = List.of(
                new CookingClick("Input filter mode: ALLOW", new CookingControls("BLOCK", "ITEM", false, false), "reference-block"),
                new CookingClick("Input filter mode: BLOCK", new CookingControls("CONTENTS", "ITEM", false, false), null),
                new CookingClick("Input filter mode: CONTENTS", CookingControls.defaults(), null),
                new CookingClick("Input filter match: ITEM", new CookingControls("ALLOW", "NAMESPACE", false, false), null),
                new CookingClick("Input match damage: Off", new CookingControls("ALLOW", "NAMESPACE", true, false), null),
                new CookingClick("Input match components: Off", new CookingControls("ALLOW", "NAMESPACE", true, true), "alternate-match"),
                new CookingClick("Input filter match: NAMESPACE", new CookingControls("ALLOW", "TAGS", true, true), null),
                new CookingClick("Input filter match: TAGS", new CookingControls("ALLOW", "ITEM", true, true), null),
                new CookingClick("Input match damage: On", new CookingControls("ALLOW", "ITEM", false, true), null),
                new CookingClick("Input match components: On", CookingControls.defaults(), null));
        for (var step : clicks) {
            clickButton(context, step.label());
            checkCookingState(context, world, guard, step.result(), "ALLOW", "ANY");
            checkCookingRow(context, step.result().labels(), step.result().icons(), glyphs, "More 1/2");
            if (step.capture() != null) context.takeScreenshot("ui-auto-smelting-controls-" + step.capture() + "-scale-" + scale);
        }
        if (scale == 3) {
            clickButton(context, "More 1/2");
            checkCookingExtras(context, "ALLOW", "ANY");
            clickButton(context, "Input tag match: ANY");
            checkCookingState(context, world, guard, CookingControls.defaults(), "ALLOW", "ALL");
            checkCookingExtras(context, "ALLOW", "ALL");
            clickButton(context, "Input tag match: ALL");
            checkCookingState(context, world, guard, CookingControls.defaults(), "ALLOW", "ANY");
            for (String[] modes : new String[][]{{"ALLOW", "BLOCK"}, {"BLOCK", "CONTENTS"}, {"CONTENTS", "ALLOW"}}) {
                clickButton(context, "Fuel filter mode: " + modes[0]);
                checkCookingState(context, world, guard, CookingControls.defaults(), modes[1], "ANY");
                checkCookingExtras(context, modes[1], "ANY");
            }
            clickButton(context, "Input tags");
            context.waitFor(client -> client.gui.screen() instanceof com.kadamitas.fabricatedbackpacks.client.screen.FilterTagsScreen);
            clickButton(context, "Back");
            context.waitFor(client -> client.gui.screen() instanceof BackpackScreen);
            checkCookingState(context, world, guard, CookingControls.defaults(), "ALLOW", "ANY");
            checkCookingExtras(context, "ALLOW", "ANY");
            clickButton(context, "More 2/2");
            checkCookingRow(context, CookingControls.defaults().labels(), CookingControls.defaults().icons(), glyphs, "More 1/2");
        }
    }

    private static CookingGuard cookingGuard(BagInventory bag, net.minecraft.world.entity.player.Player player) {
        var upgrade = upgrade(bag, 1);
        CompoundTag settings = bag.settings(upgrade);
        for (String key : List.of("input_filter_mode", "input_filter_match", "input_match_damage", "input_match_components", "input_tag_match", "fuel_filter_mode"))
            settings.remove(key);
        return new CookingGuard(bag.identity(), InventorySnapshot.capture(bag), InventorySnapshot.capture(bag.upgradeInventory(upgrade)),
                upgrade.stack().getOrDefault(BagComponents.FILTERS, InventorySnapshot.EMPTY),
                withoutSlot(InventorySnapshot.capture(player.getInventory()), 7), player.containerMenu.getCarried().copy(), settings);
    }

    private static boolean cookingStateMatches(BagInventory bag, CookingControls controls, String fuelMode, String tagMatch) {
        var settings = bag.settings(upgrade(bag, 1));
        return controls.matches(settings) && settings.getStringOr("fuel_filter_mode", "ALLOW").equals(fuelMode)
                && settings.getStringOr("input_tag_match", "ANY").equals(tagMatch) && !settings.getBooleanOr("enabled", true);
    }

    private static void checkCookingState(ClientGameTestContext context, TestSingleplayerContext world, CookingGuard guard,
                                          CookingControls controls, String fuelMode, String tagMatch) {
        world.getServer().waitFor(server -> cookingStateMatches(BagInventory.of(player(world).getInventory().getItem(7)), controls, fuelMode, tagMatch));
        world.getServer().runOnServer(server -> checkCookingGuard(guard,
                cookingGuard(BagInventory.of(player(world).getInventory().getItem(7)), player(world)), "server"));
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> client.gui.screen() instanceof BackpackScreen screen
                && cookingStateMatches(screen.getMenu().bag(), controls, fuelMode, tagMatch));
        context.runOnClient(client -> checkCookingGuard(guard,
                cookingGuard(((BackpackScreen) client.gui.screen()).getMenu().bag(), client.player), "client"));
    }

    private static void checkCookingGuard(CookingGuard expected, CookingGuard actual, String side) {
        check(expected.identity().equals(actual.identity()) && expected.storage().equals(actual.storage())
                        && expected.auxiliary().equals(actual.auxiliary()) && expected.filters().equals(actual.filters())
                        && expected.player().equals(actual.player()) && ItemStack.matches(expected.cursor(), actual.cursor())
                        && expected.otherSettings().equals(actual.otherSettings()),
                "Cooking control clicks change only their intended settings, preserving identity, every ghost/physical stack/cursor and unrelated state on " + side);
    }

    private static void checkCookingExtras(ClientGameTestContext context, String fuelMode, String tagMatch) {
        checkCookingRow(context, new String[]{"Enabled: Off", "Input tag match: " + tagMatch, "Input tags", "Fuel filter mode: " + fuelMode},
                null, null, "More 2/2");
    }

    private static void checkCookingRow(ClientGameTestContext context, String[] labels, BackpackIconButton.Icon[] icons,
                                        Map<BackpackIconButton.Icon, List<Integer>> glyphs, String moreLabel) {
        context.waitFor(client -> client.gui.screen() instanceof BackpackScreen screen && Arrays.stream(labels).allMatch(label ->
                screen.children().stream().anyMatch(child -> child instanceof BackpackIconButton button
                        && button.visible && button.active && button.getMessage().getString().equals(label))));
        context.runOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var panel = screen.upgradePanelBounds().orElseThrow();
            var targets = new ArrayList<ScreenRectangle>();
            for (int index = 0; index < labels.length; index++) {
                var button = cookingButton(screen, labels[index]);
                var expected = new ScreenRectangle(panel.left() + 6 + index * 18, panel.top() + 24, 16, 16);
                check(expected.equals(new ScreenRectangle(button.getX(), button.getY(), button.getWidth(), button.getHeight())),
                        "The four cooking actions occupy the reference16-pixel buttons in semantic order: " + labels[index]);
                checkIcon(button, false, labels[index]);
                var narrator = new ScreenNarrationCollector();
                narrator.update(button::updateNarration);
                check(narrator.collectNarrationText(true).contains(labels[index]), "Native button narration announces the action and current state: " + labels[index]);
                targets.add(expected);
                if (icons != null) {
                    check(button.getIcon() == icons[index], "The current setting selects its semantic glyph: " + labels[index]);
                    var painted = cookingGlyph(button);
                    List<Integer> previous = glyphs.putIfAbsent(icons[index], painted);
                    if (previous != null) check(previous.equals(painted), "A filter glyph is stable across real clicks and GUI scales: " + icons[index]);
                    for (var other : glyphs.entrySet()) if (other.getKey() != icons[index])
                        check(!painted.equals(other.getValue()), "Different cooking states paint different glyphs, not merely different borders: " + icons[index] + " vs " + other.getKey());
                }
            }
            var more = cookingButton(screen, moreLabel);
            var moreBounds = new ScreenRectangle(more.getX(), more.getY(), more.getWidth(), more.getHeight());
            check(more.getIcon() == BackpackIconButton.Icon.GEAR
                            && moreBounds.equals(new ScreenRectangle(panel.right() - 23, panel.top() + 124, 14, 14)),
                    "The extras gear sits in the furnace's unused lower-right corner without replacing one of the four filters");
            targets.add(moreBounds);
            for (int index = 0; index < 12; index++) targets.add(screen.ghostBounds(index).orElseThrow());
            var origin = (ContainerScreenAccess) (Object) screen;
            for (int index = 0; index < 3; index++) {
                var slot = screen.getMenu().getSlot(screen.getMenu().auxiliaryStart() + index);
                targets.add(new ScreenRectangle(origin.fabricatedBackpacks$left() + slot.x, origin.fabricatedBackpacks$top() + slot.y, 16, 16));
            }
            for (int index = 0; index < targets.size(); index++) {
                var bounds = targets.get(index);
                check(bounds.equals(bounds.intersection(panel)), "Every cooking click target remains inside its panel");
                for (int earlier = 0; earlier < index; earlier++) check(bounds.intersection(targets.get(earlier)) == null,
                        "Cooking controls, ghosts and actual item slots must have disjoint native hit boxes");
            }
        });
    }

    private static BackpackIconButton cookingButton(BackpackScreen screen, String label) {
        var matches = screen.children().stream().filter(BackpackIconButton.class::isInstance).map(BackpackIconButton.class::cast)
                .filter(button -> button.visible && button.active && button.getMessage().getString().equals(label)).toList();
        check(matches.size() == 1, "One actual visible button must expose the complete cooking label: " + label);
        return matches.getFirst();
    }

    private static List<Integer> cookingGlyph(BackpackIconButton button) {
        var client = net.minecraft.client.Minecraft.getInstance();
        var state = new GuiRenderState();
        button.extractRenderState(new GuiGraphicsExtractor(client, state, -1, -1), -1, -1, 0);
        int[] pixels = new int[button.getWidth() * button.getHeight()];
        // The native button draws its bevel before entering the icon scissor. Inspect only
        // its actual clipped glyph commands; hover/selection borders cannot satisfy this test.
        state.forEachElement(element -> {
            if (!(element instanceof ColoredRectangleRenderState rectangle) || rectangle.scissorArea() == null || rectangle.bounds() == null) return;
            check(rectangle.col1() == rectangle.col2(), "Pixel glyph spans are solid colors");
            var bounds = rectangle.bounds();
            check(bounds.left() >= button.getX() && bounds.top() >= button.getY()
                            && bounds.right() <= button.getRight() && bounds.bottom() <= button.getBottom(),
                    "Native glyph spans stay inside their16-pixel input target");
            for (int y = bounds.top(); y < bounds.bottom(); y++) for (int x = bounds.left(); x < bounds.right(); x++)
                pixels[(y - button.getY()) * button.getWidth() + x - button.getX()] = rectangle.col1();
        }, GuiRenderState.TraverseRange.ALL);
        check(Arrays.stream(pixels).anyMatch(color -> color != 0), "The actual semantic button submits visible glyph pixels");
        boolean requiresRed = switch (button.getIcon()) {
            case FILTER_BLOCK, MATCH_ITEM, IGNORE_DAMAGE, IGNORE_COMPONENTS -> true;
            default -> false;
        };
        if (requiresRed) check(Arrays.stream(pixels).anyMatch(color -> {
            int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
            return color >>> 24 != 0 && r >= 140 && r * 2 > g * 3 && r * 2 > b * 3;
        }), "The block/apple/ignore symbol retains its red semantic detail: " + button.getIcon());
        if (button.getIcon() == BackpackIconButton.Icon.FILTER_ALLOW) check(Arrays.stream(pixels).anyMatch(color -> {
            int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
            return color >>> 24 != 0 && g >= 100 && g * 5 > r * 6 && g * 5 > b * 6;
        }), "The allow state paints a green check rather than the block state's red X");
        return Arrays.stream(pixels).boxed().toList();
    }

    private static void checkCookingFilterGeometry(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var menu = screen.getMenu();
            var upgrade = menu.selected().orElseThrow();
            var panel = screen.upgradePanelBounds().orElseThrow();
            var origin = (ContainerScreenAccess) (Object) screen;
            check(upgrade.kind() == UpgradeKind.AUTO_SMELTING && menu.bag().cookingInputFilters(upgrade) == 8
                            && menu.bag().cookingFuelFilters(upgrade) == 4 && menu.bag().filterSlots(upgrade) == 12,
                    "The default auto-smelting fixture exposes eight input and four distinct fuel filter addresses");
            check(panel.width() == 84 && panel.height() == 176,
                    "The default cooking panel keeps its complete reference layout at both GUI scales: " + panel);
            var occupied = new ArrayList<ScreenRectangle>();
            for (int index = 0; index < 3; index++) {
                var slot = menu.getSlot(menu.auxiliaryStart() + index);
                var bounds = new ScreenRectangle(origin.fabricatedBackpacks$left() + slot.x,
                        origin.fabricatedBackpacks$top() + slot.y, 16, 16);
                int expectedX = panel.left() + (index == 2 ? panel.width() - 24 : 6);
                int expectedY = panel.top() + (index == 0 ? 86 : index == 1 ? 122 : 104);
                check(slot.isActive() && bounds.equals(new ScreenRectangle(expectedX, expectedY, 16, 16))
                                && bounds.equals(bounds.intersection(panel)),
                        "Each real furnace input/fuel/result slot stays inside its reference position: " + index + "=" + bounds);
                for (var previous : occupied) check(bounds.intersection(previous) == null, "Real furnace slots must not overlap");
                occupied.add(bounds);
            }
            for (int index = 0; index < 12; index++) {
                var bounds = screen.ghostBounds(index).orElseThrow();
                int local = index < 8 ? index : index - 8;
                int expectedY = panel.top() + (index < 8 ? 46 + local / 4 * 18 : 148);
                var expected = new ScreenRectangle(panel.left() + 6 + local % 4 * 18, expectedY, 16, 16);
                check(bounds.equals(expected) && bounds.equals(bounds.intersection(panel)),
                        "Each16-pixel ghost uses the exact two-row input grid or separate one-row fuel grid: " + index + "=" + bounds);
                check(index < 8 ? bounds.bottom() <= panel.top() + 86 : bounds.top() >= panel.top() + 138,
                        "Input ghosts stay above all real furnace slots and fuel ghosts below them: " + index);
                for (var previous : occupied) check(bounds.intersection(previous) == null,
                        "Cooking ghost hit targets must not overlap another ghost or a real slot: " + index);
                occupied.add(bounds);
            }
        });
    }

    private static void checkItemModels(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ResourceLocation fixture = ResourceLocation.fromNamespaceAndPath("fabricated_backpacks_tests", "energy_cell");
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
            ItemStack selected = screen.getMenu().selected().orElseThrow().stack();
            String full = selected.getHoverName().getString();
            String heading = selected.has(DataComponents.CUSTOM_NAME) ? full : "Jukebox";
            var label = screen.children().stream().filter(StringWidget.class::isInstance).map(StringWidget.class::cast)
                    .filter(widget -> widget.getMessage().getString().equals(heading)).findFirst().orElseThrow();
            int available = screen.upgradePanelBounds().orElseThrow().width() - 30;
            check(label.getWidth() == Math.min(client.font.width(label.getMessage()), available),
                    "The upgrade heading uses the configured panel width");
            String rendered = renderedText(label);
            boolean clipped = client.font.width(label.getMessage()) > available;
            if (requireClipping) check(clipped, "The long-name fixture exercises heading overflow");
            if (clipped) {
                check(rendered.endsWith("..."),
                        "An overflowing heading uses an explicit ellipsis");
                check(client.font.width(rendered) <= available, "The ellipsis stays within the measured heading width");
            } else check(rendered.equals(heading), "A default panel uses its short family name; custom headings remain complete when they fit");
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
            checkHeadingRenderOutput(screen);
        });
    }

    private static String renderedText(StringWidget label) {
        var client = net.minecraft.client.Minecraft.getInstance();
        var state = new GuiRenderState();
        label.extractRenderState(new GuiGraphicsExtractor(client, state, -1, -1), -1, -1, 0);
        var text = new ArrayList<GuiTextRenderState>();
        state.forEachText(text::add);
        check(text.size() == 1, "A heading submits exactly one native text run: " + label.getMessage().getString() + "; runs=" + text.size());
        checkNoTextShadow(text.getFirst(), label.getMessage().getString());
        return plain(nativeText(text.getFirst()));
    }

    private static void checkHeadingRenderOutput(BackpackScreen screen) {
        // Observe the actual screen extraction as well as its widgets: duplicate parent-label draws
        // would be invisible to a widget-only test. No text/render state is replaced or injected.
        var client = net.minecraft.client.Minecraft.getInstance();
        var state = new GuiRenderState();
        screen.extractRenderState(new GuiGraphicsExtractor(client, state, -1, -1), -1, -1, 0);
        var text = new ArrayList<GuiTextRenderState>();
        state.forEachText(text::add);
        var labels = screen.children().stream().filter(StringWidget.class::isInstance).map(StringWidget.class::cast)
                .filter(label -> label.visible).toList();
        check(!labels.isEmpty(), "The text regression observes visible native backpack headings");
        for (var label : labels) {
            String expected = renderedText(label);
            var area = new ScreenRectangle(label.getX() - 1, label.getY() - 1, label.getWidth() + 2, label.getHeight() + 2);
            var overlapping = text.stream().filter(run -> run.bounds() != null && run.bounds().intersection(area) != null).toList();
            check(overlapping.size() == 1 && plain(nativeText(overlapping.getFirst())).equals(expected),
                    "The full screen paints each heading once, without overlapping text: " + label.getMessage().getString()
                            + "; actual=" + overlapping.stream().map(run -> plain(nativeText(run)) + "@" + run.bounds()).toList());
            checkNoTextShadow(overlapping.getFirst(), label.getMessage().getString());
        }
        String inventory = Component.translatable("container.inventory").getString();
        var inventoryRuns = text.stream().filter(run -> plain(nativeText(run)).equals(inventory)).toList();
        check(inventoryRuns.size() == 1, "The full screen paints the Inventory caption exactly once; runs=" + inventoryRuns.size());
        checkNoTextShadow(inventoryRuns.getFirst(), inventory);
        var captionOrigin = nativeTextOrigin(inventoryRuns.getFirst());
        var screenOrigin = (ContainerScreenAccess) (Object) screen;
        check(captionOrigin.x == screenOrigin.fabricatedBackpacks$left() + screen.getMenu().inventoryX()
                        && captionOrigin.y == screenOrigin.fabricatedBackpacks$top() + screen.getMenu().layout().inventoryTitleY() + 2,
                "The actual Inventory caption starts two pixels below the full-width strip's top: " + captionOrigin);
    }

    static org.joml.Vector2f nativeTextOrigin(GuiTextRenderState state) {
        try {
            var x = GuiTextRenderState.class.getDeclaredField("x");
            var y = GuiTextRenderState.class.getDeclaredField("y");
            x.setAccessible(true);
            y.setAccessible(true);
            return state.pose.transformPosition(new org.joml.Vector2f(x.getInt(state), y.getInt(state)));
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect the native submitted text origin", failure); }
    }

    static FormattedCharSequence nativeText(GuiTextRenderState state) {
        try {
            var field = GuiTextRenderState.class.getDeclaredField("text");
            field.setAccessible(true);
            return (FormattedCharSequence) field.get(state);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect the native submitted text", failure); }
    }

    static void checkNoTextShadow(GuiTextRenderState state, String label) {
        int[] glyphs = {0};
        state.ensurePrepared().visit(new Font.GlyphVisitor() {
            @Override public void acceptGlyph(TextRenderable.Styled glyph) {
                glyphs[0]++;
                checkShadowColor(glyph, label);
            }
            @Override public void acceptEffect(TextRenderable effect) { checkShadowColor(effect, label); }
        });
        check(glyphs[0] > 0, "The native heading contains real prepared glyphs: " + label);
    }

    private static void checkShadowColor(TextRenderable glyph, String label) {
        try {
            // Exact26.2 prepared glyph/effect records expose the final color used by hasShadow().
            // Inspect that output: a style override can legitimately disable a true dropShadow flag.
            var getter = glyph.getClass().getDeclaredMethod("shadowColor");
            getter.setAccessible(true);
            int shadow = ((Number) getter.invoke(glyph)).intValue();
            check(shadow == 0, "A dark heading must not emit offset shadow glyphs: " + label + "; shadow=0x" + Integer.toHexString(shadow));
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect native prepared heading glyph " + glyph.getClass().getName(), failure); }
    }
    static String plain(FormattedCharSequence sequence) {
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
