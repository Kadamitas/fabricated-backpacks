package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlock;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.client.screen.EquipmentScreen;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A real rendered client, a newly created world, actual mouse/key input, and save/reopen checks. */
public final class BackpackClientGameTests implements FabricClientGameTest {
    private static final List<String> RECORDS = List.of("13", "cat", "blocks", "chirp", "far", "mall", "mellohi", "stal", "strad", "ward", "11", "wait");
    private final List<String> evidence = new ArrayList<>();

    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
        switch (System.getProperty("fabricated.backpacks.clientScenario", "full")) {
            case "restart" -> { ClientAcceptanceFiles.restart(context); return; }
            case "multiplayer_host" -> { MultiplayerClientAcceptance.host(context); return; }
            case "multiplayer_guest" -> { MultiplayerClientAcceptance.guest(context); return; }
            case "full" -> ClientAcceptanceFiles.beginFull();
            default -> throw new AssertionError("Unknown client acceptance scenario");
        }
        TestWorldSave save;
        String identity;
        net.minecraft.nbt.CompoundTag expected;
        try (var world = context.worldBuilder().create()) {
            save = world.getWorldSave();
            setup(world);
            evidence.addAll(world.getServer().computeOnServer(server -> ChestLootAcceptance.verify(server.overworld(), new BlockPos(0, 80, 0), true)));
            world.getConnection().waitForChunksRender();
            context.getInput().lookAt(new BlockPos(0, 80, 4));
            context.waitTicks(5);
            identity = world.getServer().computeOnServer(server -> bag(world).identity());
            screenshot(context, "01-new-world-six-tiers");

            context.getInput().pressKey(GLFW.GLFW_KEY_B);
            context.waitForScreen(BackpackScreen.class);
            context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().bag().getItem(0).getCount() == 200_000);
            check(world.getServer().computeOnServer(server -> bag(world).getItem(0).getCount()) == 200_000, "Enlarged count must survive server/client menu synchronization");
            transferStorageWithMouse(context, world);
            fillTwelveRecordSlots(context, world);
            operateJukebox(context, world);
            transferWaterWithMouse(context, world);
            clickButton(context, "Items");
            waitBrowser(context);
            searchBrowser(context, "@fabricated_backpacks backpack");
            context.waitTicks(40);
            screenshot(context, "05-native-recipe-browser");
            clickButton(context, "Close");
            context.waitForScreen(BackpackScreen.class);

            clickButton(context, "2");
            context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 1);
            context.waitTicks(2);
            clickButton(context, "Open station");
            context.waitForScreen(CraftingScreen.class);
            check(world.getServer().computeOnServer(server -> player(world).containerMenu instanceof WorkstationMenus.PortableCrafting), "Crafting screen must use the real persistent portable menu");
            clickPlayerSlot(context, 22);
            clickSlot(context, 1);
            world.getServer().waitFor(server -> !player(world).containerMenu.slots.get(0).getItem().isEmpty());
            check(world.getServer().computeOnServer(server -> player(world).containerMenu.slots.get(0).getItem().is(Items.OAK_PLANKS)), "Vanilla crafting recipe must produce oak planks");
            screenshot(context, "06-persistent-crafting");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);
            check(world.getServer().computeOnServer(server -> bag(world).upgradeInventory(BackpackTestSupport.upgrade(bag(world), 1)).getItem(0).is(Items.OAK_LOG)), "Closing the workstation must retain its grid");

            context.getInput().pressKey(GLFW.GLFW_KEY_G);
            context.waitForScreen(EquipmentScreen.class);
            clickPlayerSlot(context, 2);
            clickSlot(context, 0);
            world.getServer().waitFor(server -> BackpackRegistry.tier(BackpackEquipment.get(player(world))).orElse(null) == BackpackTier.GOLD);
            check(world.getServer().computeOnServer(server -> player(world).getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)), "Native backpack equipment must leave chest armor intact");
            screenshot(context, "07-native-equipment-with-armor");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);
            context.getInput().pressKey(GLFW.GLFW_KEY_F5);
            context.getInput().lookAt(0, 15);
            context.waitTicks(6);
            screenshot(context, "08-worn-backpack-with-armor");
            context.getInput().pressKey(GLFW.GLFW_KEY_F5);
            context.getInput().pressKey(GLFW.GLFW_KEY_F5);
            context.waitTicks(2);
            BrowserClientAcceptance.run(context, world);
            ConfiguredClientAcceptance.run(context, world);
            StorageClientAcceptance.run(context, world);
            RulesClientAcceptance.run(context, world);
            evidence.addAll(PlacedAppearanceAcceptance.run(context, world));
            check(world.getServer().computeOnServer(server -> recordCount(bag(world))) == 12, "All twelve physical records must persist after unrelated menus");
            evidence.add("Input: storage click/return, 12 physical record inserts, actual audio channels, bucket transfer, browser recipe transfer/craft/ghost/bookmarks/reload, equip with armor");
        }
        try (var reopened = save.open()) {
            reopened.getConnection().waitForChunksRender();
            context.waitTicks(5);
            check(reopened.getServer().computeOnServer(server -> bag(reopened).identity()).equals(identity), "Backpack identity must survive world save/reopen");
            check(reopened.getServer().computeOnServer(server -> bag(reopened).getItem(0).getCount()) == 200_000, "200000 stored items must survive world save/reopen");
            check(reopened.getServer().computeOnServer(server -> recordCount(bag(reopened))) == 12, "Twelve discs must survive world save/reopen");
            check(reopened.getServer().computeOnServer(server -> ResourceRuntime.tankStoredMb(bag(reopened), 2)) == 1000, "Fluid must survive world save/reopen");
            check(reopened.getServer().computeOnServer(server -> bag(reopened).upgradeInventory(BackpackTestSupport.upgrade(bag(reopened), 1)).getItem(0).getCount()) == 3, "Crafting input must survive world save/reopen");
            check(reopened.getServer().computeOnServer(server -> BackpackRegistry.tier(BackpackEquipment.get(player(reopened))).orElse(null)) == BackpackTier.GOLD, "Independent equipment attachment must survive world save/reopen");
            context.getInput().pressKey(GLFW.GLFW_KEY_B);
            context.waitForScreen(BackpackScreen.class);
            check(context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().bag().tier()) == BackpackTier.GOLD, "B must open the equipped backpack after reconnect");
            screenshot(context, "09-world-reopened-equipment");
            evidence.add("Save/reopen: identity, 200000-count stack, all12records, 1000mB water, persistent crafting input, independent equipment");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);
            expected = ClientAcceptanceFiles.snapshot(reopened);
        }
        ClientAcceptanceFiles.archive(save, expected);
        try {
            Path report = Path.of("client-acceptance.txt").toAbsolutePath();
            Files.write(report, evidence);
            var proof = new com.google.gson.JsonObject();
            proof.addProperty("passed", true);
            proof.addProperty("pid", ProcessHandle.current().pid());
            proof.add("checks", new com.google.gson.Gson().toJsonTree(evidence));
            ClientAcceptanceFiles.copyTree(Path.of("screenshots"), ClientAcceptanceFiles.ROOT.resolve("full-screenshots"));
            Files.writeString(ClientAcceptanceFiles.ROOT.resolve("full-pass.json"), proof.toString());
            System.out.println("FABRICATED_BACKPACKS_CLIENT_ACCEPTANCE_PASS " + report);
        } catch (java.io.IOException exception) { throw new AssertionError("Could not write client acceptance evidence", exception); }
    }

    private static void setup(TestSingleplayerContext world) {
        world.getServer().runCommand("fill -12 79 -12 12 79 12 minecraft:stone");
        world.getServer().runCommand("time set day");
        world.getServer().runCommand("weather clear");
        world.getServer().runOnServer(server -> {
            ServerPlayer player = player(world);
            player.getInventory().clearContent();
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(0.5, 80, 0.5);
            var bag = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_JUKEBOX, UpgradeKind.CRAFTING,
                    UpgradeKind.TANK, UpgradeKind.BATTERY, UpgradeKind.ADVANCED_MOB_CATCHER, UpgradeKind.STACK_UPGRADE_OMEGA_TIER);
            bag.setItem(0, new ItemStack(Items.DIAMOND, 200_000));
            bag.stack().set(DataComponents.CUSTOM_NAME, Component.literal("Client Acceptance Backpack"));
            bag.dye(0x437e94, 0xd6b257);
            player.getInventory().setItem(0, bag.stack());
            player.getInventory().setSelectedSlot(0);
            var worn = BackpackTestSupport.bag(BackpackTier.GOLD);
            worn.dye(0x794cab, 0xe4c370);
            player.getInventory().setItem(2, worn.stack());
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            for (int slot = 0; slot < RECORDS.size(); slot++) player.getInventory().setItem(9 + slot,
                    new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("music_disc_" + RECORDS.get(slot)))));
            player.getInventory().setItem(21, new ItemStack(Items.EMERALD, 19));
            player.getInventory().setItem(22, new ItemStack(Items.OAK_LOG, 3));
            player.getInventory().setItem(24, new ItemStack(Items.WATER_BUCKET));
            for (BackpackTier tier : BackpackTier.values()) {
                BlockPos pos = new BlockPos(-5 + tier.ordinal() * 2, 80, 4);
                player.level().setBlock(pos, BackpackRegistry.block(tier).defaultBlockState().setValue(BackpackBlock.FACING, Direction.NORTH), 3);
                var placed = (BackpackBlockEntity) player.level().getBlockEntity(pos);
                var sample = BackpackTestSupport.bag(tier);
                sample.setItem(0, new ItemStack(Items.APPLE, tier.ordinal() + 1));
                placed.setStack(sample.stack());
            }
            player.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
    }

    private static void transferStorageWithMouse(ClientGameTestContext context, TestSingleplayerContext world) {
        clickPlayerSlot(context, 21);
        clickSlot(context, 1);
        world.getServer().waitFor(server -> bag(world).getItem(1).is(Items.EMERALD) && bag(world).getItem(1).getCount() == 19);
        clickSlot(context, 1);
        clickPlayerSlot(context, 21);
        world.getServer().waitFor(server -> bag(world).getItem(1).isEmpty());
        check(world.getServer().computeOnServer(server -> player(world).getInventory().getItem(21).getCount()) == 19, "Mouse transfer and return must conserve all19 emeralds");
    }

    private void fillTwelveRecordSlots(ClientGameTestContext context, TestSingleplayerContext world) {
        clickButton(context, "1");
        context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 0);
        context.waitTicks(2);
        for (int record = 0; record < 12; record++) {
            clickPlayerSlot(context, 9 + record);
            clickSlot(context, BackpackTier.NETHERITE.slots() + BackpackTier.NETHERITE.upgradeSlots() + record);
        }
        world.getServer().waitFor(server -> recordCount(bag(world)) == 12);
        check(world.getServer().computeOnServer(server -> player(world).containerMenu.getCarried().isEmpty()), "All records must be in physical slots, not stranded on cursor");
        screenshot(context, "02-twelve-record-slots");
    }

    private void operateJukebox(ClientGameTestContext context, TestSingleplayerContext world) {
        try (var audio = new ClientAudioProbe(context)) {
        clickButton(context, "Play");
        world.getServer().waitFor(server -> bag(world).settings(BackpackTestSupport.upgrade(bag(world), 0)).getBooleanOr("playing", false));
        context.waitTicks(8);
        int initial = world.getServer().computeOnServer(server -> bag(world).settings(BackpackTestSupport.upgrade(bag(world), 0)).getIntOr("active_slot", -1));
        check(initial >= 0, "Play must select a nonempty physical record slot");
        audio.awaitActive(1);
        var moving = audio.first();
        world.getServer().runOnServer(server -> player(world).teleportTo(2.5, 80, .5));
        context.waitFor(client -> Math.abs(moving.getX() - 2.5) < .1);
        var second = world.getServer().computeOnServer(server -> {
            BlockPos pos = new BlockPos(0, 80, 6);
            player(world).level().setBlockAndUpdate(pos, BackpackRegistry.block(BackpackTier.LEATHER).defaultBlockState());
            var entity = (BackpackBlockEntity) player(world).level().getBlockEntity(pos);
            var extra = BackpackTestSupport.bag(BackpackTier.LEATHER, UpgradeKind.JUKEBOX);
            extra.upgradeInventory(BackpackTestSupport.upgrade(extra, 0)).setItem(0, new ItemStack(Items.MUSIC_DISC_13));
            entity.setStack(extra.stack());
            var live = entity.inventory();
            com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime.action(live, BackpackTestSupport.upgrade(live, 0), player(world).level(), pos, null, "play");
            return pos;
        });
        audio.awaitActive(2);
        screenshot(context, "03-jukebox-playing");
        clickButton(context, "Next");
        world.getServer().waitFor(server -> bag(world).settings(BackpackTestSupport.upgrade(bag(world), 0)).getIntOr("active_slot", -1) != initial);
        clickButton(context, "Stop");
        world.getServer().waitFor(server -> !bag(world).settings(BackpackTestSupport.upgrade(bag(world), 0)).getBooleanOr("playing", false));
        audio.awaitActive(1);
        world.getServer().runOnServer(server -> {
            var extra = ((BackpackBlockEntity) player(world).level().getBlockEntity(second)).inventory();
            com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime.action(extra, BackpackTestSupport.upgrade(extra, 0), player(world).level(), second, null, "stop");
            player(world).teleportTo(.5, 80, .5);
        });
        audio.awaitActive(0);
        evidence.add("Audio: two separate live SoundManager record instances, carrier position follows movement, stopping one retains the other; " + audio.channels());
        }
    }

    private void transferWaterWithMouse(ClientGameTestContext context, TestSingleplayerContext world) {
        clickPlayerSlot(context, 24);
        var position = context.computeOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            return new double[]{(screen.width - screen.getMenu().imageWidth()) / 2.0 + 8 + 8 * 18 + 16,
                    (screen.height - screen.getMenu().imageHeight()) / 2.0 + 75};
        });
        clickAt(context, position[0], position[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        world.getServer().waitFor(server -> ResourceRuntime.tankStoredMb(bag(world), 2) == 1000);
        check(world.getServer().computeOnServer(server -> player(world).containerMenu.getCarried().is(Items.BUCKET)), "Tank cursor transfer must return exactly the empty bucket");
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> client.player.containerMenu.getCarried().is(Items.BUCKET));
        clickPlayerSlot(context, 24);
        world.getConnection().waitForServerboundPackets();
        world.getConnection().waitForClientboundPackets();
        check(world.getServer().computeOnServer(server -> player(world).containerMenu.getCarried().isEmpty()
                && player(world).getInventory().getItem(24).is(Items.BUCKET)), "Returning the bucket must clear the server cursor without duplicate-click collection");
        context.waitFor(client -> client.player.containerMenu.getCarried().isEmpty());
        screenshot(context, "04-fluid-column-and-quantities");
    }

    static void searchBrowser(ClientGameTestContext context, String query) {
        double[] position = context.computeOnClient(client -> client.gui.screen().children().stream().filter(EditBox.class::isInstance)
                .map(EditBox.class::cast).map(box -> new double[]{box.getX() + 8, box.getY() + 8}).findFirst().orElseThrow());
        clickAt(context, position[0], position[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_CONTROL);
        context.getInput().pressKey(GLFW.GLFW_KEY_A);
        context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_CONTROL);
        context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
        context.getInput().typeChars(query);
        context.waitTicks(4);
        check(context.computeOnClient(client -> client.gui.screen().children().stream().filter(EditBox.class::isInstance)
                .map(EditBox.class::cast).findFirst().orElseThrow().getValue()).equals(query),
                "Actual Ctrl+A / replacement input must leave the exact requested text: " + query);
    }

    static void clickButton(ClientGameTestContext context, String label) {
        try {
            context.waitFor(client -> client.gui.screen() != null && client.gui.screen().children().stream()
                    .anyMatch(widget -> widget instanceof net.minecraft.client.gui.components.AbstractWidget button
                            && button.visible && button.active && button.getMessage().getString().equals(label)));
        } catch (AssertionError failure) {
            context.takeScreenshot("missing-button-" + label.replaceAll("[^a-zA-Z0-9]", "_"));
            String widgets = context.computeOnClient(client -> client.gui.screen() == null ? "no screen"
                    : client.gui.screen().getClass().getSimpleName() + " " + client.gui.screen().children().stream()
                    .filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                    .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                    .map(button -> button.getMessage().getString() + "=" + button.active + "/" + button.visible).toList());
            throw new AssertionError("Missing clickable '" + label + "': " + widgets, failure);
        }
        double[] position = context.computeOnClient(client -> client.gui.screen().children().stream()
                .filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                .filter(button -> button.visible && button.active && button.getMessage().getString().equals(label))
                .map(button -> new double[]{button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0})
                .findFirst().orElseThrow());
        clickAt(context, position[0], position[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    static void waitBrowser(ClientGameTestContext context) {
        context.waitFor(client -> client.gui.screen() != null && client.gui.screen().getClass().getSimpleName().equals("RecipeBrowserScreen"));
    }
    static ServerPlayer player(TestSingleplayerContext world) { return world.getConnection().getServerPlayer(); }
    private static BagInventory bag(TestSingleplayerContext world) { return BagInventory.of(player(world).getInventory().getItem(0)); }
    private static int recordCount(BagInventory bag) {
        var inventory = bag.upgradeInventory(BackpackTestSupport.upgrade(bag, 0));
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) if (!inventory.getItem(slot).isEmpty()) count++;
        return count;
    }
    static void clickPlayerSlot(ClientGameTestContext context, int inventorySlot) {
        int slot = context.computeOnClient(client -> {
            var menu = client.player.containerMenu;
            for (int index = 0; index < menu.slots.size(); index++) if (menu.slots.get(index).container == client.player.getInventory()
                    && menu.slots.get(index).getContainerSlot() == inventorySlot) return index;
            throw new AssertionError("Missing client player slot " + inventorySlot);
        });
        clickSlot(context, slot);
    }
    static void clickSlot(ClientGameTestContext context, int slotIndex) {
        double[] position = context.computeOnClient(client -> {
            var screen = (AbstractContainerScreen<?>) client.gui.screen();
            var menu = client.player.containerMenu;
            var slot = menu.slots.get(slotIndex);
            var origin = (com.kadamitas.fabricatedbackpacks.client.mixin.ContainerScreenAccess) screen;
            return new double[]{origin.fabricatedBackpacks$left() + slot.x + 8, origin.fabricatedBackpacks$top() + slot.y + 8};
        });
        clickAt(context, position[0], position[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    static void clickAt(ClientGameTestContext context, double x, double y, int button) {
        double[] window = context.computeOnClient(client -> new double[]{x * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
                y * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(window[0], window[1]);
        context.getInput().pressMouse(button);
        context.waitTicks(3);
    }
    static void hoverPlayerSlot(ClientGameTestContext context, int inventorySlot) {
        double[] window = context.computeOnClient(client -> {
            var screen = (AbstractContainerScreen<?>) client.gui.screen();
            var slot = client.player.containerMenu.slots.stream().filter(candidate -> candidate.container == client.player.getInventory()
                    && candidate.getContainerSlot() == inventorySlot).findFirst().orElseThrow();
            var origin = (com.kadamitas.fabricatedbackpacks.client.mixin.ContainerScreenAccess) screen;
            double x = origin.fabricatedBackpacks$left() + slot.x + 8;
            double y = origin.fabricatedBackpacks$top() + slot.y + 8;
            return new double[]{x * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
                    y * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(window[0], window[1]);
        context.waitTicks(2);
    }
    private void screenshot(ClientGameTestContext context, String name) {
        context.waitTicks(2);
        evidence.add("Screenshot: " + context.takeScreenshot(name));
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
