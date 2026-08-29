package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMode;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitGeometry;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlock;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.engine.EngineSideMode;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.client.automation.ConduitScreen;
import com.kadamitas.fabricatedbackpacks.client.automation.SteamEngineScreen;
import com.kadamitas.fabricatedbackpacks.client.automation.SteamEngineSideScreen;
import com.kadamitas.fabricatedbackpacks.client.mixin.ContainerScreenAccess;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import team.reborn.energy.api.EnergyStorage;

import java.util.List;
import java.util.ArrayList;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.*;

/** Natural world ticks and real mouse interactions; world preparation is explicitly a server fixture. */
final class AutomationClientAcceptance {
    static final BlockPos ENGINE = new BlockPos(20, 80, 2);
    static final BlockPos CENTER = new BlockPos(21, 80, 2);
    static final BlockPos ITEMS = CENTER.north();
    static final BlockPos WATER = CENTER.south();
    static final BlockPos SINK = new BlockPos(24, 80, 2);
    private static final List<BlockPos> SAVED = List.of(ENGINE, CENTER, CENTER.east(), CENTER.east(2), ITEMS, WATER, SINK);
    private static final int HAND = 8, FUEL = 26, BUCKET = 27;

    private AutomationClientAcceptance() {}

    static List<String> run(ClientGameTestContext context, TestSingleplayerContext world) {
        check(context.computeOnClient(client -> client.gui.screen() == null), "Automation acceptance starts without an existing screen");
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        var camera = context.computeOnClient(client -> client.options.getCameraType());
        Fixture previous = world.getServer().computeOnServer(server -> new Fixture(player(world).position(),
                player(world).getInventory().getSelectedSlot(), player(world).getInventory().getItem(HAND).copy(),
                player(world).getInventory().getItem(FUEL).copy(), player(world).getInventory().getItem(BUCKET).copy()));
        context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        try {
            world.getServer().runOnServer(server -> {
                var level = server.overworld();
                for (int x = 16; x <= 27; x++) for (int z = -3; z <= 7; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState());
                    for (int y = 80; y <= 84; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
                level.setBlockAndUpdate(ENGINE, AutomationRegistry.STEAM_ENGINE.defaultBlockState().setValue(SteamEngineBlock.FACING, Direction.NORTH));
                player(world).getInventory().setItem(HAND, ItemStack.EMPTY);
                player(world).getInventory().setItem(FUEL, new ItemStack(Items.COAL));
                player(world).getInventory().setItem(BUCKET, new ItemStack(Items.WATER_BUCKET));
                player(world).inventoryMenu.broadcastChanges();
            });
            move(context, world, new Vec3(20.5, 80, -.5));
            checkAutomationTooltips(context);
            context.getInput().pressKey(GLFW.GLFW_KEY_9);
            interact(context, ENGINE);
            context.waitForScreen(SteamEngineScreen.class);
            clickPlayerSlot(context, BUCKET);
            clickSlot(context, SteamEngineBlockEntity.WATER_INPUT);
            clickPlayerSlot(context, FUEL);
            clickSlot(context, SteamEngineBlockEntity.FUEL);
            world.getServer().waitFor(server -> engine(server.overworld()).active() && engine(server.overworld()).snapshot().energy() >= 40);
            context.waitFor(client -> ((SteamEngineScreen) client.gui.screen()).getMenu().energy() >= 40);
            check(world.getServer().computeOnServer(server -> engine(server.overworld()).getItem(SteamEngineBlockEntity.WATER_REMAINDER).is(Items.BUCKET)),
                    "The real water container becomes one empty bucket in the engine output slot");
            for (int scale : new int[]{2, 3}) {
                context.runOnClient(client -> { client.options.guiScale().set(scale); client.resizeGui(); });
                context.waitTicks(3);
                checkWidgetsFit(context);
                checkEngineHeading(context, false);
                context.takeScreenshot("automation-engine-menu-scale-" + scale);
            }
            clickButton(context, "Engine: On");
            world.getServer().waitFor(server -> !engine(server.overworld()).enabled() && !engine(server.overworld()).active());
            var paused = world.getServer().computeOnServer(server -> engine(server.overworld()).snapshot());
            context.waitTicks(8);
            check(world.getServer().computeOnServer(server -> engine(server.overworld()).snapshot()).equals(paused),
                    "An isolated paused engine retains exact fuel work, energy and water");
            close(context);
            world.getServer().runOnServer(server -> renameEngine(server.overworld(), Component.literal(
                    "The workshop's extraordinarily long named brass and iron steam engine")));
            try {
                interact(context, ENGINE);
                context.waitForScreen(SteamEngineScreen.class);
                checkEngineHeading(context, true);
                context.takeScreenshot("automation-engine-long-title-scale-3");
            } finally {
                if (context.computeOnClient(client -> client.gui.screen() != null)) close(context);
                world.getServer().runOnServer(server -> renameEngine(server.overworld(), null));
            }
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });

            // Place and add all three types with real Survival item-use packets, not setBlock fixtures.
            move(context, world, new Vec3(21.5, 80, -.5));
            hand(context, world, AutomationRegistry.ITEM_CONDUIT, 2);
            aim(context, new Vec3(21.5, 79.99, 2.5));
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            world.getServer().waitFor(server -> server.overworld().getBlockEntity(CENTER) instanceof ConduitBundleBlockEntity node && node.installedMask() == 1);
            context.waitFor(client -> client.level.getBlockEntity(CENTER) instanceof ConduitBundleBlockEntity node && node.installedMask() == 1);
            context.waitTicks(6);
            context.takeScreenshot("automation-single-item-conduit");
            move(context, world, new Vec3(24.0, 80, 2.5));
            interact(context, CENTER);
            world.getServer().waitFor(server -> server.overworld().getBlockEntity(CENTER.east()) instanceof ConduitBundleBlockEntity node && node.installedMask() == 1);
            check(world.getServer().computeOnServer(server -> player(world).getMainHandItem().isEmpty()),
                    "Using the same conduit type extends the line into the adjacent cell and consumes one item");
            long beforeBreak = world.getServer().computeOnServer(server -> recoverableConduits(world));
            aim(context, Vec3.atCenterOf(CENTER.east()));
            context.waitFor(client -> client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(CENTER.east()));
            context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            try {
                world.getServer().waitFor(server -> server.overworld().isEmptyBlock(CENTER.east()), 100);
            } finally {
                context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            }
            check(world.getServer().computeOnServer(server -> recoverableConduits(world)) == beforeBreak + 1,
                    "Actual empty-hand mining returns the single conduit as one recoverable item");
            move(context, world, new Vec3(21.5, 80, -.5));
            hand(context, world, AutomationRegistry.FLUID_CONDUIT, 1);
            interact(context, CENTER);
            world.getServer().waitFor(server -> node(server.overworld(), CENTER).installedMask() == 3);
            hand(context, world, AutomationRegistry.ENERGY_CONDUIT, 1);
            interact(context, CENTER);
            world.getServer().waitFor(server -> node(server.overworld(), CENTER).installedMask() == 7);
            check(world.getServer().computeOnServer(server -> player(world).getMainHandItem().isEmpty()),
                    "Survival bundle installation consumes the one newly installed conduit");

            world.getServer().runOnServer(server -> prepareEndpoints(server.overworld()));
            world.getConnection().waitForChunksRender();
            context.waitFor(client -> client.level.getBlockEntity(CENTER) instanceof ConduitBundleBlockEntity node
                    && node.visualState().installedMask() == 7 && node.visualState().connectionBits() != 0);
            move(context, world, new Vec3(23.8, 80, 4.8));
            aim(context, Vec3.atCenterOf(CENTER));
            context.takeScreenshot("automation-three-conduits-shared-block");

            // Only physical interface plates open a conduit menu; the center is not a shortcut.
            interact(context, CENTER);
            check(context.computeOnClient(client -> client.gui.screen() == null), "A bare bundle-center click does not open configuration");
            mineIndividualStrands(context, world);
            move(context, world, new Vec3(23.3, 80, 1.9));
            interactInterface(context, CENTER, ConduitKind.ITEM, Direction.NORTH);
            context.waitForScreen(ConduitScreen.class);
            mode(context, ConduitKind.ITEM, Direction.NORTH, ConduitMode.DISABLED);
            close(context);
            context.waitFor(client -> !((ConduitBundleBlockEntity) client.level.getBlockEntity(CENTER)).visualState()
                    .endpoint(ConduitKind.ITEM, Direction.NORTH));
            hand(context, world, AutomationRegistry.CONDUIT_WRENCH, 1);
            move(context, world, new Vec3(23.3, 80, 1.9));
            aim(context, Vec3.atLowerCornerOf(CENTER).add(5 / 16.0, 5 / 16.0, .12));
            context.waitFor(client -> client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(CENTER)
                    && ConduitGeometry.hitPart(((ConduitBundleBlockEntity) client.level.getBlockEntity(CENTER)).visualState(),
                    hit.getLocation().subtract(Vec3.atLowerCornerOf(CENTER)), hit.getDirection())
                    .filter(part -> part.side() == Direction.NORTH && part.role() != ConduitGeometry.Role.ENDPOINT).isPresent());
            context.takeScreenshot("automation-disabled-interface-wrench-target");
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            world.getServer().waitFor(server -> node(server.overworld(), CENTER).mode(ConduitKind.ITEM, Direction.NORTH) == ConduitMode.EXTRACT);
            check(context.computeOnClient(client -> client.gui.screen() == null), "The wrench restores the disabled tube directly without opening a center UI");
            context.waitFor(client -> ((ConduitBundleBlockEntity) client.level.getBlockEntity(CENTER)).visualState()
                    .endpoint(ConduitKind.ITEM, Direction.NORTH));
            context.takeScreenshot("automation-restored-interface-without-menu");
            hand(context, world, Items.AIR, 0);
            move(context, world, new Vec3(23.3, 80, 1.9));
            interactInterface(context, CENTER, ConduitKind.ITEM, Direction.NORTH);
            context.waitForScreen(ConduitScreen.class);
            mode(context, ConduitKind.ITEM, Direction.NORTH, ConduitMode.EXTRACT);
            close(context);
            move(context, world, new Vec3(23.3, 80, 3.12));
            interactInterface(context, CENTER, ConduitKind.FLUID, Direction.SOUTH);
            context.waitForScreen(ConduitScreen.class);
            mode(context, ConduitKind.FLUID, Direction.SOUTH, ConduitMode.EXTRACT);
            checkWidgetsFit(context);
            context.takeScreenshot("automation-conduit-source-controls");
            close(context);
            hand(context, world, Items.AIR, 0);
            long[] before = world.getServer().computeOnServer(server -> totals(server.overworld()));
            move(context, world, new Vec3(20.5, 80, -.5));
            interact(context, ENGINE);
            context.waitForScreen(SteamEngineScreen.class);
            clickButton(context, "Engine: Off");
            world.getServer().waitFor(server -> engine(server.overworld()).active());
            close(context);
            world.getServer().waitFor(server -> BackpackTestSupport.count(backpack(server.overworld(), SINK).inventory(), Items.IRON_INGOT) == 16
                    && energy(server.overworld(), SINK) > 0 && water(server.overworld(), SINK) > 0, 600);
            context.waitFor(client -> client.level.getBlockState(ENGINE).getValue(SteamEngineBlock.ACTIVE));
            float angle = context.computeOnClient(client -> ((SteamEngineBlockEntity) client.level.getBlockEntity(ENGINE)).crankAngle(1));
            context.waitTicks(3);
            check(context.computeOnClient(client -> ((SteamEngineBlockEntity) client.level.getBlockEntity(ENGINE)).crankAngle(1)) != angle,
                    "The live client ticker advances the productive engine mechanism");
            move(context, world, new Vec3(20.5, 80, -.5));
            aim(context, Vec3.atCenterOf(ENGINE));
            context.takeScreenshot("automation-engine-working-network");

            move(context, world, new Vec3(20.5, 80, -.5));
            hand(context, world, AutomationRegistry.CONDUIT_WRENCH, 1);
            interact(context, ENGINE);
            context.waitForScreen(SteamEngineSideScreen.class);
            clickButton(context, "Face: East");
            context.waitFor(client -> ((SteamEngineSideScreen) client.gui.screen()).getMenu().selectedFace() == Direction.EAST);
            clickButton(context, "Energy: East: Output");
            world.getServer().waitFor(server -> engine(server.overworld()).sideMode(ConduitKind.ENERGY, Direction.EAST) == EngineSideMode.DISABLED);
            long[] stoppedOutput = world.getServer().computeOnServer(server -> new long[]{energy(server.overworld(), SINK), engine(server.overworld()).snapshot().energy()});
            context.waitTicks(8);
            check(world.getServer().computeOnServer(server -> energy(server.overworld(), SINK)) == stoppedOutput[0]
                            && world.getServer().computeOnServer(server -> engine(server.overworld()).snapshot().energy()) > stoppedOutput[1],
                    "Disabling the real engine's east output stops transport while productive work remains stored in the engine");
            checkWidgetsFit(context);
            context.takeScreenshot("automation-engine-east-output-disabled");
            clickButton(context, "Energy: East: Disabled");
            world.getServer().waitFor(server -> energy(server.overworld(), SINK) > stoppedOutput[0], 200);
            clickButton(context, "Face: Up");
            context.waitFor(client -> ((SteamEngineSideScreen) client.gui.screen()).getMenu().selectedFace() == Direction.UP);
            clickButton(context, "Energy: Up: Output");
            world.getServer().waitFor(server -> engine(server.overworld()).sideMode(ConduitKind.ENERGY, Direction.UP) == EngineSideMode.DISABLED);
            close(context);
            hand(context, world, Items.AIR, 0);

            move(context, world, new Vec3(20.5, 80, -.5));
            interact(context, ENGINE);
            context.waitForScreen(SteamEngineScreen.class);
            clickButton(context, "Engine: On");
            world.getServer().waitFor(server -> !engine(server.overworld()).active());
            close(context);
            world.getServer().runOnServer(server -> {
                for (BlockPos pos : List.of(CENTER, CENTER.east(), CENTER.east(2)))
                    for (ConduitKind kind : ConduitKind.values()) for (Direction face : Direction.values())
                        node(server.overworld(), pos).setMode(kind, face, ConduitMode.DISABLED);
            });
            context.waitTicks(3);
            long[] after = world.getServer().computeOnServer(server -> totals(server.overworld()));
            check(after[2] == before[2] && after[2] == 16, "All16 iron ingots survive end-to-end item routing");
            check(after[1] > before[1] && (after[1] - before[1]) % 40 == 0, "The running network generates positive whole40-energy engine quanta");
            check(before[0] - after[0] == ((after[1] - before[1]) / 40) * 81,
                    "Every consumed water droplet is accounted for by actual generated energy; transport loses neither resource");
            context.waitFor(client -> !client.level.getBlockState(ENGINE).getValue(SteamEngineBlock.ACTIVE));
            float stopped = context.computeOnClient(client -> ((SteamEngineBlockEntity) client.level.getBlockEntity(ENGINE)).crankAngle(1));
            context.waitTicks(4);
            check(context.computeOnClient(client -> ((SteamEngineBlockEntity) client.level.getBlockEntity(ENGINE)).crankAngle(1)) == stopped,
                    "A paused engine stops the client mechanism without resetting it");
            return List.of("Native automation: actual Survival item-use installs all3 conduit types into one block, extends a same-type line with exact item consumption, and empty-hand mining returns one recoverable conduit. Actual targeted mining changes a stacked bundle3->2->1 with exactly one matching drop per break, retains the other strands on both server and client, and permits reinstallation.",
                    "Steam engine: mouse fuel/water slots, empty-bucket remainder, native enabled button, natural generation and paused-work retention, scale2/3 screenshots.",
                    "Shared network: actual GUI source modes,16 iron ingots and water moved through bundled lanes; engine energy reached an input-only backpack battery with exact water/energy conservation.",
                    "Wrench: a bare center does not open configuration; actual interface clicks open face-bound controls and a wrench restores a disabled tube directly. Engine output was disabled/re-enabled through the actual machine-side screen while generation continued, with an unused disabled face persisted.",
                    "Renderer: natural ACTIVE state advances the client crank and stops it when paused. Screenshots require visual inspection; no performance comparison is claimed.");
        } finally {
            if (context.computeOnClient(client -> client.gui.screen() != null)) close(context);
            world.getServer().runOnServer(server -> {
                var inventory = player(world).getInventory();
                inventory.setItem(HAND, previous.hand()); inventory.setItem(FUEL, previous.fuel()); inventory.setItem(BUCKET, previous.bucket());
                inventory.setSelectedSlot(previous.selected());
                player(world).teleportTo(previous.position().x, previous.position().y, previous.position().z);
                player(world).inventoryMenu.broadcastChanges();
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_1 + previous.selected());
            context.runOnClient(client -> { client.options.setCameraType(camera); client.options.guiScale().set(previousScale); client.resizeGui(); });
        }
    }

    private static void prepareEndpoints(ServerLevel level) {
        for (BlockPos pos : List.of(CENTER.east(), CENTER.east(2))) {
            if (!(level.getBlockEntity(pos) instanceof ConduitBundleBlockEntity))
                level.setBlockAndUpdate(pos, AutomationRegistry.CONDUIT_BUNDLE.defaultBlockState());
            for (ConduitKind kind : ConduitKind.values())
                if (!node(level, pos).has(kind)) check(node(level, pos).install(kind), "Fixture installs each missing lane once");
        }
        level.setBlockAndUpdate(ITEMS, Blocks.CHEST.defaultBlockState());
        ((Container) level.getBlockEntity(ITEMS)).setItem(0, new ItemStack(Items.IRON_INGOT, 16));
        for (BlockPos pos : List.of(WATER, SINK)) {
            level.setBlockAndUpdate(pos, BackpackRegistry.block(BackpackTier.GOLD).defaultBlockState());
            var bag = pos.equals(WATER) ? BackpackTestSupport.bag(BackpackTier.GOLD, UpgradeKind.TANK)
                    : BackpackTestSupport.bag(BackpackTier.GOLD, UpgradeKind.TANK, UpgradeKind.BATTERY);
            if (pos.equals(SINK)) bag.updateSettings(BackpackTestSupport.upgrade(bag, 1), tag -> tag.putBoolean("external_output", false));
            backpack(level, pos).setStack(bag.stack());
        }
        node(level, CENTER).setMode(ConduitKind.ITEM, Direction.SOUTH, ConduitMode.DISABLED);
        try (Transaction transaction = Transaction.openOuter()) {
            long accepted = ResourceRuntime.tankStorage(backpack(level, WATER).inventory(), 0, false)
                    .insert(FluidVariant.of(Fluids.WATER), 162_000, transaction);
            check(accepted == 162_000, "The water-source fixture starts with two exact buckets");
            transaction.commit();
        }
    }
    private static long recoverableConduits(TestSingleplayerContext world) {
        return recoverableConduits(world, ConduitKind.ITEM);
    }
    private static long recoverableConduits(TestSingleplayerContext world, ConduitKind kind) {
        return BackpackTestSupport.count(player(world).getInventory(), AutomationRegistry.conduit(kind))
                + player(world).level().getEntitiesOfClass(ItemEntity.class, new AABB(15, 78, -4, 29, 88, 9),
                        entity -> entity.getItem().is(AutomationRegistry.conduit(kind))).stream().mapToLong(entity -> entity.getItem().getCount()).sum();
    }

    private static void mineIndividualStrands(ClientGameTestContext context, TestSingleplayerContext world) {
        BlockPos position = CENTER.east();
        move(context, world, new Vec3(22.5, 80, 4.6));
        hand(context, world, Items.AIR, 0);
        var originalServer = world.getServer().computeOnServer(server -> node(server.overworld(), position));
        var originalClient = context.computeOnClient(client -> client.level.getBlockEntity(position));
        for (ConduitKind mined : List.of(ConduitKind.FLUID, ConduitKind.ITEM)) {
            int expectedMask = world.getServer().computeOnServer(server -> originalServer.installedMask() & ~mined.mask());
            long[] beforeDrops = world.getServer().computeOnServer(server -> java.util.Arrays.stream(ConduitKind.values())
                    .mapToLong(kind -> recoverableConduits(world, kind)).toArray());
            aimAtConduit(context, position, mined);
            context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            try {
                world.getServer().waitFor(server -> server.overworld().getBlockEntity(position) == originalServer
                        && originalServer.installedMask() == expectedMask, 100);
            } finally {
                context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            }
            context.waitFor(client -> client.level.getBlockEntity(position) instanceof ConduitBundleBlockEntity bundle
                    && bundle.installedMask() == expectedMask);
            context.waitTicks(8);
            check(context.computeOnClient(client -> client.level.getBlockEntity(position) == originalClient),
                    "Actual partial mining keeps the client's live bundle instead of deleting its remaining strands");
            for (ConduitKind kind : ConduitKind.values()) {
                long expected = beforeDrops[kind.ordinal()] + (kind == mined ? 1 : 0);
                long actual = world.getServer().computeOnServer(server -> recoverableConduits(world, kind));
                check(actual == expected, "Mining " + mined + " returns exactly its own one drop and no untouched "
                        + kind + " strand: expected=" + expected + ", actual=" + actual
                        + (actual == expected ? "" : world.getServer().computeOnServer(server -> player(world).level()
                        .getEntitiesOfClass(ItemEntity.class, new AABB(15, 78, -4, 29, 88, 9)).stream()
                        .map(entity -> entity.getItem() + "@" + entity.position()).toList().toString())));
            }
            context.takeScreenshot("automation-mined-" + mined.name().toLowerCase(java.util.Locale.ROOT)
                    + "-remaining-" + Integer.bitCount(expectedMask));
        }
        for (ConduitKind restored : List.of(ConduitKind.FLUID, ConduitKind.ITEM)) {
            hand(context, world, AutomationRegistry.conduit(restored), 1);
            aimAtConduit(context, position, ConduitKind.ENERGY);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            world.getServer().waitFor(server -> originalServer.has(restored));
            context.waitFor(client -> ((ConduitBundleBlockEntity) client.level.getBlockEntity(position)).has(restored));
            check(world.getServer().computeOnServer(server -> player(world).getMainHandItem().isEmpty()),
                    "Reinstalling the mined " + restored + " strand consumes only its actual held item");
        }
        check(world.getServer().computeOnServer(server -> originalServer.installedMask()) == 7,
                "All three lanes can be restored on the same bundle after individual hand mining");
        context.takeScreenshot("automation-mined-strands-reinstalled");
    }

    static void aimAtConduit(ClientGameTestContext context, BlockPos position, ConduitKind kind) {
        Vec3 target = context.computeOnClient(client -> {
            var visual = ((ConduitBundleBlockEntity) client.level.getBlockEntity(position)).visualState();
            Vec3 eye = client.player.getEyePosition();
            for (var part : ConduitGeometry.parts(visual)) {
                if (part.kind() != kind) continue;
                AABB bounds = part.bounds();
                for (double x : new double[]{.5, .2, .8}) for (double y : new double[]{.5, .2, .8})
                    for (double z : new double[]{.5, .2, .8}) {
                        Vec3 point = Vec3.atLowerCornerOf(position).add(bounds.minX + x * bounds.getXsize(),
                                bounds.minY + y * bounds.getYsize(), bounds.minZ + z * bounds.getZsize());
                        var hit = client.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
                        if (hit.getBlockPos().equals(position) && ConduitGeometry.hitKind(visual,
                                hit.getLocation().subtract(Vec3.atLowerCornerOf(position)), hit.getDirection()).orElse(null) == kind) return point;
                    }
            }
            throw new AssertionError("No exposed native mining target for " + kind + " from " + eye);
        });
        aim(context, target);
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(position)
                && ConduitGeometry.hitKind(((ConduitBundleBlockEntity) client.level.getBlockEntity(position)).visualState(),
                hit.getLocation().subtract(Vec3.atLowerCornerOf(position)), hit.getDirection()).orElse(null) == kind);
    }

    private static void mode(ClientGameTestContext context, ConduitKind kind, Direction face, ConduitMode desired) {
        String faceName = title(face.name());
        check(context.computeOnClient(client -> ((ConduitScreen) client.gui.screen()).getMenu().selectedFace()) == face,
                "A conduit menu is bound to the actual interface that was clicked");
        for (int attempt = 0; attempt < 4; attempt++) {
            ConduitMode mode = context.computeOnClient(client -> ((ConduitScreen) client.gui.screen()).getMenu().mode(kind, face));
            if (mode == desired) return;
            clickButton(context, title(kind.name()) + " Conduit: " + faceName + ": " + title(mode.name()));
            context.waitFor(client -> ((ConduitScreen) client.gui.screen()).getMenu().mode(kind, face) != mode);
        }
        throw new AssertionError("The native mode button did not reach " + desired);
    }

    private static String title(String text) { return text.substring(0, 1) + text.substring(1).toLowerCase(java.util.Locale.ROOT); }
    private static void renameEngine(ServerLevel level, Component name) {
        var engine = engine(level);
        ItemStack state = engine.dropStack();
        if (name == null) state.remove(DataComponents.CUSTOM_NAME); else state.set(DataComponents.CUSTOM_NAME, name);
        engine.applyComponentsFromItemStack(state);
        engine.setChanged();
    }
    private static void checkAutomationTooltips(ClientGameTestContext context) {
        context.runOnClient(client -> AutomationRegistry.items().forEach((id, item) -> {
            String key = "tooltip.fabricated_backpacks." + id;
            String expected = Component.translatable(key).getString();
            check(!expected.equals(key), "Automation guidance has a resolved translation: " + id);
            check(item.getDefaultInstance().getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL)
                            .stream().anyMatch(line -> line.getString().equals(expected)),
                    "The actual native item tooltip includes its installation/use guidance: " + id);
        }));
    }
    private static void checkEngineHeading(ClientGameTestContext context, boolean requireClipping) {
        context.runOnClient(client -> {
            var screen = (SteamEngineScreen) client.gui.screen();
            var origin = (ContainerScreenAccess) (Object) screen;
            int left = origin.fabricatedBackpacks$left(), top = origin.fabricatedBackpacks$top();
            var state = new GuiRenderState();
            screen.extractRenderState(new GuiGraphicsExtractor(client, state, -1, -1), -1, -1, 0);
            var runs = new ArrayList<GuiTextRenderState>();
            state.forEachText(runs::add);
            var headings = runs.stream().filter(run -> {
                var point = ConfiguredClientAcceptance.nativeTextOrigin(run);
                return point.x == left + 8 && point.y == top + 6;
            }).toList();
            check(headings.size() == 1, "The engine submits its header exactly once, without double-layered text");
            var heading = headings.getFirst();
            ConfiguredClientAcceptance.checkNoTextShadow(heading, "Steam engine title");
            String rendered = ConfiguredClientAcceptance.plain(ConfiguredClientAcceptance.nativeText(heading));
            check(client.font.width(rendered) <= 160 && heading.bounds() != null
                            && heading.bounds().left() >= left + 8 && heading.bounds().right() <= left + 168,
                    "The actual rendered engine title and glyph bounds stay inside the header");
            if (requireClipping) check(client.font.width(screen.getTitle()) > 160 && rendered.endsWith("..."),
                    "A long custom name exercises ellipsis clipping while retaining its full screen title");
            else check(rendered.equals(screen.getTitle().getString()), "The default engine title stays complete");
        });
    }
    private static void hand(ClientGameTestContext context, TestSingleplayerContext world, Item item, int count) {
        world.getServer().runOnServer(server -> { player(world).getInventory().setItem(HAND, new ItemStack(item, count)); player(world).inventoryMenu.broadcastChanges(); });
        context.waitFor(client -> count == 0 ? client.player.getMainHandItem().isEmpty()
                : client.player.getMainHandItem().is(item) && client.player.getMainHandItem().getCount() == count);
    }
    static void move(ClientGameTestContext context, TestSingleplayerContext world, Vec3 position) {
        world.getServer().runOnServer(server -> { player(world).teleportTo(position.x, position.y, position.z); player(world).setDeltaMovement(Vec3.ZERO); });
        // Small moves between a stub and its plate must await the new teleport too.
        context.waitFor(client -> client.player.position().distanceToSqr(position) < 1.0e-6);
        world.getConnection().waitForChunksRender();
        context.waitTicks(2);
        context.waitFor(client -> client.player.getPose() == Pose.STANDING);
    }
    private static void aim(ClientGameTestContext context, Vec3 point) {
        double[] look = context.computeOnClient(client -> {
            Vec3 delta = point.subtract(client.player.getEyePosition());
            return new double[]{Math.toDegrees(Math.atan2(-delta.x, delta.z)), -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)))};
        });
        context.getInput().lookAt((float) look[0], (float) look[1]);
        context.waitTicks(3);
    }
    private static void interact(ClientGameTestContext context, BlockPos position) {
        aim(context, Vec3.atCenterOf(position));
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(position));
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.waitTicks(3);
    }
    static void interactInterface(ClientGameTestContext context, BlockPos position, ConduitKind kind, Direction face) {
        context.waitFor(client -> client.level.getBlockEntity(position) instanceof ConduitBundleBlockEntity node
                && node.visualState().endpoint(kind, face));
        try {
            Vec3 target = context.computeOnClient(client -> {
                var visual = ((ConduitBundleBlockEntity) client.level.getBlockEntity(position)).visualState();
                AABB plate = ConduitGeometry.parts(visual).stream().filter(part -> part.kind() == kind
                                && part.side() == face && part.role() == ConduitGeometry.Role.ENDPOINT)
                        .findFirst().orElseThrow().bounds();
                Vec3 eye = client.player.getEyePosition();
                // Aim at a visible part of the real plate, which may have its center obscured by another strand.
                for (double x : new double[]{.5, .2, .8}) for (double y : new double[]{.5, .2, .8})
                    for (double z : new double[]{.5, .2, .8}) {
                        Vec3 point = Vec3.atLowerCornerOf(position).add(plate.minX + x * plate.getXsize(),
                                plate.minY + y * plate.getYsize(), plate.minZ + z * plate.getZsize());
                        BlockHitResult hit = client.level.clip(new ClipContext(eye, point,
                                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
                        if (isInterfaceHit(client.level, hit, position, kind, face)) return point;
                    }
                throw new AssertionError("No visible " + kind + " " + face + " plate from " + eye
                        + "; current hit=" + client.hitResult + "; visual=" + visual);
            });
            aim(context, target);
            context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                    && isInterfaceHit(client.level, hit, position, kind, face));
        } catch (AssertionError failure) {
            context.takeScreenshot("automation-interface-hit-failure-" + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + "-" + face.name().toLowerCase(java.util.Locale.ROOT));
            String actual = context.computeOnClient(client -> "eye=" + client.player.getEyePosition()
                    + ", pose=" + client.player.getPose() + ", hit=" + (client.hitResult instanceof BlockHitResult hit
                    ? hit.getBlockPos() + "/" + hit.getDirection() + "/" + hit.getLocation() : client.hitResult));
            throw new AssertionError("Cannot aim at the physical " + kind + " " + face + " interface: " + actual, failure);
        }
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.waitTicks(3);
    }
    private static boolean isInterfaceHit(ClientLevel level, BlockHitResult hit, BlockPos position, ConduitKind kind, Direction face) {
        return hit.getBlockPos().equals(position) && level.getBlockEntity(position) instanceof ConduitBundleBlockEntity node
                && ConduitGeometry.hitPart(node.visualState(), hit.getLocation().subtract(Vec3.atLowerCornerOf(position)), hit.getDirection())
                .filter(part -> part.kind() == kind && part.role() == ConduitGeometry.Role.ENDPOINT && part.side() == face).isPresent();
    }
    private static void close(ClientGameTestContext context) { context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitFor(client -> client.gui.screen() == null); }
    private static void checkWidgetsFit(ClientGameTestContext context) {
        check(context.computeOnClient(client -> client.gui.screen().children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible).allMatch(widget -> widget.getX() >= 0 && widget.getY() >= 0
                        && widget.getRight() <= client.gui.screen().width && widget.getBottom() <= client.gui.screen().height)),
                "Every visible native automation control fits the real GUI viewport");
    }
    static CompoundTag snapshot(ServerLevel level) {
        CompoundTag tag = new CompoundTag();
        for (int index = 0; index < SAVED.size(); index++) {
            BlockPos pos = SAVED.get(index);
            check(level.getBlockEntity(pos) != null, "Persisted automation fixture exists at " + pos);
            tag.put("entity_" + index, level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess()));
            tag.put("block_" + index, NbtUtils.writeBlockState(level.getBlockState(pos)));
        }
        return tag;
    }
    static void verifyReload(TestSingleplayerContext world, CompoundTag expected) {
        check(world.getServer().computeOnServer(server -> snapshot(server.overworld())).equals(expected),
                "Paused engine resources, inventory, unfinished fuel and every conduit face/type survive world/JVM restart exactly");
    }
    static void captureReload(ClientGameTestContext context, TestSingleplayerContext world, String name) {
        move(context, world, new Vec3(23.8, 80, 4.8));
        aim(context, Vec3.atCenterOf(CENTER));
        world.getConnection().waitForChunksRender();
        context.takeScreenshot(name);
    }
    static void resumeRoutingAfterReload(TestSingleplayerContext world) {
        long[] before = world.getServer().computeOnServer(server -> {
            ServerLevel level = server.overworld();
            check(BackpackTestSupport.count((Container) level.getBlockEntity(ITEMS), Items.IRON_INGOT) == 0,
                    "The persisted source is empty before adding a new one-item restart fixture");
            long[] state = {BackpackTestSupport.count(backpack(level, SINK).inventory(), Items.IRON_INGOT),
                    engine(level).snapshot().energy() + energy(level, SINK)};
            ((Container) level.getBlockEntity(ITEMS)).setItem(0, new ItemStack(Items.IRON_INGOT));
            for (BlockPos pos : List.of(CENTER, CENTER.east(), CENTER.east(2))) {
                node(level, pos).setMode(ConduitKind.ITEM, Direction.EAST, ConduitMode.INSERT);
                if (!pos.equals(CENTER)) node(level, pos).setMode(ConduitKind.ITEM, Direction.WEST, ConduitMode.INSERT);
                node(level, pos).setMode(ConduitKind.ENERGY, Direction.EAST, ConduitMode.BOTH);
                node(level, pos).setMode(ConduitKind.ENERGY, Direction.WEST, ConduitMode.BOTH);
            }
            node(level, CENTER).setMode(ConduitKind.ITEM, Direction.NORTH, ConduitMode.EXTRACT);
            engine(level).setEnabled(true);
            return state;
        });
        try {
            world.getServer().waitFor(server -> BackpackTestSupport.count(backpack(server.overworld(), SINK).inventory(), Items.IRON_INGOT) == before[0] + 1
                    && engine(server.overworld()).snapshot().energy() + energy(server.overworld(), SINK) > before[1], 600);
            world.getServer().runOnServer(server -> check(BackpackTestSupport.count((Container) server.overworld().getBlockEntity(ITEMS), Items.IRON_INGOT) == 0,
                    "The rebuilt network consumed exactly the one fresh restart-fixture item"));
        } finally {
            world.getServer().runOnServer(server -> {
                ServerLevel level = server.overworld();
                engine(level).setEnabled(false);
                for (BlockPos pos : List.of(CENTER, CENTER.east(), CENTER.east(2)))
                    for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values())
                        node(level, pos).setMode(kind, side, ConduitMode.DISABLED);
            });
        }
    }
    private static long[] totals(ServerLevel level) {
        long water = engine(level).snapshot().waterDroplets() + water(level, WATER) + water(level, SINK);
        long energy = engine(level).snapshot().energy() + energy(level, SINK);
        int items = BackpackTestSupport.count((Container) level.getBlockEntity(ITEMS), Items.IRON_INGOT)
                + BackpackTestSupport.count(backpack(level, SINK).inventory(), Items.IRON_INGOT);
        return new long[]{water, energy, items};
    }
    private static long water(ServerLevel level, BlockPos pos) {
        long result = 0;
        var storage = FluidStorage.SIDED.find(level, pos, Direction.UP);
        check(storage != null, "The native tank advertises Fabric FluidStorage");
        for (var view : storage) result += view.getAmount();
        return result;
    }
    private static long energy(ServerLevel level, BlockPos pos) {
        var storage = EnergyStorage.SIDED.find(level, pos, Direction.UP);
        check(storage != null, "The native battery advertises Team Reborn EnergyStorage");
        return storage.getAmount();
    }
    private static SteamEngineBlockEntity engine(ServerLevel level) { return (SteamEngineBlockEntity) level.getBlockEntity(ENGINE); }
    private static ConduitBundleBlockEntity node(ServerLevel level, BlockPos pos) { return (ConduitBundleBlockEntity) level.getBlockEntity(pos); }
    private static BackpackBlockEntity backpack(ServerLevel level, BlockPos pos) { return (BackpackBlockEntity) level.getBlockEntity(pos); }
    private record Fixture(Vec3 position, int selected, ItemStack hand, ItemStack fuel, ItemStack bucket) {}
}
