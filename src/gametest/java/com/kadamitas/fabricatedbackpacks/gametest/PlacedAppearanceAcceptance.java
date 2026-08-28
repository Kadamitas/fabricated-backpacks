package com.kadamitas.fabricatedbackpacks.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlock;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.client.render.BackpackVisualState;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.item.BackpackDisplay;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.check;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.player;

/** Real world rendering; the viewer and block-damage inputs below are explicitly server fixtures. */
public final class PlacedAppearanceAcceptance {
    // The raised flap crosses y=96 into the next section. No render state or lid value is injected.
    private static final BlockPos BAG = new BlockPos(0, 95, -6);
    private static final int BODY = 0x2c8890;
    private static final int TRIM = 0xb54c45;
    private static final int RECOLORED_BODY = 0x794cab;
    private static final int RECOLORED_TRIM = 0xe4c370;
    private static final int BREAKER_FIXTURE_ID = 1_000_001;
    private static final float EPSILON = .00001F;
    // Read and written only on the client thread; the test mixin observes completed vanilla calls.
    private static PlacedAppearanceAcceptance meshObserver;

    private final ClientGameTestContext context;
    private final TestSingleplayerContext world;
    private final List<String> evidence = new ArrayList<>();
    private final List<ObservedTick> ticks = new ArrayList<>();
    private final List<Capture> captures = new ArrayList<>();
    private final List<MeshInvalidation> meshInvalidations = new ArrayList<>();

    private PlacedAppearanceAcceptance(ClientGameTestContext context, TestSingleplayerContext world) {
        this.context = context;
        this.world = world;
    }

    static List<String> run(ClientGameTestContext context, TestSingleplayerContext world) {
        return new PlacedAppearanceAcceptance(context, world).run();
    }

    private List<String> run() {
        check(context.computeOnClient(client -> client.gui.screen() == null && client.player != null
                && client.player.containerMenu.getCarried().isEmpty()), "Placed appearance acceptance needs a clear screen and cursor");
        var previousCamera = context.computeOnClient(client -> client.options.getCameraType());
        PlayerPose previousPose = world.getServer().computeOnServer(server -> new PlayerPose(player(world).getX(),
                player(world).getY(), player(world).getZ(), player(world).getYRot(), player(world).getXRot()));
        Map<BlockPos, BlockState> previousBlocks = prepare();
        boolean passed = false;
        String failure = null;
        try {
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            focus(Direction.NORTH);
            context.waitFor(client -> client.level.getBlockEntity(BAG) instanceof BackpackBlockEntity);
            context.waitFor(client -> appearanceMatches(client, BODY, TRIM, "minecraft:diamond", 0, 0));
            world.getConnection().waitForChunksRender();
            context.waitTicks(2);
            check(sample().end == 0F, "An unopened placed backpack starts closed");
            checkAppearance(BODY, TRIM, "minecraft:diamond", 0, 0);
            verifyCulling();
            capture("placed-01-closed-dyed-stored-display");

            viewer(true);
            Sample middle = await("opening", value -> value.open && value.end >= .5F && value.end < 1F);
            check(middle.begin < middle.middle && middle.middle < middle.end,
                    "The live placed lid has a real intermediate render pose");
            capture("placed-02-opening-midpoint");
            await("opening-end", value -> value.open && value.begin == 1F && value.end == 1F);
            capture("placed-03-open-at-section-boundary");

            // Two server-side viewers keep OPEN true until the last viewer closes.
            viewer(true);
            viewer(false);
            context.waitTicks(2);
            check(sample().open && sample().end == 1F, "One remaining viewer keeps the live flap open");

            viewer(false);
            Sample closing = await("closing-before-reversal", value -> !value.open && value.end <= .7F && value.end > .25F);
            check(closing.end < closing.begin, "Real client ticks begin closing the lid");
            viewer(true);
            Sample reversed = await("reversal", value -> value.open && value.end > value.begin);
            check(reversed.begin > 0F && reversed.begin < 1F,
                    "The server viewer change reverses an unfinished close, without resetting the lid");
            capture("placed-04-reversed-toward-open");
            await("reversal-end", value -> value.open && value.begin == 1F);

            world.getServer().runOnServer(server -> server.overworld().destroyBlockProgress(BREAKER_FIXTURE_ID, BAG, 5));
            context.waitFor(client -> client.level.destructionProgress().containsKey(BAG.asLong()));
            capture("placed-05-breaking-overlay-server-fixture");
            world.getServer().runOnServer(server -> server.overworld().destroyBlockProgress(BREAKER_FIXTURE_ID, BAG, -1));
            context.waitFor(client -> !client.level.destructionProgress().containsKey(BAG.asLong()));

            viewer(false);
            await("closing", value -> !value.open && value.end <= .5F && value.end > 0F);
            capture("placed-06-closing-midpoint");
            await("closed-end", value -> !value.open && value.begin == 0F && value.end == 0F);
            capture("placed-07-closed-again");

            verifySameFacingRecolor();
            viewer(true);
            await("recolored-open", value -> value.open && value.begin == 1F);
            for (Direction direction : List.of(Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                world.getServer().runOnServer(server -> server.overworld().setBlock(BAG,
                        serverBag().getBlockState().setValue(BackpackBlock.FACING, direction), 3));
                context.waitFor(client -> client.level.getBlockState(BAG).getValue(BackpackBlock.FACING) == direction);
                focus(direction);
                check(sample().end == 1F, "A facing change preserves the live lid position");
                capture("placed-09-open-facing-" + direction.getSerializedName());
            }
            check(world.getServer().computeOnServer(server -> {
                var bag = serverBag().inventory();
                return bag.getItem(0).isEmpty() && bag.getItem(2).is(Items.DIAMOND) && bag.getItem(2).getCount() == 17
                        && bag.getItem(1).is(Items.AMETHYST_SHARD) && bag.getItem(1).getCount() == 32;
            }), "Viewer, dye and display changes preserve every physical fixture item");

            // An additional tier exercises the original moving netherite guard.
            viewer(false);
            world.getServer().runOnServer(server -> {
                server.overworld().setBlock(BAG, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState()
                        .setValue(BackpackBlock.FACING, Direction.NORTH), 3);
                var bag = BackpackTestSupport.bag(BackpackTier.NETHERITE);
                bag.setItem(0, new ItemStack(Items.OAK_PLANKS, 23));
                bag.updateSettings(tag -> { tag.putInt("display_slot", 0); tag.putInt("display_depth", 0); });
                serverBag().setStack(bag.stack());
            });
            context.waitFor(client -> client.level.getBlockEntity(BAG) instanceof BackpackBlockEntity entity
                    && BackpackRegistry.tier(entity.stack()).orElse(null) == BackpackTier.NETHERITE);
            focus(Direction.NORTH);
            await("netherite-closed-start", value -> !value.open && value.begin == 0F && value.end == 0F);
            capture("placed-10-netherite-closed-block-display");
            viewer(true);
            await("netherite-open", value -> value.open && value.begin == 1F);
            capture("placed-11-netherite-open-guard-and-display");
            viewer(false);
            await("netherite-closed", value -> !value.open && value.begin == 0F);

            evidence.add("Placed appearance: real client ticker closed/mid/open/close and unfinished-close reversal; all four facings; y=95 section boundary; stored, memory and block-item displays; two dye layers.");
            evidence.add("Fixture boundary: server BE viewer calls and a server destruction packet, not a second live viewer or physical mining input. Captures require visual review; no pixel golden or FPS claim.");
            passed = true;
            return List.copyOf(evidence);
        } catch (RuntimeException | AssertionError exception) {
            failure = exception.toString();
            throw exception;
        } finally {
            context.runOnClient(client -> { if (meshObserver == this) meshObserver = null; });
            try {
                world.getServer().runOnServer(server -> {
                    server.overworld().destroyBlockProgress(BREAKER_FIXTURE_ID, BAG, -1);
                    if (server.overworld().getBlockEntity(BAG) instanceof BackpackBlockEntity entity)
                        while (entity.viewers() > 0) entity.close();
                    previousBlocks.forEach((position, state) -> server.overworld().setBlock(position, state, 3));
                    player(world).teleportTo(previousPose.x, previousPose.y, previousPose.z);
                    player(world).setDeltaMovement(Vec3.ZERO);
                });
                world.getConnection().waitForClientboundPackets();
                context.getInput().lookAt(previousPose.yaw, previousPose.pitch);
                context.runOnClient(client -> client.options.setCameraType(previousCamera));
            } finally { writeReport(passed, failure); }
        }
    }

    private Map<BlockPos, BlockState> prepare() {
        return world.getServer().computeOnServer(server -> {
            var level = server.overworld();
            Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
            previous.put(BAG, level.getBlockState(BAG));
            for (int x = -5; x <= 5; x++) for (int z = -11; z <= 0; z++) {
                BlockPos position = new BlockPos(x, BAG.getY() - 1, z);
                check(level.getBlockEntity(position) == null, "Appearance platform must not replace another fixture's block entity");
                previous.put(position, level.getBlockState(position));
            }
            check(level.getBlockEntity(BAG) == null, "Appearance fixture position must be unused");
            previous.keySet().stream().filter(position -> !position.equals(BAG))
                    .forEach(position -> level.setBlock(position, Blocks.STONE.defaultBlockState(), 3));
            level.setBlock(BAG, BackpackRegistry.block(BackpackTier.GOLD).defaultBlockState()
                    .setValue(BackpackBlock.FACING, Direction.NORTH), 3);
            var bag = BackpackTestSupport.bag(BackpackTier.GOLD);
            bag.dye(BODY, TRIM);
            bag.setItem(0, new ItemStack(Items.DIAMOND, 17));
            bag.setItem(1, new ItemStack(Items.AMETHYST_SHARD, 32));
            bag.updateSettings(tag -> tag.putInt("display_slot", 0));
            serverBag().setStack(bag.stack());
            return previous;
        });
    }

    private BackpackBlockEntity serverBag() {
        return (BackpackBlockEntity) player(world).level().getBlockEntity(BAG);
    }

    /** Called only by the test client mixin, after vanilla has dirtied the render sections. */
    public static void observeMeshInvalidation(ClientLevel level, BlockPos position, int flags) {
        var observer = meshObserver;
        if (observer == null || !BAG.equals(position)
                || !(level.getBlockEntity(position) instanceof BackpackBlockEntity entity)) return;
        observer.meshInvalidations.add(new MeshInvalidation(level.getGameTime(),
                entity.getBlockState().getValue(BackpackBlock.FACING).getSerializedName(),
                entity.getBlockState().getValue(BackpackBlock.OPEN),
                BackpackVisualState.color(entity.stack(), 0) & 0xffffff,
                BackpackVisualState.color(entity.stack(), 1) & 0xffffff, flags));
    }

    private void verifySameFacingRecolor() {
        world.getConnection().waitForClientboundPackets();
        world.getConnection().waitForChunksRender();
        context.runOnClient(client -> { meshInvalidations.clear(); meshObserver = this; });
        try {
            // Move the existing stack and change only the body tint; keep the block state untouched.
            world.getServer().runOnServer(server -> {
                var bag = serverBag().inventory();
                ItemStack diamonds = bag.removeItemNoUpdate(0);
                bag.setItem(2, diamonds);
                bag.remember(0, new ItemStack(Items.EMERALD));
                bag.dye(RECOLORED_BODY, TRIM);
                bag.updateSettings(tag -> { tag.putInt("display_rotation", 45); tag.putInt("display_depth", 8); });
            });
            context.waitFor(client -> appearanceMatches(client, RECOLORED_BODY, TRIM, "minecraft:emerald", 45, 8));
            world.getConnection().waitForChunksRender();
            checkMeshInvalidations(1, RECOLORED_BODY, TRIM);
            capture("placed-08a-body-only-same-facing");

            world.getServer().runOnServer(server -> serverBag().inventory().dye(RECOLORED_BODY, RECOLORED_TRIM));
            context.waitFor(client -> appearanceMatches(client, RECOLORED_BODY, RECOLORED_TRIM, "minecraft:emerald", 45, 8));
            world.getConnection().waitForChunksRender();
            checkMeshInvalidations(2, RECOLORED_BODY, RECOLORED_TRIM);

            // Repeated appearance packets must not rebuild an unchanged body mesh.
            for (int repeat = 0; repeat < 2; repeat++) {
                world.getServer().runOnServer(server -> serverBag().synchronize());
                world.getConnection().waitForClientboundPackets();
            }
            world.getConnection().waitForChunksRender();
            checkMeshInvalidations(2, RECOLORED_BODY, RECOLORED_TRIM);

            // Exterior-icon changes are live-rendered and do not alter the body tint mesh.
            world.getServer().runOnServer(server -> serverBag().inventory().updateSettings(tag -> tag.putInt("display_rotation", 90)));
            context.waitFor(client -> appearanceMatches(client, RECOLORED_BODY, RECOLORED_TRIM, "minecraft:emerald", 90, 8));
            world.getConnection().waitForChunksRender();
            checkMeshInvalidations(2, RECOLORED_BODY, RECOLORED_TRIM);
            world.getServer().runOnServer(server -> serverBag().inventory().updateSettings(tag -> tag.putInt("display_rotation", 45)));
            context.waitFor(client -> appearanceMatches(client, RECOLORED_BODY, RECOLORED_TRIM, "minecraft:emerald", 45, 8));
            context.waitTicks(25);
            world.getConnection().waitForChunksRender();
            checkMeshInvalidations(2, RECOLORED_BODY, RECOLORED_TRIM);
            checkAppearance(RECOLORED_BODY, RECOLORED_TRIM, "minecraft:emerald", 45, 8);
            capture("placed-08-recolored-memory-display");
            evidence.add("Same-facing recolor: observed completed native client mesh invalidations for body-only and trim-only changes; none for repeated packets, icon rotation changes or 25 idle client ticks. Closed NORTH state retained; real captures still require visual review.");
        } finally {
            context.runOnClient(client -> { if (meshObserver == this) meshObserver = null; });
        }
    }

    private void checkMeshInvalidations(int expected, int body, int trim) {
        context.runOnClient(client -> {
            check(meshInvalidations.size() == expected,
                    "Only effective dye changes dirty the actual client mesh: expected " + expected + ", observed " + meshInvalidations);
            MeshInvalidation update = meshInvalidations.getLast();
            check(update.body == body && update.trim == trim && update.facing.equals("north") && !update.open,
                    "The mesh rebuild uses the new dyes without a facing or OPEN transition: " + update);
            var state = client.level.getBlockState(BAG);
            check(state.getValue(BackpackBlock.FACING) == Direction.NORTH && !state.getValue(BackpackBlock.OPEN),
                    "Same-facing recolor must not rely on changing the block state");
        });
    }

    private void focus(Direction facing) {
        Direction side = facing.getClockWise();
        double x = BAG.getX() + .5 + facing.getStepX() * 2.6 + side.getStepX() * 1.35;
        double z = BAG.getZ() + .5 + facing.getStepZ() * 2.6 + side.getStepZ() * 1.35;
        world.getServer().runOnServer(server -> {
            player(world).teleportTo(x, BAG.getY(), z);
            player(world).setDeltaMovement(Vec3.ZERO);
        });
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> Math.abs(client.player.getX() - x) < .05 && Math.abs(client.player.getZ() - z) < .05);
        context.getInput().lookAt(BAG);
        world.getConnection().waitForChunksRender();
        context.waitTicks(2);
    }

    private void viewer(boolean open) {
        world.getServer().runOnServer(server -> {
            if (open) serverBag().open();
            else {
                check(serverBag().viewers() > 0, "The fixture must close an existing viewer");
                serverBag().close();
            }
        });
    }

    private Sample await(String phase, Predicate<Sample> predicate) {
        for (int tick = 0; tick < 40; tick++) {
            Sample before = sample();
            ticks.add(new ObservedTick(phase, tick, before));
            check(valid(before), "The live lid pose is finite, bounded and interpolated: " + before);
            if (predicate.test(before)) return before;
            context.waitTick();
            Sample after = sample();
            check(Math.abs(after.begin - before.end) < EPSILON,
                    "Consecutive real client ticks keep the previous render pose: " + before + " -> " + after);
        }
        throw new AssertionError("Timed out observing placed flap phase " + phase + ": " + sample());
    }

    private static boolean valid(Sample sample) {
        return Float.isFinite(sample.begin) && Float.isFinite(sample.middle) && Float.isFinite(sample.end)
                && sample.begin >= 0F && sample.begin <= 1F && sample.end >= 0F && sample.end <= 1F
                && sample.middle >= Math.min(sample.begin, sample.end) && sample.middle <= Math.max(sample.begin, sample.end);
    }

    private Sample sample() {
        return context.computeOnClient(client -> {
            check(client.level.getBlockEntity(BAG) instanceof BackpackBlockEntity, "The actual client must contain the fixture block entity");
            var entity = (BackpackBlockEntity) client.level.getBlockEntity(BAG);
            var renderer = client.levelRenderer.blockEntityRenderDispatcher().getRenderer(entity);
            check(renderer != null, "The actual client must resolve the production backpack block renderer");
            var display = BackpackDisplay.from(entity.stack());
            var contents = entity.stack().getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY);
            return new Sample(client.level.getGameTime(), entity.getBlockState().getValue(BackpackBlock.OPEN),
                    entity.lidOpenness(0F), entity.lidOpenness(.5F), entity.lidOpenness(1F),
                    entity.getBlockState().getValue(BackpackBlock.FACING).getSerializedName(),
                    BackpackVisualState.color(entity.stack(), 0) & 0xffffff, BackpackVisualState.color(entity.stack(), 1) & 0xffffff,
                    display.map(value -> BuiltInRegistries.ITEM.getKey(value.icon().getItem()).toString()).orElse(""),
                    display.map(value -> value.icon().getCount()).orElse(0), display.map(BackpackDisplay::rotation).orElse(0),
                    display.map(BackpackDisplay::depth).orElse(0), contents.entries().size(),
                    renderer.shouldRender(entity, client.gameRenderer.mainCamera().position()), renderer.shouldRenderOffScreen(), renderer.getViewDistance());
        });
    }

    private void verifyCulling() {
        Sample front = sample();
        check(front.eligible && !front.global && front.viewDistance <= 128,
                "A visible nearby backpack uses bounded normal-section rendering");
        check(context.computeOnClient(client -> {
            var entity = (BackpackBlockEntity) client.level.getBlockEntity(BAG);
            return !client.levelRenderer.blockEntityRenderDispatcher().getRenderer(entity)
                    .shouldRender(entity, Vec3.atCenterOf(BAG).add(1_000, 0, 0));
        }), "The production renderer rejects a distant camera before extracting a model");
        float[] look = context.computeOnClient(client -> new float[]{client.player.getYRot(), client.player.getXRot()});
        context.getInput().lookAt(look[0] + 180F, look[1]);
        context.waitTicks(2);
        check(!sample().eligible, "The production renderer rejects a bag behind the real camera frustum");
        context.getInput().lookAt(BAG);
        context.waitTicks(2);
        check(sample().eligible, "The nearby backpack becomes eligible again when the camera returns");
        evidence.add("Render eligibility: visible/front accepted; actual behind-camera frustum rejected; far-camera predicate rejected; no global block-entity render registration. This is not a frame-rate benchmark.");
    }

    private void checkAppearance(int body, int trim, String item, int rotation, int depth) {
        check(context.computeOnClient(client -> appearanceMatches(client, body, trim, item, rotation, depth)),
                "The actual client receives the expected public dye and display snapshot");
        Sample sample = sample();
        check(sample.publicEntries == 1 && sample.displayCount == 1,
                "The public client receives one display icon, not private stored quantities or other contents");
    }

    private static boolean appearanceMatches(Minecraft client, int body, int trim, String item, int rotation, int depth) {
        if (!(client.level.getBlockEntity(BAG) instanceof BackpackBlockEntity entity)) return false;
        var display = BackpackDisplay.from(entity.stack());
        return (BackpackVisualState.color(entity.stack(), 0) & 0xffffff) == body
                && (BackpackVisualState.color(entity.stack(), 1) & 0xffffff) == trim
                && display.filter(value -> BuiltInRegistries.ITEM.getKey(value.icon().getItem()).toString().equals(item)
                        && value.rotation() == rotation && value.depth() == depth).isPresent();
    }

    private void capture(String name) {
        Sample before = sample();
        check(before.eligible, "The actual renderer must accept the fixture before capturing " + name);
        // Fabric's standard screenshot uses partial tick 1 and the real GameRenderer.
        // The lid position comes only from the preceding world client ticks.
        Path screenshot = context.takeScreenshot(name);
        check(Files.isRegularFile(screenshot), "The real client screenshot must exist: " + screenshot);
        captures.add(new Capture(name, screenshot.toAbsolutePath().toString(), before));
        evidence.add("Screenshot: " + screenshot);
    }

    private void writeReport(boolean passed, String failure) {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        var report = new JsonObject();
        report.addProperty("state_assertions_passed", passed);
        report.addProperty("minecraft_client_capture", true);
        report.addProperty("visual_review_required", true);
        report.addProperty("fixture", "A real placed backpack at y=95; viewer counts and block damage are server fixtures, not a second live player or mining input.");
        report.addProperty("capture_state", "Sampled immediately before Fabric's standard GameRenderer screenshot; partial tick 1. No lid or render state is assigned.");
        report.addProperty("performance_measurement", false);
        report.addProperty("pid", ProcessHandle.current().pid());
        if (failure != null) report.addProperty("failure", failure);
        report.add("observed_ticks", gson.toJsonTree(ticks));
        report.add("mesh_invalidations", gson.toJsonTree(meshInvalidations));
        report.add("captures", gson.toJsonTree(captures));
        try {
            Files.createDirectories(ClientAcceptanceFiles.ROOT);
            Files.writeString(ClientAcceptanceFiles.ROOT.resolve("placed-appearance.json"), gson.toJson(report));
            Files.writeString(Path.of("placed-appearance.json"), gson.toJson(report));
        } catch (IOException exception) { throw new AssertionError("Could not write placed appearance evidence", exception); }
    }

    private record PlayerPose(double x, double y, double z, float yaw, float pitch) {}
    private record Sample(long clientTime, boolean open, float begin, float middle, float end, String facing,
                          int body, int trim, String displayItem, int displayCount, int displayRotation, int displayDepth,
                          int publicEntries, boolean eligible, boolean global, int viewDistance) {}
    private record ObservedTick(String phase, int tick, Sample state) {}
    private record MeshInvalidation(long clientTime, String facing, boolean open, int body, int trim, int flags) {}
    private record Capture(String name, String path, Sample stateImmediatelyBeforeRequest) {}
}
