package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlock;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackSettingsScreen;
import com.kadamitas.fabricatedbackpacks.client.screen.EquipmentScreen;
import com.kadamitas.fabricatedbackpacks.client.render.BackpackRendering;
import com.kadamitas.fabricatedbackpacks.client.render.BackpackVisualState;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus;
import com.kadamitas.fabricatedbackpacks.item.BackpackDisplay;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A real rendered client, a newly created world, actual mouse/key input, and save/reopen checks. */
public final class BackpackClientGameTests implements FabricClientGameTest {
    private static final List<String> RECORDS = List.of("13", "cat", "blocks", "chirp", "far", "mall", "mellohi", "stal", "strad", "ward", "11", "wait");
    private static final List<String> MOD_KEY_BINDINGS = List.of(
            "key.fabricated_backpacks.open", "key.fabricated_backpacks.equipment", "key.fabricated_backpacks.browser",
            "key.fabricated_backpacks.transfer", "key.fabricated_backpacks.deposit", "key.fabricated_backpacks.restock",
            "key.fabricated_backpacks.tool_cycle", "key.fabricated_backpacks.upgrade_1", "key.fabricated_backpacks.upgrade_2",
            "key.fabricated_backpacks.upgrade_3", "key.fabricated_backpacks.upgrade_4", "key.fabricated_backpacks.upgrade_5");
    private final List<String> evidence = new ArrayList<>();

    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
        switch (System.getProperty("fabricated.backpacks.clientScenario", "full")) {
            case "restart" -> { ClientAcceptanceFiles.restart(context); return; }
            case "automation" -> { ClientAcceptanceFiles.automation(context); return; }
            case "automation_restart" -> { ClientAcceptanceFiles.restartAutomation(context); return; }
            case "automation_jei" -> { JeiConduitClientAcceptance.run(context); return; }
            case "appearance" -> {
                try (var world = context.worldBuilder().create()) {
                    setup(world);
                    world.getConnection().waitForChunksRender();
                    wornAppearance(context, world);
                }
                System.out.println("FABRICATED_BACKPACKS_WORN_APPEARANCE_PASS");
                return;
            }
            case "input" -> {
                try (var world = context.worldBuilder().create()) {
                    setup(world);
                    world.getConnection().waitForChunksRender();
                    inputIsolation(context, world);
                }
                System.out.println("FABRICATED_BACKPACKS_INPUT_ISOLATION_PASS");
                return;
            }
            case "jukebox_pages" -> {
                try (var world = context.worldBuilder().create()) {
                    setupJukeboxPages(world);
                    world.getConnection().waitForChunksRender();
                    jukeboxPages(context, world);
                }
                System.out.println("FABRICATED_BACKPACKS_JUKEBOX_PAGES_PASS");
                return;
            }
            case "multiplayer_host" -> { MultiplayerClientAcceptance.host(context); return; }
            case "multiplayer_guest" -> { MultiplayerClientAcceptance.guest(context); return; }
            case "full" -> ClientAcceptanceFiles.beginFull();
            default -> throw new AssertionError("Unknown client acceptance scenario");
        }
        TestWorldSave save;
        String identity;
        net.minecraft.nbt.CompoundTag expected;
        net.minecraft.nbt.CompoundTag automationExpected;
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
            inputIsolation(context, world);
            transferStorageWithMouse(context, world);
            fillTwelveRecordSlots(context, world);
            ConfiguredClientAcceptance.referenceLayout(context, world);
            operateJukebox(context, world);
            transferWaterWithMouse(context, world);
            clickButton(context, "Items");
            waitBrowser(context);
            searchBrowser(context, "@fabricated_backpacks backpack");
            context.waitTicks(40);
            screenshot(context, "05-native-recipe-browser");
            clickButton(context, "Close");
            context.waitForScreen(BackpackScreen.class);

            selectUpgrade(context, 1);
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
            wornAppearance(context, world);
            BrowserClientAcceptance.run(context, world);
            ConfiguredClientAcceptance.run(context, world);
            StorageClientAcceptance.run(context, world);
            RulesClientAcceptance.run(context, world);
            evidence.addAll(PlacedAppearanceAcceptance.run(context, world));
            evidence.addAll(AutomationClientAcceptance.run(context, world));
            automationExpected = world.getServer().computeOnServer(server -> AutomationClientAcceptance.snapshot(server.overworld()));
            check(world.getServer().computeOnServer(server -> recordCount(bag(world))) == 12, "All twelve physical records must persist after unrelated menus");
            evidence.add("Input: storage click/return, 12 physical record inserts, actual audio channels, bucket transfer, browser recipe transfer/craft/ghost/bookmarks/reload, equip with armor");
        }
        try (var reopened = save.open()) {
            reopened.getConnection().waitForChunksRender();
            context.waitTicks(5);
            AutomationClientAcceptance.verifyReload(reopened, automationExpected);
            AutomationClientAcceptance.resumeRoutingAfterReload(reopened);
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
            evidence.add("Save/reopen: exact paused steam engine components, physical slots and every installed conduit lane/face mode.");
            evidence.add("Reload routing: the reconstructed conduit graph transfers a new physical item and the saved engine resumes natural generation.");
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

    private static void inputIsolation(ClientGameTestContext context, TestSingleplayerContext world) {
        context.getInput().pressKey(GLFW.GLFW_KEY_B);
        context.waitForScreen(BackpackScreen.class);
        context.runOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var state = new GuiRenderState();
            screen.extractRenderState(new GuiGraphicsExtractor(client, state, -1, -1), -1, -1, 0);
            var labels = new ArrayList<String>();
            state.forEachText(rendered -> labels.add(ConfiguredClientAcceptance.plain(
                    ConfiguredClientAcceptance.nativeText(rendered))));
            check(labels.contains("200000"), "A large stored stack renders its full exact count: " + labels);
        });
        selectUpgrade(context, 0);
        context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 0);
        checkUpgradeSettingTooltips(context);
        clickButton(context, "Prefs");
        context.waitForScreen(BackpackSettingsScreen.class);
        StorageClientAcceptance.checkSettingsTooltips(context);
        clickButton(context, "Back to backpack");
        context.waitForScreen(BackpackScreen.class);
        searchBrowser(context, "seed");
        int containerId = context.computeOnClient(client -> client.player.containerMenu.containerId);
        context.getInput().pressKey(GLFW.GLFW_KEY_B);
        context.getInput().typeChars("b");
        context.waitTicks(3);
        check(context.computeOnClient(client -> client.gui.screen() instanceof BackpackScreen screen
                        && screen.getMenu().containerId == containerId
                        && screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                        .anyMatch(box -> box.visible && box.active && box.isFocused() && box.getValue().equals("seedb"))),
                "Typing physical B into a backpack search field keeps the same menu focused and enters the letter");

        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null);
        context.getInput().pressKey(GLFW.GLFW_KEY_E);
        context.waitForScreen(InventoryScreen.class);
        for (int press = 0; press < 3; press++) context.getInput().pressKey(GLFW.GLFW_KEY_B);
        context.waitTicks(5);
        check(context.computeOnClient(client -> client.gui.screen() instanceof InventoryScreen),
                "Repeated backpack key presses are ignored while an inventory menu is open");
        world.getServer().waitFor(server -> player(world).containerMenu == player(world).inventoryMenu);
        allBindingsIgnoreMenus(context, world);
        context.getInput().holdKey(GLFW.GLFW_KEY_B);
        try {
            context.waitTicks(3);
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);
            context.waitTicks(5);
            world.getServer().waitFor(server -> player(world).containerMenu == player(world).inventoryMenu);
            check(context.computeOnClient(client -> client.gui.screen() == null),
                    "A held backpack key consumed by a menu does not queue an open after that menu closes");
        } finally {
            context.getInput().releaseKey(GLFW.GLFW_KEY_B);
        }
        context.getInput().pressKey(GLFW.GLFW_KEY_B);
        context.waitForScreen(BackpackScreen.class);
    }

    private static void checkUpgradeSettingTooltips(ClientGameTestContext context) {
        context.waitTicks(2);
        checkUpgradeSettingTooltips(context, false);
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        try {
            context.waitTicks(2);
            checkUpgradeSettingTooltips(context, true);
        } finally {
            context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        }
        context.waitTicks(2);
        checkUpgradeSettingTooltips(context, false);
    }

    private static void checkUpgradeSettingTooltips(ClientGameTestContext context, boolean expected) {
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof BackpackScreen, "Upgrade context-help checks run on the real backpack screen");
            var names = java.util.Set.of("Play", "Stop", "Previous", "Next", "Shuffle", "Repeat");
            List<BackpackIconButton> controls = client.gui.screen().children().stream()
                    .filter(BackpackIconButton.class::isInstance).map(BackpackIconButton.class::cast)
                    .filter(button -> names.contains(button.getMessage().getString().replaceFirst(":.*$", ""))).toList();
            check(controls.size() == names.size(), "All six advanced-jukebox settings are covered by Shift-only context help: "
                    + controls.stream().map(button -> button.getMessage().getString()).toList());
            for (BackpackIconButton control : controls) {
                String label = control.getMessage().getString();
                var tooltip = ((com.kadamitas.fabricatedbackpacks.gametest.mixin.TestWidgetTooltipAccess) (Object) control)
                        .fabricatedBackpacksTests$tooltip().get();
                if (!expected) {
                    check(tooltip == null, "Upgrade context help stays hidden without Shift: " + label);
                    continue;
                }
                check(tooltip != null, "Holding Shift attaches context help to upgrade setting: " + label);
                String help = tooltip.toCharSequence(client).stream().map(ConfiguredClientAcceptance::plain)
                        .collect(java.util.stream.Collectors.joining(" ")).replaceAll("\\s+", " ").strip();
                check(help.length() > label.length() + 12 && !help.equalsIgnoreCase(label),
                        "Upgrade help explains the setting instead of repeating its label: " + label + " -> " + help);
                if (label.equals("Play")) check(help.contains("first occupied disc slot"),
                        "Play help explains which physical record starts: " + help);
                if (label.startsWith("Repeat")) check(help.contains("OFF, ALL, and ONE"),
                        "Repeat help explains every available mode: " + help);
            }
        });
    }

    private static void allBindingsIgnoreMenus(ClientGameTestContext context, TestSingleplayerContext world) {
        ItemStack bagBefore = world.getServer().computeOnServer(server -> player(world).getInventory().getItem(0).copy());
        ItemStack wornBefore = world.getServer().computeOnServer(server -> BackpackEquipment.get(player(world)).copy());
        context.runOnClient(client -> {
            var actual = java.util.Arrays.stream(client.options.keyMappings).map(KeyMapping::getName)
                    .filter(name -> name.startsWith("key.fabricated_backpacks.")).collect(java.util.stream.Collectors.toSet());
            check(actual.equals(java.util.Set.copyOf(MOD_KEY_BINDINGS)),
                    "The menu-isolation matrix covers every registered Fabricated Backpacks binding: " + actual);
        });
        try {
            for (String name : MOD_KEY_BINDINGS) {
                bindOnly(context, name, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10);
                boolean upgrade = name.contains(".upgrade_");
                if (upgrade) context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_ALT);
                try {
                    context.getInput().pressKey(GLFW.GLFW_KEY_F10);
                    context.waitTicks(2);
                } finally {
                    if (upgrade) context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_ALT);
                }
                check(context.computeOnClient(client -> client.gui.screen() instanceof InventoryScreen),
                        name + " cannot replace or close an open keyboard-driven menu");

                bindOnly(context, name, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_4);
                if (upgrade) context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_ALT);
                try {
                    context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_4);
                    context.waitTicks(2);
                } finally {
                    if (upgrade) context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_ALT);
                }
                check(context.computeOnClient(client -> client.gui.screen() instanceof InventoryScreen),
                        name + " cannot replace or close an open mouse-driven menu");
            }

            context.runOnClient(client -> {
                InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F10);
                for (String name : MOD_KEY_BINDINGS) KeyMapping.get(name).setKey(key);
                KeyMapping.resetMapping();
                KeyMapping.click(key);
            });
            context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_ALT);
            try { context.waitTicks(3); }
            finally { context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_ALT); }
            check(context.computeOnClient(client -> client.gui.screen() instanceof InventoryScreen),
                    "Even pending clicks for every mod binding are drained without acting while a menu is open");
            check(world.getServer().computeOnServer(server -> player(world).containerMenu == player(world).inventoryMenu
                            && ItemStack.matches(player(world).getInventory().getItem(0), bagBefore)
                            && ItemStack.matches(BackpackEquipment.get(player(world)), wornBefore)),
                    "Menu-blocked bindings cannot mutate storage, equipment, or upgrade settings on the server");
        } finally {
            context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_ALT);
            context.runOnClient(client -> {
                for (String name : MOD_KEY_BINDINGS) {
                    KeyMapping mapping = KeyMapping.get(name);
                    mapping.setKey(mapping.getDefaultKey());
                }
                KeyMapping.resetMapping();
            });
        }
    }

    private static void bindOnly(ClientGameTestContext context, String selected, InputConstants.Type type, int code) {
        context.runOnClient(client -> {
            InputConstants.Key unbound = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_UNKNOWN);
            for (String name : MOD_KEY_BINDINGS) KeyMapping.get(name).setKey(unbound);
            KeyMapping.get(selected).setKey(type.getOrCreate(code));
            KeyMapping.resetMapping();
        });
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

    private static void setupJukeboxPages(TestSingleplayerContext world) {
        world.getServer().runCommand("fill -4 79 -4 4 79 4 minecraft:stone");
        world.getServer().runCommand("time set day");
        world.getServer().runCommand("weather clear");
        world.getServer().runOnServer(server -> {
            ServerPlayer player = player(world);
            player.getInventory().clearContent();
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(.5, 80, .5);
            BagInventory bag = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_JUKEBOX);
            var records = new SimpleContainer(200);
            for (int slot = 0; slot < records.getContainerSize(); slot++) records.setItem(slot, new ItemStack(Items.MUSIC_DISC_13));
            records.setItem(99, new ItemStack(Items.MUSIC_DISC_BLOCKS));
            records.setItem(199, new ItemStack(Items.MUSIC_DISC_WAIT));
            bag.upgrades().getItem(0).set(BagComponents.CONTENTS, InventorySnapshot.capture(records));
            bag.upgrades().setChanged();
            bag.stack().set(DataComponents.CUSTOM_NAME, Component.literal("Two Hundred Disc Backpack"));
            player.getInventory().setItem(0, bag.stack());
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
    }

    private static void jukeboxPages(ClientGameTestContext context, TestSingleplayerContext world) {
        context.getInput().pressKey(GLFW.GLFW_KEY_B);
        context.waitForScreen(BackpackScreen.class);
        selectUpgrade(context, 0);
        context.waitFor(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var inventory = screen.getMenu().bag().upgradeInventory(screen.getMenu().selected().orElseThrow());
            return inventory.getContainerSize() == 200 && java.util.stream.IntStream.range(0, 200)
                    .allMatch(slot -> !inventory.getItem(slot).isEmpty());
        });
        world.getServer().waitFor(server -> {
            BagInventory live = bag(world);
            var inventory = live.upgradeInventory(BackpackTestSupport.upgrade(live, 0));
            return inventory.getContainerSize() == 200 && java.util.stream.IntStream.range(0, 200)
                    .allMatch(slot -> !inventory.getItem(slot).isEmpty());
        });

        List<Integer> firstPage = visibleJukeboxSlots(context);
        check(!firstPage.isEmpty() && firstPage.getFirst() == 0
                        && java.util.stream.IntStream.range(0, firstPage.size()).allMatch(index -> firstPage.get(index) == index),
                "The focused client begins on one contiguous first page of the 200-slot jukebox");
        clickButton(context, jukeboxPageButton(context, "Previous slots "));
        List<Integer> lastPage = visibleJukeboxSlots(context);
        check(!lastPage.isEmpty() && lastPage.getLast() == 199
                        && lastPage.getFirst() == Math.floorDiv(199, firstPage.size()) * firstPage.size(),
                "Previous from page one wraps to the partial page containing physical record slot 199");
        int finalSlot = context.computeOnClient(client -> ((BackpackScreen) client.gui.screen()).getMenu().auxiliaryStart() + 199);
        clickSlot(context, finalSlot);
        world.getServer().waitFor(server -> player(world).containerMenu.getCarried().is(Items.MUSIC_DISC_WAIT)
                && player(world).containerMenu.getSlot(finalSlot).getItem().isEmpty());
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> client.player.containerMenu.getCarried().is(Items.MUSIC_DISC_WAIT)
                && client.player.containerMenu.getSlot(finalSlot).getItem().isEmpty());
        separateJukeboxClicks(context);
        clickSlot(context, finalSlot);
        world.getServer().waitFor(server -> player(world).containerMenu.getCarried().isEmpty()
                && player(world).containerMenu.getSlot(finalSlot).getItem().is(Items.MUSIC_DISC_WAIT));
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> client.player.containerMenu.getCarried().isEmpty()
                && client.player.containerMenu.getSlot(finalSlot).getItem().is(Items.MUSIC_DISC_WAIT));
        clickButton(context, jukeboxPageButton(context, "Next slots "));
        check(visibleJukeboxSlots(context).equals(firstPage), "Next from the last jukebox page wraps back to the exact first page");
        navigateToJukeboxSlot(context, 99);
        check(visibleJukeboxSlots(context).contains(99), "Forward page controls expose the middle physical record slot 99");
        context.takeScreenshot("jukebox-pages-200-middle");

        world.getServer().runOnServer(server -> {
            BagInventory bag = bag(world);
            var records = bag.upgradeInventory(BackpackTestSupport.upgrade(bag, 0));
            records.clearContent();
            records.setItem(0, new ItemStack(Items.MUSIC_DISC_13));
            records.setItem(99, new ItemStack(Items.MUSIC_DISC_BLOCKS));
            records.setItem(199, new ItemStack(Items.MUSIC_DISC_WAIT));
            player(world).containerMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> {
            var menu = ((BackpackScreen) client.gui.screen()).getMenu();
            var records = menu.bag().upgradeInventory(menu.selected().orElseThrow());
            return java.util.stream.IntStream.range(0, 200).filter(slot -> !records.getItem(slot).isEmpty()).boxed().toList()
                    .equals(List.of(0, 99, 199));
        });

        navigateToJukeboxSlot(context, 0);
        try (var audio = new ClientAudioProbe(context)) {
            clickButton(context, "Play");
            awaitJukeboxSlot(world, 0);
            audio.awaitActive(1);
            context.takeScreenshot("jukebox-pages-playing-0");

            clickButton(context, "Next");
            awaitJukeboxSlot(world, 99);
            audio.awaitActive(1);
            navigateToJukeboxSlot(context, 99);
            context.takeScreenshot("jukebox-pages-playing-99");

            clickButton(context, "Next");
            awaitJukeboxSlot(world, 199);
            audio.awaitActive(1);
            navigateToJukeboxSlot(context, 199);
            context.takeScreenshot("jukebox-pages-playing-199");

            clickButton(context, "Stop");
            world.getServer().waitFor(server -> !bag(world).settings(BackpackTestSupport.upgrade(bag(world), 0))
                    .getBooleanOr("playing", false));
            audio.awaitActive(0);
        }
    }

    private static void awaitJukeboxSlot(TestSingleplayerContext world, int slot) {
        world.getServer().waitFor(server -> {
            BagInventory bag = bag(world);
            var state = bag.settings(BackpackTestSupport.upgrade(bag, 0));
            return state.getBooleanOr("playing", false) && state.getIntOr("active_slot", -1) == slot;
        });
    }

    private static List<Integer> visibleJukeboxSlots(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            var menu = ((BackpackScreen) client.gui.screen()).getMenu();
            return java.util.stream.IntStream.range(0, menu.auxiliaryCount())
                    .filter(index -> menu.getSlot(menu.auxiliaryStart() + index).isActive()).boxed().toList();
        });
    }

    private static String jukeboxPageButton(ClientGameTestContext context, String prefix) {
        return context.computeOnClient(client -> client.gui.screen().children().stream()
                .filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                .filter(widget -> widget.visible && widget.active && widget.getMessage().getString().startsWith(prefix))
                .map(widget -> widget.getMessage().getString()).findFirst().orElseThrow());
    }

    private static void navigateToJukeboxSlot(ClientGameTestContext context, int physicalSlot) {
        var pages = new java.util.HashSet<String>();
        while (!visibleJukeboxSlots(context).contains(physicalSlot)) {
            String next = jukeboxPageButton(context, "Next slots ");
            check(pages.add(next) && pages.size() <= 200,
                    "Jukebox paging must reach physical slot " + physicalSlot + " without cycling: " + pages);
            clickButton(context, next);
        }
    }

    private static void separateJukeboxClicks(ClientGameTestContext context) {
        long before = context.computeOnClient(client -> net.minecraft.util.Util.getMillis());
        try {
            Thread.sleep(net.minecraft.client.MouseHandler.DOUBLE_CLICK_THRESHOLD_MS + 1);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while separating two physical jukebox-slot clicks", failure);
        }
        check(context.computeOnClient(client -> net.minecraft.util.Util.getMillis()) - before
                        >= net.minecraft.client.MouseHandler.DOUBLE_CLICK_THRESHOLD_MS,
                "The high-slot return click occurs outside Minecraft's native double-click window");
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

    private void wornAppearance(ClientGameTestContext context, TestSingleplayerContext world) {
        check(context.computeOnClient(client -> client.gui.screen() == null && client.getCameraEntity() == client.player
                && client.player.containerMenu.getCarried().isEmpty()), "Worn captures start from the real local player and a clear cursor");
        var camera = context.computeOnClient(client -> client.options.getCameraType());
        boolean hudHidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        var before = world.getServer().computeOnServer(server -> {
            var wearer = player(world);
            return new WornFixture(BackpackEquipment.get(wearer).copy(), wearer.getItemBySlot(EquipmentSlot.CHEST).copy(),
                    wearer.getItemBySlot(EquipmentSlot.OFFHAND).copy(), wearer.getInventory().getItem(8).copy(),
                    wearer.getInventory().getSelectedSlot(), wearer.position(), wearer.getYRot(), wearer.getXRot());
        });
        var captures = new ArrayList<WornCapture>();
        boolean passed = false;
        try {
            world.getServer().runOnServer(server -> {
                var wearer = player(world);
                var pack = BackpackTestSupport.bag(BackpackTier.GOLD);
                pack.setItem(0, new ItemStack(Items.DIAMOND, 17));
                pack.updateSettings(settings -> settings.putInt("display_slot", 0));
                BackpackEquipment.set(wearer, pack.stack());
                wearer.getInventory().setItem(8, ItemStack.EMPTY);
                wearer.getInventory().setSelectedSlot(8);
                wearer.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                wearer.setDeltaMovement(Vec3.ZERO);
                wearer.inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            context.getInput().pressKey(GLFW.GLFW_KEY_9);
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.getInput().lookAt(0F, 10F);
            hideCaptureHud(context, true);
            context.waitTicks(12);
            UUID wearer = context.computeOnClient(client -> client.player.getUUID());
            try (var probe = new WornFrameProbe(context, wearer)) {
                for (int variant = 0; variant < 3; variant++) {
                    boolean armored = variant > 0;
                    boolean dyed = variant == 2;
                    int body = dyed ? 0xb8292f : BackpackVisualState.DEFAULT_BODY_COLOR;
                    int trim = dyed ? 0xc9985f : BackpackVisualState.DEFAULT_TRIM_COLOR;
                    world.getServer().runOnServer(server -> {
                        var actor = player(world);
                        actor.setItemSlot(EquipmentSlot.CHEST, armored ? new ItemStack(Items.DIAMOND_CHESTPLATE) : ItemStack.EMPTY);
                        var pack = BackpackEquipment.inventory(actor).orElseThrow();
                        if (dyed) pack.dye(body, trim);
                        check(BackpackEquipment.setFromInventory(actor, pack), "The real equipped fixture publishes its canonical appearance");
                        actor.inventoryMenu.broadcastChanges();
                    });
                    world.getConnection().waitForClientboundPackets();
                    context.waitFor(client -> wornAppearanceMatches(client.player, armored, body, trim)
                            && client.player.getMainHandItem().isEmpty() && client.player.getOffhandItem().isEmpty());
                    context.waitTicks(3);
                    String name = "worn-rear-" + (dyed ? "dyed" : "default") + (armored ? "-armor" : "-no-armor");
                    captureWorn(context, probe, captures, name, armored, body, trim, false);
                }
                context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
                try {
                    context.waitFor(client -> client.player.isCrouching());
                    context.waitTicks(4);
                    captureWorn(context, probe, captures, "worn-rear-dyed-armor-crouching", true, 0xb8292f, 0xc9985f, true);
                } finally { context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT); }
            }
            check(world.getServer().computeOnServer(server -> {
                var pack = BackpackEquipment.inventory(player(world)).orElseThrow();
                return pack.getItem(0).is(Items.DIAMOND) && pack.getItem(0).getCount() == 17;
            }), "Worn rendering, dye, armor and crouch changes preserve the real display item's full stored count");
            evidence.add("Worn appearance: real local player rear views with empty hands, default/dual-dyed leather, with/without diamond chest armor, and native crouch; native extracted avatar snapshots completed through END_MAIN and selected-item display. Fit and clipping still require visual review.");
            passed = true;
        } finally {
            context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
            world.getServer().runOnServer(server -> {
                var wearer = player(world);
                BackpackEquipment.set(wearer, before.backpack());
                wearer.setItemSlot(EquipmentSlot.CHEST, before.chest());
                wearer.setItemSlot(EquipmentSlot.OFFHAND, before.offhand());
                wearer.getInventory().setItem(8, before.hotbar());
                wearer.getInventory().setSelectedSlot(before.selected());
                wearer.teleportTo(before.position().x, before.position().y, before.position().z);
                wearer.setDeltaMovement(Vec3.ZERO);
                wearer.inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            context.getInput().pressKey(GLFW.GLFW_KEY_1 + before.selected());
            context.getInput().lookAt(before.yaw(), before.pitch());
            context.runOnClient(client -> client.options.setCameraType(camera));
            hideCaptureHud(context, hudHidden);
            check(world.getServer().computeOnServer(server -> ItemStack.matches(BackpackEquipment.get(player(world)), before.backpack())
                    && ItemStack.matches(player(world).getItemBySlot(EquipmentSlot.CHEST), before.chest())
                    && ItemStack.matches(player(world).getItemBySlot(EquipmentSlot.OFFHAND), before.offhand())
                    && ItemStack.matches(player(world).getInventory().getItem(8), before.hotbar())),
                    "Worn appearance fixtures restore equipment, chest armor and both hand slots");
            var report = new com.google.gson.JsonObject();
            report.addProperty("state_assertions_passed", passed);
            report.addProperty("visual_review_required", true);
            report.addProperty("pid", ProcessHandle.current().pid());
            report.addProperty("fixture", "Server-provided equipment and chest armor; real local player, normal third-person option, F1 and crouch input. No renderer state is assigned.");
            report.add("captures", new com.google.gson.Gson().toJsonTree(captures));
            try {
                Files.createDirectories(ClientAcceptanceFiles.ROOT);
                Files.writeString(ClientAcceptanceFiles.ROOT.resolve("worn-appearance.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (java.io.IOException failure) { throw new AssertionError("Could not write worn appearance evidence", failure); }
        }
    }

    private void captureWorn(ClientGameTestContext context, WornFrameProbe probe, List<WornCapture> captures, String name,
                             boolean armored, int body, int trim, boolean crouching) {
        long before = probe.sequence();
        Path image = context.takeScreenshot(name);
        WornFrame frame = probe.after(before);
        frame.require(armored, body, trim, crouching);
        check(frame.cameraFacingDot() < -.9, "The captured real camera is behind the rendered torso: " + frame);
        check(context.computeOnClient(client -> client.gui.hud.isHidden() && client.player.getMainHandItem().isEmpty()
                && client.player.getOffhandItem().isEmpty()), "Worn captures hide HUD/toasts through F1 and keep both hands empty");
        captures.add(new WornCapture(name, image.toAbsolutePath().toString(), frame));
        evidence.add("Screenshot: " + image);
    }

    static void hideCaptureHud(ClientGameTestContext context, boolean hidden) {
        if (context.computeOnClient(client -> client.gui.hud.isHidden()) != hidden) context.getInput().pressKey(GLFW.GLFW_KEY_F1);
        context.waitFor(client -> client.gui.hud.isHidden() == hidden);
    }

    private static boolean wornAppearanceMatches(net.minecraft.world.entity.player.Player player, boolean armored, int body, int trim) {
        ItemStack visual = BackpackEquipment.visual(player);
        return BackpackRegistry.tier(visual).orElse(null) == BackpackTier.GOLD
                && (BackpackVisualState.color(visual, 0) & 0xffffff) == body
                && (BackpackVisualState.color(visual, 1) & 0xffffff) == trim
                && (armored ? player.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE) : player.getItemBySlot(EquipmentSlot.CHEST).isEmpty())
                && BackpackDisplay.from(visual).filter(display -> display.icon().is(Items.DIAMOND) && display.icon().getCount() == 1).isPresent();
    }

    private record WornFixture(ItemStack backpack, ItemStack chest, ItemStack offhand, ItemStack hotbar, int selected,
                               Vec3 position, float yaw, float pitch) {}
    private record WornCapture(String name, String path, WornFrame frame) {}

    /** Read-only observation of completed native world frames; never creates or submits a renderer state. */
    static final class WornFrameProbe implements AutoCloseable {
        private static volatile WornFrameProbe active;
        private static boolean registered;
        private final ClientGameTestContext context;
        private final int entityId;
        private final RenderStateDataKey<BackpackVisualState> key;
        private volatile WornFrame latest;
        private volatile PendingFrame pending;
        private volatile long extractedFrames;
        private volatile long completedFrames;
        private volatile int lastEntityCount;
        private record PendingFrame(LevelRenderState state, WornFrame frame) {}

        @SuppressWarnings("unchecked")
        WornFrameProbe(ClientGameTestContext context, UUID playerId) {
            this.context = context;
            entityId = context.computeOnClient(client -> client.level.getPlayerByUUID(playerId).getId());
            try {
                // Read the production key by identity; do not expose it or replace the captured value.
                var field = BackpackRendering.class.getDeclaredField("WORN");
                field.setAccessible(true);
                key = (RenderStateDataKey<BackpackVisualState>) field.get(null);
            } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot observe the production worn snapshot", failure); }
            context.runOnClient(client -> {
                check(active == null, "Worn render observations must not overlap");
                if (!registered) {
                    LevelExtractionEvents.END_EXTRACTION.register(frame -> { var observer = active; if (observer != null) observer.observeExtraction(frame); });
                    LevelRenderEvents.END_MAIN.register(frame -> { var observer = active; if (observer != null) observer.completeFrame(frame); });
                    registered = true;
                }
                active = this;
            });
        }
        private void observeExtraction(LevelExtractionContext context) {
            // 26.2 submitFeatures clears entityRenderStates before any main-pass render event.
            // Copy only observed values here; publish nothing until this same native frame finishes.
            pending = null;
            long sequence = ++extractedFrames;
            var state = context.levelState();
            lastEntityCount = state.entityRenderStates.size();
            for (var entity : state.entityRenderStates) if (entity instanceof AvatarRenderState avatar && avatar.id == entityId) {
                var visual = ((FabricRenderState) avatar).getDataOrDefault(key, BackpackVisualState.EMPTY);
                Vec3 camera = state.cameraRenderState.pos;
                double dx = camera.x - avatar.x, dz = camera.z - avatar.z;
                double distance = Math.sqrt(dx * dx + dz * dz);
                double yaw = Math.toRadians(avatar.bodyRot);
                double facing = distance == 0 ? Double.NaN : (dx * -Math.sin(yaw) + dz * Math.cos(yaw)) / distance;
                pending = new PendingFrame(state, new WornFrame(sequence, state.gameTime, entityId,
                        visual.present(), visual.tier().id(), visual.bodyColor() & 0xffffff, visual.trimColor() & 0xffffff,
                        avatar.chestEquipment.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(avatar.chestEquipment.getItem()).toString(),
                        avatar.isCrouching, avatar.isSpectator, avatar.isInvisible, facing, avatar.bodyRot,
                        avatar.x, avatar.y, avatar.z, camera.x, camera.y, camera.z));
                break;
            }
        }
        private void completeFrame(LevelRenderContext context) {
            completedFrames++;
            PendingFrame candidate = pending;
            pending = null;
            if (candidate != null && candidate.state() == context.levelState()
                    && candidate.frame().gameTime() == context.levelState().gameTime) latest = candidate.frame();
        }
        long sequence() { WornFrame frame = latest; return frame == null ? 0 : frame.sequence(); }
        WornFrame after(long before) {
            WornFrame frame = latest;
            check(frame != null && frame.sequence() > before, "The screenshot must complete a fresh native frame of the actual player " + entityId
                    + "; before=" + before + ", latest=" + frame + ", extractions=" + extractedFrames
                    + ", completedMainPasses=" + completedFrames + ", lastEntityCount=" + lastEntityCount);
            return frame;
        }
        @Override public void close() { context.runOnClient(client -> { if (active == this) active = null; }); }
    }

    record WornFrame(long sequence, long gameTime, int entityId, boolean backpackPresent, String tier, int body, int trim,
                     String chest, boolean crouching, boolean spectator, boolean invisible, double cameraFacingDot,
                     float bodyYaw, double x, double y, double z, double cameraX, double cameraY, double cameraZ) {
        void require(boolean armored, int expectedBody, int expectedTrim, boolean expectedCrouch) {
            check(backpackPresent && tier.equals(BackpackTier.GOLD.id()) && body == expectedBody && trim == expectedTrim
                            && chest.equals(armored ? "minecraft:diamond_chestplate" : "") && crouching == expectedCrouch
                            && !spectator && !invisible && Double.isFinite(cameraFacingDot),
                    "The completed native avatar frame must carry the expected backpack, armor and pose: " + this
                            + "; expected tier=" + BackpackTier.GOLD.id() + ", body=" + expectedBody + ", trim=" + expectedTrim
                            + ", chest=" + (armored ? "minecraft:diamond_chestplate" : "") + ", crouching=" + expectedCrouch);
        }
    }

    private void fillTwelveRecordSlots(ClientGameTestContext context, TestSingleplayerContext world) {
        selectUpgrade(context, 0);
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
        clickResource(context, 2);
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
        if (context.computeOnClient(client -> client.gui.screen() instanceof BackpackScreen
                && client.gui.screen().children().stream().filter(EditBox.class::isInstance)
                .map(EditBox.class::cast).noneMatch(box -> box.visible && box.active))) {
            clickButton(context, "Search");
        }
        context.waitFor(client -> client.gui.screen().children().stream().filter(EditBox.class::isInstance)
                .map(EditBox.class::cast).anyMatch(box -> box.visible && box.active));
        double[] position = context.computeOnClient(client -> client.gui.screen().children().stream().filter(EditBox.class::isInstance)
                .map(EditBox.class::cast).filter(box -> box.visible && box.active)
                .map(box -> new double[]{box.getX() + 8, box.getY() + 8}).findFirst().orElseThrow());
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

    static void selectUpgrade(ClientGameTestContext context, int upgradeSlot) {
        check(upgradeSlot >= 0, "An upgrade tab needs a nonnegative physical slot");
        context.waitFor(client -> client.gui.screen() instanceof BackpackScreen screen
                && screen.getMenu().bag().installedUpgrades().stream().anyMatch(upgrade -> upgrade.slot() == upgradeSlot));
        String label = context.computeOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var upgrade = screen.getMenu().bag().installedUpgrades().stream()
                    .filter(installed -> installed.slot() == upgradeSlot).findFirst().orElseThrow();
            return "Upgrade " + (upgradeSlot + 1) + ": " + upgrade.stack().getHoverName().getString();
        });
        clickButton(context, label);
        context.waitFor(client -> client.gui.screen() instanceof BackpackScreen screen
                && screen.getMenu().selectedSlot() == upgradeSlot);
        context.waitTicks(2);
    }

    static void clickGhost(ClientGameTestContext context, int physicalGhostIndex) {
        clickBackpackRegion(context, screen -> screen.ghostBounds(physicalGhostIndex)
                .orElseThrow(() -> new AssertionError("Ghost filter " + physicalGhostIndex + " is not visible")));
    }

    static void clickResource(ClientGameTestContext context, int upgradeSlot) {
        clickBackpackRegion(context, screen -> screen.resourceBounds(upgradeSlot)
                .orElseThrow(() -> new AssertionError("Resource upgrade " + upgradeSlot + " has no visible hit target")));
    }

    private static void clickBackpackRegion(ClientGameTestContext context,
            java.util.function.Function<BackpackScreen, net.minecraft.client.gui.navigation.ScreenRectangle> region) {
        double[] position = context.computeOnClient(client -> {
            var screen = (BackpackScreen) client.gui.screen();
            var bounds = region.apply(screen);
            check(bounds.left() >= 0 && bounds.top() >= 0 && bounds.right() <= screen.width && bounds.bottom() <= screen.height,
                    "The actual backpack hit target must fit the viewport: " + bounds);
            return new double[]{bounds.left() + bounds.width() / 2.0, bounds.top() + bounds.height() / 2.0};
        });
        clickAt(context, position[0], position[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
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
            check(slot.isActive(), "The requested physical slot must be visible before a real mouse click: " + slotIndex);
            check(origin.fabricatedBackpacks$left() + slot.x >= 0 && origin.fabricatedBackpacks$top() + slot.y >= 0
                            && origin.fabricatedBackpacks$left() + slot.x + 16 <= screen.width
                            && origin.fabricatedBackpacks$top() + slot.y + 16 <= screen.height,
                    "The requested physical slot must fit the actual viewport: " + slotIndex);
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
