package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterMode;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMode;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.client.automation.ConduitScreen;
import com.kadamitas.fabricatedbackpacks.client.browser.RegistryPickerScreen;
import com.kadamitas.fabricatedbackpacks.client.mixin.ContainerScreenAccess;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.*;

/** Real interface clicks and searches configure a live backpack-to-backpack three-resource link. */
final class ConduitFilterClientAcceptance {
    static final BlockPos PIPE = new BlockPos(35, 80, 12), SOURCE = PIPE.north(), DESTINATION = PIPE.south();
    private static final FluidVariant WATER = FluidVariant.of(Fluids.WATER), LAVA = FluidVariant.of(Fluids.LAVA);
    private static final long INITIAL_WATER = 162_017, INITIAL_LAVA = 81_009, BLOCKED_WATER = 12_345;
    private ConduitFilterClientAcceptance() {}

    static List<String> run(ClientGameTestContext context, TestSingleplayerContext world) {
        prepare(context, world);
        var inventory = world.getServer().computeOnServer(server -> inventory(world));
        open(context, world);
        var nativeMenu = context.computeOnClient(client -> client.player.containerMenu);
        selectPanel(context, ConduitKind.ITEM);
        setMode(context, ConduitKind.ITEM, ConduitFilterMode.ALLOW);
        pick(context, ConduitKind.ITEM, 0, "@minecraft \"cobblestone\" -mossy -infested", "Cobblestone");
        // The searchable registry also includes this mod, and ghost clearing consumes nothing.
        pick(context, ConduitKind.ITEM, 8, "@fabricated_backpacks \"steam engine\"", "Steam Engine");
        var last = context.computeOnClient(client -> ((ConduitScreen) client.gui.screen()).filterTargets().get(8).bounds());
        clickAt(context, last.left() + 8, last.top() + 8, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.waitFor(client -> ((ConduitScreen) client.gui.screen()).getMenu().filter(ConduitKind.ITEM).entry(8).isEmpty());
        check(context.computeOnClient(client -> client.player.containerMenu == nativeMenu), "Searching and returning preserves the exact live conduit menu");
        checkWidgets(context);
        context.takeScreenshot("automation-backpack-item-whitelist");
        checkTitleTooltip(context);
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            checkWidgets(context);
            context.takeScreenshot("automation-backpack-filter-gui-three");
            checkTitleTooltip(context);
        } finally { context.runOnClient(client -> { client.options.guiScale().set(scale); client.resizeGui(); }); }
        selectPanel(context, ConduitKind.FLUID);
        setMode(context, ConduitKind.FLUID, ConduitFilterMode.ALLOW);
        pick(context, ConduitKind.FLUID, 0, "@minecraft water", "Water");
        context.takeScreenshot("automation-backpack-fluid-whitelist");
        close(context);
        world.getServer().runOnServer(server -> seed(server.overworld()));
        world.getServer().waitFor(server -> {
            var level = server.overworld();
            return count(level, DESTINATION, Items.COBBLESTONE) == 24 && fluid(level, DESTINATION, WATER) == INITIAL_WATER
                    && ResourceRuntime.batteryStored(bag(level, DESTINATION).inventory(), 2) == 1_000;
        }, 200);
        world.getServer().runOnServer(server -> {
            var level = server.overworld();
            check(count(level, SOURCE, Items.IRON_INGOT) == 16 && count(level, DESTINATION, Items.IRON_INGOT) == 0,
                    "The item whitelist transfers cobblestone but keeps all sixteen iron ingots at source");
            check(fluid(level, SOURCE, LAVA) == INITIAL_LAVA && fluid(level, DESTINATION, LAVA) == 0,
                    "The fluid whitelist transfers water but no lava droplet");
            totals(level, 24, INITIAL_WATER);
        });
        open(context, world);
        selectPanel(context, ConduitKind.ITEM);
        setMode(context, ConduitKind.ITEM, ConduitFilterMode.BLOCK);
        context.takeScreenshot("automation-backpack-item-blacklist");
        selectPanel(context, ConduitKind.FLUID);
        setMode(context, ConduitKind.FLUID, ConduitFilterMode.BLOCK);
        context.takeScreenshot("automation-backpack-fluid-blacklist");
        close(context);
        world.getServer().runOnServer(server -> {
            bag(server.overworld(), SOURCE).inventory().setItem(1, new ItemStack(Items.COBBLESTONE, 7));
            fill(server.overworld(), 1, WATER, BLOCKED_WATER);
        });
        world.getServer().waitFor(server -> count(server.overworld(), DESTINATION, Items.IRON_INGOT) == 16
                && fluid(server.overworld(), DESTINATION, LAVA) == INITIAL_LAVA, 200);
        context.waitTicks(12);
        world.getServer().runOnServer(server -> {
            var level = server.overworld();
            check(count(level, SOURCE, Items.COBBLESTONE) == 7 && fluid(level, SOURCE, WATER) == BLOCKED_WATER,
                    "Live blacklist controls keep new cobblestone and water at source while admitting iron and lava");
            totals(level, 31, INITIAL_WATER + BLOCKED_WATER);
            check(IntStream.range(0, inventory.size()).allMatch(slot -> ItemStack.matches(inventory.get(slot), player(world).getInventory().getItem(slot)))
                            && player(world).containerMenu.getCarried().isEmpty(),
                    "All filter searches, picks, clears and mode changes leave every physical player stack and cursor untouched");
            for (Direction face : Direction.values()) if (face != Direction.NORTH)
                for (ConduitKind kind : List.of(ConduitKind.ITEM, ConduitKind.FLUID))
                    check(pipe(level).filter(kind, face).mode() == ConduitFilterMode.OFF && pipe(level).filter(kind, face).entries().isEmpty(),
                            "The actual filter UI edits only its physically opened face");
        });
        AutomationClientAcceptance.move(context, world, new Vec3(38, 80, 14.8));
        context.getInput().lookAt(PIPE);
        context.waitTicks(3);
        context.takeScreenshot("automation-backpack-three-resource-link");
        return List.of("Backpack-to-backpack: actual native interface searches set item/fluid whitelists; cobblestone and exact water droplets pass while iron/lava remain. All1,000 FE reaches the input-only receiving bag.",
                "Live blacklist: actual mode buttons reverse permission; named iron/lava move, fresh cobblestone/water remain, both bag totals conserve every resource and item component.",
                "Filter UI: native and modded registry search, exact same live menu after picker return, sparse ghost clear, unchanged player inventory/cursor, face isolation and GUI scales2/3.");
    }

    static void prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (int x = 31; x <= 40; x++) for (int z = 8; z <= 17; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState());
                for (int y = 80; y < 85; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
            for (BlockPos pos : List.of(SOURCE, DESTINATION)) {
                level.setBlockAndUpdate(pos, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
                var inventory = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.TANK, UpgradeKind.BATTERY);
                if (pos.equals(DESTINATION)) inventory.updateSettings(BackpackTestSupport.upgrade(inventory, 2), tag -> tag.putBoolean("external_output", false));
                bag(level, pos).setStack(inventory.stack());
            }
            level.setBlockAndUpdate(PIPE, AutomationRegistry.CONDUIT_BUNDLE.defaultBlockState());
            for (ConduitKind kind : ConduitKind.values()) {
                check(pipe(level).install(kind), "Fixture installs each type once");
                for (Direction face : Direction.values()) pipe(level).setMode(kind, face,
                        face == Direction.NORTH ? ConduitMode.EXTRACT : face == Direction.SOUTH ? ConduitMode.INSERT : ConduitMode.DISABLED);
            }
            pipe(level).refreshVisual();
            player(world).getInventory().setItem(8, ItemStack.EMPTY);
            player(world).getInventory().setSelectedSlot(8);
            player(world).inventoryMenu.broadcastChanges();
        });
        context.getInput().pressKey(GLFW.GLFW_KEY_9);
        context.waitFor(client -> client.player.getMainHandItem().isEmpty());
    }

    static void open(ClientGameTestContext context, TestSingleplayerContext world) {
        // The item strand is the western/lowest lane; view it from the west when all three plates share a face.
        AutomationClientAcceptance.move(context, world, new Vec3(33.2, 80, 11.9));
        AutomationClientAcceptance.interactInterface(context, PIPE, ConduitKind.ITEM, Direction.NORTH);
        context.waitForScreen(ConduitScreen.class);
        context.waitFor(client -> ((ConduitScreen) client.gui.screen()).getMenu().selectedFace() == Direction.NORTH);
    }

    static void selectPanel(ClientGameTestContext context, ConduitKind kind) {
        if (context.computeOnClient(client -> ((ConduitScreen) client.gui.screen()).selectedFilterKind().orElse(null)) != kind)
            clickButton(context, prefix(kind) + "Filters");
        context.waitFor(client -> ((ConduitScreen) client.gui.screen()).selectedFilterKind().orElse(null) == kind);
    }

    static void setMode(ClientGameTestContext context, ConduitKind kind, ConduitFilterMode mode) {
        for (int attempt = 0; attempt < 3; attempt++) {
            var current = context.computeOnClient(client -> ((ConduitScreen) client.gui.screen()).getMenu().filter(kind).mode());
            if (current == mode) return;
            clickButton(context, prefix(kind) + "Filter " + title(current.name()));
            context.waitFor(client -> ((ConduitScreen) client.gui.screen()).getMenu().filter(kind).mode() != current);
        }
        throw new AssertionError("Native filter mode button did not reach " + mode);
    }

    private static void pick(ClientGameTestContext context, ConduitKind kind, int slot, String query, String itemName) {
        clickButton(context, prefix(kind) + "Filter " + (slot + 1) + ": Empty");
        context.waitForScreen(RegistryPickerScreen.class);
        searchBrowser(context, query);
        clickButton(context, itemName);
        context.waitForScreen(ConduitScreen.class);
        context.waitFor(client -> ((ConduitScreen) client.gui.screen()).getMenu().filter(kind).entry(slot).isPresent());
    }

    static void close(ClientGameTestContext context) {
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null);
    }
    private static String prefix(ConduitKind kind) { return title(kind.name()) + " Conduit: North: "; }
    private static String title(String value) { return value.substring(0, 1) + value.substring(1).toLowerCase(java.util.Locale.ROOT); }
    private static void checkWidgets(ClientGameTestContext context) {
        check(context.computeOnClient(client -> client.gui.screen().children().stream().filter(AbstractWidget.class::isInstance)
                        .map(AbstractWidget.class::cast).filter(widget -> widget.visible).allMatch(widget -> widget.getX() >= 0 && widget.getY() >= 0
                                && widget.getRight() <= client.gui.screen().width && widget.getBottom() <= client.gui.screen().height)),
                "The actual attached filter panel and all nine ghost controls fit the viewport");
    }
    private static void checkTitleTooltip(ClientGameTestContext context) {
        double[] pointer = context.computeOnClient(client -> {
            var origin = (ContainerScreenAccess) (Object) client.gui.screen();
            return new double[]{(origin.fabricatedBackpacks$left() + 20) * client.getWindow().getScreenWidth()
                    / (double) client.getWindow().getGuiScaledWidth(),
                    (origin.fabricatedBackpacks$top() + 9) * client.getWindow().getScreenHeight()
                            / (double) client.getWindow().getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(pointer[0], pointer[1]);
        context.waitTicks(2);
        context.takeScreenshot("automation-conduit-help-gui-" + context.computeOnClient(client -> client.getWindow().getGuiScale()));
        context.runOnClient(client -> {
            var screen = (ConduitScreen) client.gui.screen();
            var origin = (ContainerScreenAccess) (Object) screen;
            int mouseX = origin.fabricatedBackpacks$left() + 20, mouseY = origin.fabricatedBackpacks$top() + 9;
            var state = new GuiRenderState();
            var graphics = new GuiGraphicsExtractor(client, state, mouseX, mouseY);
            screen.extractBackground(graphics, mouseX, mouseY, 0);
            screen.extractRenderState(graphics, mouseX, mouseY, 0);
            var before = new ArrayList<GuiTextRenderState>();
            state.forEachText(before::add);
            graphics.extractDeferredElements(mouseX, mouseY, 0);
            var after = new ArrayList<GuiTextRenderState>();
            state.forEachText(after::add);
            var tooltip = after.subList(before.size(), after.size());
            check(tooltip.size() > 1, "The actual long conduit help wraps into multiple tooltip lines");
            check(tooltip.stream().allMatch(line -> line.bounds() != null && line.bounds().left() >= 0
                            && line.bounds().top() >= 0 && line.bounds().right() <= screen.width && line.bounds().bottom() <= screen.height),
                    "Every rendered tooltip glyph and shadow remains inside the actual GUI viewport");
            String rendered = tooltip.stream().map(ConfiguredClientAcceptance::nativeText).map(ConfiguredClientAcceptance::plain)
                    .collect(java.util.stream.Collectors.joining()).replaceAll("\\s", "");
            String full = Component.translatable("automation.fabricated_backpacks.conduit_help").getString().replaceAll("\\s", "");
            check(rendered.equals(full), "Wrapping retains the complete help instead of truncating or hiding it");
        });
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(1);
    }
    static List<ItemStack> inventory(TestSingleplayerContext world) {
        return IntStream.range(0, player(world).getInventory().getContainerSize()).mapToObj(slot -> player(world).getInventory().getItem(slot).copy()).toList();
    }
    private static void seed(ServerLevel level) {
        var source = bag(level, SOURCE).inventory();
        var iron = new ItemStack(Items.IRON_INGOT, 16);
        iron.set(DataComponents.CUSTOM_NAME, Component.literal("Filter test iron"));
        source.setItem(0, iron); source.setItem(1, new ItemStack(Items.COBBLESTONE, 24));
        fill(level, 0, LAVA, INITIAL_LAVA); fill(level, 1, WATER, INITIAL_WATER);
        try (var tx = Transaction.openOuter()) {
            var battery = ResourceRuntime.energyStorage(source);
            for (long inserted = 0; inserted < 1_000; ) {
                long accepted = battery.insert(1_000 - inserted, tx);
                check(accepted > 0 && accepted <= 1_000 - inserted, "The source battery accepts a bounded fixture charge");
                inserted += accepted;
            }
            tx.commit();
        }
    }
    private static void fill(ServerLevel level, int slot, FluidVariant fluid, long amount) {
        try (var tx = Transaction.openOuter()) {
            check(ResourceRuntime.tankStorage(bag(level, SOURCE).inventory(), slot, false).insert(fluid, amount, tx) == amount,
                    "The actual source tank accepts the exact fixture droplet amount");
            tx.commit();
        }
    }
    private static long fluid(ServerLevel level, BlockPos pos, FluidVariant fluid) {
        long total = 0;
        for (var view : ResourceRuntime.fluidStorage(bag(level, pos).inventory())) if (view.getResource().equals(fluid)) total += view.getAmount();
        return total;
    }
    private static int count(ServerLevel level, BlockPos pos, net.minecraft.world.item.Item item) {
        return BackpackTestSupport.count(bag(level, pos).inventory(), item);
    }
    private static void totals(ServerLevel level, int cobble, long water) {
        check(count(level, SOURCE, Items.COBBLESTONE) + count(level, DESTINATION, Items.COBBLESTONE) == cobble, "All cobblestone is conserved");
        check(count(level, SOURCE, Items.IRON_INGOT) + count(level, DESTINATION, Items.IRON_INGOT) == 16, "All iron is conserved");
        check(fluid(level, SOURCE, WATER) + fluid(level, DESTINATION, WATER) == water, "All water droplets are conserved");
        check(fluid(level, SOURCE, LAVA) + fluid(level, DESTINATION, LAVA) == INITIAL_LAVA, "All lava droplets are conserved");
        check(ResourceRuntime.batteryStored(bag(level, SOURCE).inventory(), 2) == 0
                && ResourceRuntime.batteryStored(bag(level, DESTINATION).inventory(), 2) == 1_000, "Energy flows only toward the receiving backpack");
        for (BlockPos pos : List.of(SOURCE, DESTINATION)) for (int slot = 0; slot < bag(level, pos).inventory().getContainerSize(); slot++) {
            var stack = bag(level, pos).inventory().getItem(slot);
            if (stack.is(Items.IRON_INGOT)) check(Component.literal("Filter test iron").equals(stack.get(DataComponents.CUSTOM_NAME)),
                    "Registry identity filtering preserves transferred item components");
        }
    }
    static CompoundTag snapshot(ServerLevel level) {
        var tag = new CompoundTag();
        for (BlockPos pos : List.of(SOURCE, PIPE, DESTINATION)) {
            String key = pos.equals(PIPE) ? "pipe" : pos.equals(SOURCE) ? "source" : "destination";
            tag.put(key, level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess()));
            tag.put(key + "_block", NbtUtils.writeBlockState(level.getBlockState(pos)));
        }
        return tag;
    }
    static void verifyReload(TestSingleplayerContext world, CompoundTag expected) {
        world.getServer().runOnServer(server -> check(snapshot(server.overworld()).equals(expected),
                "World/JVM restart preserves exact bag contents and every conduit ghost, mode and face"));
    }
    static void resumeAfterReload(TestSingleplayerContext world) {
        long[] before = world.getServer().computeOnServer(server -> {
            var level = server.overworld();
            long[] values = {count(level, DESTINATION, Items.GOLD_INGOT), fluid(level, DESTINATION, LAVA)};
            bag(level, SOURCE).inventory().setItem(2, new ItemStack(Items.GOLD_INGOT, 3));
            fill(level, 0, LAVA, 171);
            return values;
        });
        world.getServer().waitFor(server -> count(server.overworld(), DESTINATION, Items.GOLD_INGOT) == before[0] + 3
                && fluid(server.overworld(), DESTINATION, LAVA) == before[1] + 171, 200);
        world.getServer().runOnServer(server -> check(count(server.overworld(), SOURCE, Items.COBBLESTONE) == 7
                        && fluid(server.overworld(), SOURCE, WATER) == BLOCKED_WATER,
                "Reconstructed filtered routing admits fresh resources and still blocks saved cobble/water"));
    }
    static ConduitBundleBlockEntity pipe(ServerLevel level) { return (ConduitBundleBlockEntity) level.getBlockEntity(PIPE); }
    private static BackpackBlockEntity bag(ServerLevel level, BlockPos pos) { return (BackpackBlockEntity) level.getBlockEntity(pos); }
}
