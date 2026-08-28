package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMode;
import com.kadamitas.fabricatedbackpacks.automation.engine.EngineSideMode;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlock;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineComponents;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineMenu;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineMenus;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineState;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineSides;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineSideMenu;
import com.kadamitas.fabricatedbackpacks.config.AutomationConfig;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.ServerConfig;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Real registered blocks, native fuel and Transfer/Energy APIs; the network peer is an EmbeddedChannel fixture. */
public final class SteamEngineGameTests {
    private static final FluidVariant WATER = FluidVariant.of(Fluids.WATER);
    private static final Map<Endpoint, Receiver> RECEIVERS = new HashMap<>();
    private static Block receiverBlock;
    private record Endpoint(Level level, BlockPos position) { }
    private record Receiver(Direction face, SimpleEnergyStorage storage, Set<Direction> queried) { }

    private SteamEngineGameTests() { }

    public static void registerFixtures() {
        if (receiverBlock != null) return;
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("fabricated_backpacks_tests", "steam_energy_receiver");
        receiverBlock = Registry.register(BuiltInRegistries.BLOCK, id,
                new Block(Block.Properties.of()));
        EnergyStorage.SIDED.registerForBlocks((level, position, state, entity, side) -> {
            Receiver receiver = RECEIVERS.get(new Endpoint(level, position));
            if (receiver == null) return null;
            receiver.queried().add(side);
            return side == receiver.face() ? receiver.storage() : null;
        }, receiverBlock);
    }

    public static void generationAndPauses(GameTestHelper helper) {
        var level = helper.getLevel();
        var rules = BackpackConfig.get().automation().engine();
        long quantum = mb(rules.waterMbPerTick());
        var running = place(helper, new BlockPos(1, 1, 1), new SteamEngineState(quantum * 2, 0, 0, 0, true));
        running.setItem(SteamEngineBlockEntity.FUEL, new ItemStack(Items.COAL, 2));
        int duration = net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.getFuel().getOrDefault(Items.COAL, 0);
        tick(running, level);
        helper.assertTrue(running.active(), "ACTIVE starts only on an actual productive boiler tick");
        helper.assertValueEqual(running.snapshot(), new SteamEngineState(quantum, rules.energyPerTick(), duration - 1, duration, true),
                "One vanilla coal starts one finite burn and consumes exactly one water quantum");
        assertStack(helper, running.getItem(0), Items.COAL, 1, "Starting a burn consumes one fuel item");
        var first = running.dropStack();
        tick(running, level);
        assertStack(helper, running.dropStack(), first, "A repeated engine call in the same server tick cannot generate twice");

        var dry = place(helper, new BlockPos(3, 1, 1), new SteamEngineState(quantum - 1, 0, 40, 100, true));
        var dryBefore = dry.dropStack();
        tick(dry, level);
        assertStack(helper, dry.dropStack(), dryBefore, "An incomplete water quantum preserves unfinished fuel");
        helper.assertFalse(dry.active(), "Dry boilers are not animated as running");
        var full = place(helper, new BlockPos(5, 1, 1), new SteamEngineState(quantum, rules.energyCapacity(), 40, 100, true));
        full.setItem(0, new ItemStack(Items.COAL, 3));
        var fullBefore = full.dropStack();
        tick(full, level);
        assertStack(helper, full.dropStack(), fullBefore, "A full energy buffer consumes neither water nor fuel work");
        helper.assertFalse(full.active(), "A full buffer stops the active state");
        var almostFull = place(helper, new BlockPos(5, 1, 3), new SteamEngineState(quantum, rules.energyCapacity() - rules.energyPerTick() + 1, 0, 0, true));
        almostFull.setItem(0, new ItemStack(Items.COAL));
        var almostBefore = almostFull.dropStack();
        tick(almostFull, level);
        assertStack(helper, almostFull.dropStack(), almostBefore, "Insufficient energy room cannot consume a fresh fuel prematurely");
        var overWater = place(helper, new BlockPos(3, 1, 5), new SteamEngineState(mb(rules.waterCapacityMb()) + 1, 0, 40, 100, true));
        var overWaterBefore = overWater.dropStack();
        tick(overWater, level);
        assertStack(helper, overWater.dropStack(), overWaterBefore, "An oversized saved water tank pauses work after a capacity reduction without deleting water");

        running.setEnabled(false);
        var paused = running.dropStack();
        helper.assertFalse(running.active(), "Disabling immediately stops ACTIVE");
        helper.runAfterDelay(3, () -> {
            assertStack(helper, running.dropStack(), paused, "Real block ticks preserve all paused resources and remaining fuel");
            running.setEnabled(true);
            helper.runAfterDelay(3, () -> {
                helper.assertValueEqual(running.snapshot().energy(), rules.energyPerTick() * 2, "Resuming uses the remaining water exactly once");
                helper.assertValueEqual(running.snapshot().waterDroplets(), 0L, "Resuming does not create or round away water");
                helper.assertValueEqual(running.snapshot().burnRemaining(), duration - 2, "Dry ticks after resuming preserve unfinished fuel");
                helper.assertFalse(running.active(), "The naturally ticking boiler becomes inactive after its last water quantum");
                assertStack(helper, running.getItem(0), Items.COAL, 1, "Pause/resume never charges another fuel item");
                helper.succeed();
            });
        });
    }

    public static void fuelAndContainerConservation(GameTestHelper helper) {
        var level = helper.getLevel();
        var rules = BackpackConfig.get().automation().engine();
        var engine = place(helper, new BlockPos(1, 1, 1), SteamEngineState.EMPTY);
        engine.setItem(SteamEngineBlockEntity.FUEL, new ItemStack(Items.LAVA_BUCKET));
        engine.setItem(SteamEngineBlockEntity.WATER_INPUT, new ItemStack(Items.WATER_BUCKET));
        tick(engine, level);
        helper.assertValueEqual(engine.snapshot().waterDroplets(), FluidConstants.BUCKET - mb(rules.waterMbPerTick()),
                "The vanilla water bucket empties through the real item fluid API");
        helper.assertValueEqual(engine.snapshot().energy(), rules.energyPerTick(), "The accepted water powers one engine tick");
        helper.assertValueEqual(engine.snapshot().burnRemaining(), net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.getFuel().getOrDefault(Items.LAVA_BUCKET, 0) - 1,
                "The lava bucket uses the loaded vanilla fuel duration");
        helper.assertTrue(engine.getItem(0).isEmpty() && engine.getItem(1).isEmpty(), "Both consumed containers leave their input slots");
        assertStack(helper, engine.getItem(2), Items.BUCKET, 1, "Fuel keeps its vanilla bucket remainder");
        assertStack(helper, engine.getItem(3), Items.BUCKET, 1, "Water transfer keeps its independent bucket remainder");

        var blockedFuel = place(helper, new BlockPos(3, 1, 1), new SteamEngineState(FluidConstants.BUCKET, 0, 0, 0, true));
        blockedFuel.setItem(0, new ItemStack(Items.LAVA_BUCKET));
        blockedFuel.setItem(2, new ItemStack(Items.COBBLESTONE, 64));
        var blockedBefore = blockedFuel.dropStack();
        tick(blockedFuel, level);
        assertStack(helper, blockedFuel.dropStack(), blockedBefore, "Blocked fuel remainders roll back the entire generation attempt");

        var blockedWater = place(helper, new BlockPos(5, 1, 1), SteamEngineState.EMPTY);
        blockedWater.setItem(1, new ItemStack(Items.WATER_BUCKET));
        blockedWater.setItem(3, new ItemStack(Items.COBBLESTONE, 64));
        var waterBefore = blockedWater.dropStack();
        tick(blockedWater, level);
        assertStack(helper, blockedWater.dropStack(), waterBefore, "Blocked water outputs cannot consume a bucket or create water");

        var nearCapacity = place(helper, new BlockPos(1, 1, 3), new SteamEngineState(mb(rules.waterCapacityMb()) - 1, 0, 0, 0, true));
        nearCapacity.setItem(1, new ItemStack(Items.WATER_BUCKET));
        var nearBefore = nearCapacity.dropStack();
        tick(nearCapacity, level);
        assertStack(helper, nearCapacity.dropStack(), nearBefore, "An indivisible bucket is retained when only one droplet of tank room remains");

        var invalid = place(helper, new BlockPos(3, 1, 3), SteamEngineState.EMPTY);
        helper.assertFalse(invalid.canPlaceItem(0, new ItemStack(Items.DIAMOND)), "Ordinary items are not fuel");
        helper.assertFalse(invalid.canPlaceItem(1, new ItemStack(Items.LAVA_BUCKET)), "Lava is not boiler water");
        helper.assertFalse(invalid.canPlaceItem(2, new ItemStack(Items.BUCKET)), "Fuel remainder is an output-only slot");
        helper.assertFalse(invalid.canPlaceItem(3, new ItemStack(Items.BUCKET)), "Water remainder is an output-only slot");
        invalid.setItem(0, new ItemStack(Items.DIAMOND));
        invalid.setItem(1, new ItemStack(Items.LAVA_BUCKET));
        var invalidBefore = invalid.dropStack();
        tick(invalid, level);
        assertStack(helper, invalid.dropStack(), invalidBefore, "Even preexisting invalid inputs are never consumed");
        var hopperEngine = place(helper, new BlockPos(5, 2, 5), SteamEngineState.EMPTY.enabled(false));
        hopperEngine.setItem(2, new ItemStack(Items.BUCKET, 2));
        level.setBlockAndUpdate(hopperEngine.getBlockPos().above(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        level.setBlockAndUpdate(hopperEngine.getBlockPos().below(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        var inputHopper = (HopperBlockEntity) level.getBlockEntity(hopperEngine.getBlockPos().above());
        var outputHopper = (HopperBlockEntity) level.getBlockEntity(hopperEngine.getBlockPos().below());
        inputHopper.setItem(0, new ItemStack(Items.COAL, 4));
        helper.runAfterDelay(18, () -> {
            helper.assertTrue(count(hopperEngine, Items.COAL) > 0, "The actual vanilla hopper inserts fuel through the engine's sided container contract");
            helper.assertValueEqual(count(inputHopper, Items.COAL) + count(hopperEngine, Items.COAL), 4,
                    "Native hopper fuel insertion conserves all input items");
            helper.assertValueEqual(count(outputHopper, Items.COAL), 0, "The lower native hopper cannot steal unfinished fuel");
            helper.assertValueEqual(count(outputHopper, Items.BUCKET), 2, "The lower native hopper extracts both actual remainder items");
            helper.assertValueEqual(count(hopperEngine, Items.BUCKET), 0, "Extracted remainders are not duplicated in the engine");
            helper.succeed();
        });
    }

    public static void sidedTransactions(GameTestHelper helper) {
        var level = helper.getLevel();
        var engine = place(helper, new BlockPos(3, 1, 3), new SteamEngineState(0, 10_000, 0, 0, false));
        List<Direction> sides = new ArrayList<>(List.of(Direction.values()));
        sides.add(null);
        int committed = 0;
        for (Direction side : sides) {
            var items = ItemStorage.SIDED.find(level, engine.getBlockPos(), side);
            var water = FluidStorage.SIDED.find(level, engine.getBlockPos(), side);
            var energy = EnergyStorage.SIDED.find(level, engine.getBlockPos(), side);
            helper.assertTrue(items != null && water != null && energy != null, "All six faces and unsided lookups expose the standard APIs");
            helper.assertFalse(energy.supportsInsertion(), "The engine is an energy source, never a battery sink");
            var before = engine.dropStack();
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.COAL), 2, outer), 2L, "Fuel insertion uses transactional item storage");
                helper.assertValueEqual(water.insert(WATER, 83, outer), 83L, "Water storage preserves non-millibucket fractions");
                try (Transaction inner = outer.openNested()) {
                    helper.assertValueEqual(energy.extract(17, inner), 17L, "A nested transaction can reserve energy output");
                    inner.commit();
                }
            }
            assertStack(helper, engine.dropStack(), before, "Outer abort restores all three resources and output allowance on face " + side);
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIAMOND), 1, transaction), 0L, "External item insertion cannot bypass fuel filters");
                helper.assertValueEqual(water.insert(FluidVariant.of(Fluids.LAVA), 81, transaction), 0L, "External fluid insertion rejects lava");
                var decoratedWater = FluidVariant.of(Fluids.WATER, DataComponentPatch.builder()
                        .set(DataComponents.CUSTOM_NAME, Component.literal("Distinct fluid components")).build());
                helper.assertValueEqual(water.insert(decoratedWater, 81, transaction), 0L,
                        "Water with different components is rejected rather than silently normalized and consumed");
                helper.assertValueEqual(energy.insert(100, transaction), 0L, "External energy cannot be laundered through a generator");
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.COAL), 1, transaction), 1L, "Fuel can commit on every standard face");
                helper.assertValueEqual(water.insert(WATER, 83, transaction), 83L, "Fractional water can commit on every standard face");
                helper.assertValueEqual(energy.extract(11, transaction), 11L, "Energy can be extracted within the shared source budget");
                helper.assertValueEqual(items.extract(ItemVariant.of(Items.COAL), Long.MAX_VALUE, transaction), 0L,
                        "Automation extracts outputs, not unconsumed fuel, including through the unsided view");
                transaction.commit();
            }
            committed++;
        }
        helper.assertValueEqual(engine.snapshot().waterDroplets(), committed * 83L, "All committed fluid transfers are conserved exactly");
        helper.assertValueEqual(engine.snapshot().energy(), 10_000L - committed * 11, "All committed energy transfers are conserved exactly");
        assertStack(helper, engine.getItem(0), Items.COAL, committed, "All committed fuel items remain owned by the machine");
        var items = ItemStorage.SIDED.find(level, engine.getBlockPos(), null);
        var water = FluidStorage.SIDED.find(level, engine.getBlockPos(), null);
        var energy = EnergyStorage.SIDED.find(level, engine.getBlockPos(), null);
        var beforeTransfer = engine.dropStack();
        var fluidReceiver = new net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage<FluidVariant>() {
            @Override protected FluidVariant getBlankVariant() { return FluidVariant.blank(); }
            @Override protected long getCapacity(FluidVariant variant) { return FluidConstants.BUCKET; }
        };
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(StorageUtil.move(water, fluidReceiver, WATER::equals, 42, outer), 42L,
                    "An independent standard fluid endpoint receives an exact partial transfer");
            try (Transaction nested = outer.openNested()) {
                helper.assertValueEqual(energy.extract(17, nested), 17L, "Another resource may commit inside that open transaction");
                nested.commit();
            }
        }
        assertStack(helper, engine.dropStack(), beforeTransfer, "Outer abort restores source quantities after cross-endpoint and nested resource transfers");
        helper.assertValueEqual(fluidReceiver.amount, 0L, "The same abort restores the independent destination");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(energy.extract(Long.MAX_VALUE, transaction),
                    BackpackConfig.get().automation().engine().energyOutputPerTick() - committed * 11L,
                    "All side handles and aborted operations share one exact remaining output allowance");
        }
        engine.setItem(2, new ItemStack(Items.BUCKET, 3));
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(items.extract(ItemVariant.of(Items.BUCKET), 2, transaction), 2L, "A real remainder can be extracted");
            transaction.commit();
        }
        assertStack(helper, engine.getItem(2), Items.BUCKET, 1, "Remainder extraction changes exactly the owned output count");
        var publicTag = engine.getUpdateTag(level.registryAccess());
        helper.assertValueEqual(publicTag.getAllKeys(), Set.of("ports"), "Chunk data contains only side capability flags, never owned resource quantities");
        helper.assertValueEqual(NbtAccess.getLongOr(publicTag, "ports", -1), engine.sideConfig().bits(), "Every public capability bit matches the live configuration");
        var retainedItemView = items.iterator().next();
        var retainedWaterView = water.iterator().next();
        var readyChunk = level.getChunkSource().getChunkNow(engine.getBlockPos().getX() >> 4, engine.getBlockPos().getZ() >> 4);
        helper.assertTrue(readyChunk != null, "The engine fixture is in a completed, registered chunk");
        var beforeUnregister = engine.dropStack();
        // A synchronous registration gap retains the real block state and cached API handles.
        // Probes must not create/promote a replacement BE; actual save.open covers chunk-future completion.
        helper.assertTrue(readyChunk.getBlockEntities().remove(engine.getBlockPos(), engine), "The fixture removes only its own registered BE mapping");
        try {
            helper.assertFalse(engine.isRemoved(), "The registration gap exercises identity rather than the removed flag");
            helper.assertFalse(items.supportsInsertion() || water.supportsInsertion() || energy.supportsExtraction(),
                    "Unregistered physical ownership immediately invalidates all cached capabilities");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.COAL), 1, transaction), 0L, "An unregistered engine rejects fuel admission");
                helper.assertValueEqual(water.insert(WATER, 81, transaction), 0L, "An unregistered engine rejects water admission");
                helper.assertValueEqual(energy.extract(1, transaction), 0L, "An unregistered engine rejects energy output");
                transaction.commit();
            }
            helper.assertFalse(readyChunk.getBlockEntities().containsKey(engine.getBlockPos()),
                    "Capability discovery must not create a replacement engine while registration is incomplete");
        } finally { readyChunk.getBlockEntities().put(engine.getBlockPos(), engine); }
        assertStack(helper, engine.dropStack(), beforeUnregister, "Ownership probes preserve exact contents, fuel work, fluid and energy");
        helper.assertTrue(items.supportsInsertion() && water.supportsInsertion() && energy.supportsExtraction(),
                "The same cached ports recover when their original physical owner is registered again");
        level.setBlockAndUpdate(engine.getBlockPos(), Blocks.STONE.defaultBlockState());
        helper.assertValueEqual(retainedItemView.getAmount(), 0L, "An already-held item view becomes inert after block replacement");
        helper.assertValueEqual(retainedWaterView.getAmount(), 0L, "An already-held fluid view becomes inert after block replacement");
        helper.assertValueEqual(energy.getAmount(), 0L, "An already-held energy port becomes inert after block replacement");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(items.insert(ItemVariant.of(Items.COAL), 1, transaction), 0L, "A stale item adapter cannot mutate a replaced machine");
            helper.assertValueEqual(water.insert(WATER, 81, transaction), 0L, "A stale fluid adapter cannot mutate a replaced machine");
            helper.assertValueEqual(energy.extract(1, transaction), 0L, "A stale energy adapter cannot mutate a replaced machine");
            transaction.commit();
        }
        helper.succeed();
    }

    public static void neighborEnergyOutput(GameTestHelper helper) {
        var level = helper.getLevel();
        var isolated = place(helper, new BlockPos(1, 2, 1), new SteamEngineState(0, 257, 0, 0, false));
        var pending = place(helper, new BlockPos(1, 2, 2), SteamEngineState.EMPTY.enabled(false));
        var pendingChunk = level.getChunkSource().getChunkNow(pending.getBlockPos().getX() >> 4, pending.getBlockPos().getZ() >> 4);
        helper.assertTrue(pendingChunk != null && pendingChunk.getBlockEntities().remove(pending.getBlockPos(), pending),
                "The neighbor fixture exposes a real BE-bearing block before its entity is registered");
        var beforePendingProbe = isolated.dropStack();
        try {
            tick(isolated, level);
            helper.assertFalse(pendingChunk.getBlockEntities().containsKey(pending.getBlockPos()),
                    "An energy-source neighbor scan cannot promote or create an unregistered destination BE");
            assertStack(helper, isolated.dropStack(), beforePendingProbe, "Skipping an unregistered neighbor spends no source energy or fuel work");
        } finally { pendingChunk.getBlockEntities().put(pending.getBlockPos(), pending); }
        var engine = place(helper, new BlockPos(3, 2, 3), new SteamEngineState(0, 10_000, 0, 0, false));
        List<Receiver> receivers = new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockPos neighbor = engine.getBlockPos().relative(side);
            level.setBlockAndUpdate(neighbor, receiverBlock.defaultBlockState());
            Receiver receiver = new Receiver(side.getOpposite(), new SimpleEnergyStorage(1_000_000, 64, 0), new HashSet<>());
            receivers.add(receiver);
            RECEIVERS.put(new Endpoint(level, neighbor), receiver);
        }
        long start = level.getGameTime();
        tick(engine, level);
        long total = receivers.stream().mapToLong(receiver -> receiver.storage().amount).sum();
        long rate = BackpackConfig.get().automation().engine().energyOutputPerTick();
        helper.assertValueEqual(total, rate, "A source pushes energy through public neighbor APIs without requiring a machine to pull");
        helper.assertValueEqual(engine.snapshot().energy() + total, 10_000L, "Six neighbor transfers conserve source plus destinations");
        try (Transaction transaction = Transaction.openOuter()) {
            for (Direction side : Direction.values()) helper.assertValueEqual(EnergyStorage.SIDED.find(level, engine.getBlockPos(), side)
                    .extract(1, transaction), 0L, "Every face shares the same spent output allowance");
        }
        var adjacentA = place(helper, new BlockPos(1, 1, 6), new SteamEngineState(0, 777, 0, 0, false));
        var adjacentB = place(helper, new BlockPos(2, 1, 6), new SteamEngineState(0, 333, 0, 0, false));
        tick(adjacentA, level);
        tick(adjacentB, level);
        helper.assertValueEqual(adjacentA.snapshot().energy(), 777L, "Neighboring generators cannot circulate energy into each other");
        helper.assertValueEqual(adjacentB.snapshot().energy(), 333L, "A generator never advertises a fake sink endpoint");
        for (int scenario = 0; scenario < 4; scenario++) {
            int fault = scenario;
            var guarded = place(helper, new BlockPos(1, 1, 1), new SteamEngineState(0, 777, 0, 0, false));
            BlockPos target = guarded.getBlockPos().above();
            level.setBlockAndUpdate(target, receiverBlock.defaultBlockState());
            var targetStorage = new SimpleEnergyStorage(1_000_000, Long.MAX_VALUE, 0) {
                @Override public long insert(long maximum, TransactionContext transaction) {
                    long accepted = super.insert(maximum, transaction);
                    if (fault == 0) level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                    if (fault == 1) level.setBlockAndUpdate(guarded.getBlockPos(), Blocks.STONE.defaultBlockState());
                    if (fault == 3) guarded.setSideMode(ConduitKind.ENERGY, Direction.UP, EngineSideMode.DISABLED);
                    return fault == 2 ? accepted + 1 : accepted;
                }
            };
            RECEIVERS.put(new Endpoint(level, target), new Receiver(Direction.DOWN, targetStorage, new HashSet<>()));
            tick(guarded, level);
            helper.assertValueEqual(guarded.snapshot().energy(), 777L,
                    "Replacement, overreporting, or disabling the source side mid-transfer cannot debit energy: scenario " + fault);
            helper.assertValueEqual(targetStorage.amount, 0L,
                    "A rejected callback transfer rolls back the destination too: scenario " + fault);
            RECEIVERS.remove(new Endpoint(level, target));
            level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
        }
        helper.runAfterDelay(4, () -> {
            long received = receivers.stream().mapToLong(receiver -> receiver.storage().amount).sum();
            helper.assertValueEqual(engine.snapshot().energy() + received, 10_000L, "Natural repeated source ticks preserve all energy");
            helper.assertTrue(received <= rate * (level.getGameTime() - start + 1), "Automatic pushes cannot multiply the configured rate by side count");
            for (Receiver receiver : receivers) helper.assertValueEqual(receiver.queried(), Set.of(receiver.face()),
                    "Rotating source iteration visits every loaded neighbor through its opposite face");
            for (Direction side : Direction.values()) RECEIVERS.remove(new Endpoint(level, engine.getBlockPos().relative(side)));
            helper.succeed();
        });
    }

    public static void persistenceAndBreaks(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = player(helper);
        player.setYRot(180);
        var saved = new SteamEngineState(27_027, 12_345, 1_501, 1_600, false);
        ItemStack namedFuel = new ItemStack(Items.COAL, 7);
        namedFuel.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved engine fuel"));
        ItemStack expected = new ItemStack(AutomationRegistry.STEAM_ENGINE_ITEM);
        expected.set(SteamEngineComponents.STATE, saved);
        expected.set(SteamEngineComponents.SIDES, SteamEngineSides.DEFAULT
                .with(ConduitKind.ITEM, Direction.UP, EngineSideMode.INPUT)
                .with(ConduitKind.FLUID, Direction.WEST, EngineSideMode.OUTPUT)
                .with(ConduitKind.ENERGY, Direction.EAST, EngineSideMode.DISABLED));
        expected.set(DataComponents.CUSTOM_NAME, Component.literal("Persistent boiler"));
        expected.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(namedFuel,
                new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.BUCKET, 2), new ItemStack(Items.BUCKET, 3))));
        expected = roundTrip(level, expected);
        BlockPos position = helper.absolutePos(new BlockPos(2, 1, 2));
        placeWithItem(helper, player, position, expected.copy());
        var engine = (SteamEngineBlockEntity) level.getBlockEntity(position);
        helper.assertTrue(engine != null, "Native BlockItem placement creates the registered steam engine");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "Placement consumes exactly one stateful engine item");
        helper.assertValueEqual(level.getBlockState(position).getValue(SteamEngineBlock.FACING), Direction.SOUTH, "The engine faces its placing player");
        assertStack(helper, engine.dropStack(), expected, "Implicit item components restore every owned resource and physical stack");
        var loaded = (SteamEngineBlockEntity) BlockEntity.loadStatic(position, engine.getBlockState(),
                engine.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
        helper.assertTrue(loaded != null, "The registered block entity decodes its actual saved data");
        assertStack(helper, loaded.dropStack(), expected, "Disk save/load preserves exact contents, fractional droplets, FE and unfinished fuel");
        var stale = engine.energyStorage(Direction.NORTH);
        level.destroyBlock(position, true, player);
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(position).inflate(.1));
        helper.assertValueEqual(drops.size(), 1, "Breaking produces one engine without separately spilling duplicate inputs or outputs");
        assertStack(helper, drops.getFirst().getItem(), expected, "The stateful break drop retains all resources");
        ItemStack recovered = drops.getFirst().getItem().copy();
        drops.getFirst().discard();
        helper.assertValueEqual(stale.getAmount(), 0L, "Breaking invalidates retained external endpoints");
        placeWithItem(helper, player, position, recovered);
        var replaced = (SteamEngineBlockEntity) level.getBlockEntity(position);
        assertStack(helper, replaced.dropStack(), expected, "Re-placing the real break drop restores exactly the original state");
        player.setGameMode(GameType.CREATIVE);
        helper.assertTrue(player.gameMode.destroyBlock(position), "The native creative destruction path succeeds");
        drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(position).inflate(.1));
        helper.assertValueEqual(drops.size(), 1, "Creative destruction of a populated engine preserves one stateful item");
        assertStack(helper, drops.getFirst().getItem(), expected, "Creative destruction does not scatter or erase resources");
        drops.getFirst().discard();
        helper.succeed();
    }

    public static void menuAuthorityAndCounters(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        var custom = new AutomationConfig.Engine(1_000_000, 900_000_000_000L, 1, 40, 256, 1_000);
        BackpackConfig.configure(new ServerConfig(previous.format(), previous.capacities(), previous.storage(), previous.capture(),
                previous.carriers(), previous.chestLoot(), previous.upgrades(), new AutomationConfig(previous.automation().conduits(), custom)));
        try (Peer peer = peer(helper)) {
            var player = peer.player;
            var engine = place(helper, new BlockPos(3, 1, 3), new SteamEngineState(6_553_701, 712_345_678_901L, 70_001, 80_003, false));
            engine.setItem(0, new ItemStack(Items.COAL, 3));
            player.setPos(Vec3.atCenterOf(engine.getBlockPos()).add(0, 0, 1.5));
            helper.assertTrue(SteamEngineMenus.open(player, engine), "The real server opens the registered extended menu");
            var menu = (SteamEngineMenu) player.containerMenu;
            peer.channel.runPendingTasks();
            var mirror = new SteamEngineMenu(menu.containerId, player.getInventory(), engine.getBlockPos());
            Set<Integer> fields = new HashSet<>();
            for (var packet : peer.data) if (packet.getContainerId() == menu.containerId) {
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    ClientboundContainerSetDataPacket.STREAM_CODEC.encode(buffer, packet);
                    var decoded = ClientboundContainerSetDataPacket.STREAM_CODEC.decode(buffer);
                    mirror.setData(decoded.getId(), decoded.getValue());
                    fields.add(decoded.getId());
                } finally { buffer.release(); }
            }
            helper.assertValueEqual(fields.size(), SteamEngineMenu.DATA_COUNT, "The actual menu sends every counter word through vanilla data packets");
            helper.assertValueEqual(mirror.waterDroplets(), engine.snapshot().waterDroplets(), "Signed short packet words preserve fractional water");
            helper.assertValueEqual(mirror.waterCapacityDroplets(), mb(custom.waterCapacityMb()), "Large water capacity survives native synchronization");
            helper.assertValueEqual(mirror.energy(), engine.snapshot().energy(), "Energy beyond signed32-bit survives actual packet codec round trips");
            helper.assertValueEqual(mirror.energyCapacity(), custom.energyCapacity(), "Configured long FE capacity survives actual packet codec round trips");
            helper.assertValueEqual(mirror.burnRemaining(), 70_001, "Fuel work beyond a signed short survives synchronization");
            helper.assertValueEqual(mirror.burnDuration(), 80_003, "Full fuel duration survives synchronization");
            helper.assertFalse(mirror.enabled(), "The initial enabled preference synchronizes exactly");
            var before = engine.dropStack();
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId + 1, 0));
            assertStack(helper, engine.dropStack(), before, "A stale native menu ID cannot toggle the engine");
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, 99));
            assertStack(helper, engine.dropStack(), before, "An unknown native button cannot alter resource state");
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, 0));
            helper.assertTrue(engine.enabled(), "The valid native button toggles only the current server machine");
            helper.assertValueEqual(engine.snapshot().energy(), 712_345_678_901L, "Enabling does not create or delete stored energy");
            menu.clicked(0, 0, ClickType.PICKUP, player);
            assertStack(helper, menu.getCarried(), Items.COAL, 3, "A valid native slot pickup removes the exact fuel stack");
            menu.clicked(0, 0, ClickType.PICKUP, player);
            helper.assertTrue(menu.getCarried().isEmpty(), "Returning fuel clears the cursor");
            assertStack(helper, engine.getItem(0), Items.COAL, 3, "Returning fuel restores the exact physical input");
            player.getInventory().setItem(9, new ItemStack(Items.WATER_BUCKET));
            menu.quickMoveStack(player, menuSlot(menu, player.getInventory(), 9));
            assertStack(helper, engine.getItem(1), Items.WATER_BUCKET, 1, "Native shift movement routes water containers into the water input");
            helper.assertTrue(player.getInventory().getItem(9).isEmpty(), "Shift movement does not duplicate the water bucket");
            player.setGameMode(GameType.SPECTATOR);
            var spectatorBefore = engine.dropStack();
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, 0));
            menu.clicked(0, 0, ClickType.PICKUP, player);
            helper.assertTrue(menu.quickMoveStack(player, 0).isEmpty(), "Spectators cannot extract through direct shift actions");
            assertStack(helper, engine.dropStack(), spectatorBefore, "Spectator buttons and slot actions preserve the exact machine state");
            helper.assertTrue(menu.getCarried().isEmpty(), "Spectator interactions cannot put machine items on the cursor");
            player.setGameMode(GameType.SURVIVAL);
            var beforeShrink = engine.dropStack();
            BackpackConfig.configure(previous);
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(engine.fluidStorage(null).insert(WATER, 1, transaction), 0L,
                        "A smaller configured tank does not accept more water above its new bound");
                transaction.commit();
            }
            tick(engine, helper.getLevel());
            assertStack(helper, engine.dropStack(), beforeShrink,
                    "A capacity reduction preserves oversized saved resources and cannot spend fuel while the energy buffer is overfull");
            player.closeContainer();
            helper.assertTrue(menu.quickMoveStack(player, 0).isEmpty(), "A closed menu cannot continue extracting fuel");
            helper.assertFalse(menu.clickMenuButton(player, 0), "A closed menu cannot continue toggling the engine");
            helper.getLevel().setBlockAndUpdate(engine.getBlockPos(), Blocks.STONE.defaultBlockState());
            helper.assertFalse(menu.stillValid(player), "Replacing the physical block invalidates the old menu");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void sideConfigurationAndTransactions(GameTestHelper helper) {
        var level = helper.getLevel();
        var engine = place(helper, new BlockPos(3, 1, 3), new SteamEngineState(1_000, 10_000, 0, 0, false));
        engine.setItem(0, new ItemStack(Items.COAL, 3));
        engine.setItem(2, new ItemStack(Items.BUCKET, 12));
        for (Direction side : Direction.values()) {
            var items = ItemStorage.SIDED.find(level, engine.getBlockPos(), side);
            var water = FluidStorage.SIDED.find(level, engine.getBlockPos(), side);
            var energy = EnergyStorage.SIDED.find(level, engine.getBlockPos(), side);
            helper.assertTrue(items instanceof SlottedStorage<?> && water != null && energy != null,
                    "Every physical engine face exposes standard live storage adapters");
            var slots = (SlottedStorage<ItemVariant>) items;
            var fuelView = slots.getSlot(0);
            var outputView = slots.getSlot(2);
            var waterView = water.iterator().next();
            for (ConduitKind kind : ConduitKind.values())
                helper.assertTrue(engine.setSideMode(kind, side, EngineSideMode.DISABLED), "An initially open face can be disabled");
            helper.assertFalse(items.supportsInsertion() || items.supportsExtraction() || water.supportsInsertion()
                    || water.supportsExtraction() || energy.supportsInsertion() || energy.supportsExtraction(),
                    "Already-held adapters revoke both capabilities immediately on " + side);
            helper.assertTrue(fuelView.isResourceBlank() && outputView.isResourceBlank() && waterView.isResourceBlank(),
                    "Already-held disabled views do not expose their previously visible resources");
            helper.assertValueEqual(fuelView.getAmount() + outputView.getAmount() + waterView.getAmount() + energy.getAmount(), 0L,
                    "Disabled retained views report no accessible quantities");
            helper.assertValueEqual(fuelView.getCapacity() + outputView.getCapacity() + waterView.getCapacity() + energy.getCapacity(), 0L,
                    "Disabled retained views report no accessible capacity");
            helper.assertValueEqual(engine.getSlotsForFace(side).length, 0, "Vanilla hopper slot discovery also respects disabled faces");
            var closed = engine.dropStack();
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.COAL), 1, transaction), 0L, "Disabled items cannot insert");
                helper.assertValueEqual(outputView.extract(ItemVariant.of(Items.BUCKET), 1, transaction), 0L, "Disabled retained item views cannot extract");
                helper.assertValueEqual(water.insert(WATER, 1, transaction), 0L, "Disabled water cannot insert");
                helper.assertValueEqual(waterView.extract(WATER, 1, transaction), 0L, "Disabled retained fluid views cannot extract");
                helper.assertValueEqual(energy.extract(1, transaction), 0L, "Disabled energy cannot extract");
                transaction.commit();
            }
            assertStack(helper, engine.dropStack(), closed, "Disabled API attempts preserve every component");

            engine.setSideMode(ConduitKind.ITEM, side, EngineSideMode.INPUT);
            engine.setSideMode(ConduitKind.FLUID, side, EngineSideMode.INPUT);
            engine.setSideMode(ConduitKind.ENERGY, side, EngineSideMode.OUTPUT);
            helper.assertTrue(items.supportsInsertion() && !items.supportsExtraction() && water.supportsInsertion()
                    && !water.supportsExtraction() && energy.supportsExtraction(), "The same handles adopt their new directional permissions");
            helper.assertValueEqual(fuelView.getAmount(), 3L, "Reopened input view sees the unchanged physical fuel");
            helper.assertValueEqual(outputView.getAmount(), 0L, "An input-only item face hides output slots");
            helper.assertTrue(engine.canPlaceItemThroughFace(0, new ItemStack(Items.COAL), side)
                    && !engine.canTakeItemThroughFace(2, new ItemStack(Items.BUCKET), side),
                    "The native hopper contract matches input-only API permissions");
            var beforeAbort = engine.dropStack();
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(fuelView.insert(ItemVariant.of(Items.COAL), 1, outer), 1L, "The retained input slot admits valid fuel");
                helper.assertValueEqual(water.insert(WATER, 7, outer), 7L, "Input water retains exact droplets");
                helper.assertValueEqual(waterView.extract(WATER, 1, outer), 0L, "An input-only fluid view cannot bypass its extraction gate");
                try (Transaction nested = outer.openNested()) {
                    helper.assertValueEqual(energy.extract(11, nested), 11L, "A reopened output face participates in nested resource transactions");
                    nested.commit();
                }
            }
            assertStack(helper, engine.dropStack(), beforeAbort, "Outer abort restores item, water, energy and output allowance after nested commit");

            engine.setSideMode(ConduitKind.ITEM, side, EngineSideMode.OUTPUT);
            engine.setSideMode(ConduitKind.FLUID, side, EngineSideMode.OUTPUT);
            helper.assertTrue(!items.supportsInsertion() && items.supportsExtraction() && !water.supportsInsertion()
                    && water.supportsExtraction(), "Output-only modes change existing handles without relookup");
            helper.assertValueEqual(fuelView.getAmount(), 0L, "An output-only face hides its unconsumed fuel");
            helper.assertTrue(!engine.canPlaceItemThroughFace(0, new ItemStack(Items.COAL), side)
                    && engine.canTakeItemThroughFace(2, new ItemStack(Items.BUCKET), side),
                    "The native hopper contract matches output-only permissions");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(fuelView.insert(ItemVariant.of(Items.COAL), 1, transaction), 0L, "Retained input views cannot bypass output-only mode");
                helper.assertValueEqual(water.insert(WATER, 1, transaction), 0L, "Output-only water rejects insertion");
                helper.assertValueEqual(outputView.extract(ItemVariant.of(Items.BUCKET), 1, transaction), 1L, "The same output view can extract after reopening");
                helper.assertValueEqual(waterView.extract(WATER, 13, transaction), 13L, "Fluid extraction conserves fractions on every physical side");
                helper.assertValueEqual(energy.extract(11, transaction), 11L, "Every side spends only its actual share of output allowance");
                helper.assertValueEqual(energy.insert(11, transaction), 0L, "An engine cannot be turned into an energy sink");
                transaction.commit();
            }
            engine.setSideMode(ConduitKind.ITEM, side, EngineSideMode.BOTH);
            engine.setSideMode(ConduitKind.FLUID, side, EngineSideMode.BOTH);
        }
        assertStack(helper, engine.getItem(0), Items.COAL, 3, "Aborted input operations never retain extra fuel");
        assertStack(helper, engine.getItem(2), Items.BUCKET, 6, "Six committed output operations remove exactly six buckets");
        helper.assertValueEqual(engine.snapshot().waterDroplets(), 922L, "Committed side operations preserve the exact remaining droplets");
        helper.assertValueEqual(engine.snapshot().energy(), 9_934L, "Committed side operations preserve the exact remaining FE");

        var unsidedItems = ItemStorage.SIDED.find(level, engine.getBlockPos(), null);
        var unsidedWater = FluidStorage.SIDED.find(level, engine.getBlockPos(), null);
        var unsidedEnergy = EnergyStorage.SIDED.find(level, engine.getBlockPos(), null);
        var unsidedOutput = ((SlottedStorage<ItemVariant>) unsidedItems).getSlot(2);
        var unsidedWaterView = unsidedWater.iterator().next();
        for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values())
            engine.setSideMode(kind, side, EngineSideMode.DISABLED);
        helper.assertFalse(unsidedItems.supportsInsertion() || unsidedItems.supportsExtraction() || unsidedWater.supportsInsertion()
                || unsidedWater.supportsExtraction() || unsidedEnergy.supportsExtraction(), "Unsided access cannot bypass six disabled faces");
        helper.assertValueEqual(unsidedOutput.getAmount() + unsidedWaterView.getAmount() + unsidedEnergy.getAmount(), 0L,
                "Previously retained unsided views become inert too");
        var allClosed = engine.dropStack();
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(unsidedItems.insert(ItemVariant.of(Items.COAL), 1, transaction), 0L, "Closed unsided items reject insertion");
            helper.assertValueEqual(unsidedOutput.extract(ItemVariant.of(Items.BUCKET), 1, transaction), 0L, "Closed unsided output rejects extraction");
            helper.assertValueEqual(unsidedWater.insert(WATER, 1, transaction), 0L, "Closed unsided water rejects insertion");
            helper.assertValueEqual(unsidedWaterView.extract(WATER, 1, transaction), 0L, "Closed unsided water view rejects extraction");
            helper.assertValueEqual(unsidedEnergy.extract(1, transaction), 0L, "Closed unsided energy rejects extraction");
            transaction.commit();
        }
        assertStack(helper, engine.dropStack(), allClosed, "All-disabled unsided attempts preserve the complete machine");
        engine.setSideMode(ConduitKind.ITEM, Direction.UP, EngineSideMode.BOTH);
        engine.setSideMode(ConduitKind.FLUID, Direction.UP, EngineSideMode.BOTH);
        engine.setSideMode(ConduitKind.ENERGY, Direction.UP, EngineSideMode.OUTPUT);
        helper.assertTrue(unsidedItems.supportsInsertion() && unsidedItems.supportsExtraction() && unsidedWater.supportsInsertion()
                && unsidedWater.supportsExtraction() && unsidedEnergy.supportsExtraction(), "A single reopened face revives the same aggregate handles");
        helper.assertValueEqual(unsidedOutput.getAmount(), 6L, "Reopening never deletes hidden remainder items");
        helper.assertValueEqual(unsidedWaterView.getAmount(), 922L, "Reopening never deletes hidden water");
        helper.assertValueEqual(unsidedEnergy.getAmount(), 9_934L, "Reopening never deletes hidden energy");
        var reopened = engine.dropStack();
        helper.assertFalse(engine.setSideMode(ConduitKind.ENERGY, Direction.UP, EngineSideMode.INPUT)
                || engine.setSideMode(ConduitKind.ENERGY, Direction.UP, EngineSideMode.BOTH), "Even direct mode setters reject energy input");
        assertStack(helper, engine.dropStack(), reopened, "Invalid generator modes leave the complete machine unchanged");
        level.setBlockAndUpdate(engine.getBlockPos(), Blocks.STONE.defaultBlockState());
        helper.assertValueEqual(unsidedOutput.getAmount() + unsidedWaterView.getAmount() + unsidedEnergy.getAmount(), 0L,
                "Retained side-aware views remain fenced after physical replacement");

        var internal = place(helper, new BlockPos(1, 1, 1), SteamEngineState.EMPTY);
        for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values())
            internal.setSideMode(kind, side, EngineSideMode.DISABLED);
        internal.setItem(0, new ItemStack(Items.COAL));
        internal.setItem(1, new ItemStack(Items.WATER_BUCKET));
        tick(internal, level);
        var rules = BackpackConfig.get().automation().engine();
        helper.assertValueEqual(internal.snapshot().waterDroplets(), FluidConstants.BUCKET - mb(rules.waterMbPerTick()),
                "Closed external ports do not block the engine's own water-container operation");
        helper.assertValueEqual(internal.snapshot().energy(), rules.energyPerTick(), "Closed energy ports still allow internal finite generation");
        helper.assertValueEqual(internal.snapshot().burnRemaining(), net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.getFuel().getOrDefault(Items.COAL, 0) - 1,
                "Closed item ports still use exactly one unit of vanilla fuel work");
        helper.assertTrue(internal.getItem(0).isEmpty() && internal.getItem(1).isEmpty(), "Internal work consumes only the actual input items");
        assertStack(helper, internal.getItem(3), Items.BUCKET, 1, "Internal water work preserves its real bucket remainder");
        helper.succeed();
    }

    public static void sideMenuAuthority(GameTestHelper helper) {
        try (Peer peer = peer(helper)) {
            var player = peer.player;
            var engine = place(helper, new BlockPos(3, 1, 3), new SteamEngineState(27_027, 12_345, 701, 1_600, false));
            engine.setItem(0, new ItemStack(Items.COAL, 7));
            player.setPos(Vec3.atCenterOf(engine.getBlockPos()).add(0, 0, 1.5));
            helper.assertTrue(SteamEngineMenus.openSides(player, engine, Direction.EAST), "A real native side menu opens for an accessible engine");
            var menu = (SteamEngineSideMenu) player.containerMenu;
            helper.assertValueEqual(menu.slots.size(), 0, "The side menu exposes no inventory or resource counters");
            peer.channel.runPendingTasks();
            var mirror = new SteamEngineSideMenu(menu.containerId, player.getInventory(), engine.getBlockPos());
            Set<Integer> fields = new HashSet<>();
            for (var packet : peer.data) if (packet.getContainerId() == menu.containerId) {
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    ClientboundContainerSetDataPacket.STREAM_CODEC.encode(buffer, packet);
                    var decoded = ClientboundContainerSetDataPacket.STREAM_CODEC.decode(buffer);
                    helper.assertTrue(decoded.getId() >= 0 && decoded.getId() < SteamEngineSideMenu.DATA_COUNT,
                            "Only the selected face and eighteen permission values travel through the menu");
                    mirror.setData(decoded.getId(), decoded.getValue());
                    fields.add(decoded.getId());
                } finally { buffer.release(); }
            }
            helper.assertValueEqual(fields.size(), SteamEngineSideMenu.DATA_COUNT, "Actual vanilla data packets carry every side preference");
            helper.assertValueEqual(mirror.selectedFace(), Direction.EAST, "The initially clicked physical face survives native synchronization");
            for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values())
                helper.assertValueEqual(mirror.mode(kind, side), engine.sideMode(kind, side), "All native mode words preserve their exact meanings");
            var before = engine.dropStack();
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId + 1, 10));
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, 99));
            assertStack(helper, engine.dropStack(), before, "Stale IDs and unknown side actions cannot mutate a machine");
            for (Direction side : Direction.values()) {
                player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, side.ordinal()));
                helper.assertValueEqual(menu.selectedFace(), side, "Actual native buttons select the requested physical face");
            }
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, Direction.EAST.ordinal()));
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, 10 + ConduitKind.ITEM.ordinal()));
            helper.assertValueEqual(engine.sideMode(ConduitKind.ITEM, Direction.EAST), EngineSideMode.DISABLED,
                    "The actual item control cycles the selected face from Both to Disabled");
            helper.assertValueEqual(engine.sideMode(ConduitKind.ITEM, Direction.WEST), EngineSideMode.BOTH,
                    "Changing one side cannot change the opposite side");
            helper.assertValueEqual(engine.sideMode(ConduitKind.FLUID, Direction.EAST), EngineSideMode.BOTH,
                    "Changing items cannot change fluid permissions");
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, 10 + ConduitKind.ENERGY.ordinal()));
            helper.assertValueEqual(engine.sideMode(ConduitKind.ENERGY, Direction.EAST), EngineSideMode.DISABLED, "Energy Output can be disabled through its real button");
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, 10 + ConduitKind.ENERGY.ordinal()));
            helper.assertValueEqual(engine.sideMode(ConduitKind.ENERGY, Direction.EAST), EngineSideMode.OUTPUT,
                    "A disabled energy face reopens without ever entering an input mode");
            before.set(SteamEngineComponents.SIDES, engine.sideConfig());
            assertStack(helper, engine.dropStack(), before, "Side controls change only the permission component, never contents, amounts or burn work");
            var other = player(helper);
            other.setPos(player.position());
            helper.assertFalse(menu.clickMenuButton(other, 10), "A different player cannot mutate another owner's open side menu");
            player.setGameMode(GameType.SPECTATOR);
            var spectator = engine.dropStack();
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu.containerId, 10));
            helper.assertFalse(menu.clickMenuButton(player, 10), "Spectators cannot invoke side actions directly either");
            assertStack(helper, engine.dropStack(), spectator, "Spectator side requests preserve the exact machine");
            player.setGameMode(GameType.SURVIVAL);
            player.closeContainer();
            helper.assertFalse(menu.clickMenuButton(player, 10), "An old closed side menu has no lingering authority");

            for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values())
                engine.setSideMode(kind, side, EngineSideMode.DISABLED);
            helper.assertTrue(SteamEngineMenus.openSides(player, engine, Direction.DOWN), "Disabling all ports does not make machine configuration unreachable");
            var closedPortsMenu = (SteamEngineSideMenu) player.containerMenu;
            player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(closedPortsMenu.containerId, 10 + ConduitKind.FLUID.ordinal()));
            helper.assertValueEqual(engine.sideMode(ConduitKind.FLUID, Direction.DOWN), EngineSideMode.INPUT,
                    "The native menu reopens a fully disabled machine's selected fluid face");
            var beforeDistance = engine.dropStack();
            player.setPos(player.position().add(20, 0, 0));
            helper.assertFalse(closedPortsMenu.clickMenuButton(player, 10), "Remote players cannot edit a previously opened engine");
            assertStack(helper, engine.dropStack(), beforeDistance, "Out-of-range side actions preserve the exact machine");
            helper.getLevel().setBlockAndUpdate(engine.getBlockPos(), Blocks.STONE.defaultBlockState());
            helper.assertFalse(closedPortsMenu.stillValid(player), "A replaced engine invalidates the side menu");
            helper.assertFalse(closedPortsMenu.clickMenuButton(player, 10), "A stale side menu cannot edit a replacement block");
        }
        helper.succeed();
    }

    public static void sideOutputConnections(GameTestHelper helper) {
        var level = helper.getLevel();
        var engine = place(helper, new BlockPos(2, 2, 3), new SteamEngineState(0, 4_096, 0, 0, false));
        for (Direction side : Direction.values()) engine.setSideMode(ConduitKind.ENERGY, side, EngineSideMode.DISABLED);
        BlockPos pipePosition = engine.getBlockPos().east();
        BlockPos receiverPosition = pipePosition.east();
        level.setBlockAndUpdate(receiverPosition, receiverBlock.defaultBlockState());
        var receiver = new Receiver(Direction.WEST, new SimpleEnergyStorage(1_000_000, 17, 0), new HashSet<>());
        RECEIVERS.put(new Endpoint(level, receiverPosition), receiver);
        level.setBlockAndUpdate(pipePosition, AutomationRegistry.CONDUIT_BUNDLE.defaultBlockState());
        var pipe = (ConduitBundleBlockEntity) level.getBlockEntity(pipePosition);
        helper.assertTrue(pipe.install(ConduitKind.ENERGY), "The real neighboring conduit installs its energy route");
        for (Direction side : Direction.values()) pipe.setMode(ConduitKind.ENERGY, side,
                side == Direction.WEST ? ConduitMode.EXTRACT : side == Direction.EAST ? ConduitMode.INSERT : ConduitMode.DISABLED);
        var retainedSide = EnergyStorage.SIDED.find(level, engine.getBlockPos(), Direction.EAST);
        var retainedAggregate = EnergyStorage.SIDED.find(level, engine.getBlockPos(), null);
        helper.assertFalse(retainedSide.supportsExtraction() || retainedAggregate.supportsExtraction(), "Closed machine ports advertise no output");
        helper.assertFalse(pipe.visualState().connected(ConduitKind.ENERGY, Direction.WEST), "The actual conduit cannot connect to a disabled engine face");
        engine.setSideMode(ConduitKind.ENERGY, Direction.EAST, EngineSideMode.OUTPUT);
        helper.assertTrue(retainedSide.supportsExtraction() && retainedAggregate.supportsExtraction(), "Reopening updates both retained API handles");
        helper.assertTrue(pipe.visualState().connected(ConduitKind.ENERGY, Direction.WEST), "The engine's neighbor notification immediately refreshes the conduit capability");
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(receiver.storage().amount > 0, "Natural engine and conduit ticks deliver energy after reopening");
            helper.assertValueEqual(receiver.storage().amount + engine.snapshot().energy(), 4_096L, "Reopened external output conserves source plus receiver");
            engine.setSideMode(ConduitKind.ENERGY, Direction.EAST, EngineSideMode.DISABLED);
            helper.assertFalse(pipe.visualState().connected(ConduitKind.ENERGY, Direction.WEST), "Closing the side removes the live conduit connection without replacing either block");
            helper.assertFalse(retainedSide.supportsExtraction() || retainedAggregate.supportsExtraction(), "Closing the only active side revokes held handles again");
            long stopped = receiver.storage().amount;
            helper.runAfterDelay(5, () -> {
                helper.assertValueEqual(receiver.storage().amount, stopped, "Neither automatic push nor conduit pull can bypass a disabled face");
                helper.assertValueEqual(engine.snapshot().energy(), 4_096L - stopped, "Stopping output never drains or deletes source energy");
                engine.setSideMode(ConduitKind.ENERGY, Direction.EAST, EngineSideMode.OUTPUT);
                helper.runAfterDelay(12, () -> {
                    helper.assertTrue(receiver.storage().amount > stopped, "Re-enabling the same physical interface resumes the existing network");
                    helper.assertValueEqual(receiver.storage().amount + engine.snapshot().energy(), 4_096L, "Repeated disable/re-enable cycles retain exact total energy");
                    RECEIVERS.remove(new Endpoint(level, receiverPosition));
                    helper.succeed();
                });
            });
        });
    }

    private static long mb(long value) { return value * (FluidConstants.BUCKET / 1_000); }
    private static void tick(SteamEngineBlockEntity engine, ServerLevel level) {
        SteamEngineBlockEntity.tick(level, engine.getBlockPos(), engine.getBlockState(), engine);
    }
    private static SteamEngineBlockEntity place(GameTestHelper helper, BlockPos relative, SteamEngineState state) {
        BlockPos position = helper.absolutePos(relative);
        helper.getLevel().setBlockAndUpdate(position, AutomationRegistry.STEAM_ENGINE.defaultBlockState());
        var engine = (SteamEngineBlockEntity) helper.getLevel().getBlockEntity(position);
        ItemStack source = new ItemStack(AutomationRegistry.STEAM_ENGINE_ITEM);
        source.set(SteamEngineComponents.STATE, state);
        engine.applyComponentsFromItemStack(source);
        return engine;
    }
    private static void placeWithItem(GameTestHelper helper, ServerPlayer player, BlockPos position, ItemStack item) {
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        var hit = new BlockHitResult(Vec3.atCenterOf(position.below()).add(0, .5, 0), Direction.UP, position.below(), false);
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private static Peer peer(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(id, "steam_" + id.toString().substring(0, 8)), false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        var peer = new Peer(player, connection, channel);
        channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                if (message instanceof ClientboundContainerSetDataPacket data) peer.data.add(data);
                super.write(context, message, promise);
            }
        });
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return peer;
    }
    private static final class Peer implements AutoCloseable {
        final ServerPlayer player;
        final Connection connection;
        final EmbeddedChannel channel;
        final List<ClientboundContainerSetDataPacket> data = new ArrayList<>();
        Peer(ServerPlayer player, Connection connection, EmbeddedChannel channel) {
            this.player = player; this.connection = connection; this.channel = channel;
        }
        @Override public void close() {
            player.closeContainer();
            connection.disconnect(Component.literal("Steam engine test finished"));
            connection.handleDisconnection();
            channel.finishAndReleaseAll();
        }
    }
}
