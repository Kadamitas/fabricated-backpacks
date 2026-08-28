package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlock;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilter;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterMode;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitGeometry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenu;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMode;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitNetworks;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitRedstone;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.resource.BackpackTank;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyStorage;

import java.util.EnumSet;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Actual blocks and public sided APIs. Players and the explicitly marked chunk callback are server fixtures. */
public final class ConduitGameTests {
    private static final FluidVariant WATER = FluidVariant.of(Fluids.WATER);
    private static final FluidVariant BACKPACK_WATER = FluidVariant.of(Fluids.WATER, DataComponentPatch.builder()
            .set(DataComponents.CUSTOM_NAME, Component.literal("Conduit water sample")).build());
    private static final FluidVariant BACKPACK_LAVA = FluidVariant.of(Fluids.LAVA);
    private static final long BACKPACK_WATER_AMOUNT = mb(300) + 17;
    private static final long BACKPACK_LAVA_AMOUNT = mb(200) + 23;
    private static MachineBlock machineBlock;
    private static BlockEntityType<Machine> machineType;
    private static final Set<BlockPos> PROTECTED_MINING = new HashSet<>();
    private ConduitGameTests() {}

    public static void registerFixtures() {
        if (machineBlock != null) return;
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("fabricated_backpacks_tests", "conduit_machine");
        machineBlock = Registry.register(BuiltInRegistries.BLOCK, id,
                new MachineBlock(Block.Properties.of()));
        machineType = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id,
                FabricBlockEntityTypeBuilder.create(Machine::new, machineBlock).build());
        ItemStorage.SIDED.registerForBlockEntity((machine, side) -> machine.itemEnabled && machine.itemSides.contains(side)
                ? machine.itemHandler == null ? machine.itemApi : machine.itemHandler.get() : null, machineType);
        FluidStorage.SIDED.registerForBlockEntity((machine, side) -> machine.fluidEnabled && machine.fluidSides.contains(side)
                ? machine.fluidHandler == null ? machine.fluidApi : machine.fluidHandler.get() : null, machineType);
        EnergyStorage.SIDED.registerForBlockEntity((machine, side) -> machine.energyEnabled && machine.energySides.contains(side)
                ? machine.energyHandler == null ? machine.energyApi : machine.energyHandler.apply(side) : null, machineType);
        PlayerBlockBreakEvents.BEFORE.register((level, player, position, state, entity) -> !PROTECTED_MINING.contains(position));
    }

    private static final class MachineBlock extends BaseEntityBlock {
        private static final MapCodec<MachineBlock> CODEC = simpleCodec(MachineBlock::new);
        MachineBlock(Properties properties) { super(properties); }
        @Override protected MapCodec<MachineBlock> codec() { return CODEC; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Machine(pos, state); }
        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide ? null : createTickerHelper(type, machineType, (world, position, blockState, machine) -> {
                Runnable action = machine.nextTick;
                machine.nextTick = null;
                if (action != null) action.run();
            });
        }
    }
    private static final class ItemBuffer extends SimpleContainer {
        Predicate<ItemStack> accepts = item -> true;
        ItemBuffer() { super(4); }
        @Override public boolean canPlaceItem(int slot, ItemStack item) { return accepts.test(item); }
    }
    private static final class Tank extends SingleVariantStorage<FluidVariant> {
        long capacity = FluidConstants.BUCKET * 8;
        @Override protected FluidVariant getBlankVariant() { return FluidVariant.blank(); }
        @Override protected long getCapacity(FluidVariant variant) { return capacity; }
    }
    private static final class Energy extends SimpleEnergyStorage {
        long acceptance = Long.MAX_VALUE;
        Runnable afterInsert = () -> {};
        Energy() { super(100_000, Long.MAX_VALUE, Long.MAX_VALUE); }
        @Override public long insert(long maximum, TransactionContext transaction) {
            long accepted = super.insert(Math.min(maximum, acceptance), transaction);
            if (accepted > 0) afterInsert.run();
            return accepted;
        }
    }
    private static final class ExportGate<T> implements Storage<T> {
        final Storage<T> delegate;
        final BooleanSupplier exports;
        ExportGate(Storage<T> delegate, BooleanSupplier exports) { this.delegate = delegate; this.exports = exports; }
        @Override public boolean supportsExtraction() { return exports.getAsBoolean(); }
        @Override public long insert(T resource, long maximum, TransactionContext transaction) { return delegate.insert(resource, maximum, transaction); }
        @Override public long extract(T resource, long maximum, TransactionContext transaction) {
            return exports.getAsBoolean() ? delegate.extract(resource, maximum, transaction) : 0;
        }
        @Override public Iterator<StorageView<T>> iterator() {
            Iterator<StorageView<T>> backing = delegate.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return backing.hasNext(); }
                @Override public StorageView<T> next() {
                    StorageView<T> view = backing.next();
                    return new StorageView<>() {
                        @Override public boolean isResourceBlank() { return view.isResourceBlank(); }
                        @Override public T getResource() { return view.getResource(); }
                        @Override public long getAmount() { return view.getAmount(); }
                        @Override public long getCapacity() { return view.getCapacity(); }
                        @Override public long extract(T resource, long maximum, TransactionContext transaction) {
                            return exports.getAsBoolean() ? view.extract(resource, maximum, transaction) : 0;
                        }
                    };
                }
            };
        }
    }
    private static final class Machine extends BlockEntity {
        final ItemBuffer items = new ItemBuffer();
        final InventoryStorage itemStorage = InventoryStorage.of(items, null);
        final Tank tank = new Tank();
        final Energy energy = new Energy();
        boolean itemEnabled, fluidEnabled, energyEnabled;
        Supplier<Storage<ItemVariant>> itemHandler;
        Supplier<Storage<FluidVariant>> fluidHandler;
        Function<Direction, EnergyStorage> energyHandler;
        Runnable nextTick;
        boolean exportItems = true, exportFluid = true, exportEnergy = true;
        final Set<Direction> itemSides = EnumSet.allOf(Direction.class);
        final Set<Direction> fluidSides = EnumSet.allOf(Direction.class);
        final Set<Direction> energySides = EnumSet.allOf(Direction.class);
        final Storage<ItemVariant> itemApi = new ExportGate<>(itemStorage, () -> exportItems);
        final Storage<FluidVariant> fluidApi = new ExportGate<>(tank, () -> exportFluid);
        final EnergyStorage energyApi = new EnergyStorage() {
            @Override public boolean supportsInsertion() { return true; }
            @Override public boolean supportsExtraction() { return exportEnergy; }
            @Override public long getAmount() { return energy.amount; }
            @Override public long getCapacity() { return energy.capacity; }
            @Override public long insert(long maximum, TransactionContext transaction) { return energy.insert(maximum, transaction); }
            @Override public long extract(long maximum, TransactionContext transaction) { return exportEnergy ? energy.extract(maximum, transaction) : 0; }
        };
        Machine(BlockPos position, BlockState state) { super(machineType, position, state); }
    }

    private static Machine machine(GameTestHelper helper, BlockPos relative) {
        BlockPos position = helper.absolutePos(relative);
        helper.getLevel().setBlock(position, machineBlock.defaultBlockState(), 3);
        return (Machine) helper.getLevel().getBlockEntity(position);
    }
    private static ConduitBundleBlockEntity conduit(GameTestHelper helper, BlockPos relative, ConduitKind... kinds) {
        BlockPos position = helper.absolutePos(relative);
        helper.getLevel().setBlock(position, AutomationRegistry.CONDUIT_BUNDLE.defaultBlockState(), 3);
        var entity = (ConduitBundleBlockEntity) helper.getLevel().getBlockEntity(position);
        for (ConduitKind kind : kinds) helper.assertTrue(entity.install(kind), "fixture installs its one physical lane: " + kind);
        return entity;
    }
    private static void port(ConduitBundleBlockEntity entity, Direction face, ConduitMode mode) {
        for (ConduitKind kind : entity.installed()) entity.setMode(kind, face, mode);
    }
    private static long mb(long amount) { return amount * (FluidConstants.BUCKET / 1_000); }
    private static void fill(Tank tank, long amount) { tank.variant = WATER; tank.amount = amount; }
    private static void enable(Machine machine) { machine.itemEnabled = machine.fluidEnabled = machine.energyEnabled = true; }
    private static void onMachineTick(GameTestHelper helper, Machine machine, Runnable action) {
        machine.nextTick = () -> {
            try { action.run(); }
            catch (RuntimeException failure) {
                // GameTest assertions are delivered back through its own task runner, not as a server ticker crash.
                helper.runAfterDelay(1, () -> { throw failure; });
            }
        };
    }

    private static BlockHitResult partHit(ConduitBundleBlockEntity bundle, ConduitKind kind, ConduitGeometry.Role role,
                                          Direction branch, Direction normal) {
        for (var part : ConduitGeometry.parts(bundle.visualState())) {
            if (part.kind() != kind || part.role() != role || part.side() != branch) continue;
            AABB box = part.bounds();
            Vec3 point = new Vec3(normal == Direction.WEST ? box.minX : normal == Direction.EAST ? box.maxX : (box.minX + box.maxX) / 2,
                    normal == Direction.DOWN ? box.minY : normal == Direction.UP ? box.maxY : (box.minY + box.maxY) / 2,
                    normal == Direction.NORTH ? box.minZ : normal == Direction.SOUTH ? box.maxZ : (box.minZ + box.maxZ) / 2);
            var selected = ConduitGeometry.hitPart(bundle.visualState(), point, normal).orElse(null);
            if (selected != null && selected.kind() == kind && selected.role() == role && selected.side() == branch)
                return new BlockHitResult(Vec3.atLowerCornerOf(bundle.getBlockPos()).add(point), normal, bundle.getBlockPos(), false);
        }
        throw new IllegalStateException("No exposed " + kind + " " + role + " on branch " + branch + " with normal " + normal);
    }

    /** A connected server-player fixture aims through Minecraft's real outline ray before native mining completes. */
    private static void aimForMining(GameTestHelper helper, ServerPlayer player, ConduitBundleBlockEntity bundle, ConduitKind kind) {
        bundle.refreshVisual();
        for (var part : ConduitGeometry.parts(bundle.visualState())) {
            if (part.kind() != kind || part.role() != ConduitGeometry.Role.HUB) continue;
            Vec3 target = Vec3.atLowerCornerOf(bundle.getBlockPos()).add(part.bounds().getCenter());
            for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP)) {
                Vec3 eye = target.add(direction.getStepX() * 2, direction.getStepY() * 2, direction.getStepZ() * 2);
                player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
                var hit = helper.getLevel().clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                if (!hit.getBlockPos().equals(bundle.getBlockPos()) || ConduitGeometry.hitKind(bundle.visualState(),
                        hit.getLocation().subtract(Vec3.atLowerCornerOf(bundle.getBlockPos())), hit.getDirection()).orElse(null) != kind) continue;
                Vec3 delta = target.subtract(eye);
                float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
                player.setYRot(yaw);
                player.setYHeadRot(yaw);
                player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
                var picked = player.pick(player.blockInteractionRange(), 1, false);
                helper.assertTrue(picked instanceof BlockHitResult actual && actual.getBlockPos().equals(bundle.getBlockPos())
                                && ConduitGeometry.hitKind(bundle.visualState(), actual.getLocation().subtract(Vec3.atLowerCornerOf(bundle.getBlockPos())),
                                actual.getDirection()).orElse(null) == kind,
                        "The actual server-player head ray reaches the intended " + kind + " strand");
                return;
            }
        }
        helper.fail("No exposed native mining ray for " + kind);
    }

    private static int droppedConduits(ServerLevel level, BlockPos position, ConduitKind kind) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(position).inflate(.3)).stream()
                .filter(entity -> entity.getItem().is(AutomationRegistry.conduit(kind)))
                .mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    public static void bundlingAndSurvivalDrops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper);
        BlockPos position = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlock(position, Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(position.above(), Blocks.STONE.defaultBlockState(), 3);
        for (ConduitKind kind : ConduitKind.values()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AutomationRegistry.conduit(kind), 3));
            BlockHitResult hit = kind == ConduitKind.ITEM
                    ? new BlockHitResult(Vec3.atCenterOf(position.below()).add(0, .5, 0), Direction.UP, position.below(), false)
                    : new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false);
            player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "Actual Survival item use consumes one " + kind);
            var bundle = (ConduitBundleBlockEntity) level.getBlockEntity(position);
            helper.assertTrue(bundle.has(kind), "The used lane is installed");
            player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false)));
            helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "A blocked same-kind extension consumes nothing");
            helper.assertTrue(level.getBlockEntity(position) == bundle && bundle.has(kind), "A blocked extension preserves the original lane");
            player.getInventory().setItem(10 + kind.ordinal(), player.getMainHandItem().copy());
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        var bundle = (ConduitBundleBlockEntity) level.getBlockEntity(position);
        helper.assertValueEqual(bundle.installedMask(), 7, "All three independent lanes share one BE");
        helper.assertTrue(bundle.getBlockState().getValue(ConduitBundleBlock.WATERLOGGED), "The original water source remains waterlogged");
        bundle.setMode(ConduitKind.ENERGY, Direction.NORTH, ConduitMode.DISABLED);
        player.setItemInHand(InteractionHand.MAIN_HAND, player.getInventory().removeItem(10, 1));
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(position).add(0, 0, -.5), Direction.NORTH, position, false)));
        helper.assertTrue(player.getMainHandItem().isEmpty(), "Native same-kind adjacent placement consumes exactly its one Survival item");
        BlockPos extensionPosition = position.north();
        var extension = (ConduitBundleBlockEntity) level.getBlockEntity(extensionPosition);
        helper.assertTrue(extension != null && extension != bundle && extension.installedMask() == ConduitKind.ITEM.mask(),
                "Same-kind use extends into an adjacent physical bundle instead of consuming the interaction");
        helper.assertTrue(level.getBlockEntity(position) == bundle && bundle.installedMask() == 7,
                "Extension never replaces the original three-lane bundle");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AutomationRegistry.CONDUIT_WRENCH));
        player.setShiftKeyDown(true);
        var extensionHub = ConduitGeometry.parts(extension.visualState()).stream()
                .filter(value -> value.role() == ConduitGeometry.Role.HUB).findFirst().orElseThrow().bounds();
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(
                Vec3.atLowerCornerOf(extensionPosition).add((extensionHub.minX + extensionHub.maxX) / 2,
                        extensionHub.maxY, (extensionHub.minZ + extensionHub.maxZ) / 2), Direction.UP, extensionPosition, false)));
        helper.assertValueEqual(count(player.getInventory(), AutomationRegistry.ITEM_CONDUIT), 2,
                "Removing the new extension returns its exact original item");
        helper.assertTrue(level.getBlockEntity(extensionPosition) == null, "The removed extension leaves no bundle behind");
        for (ConduitKind kind : List.of(ConduitKind.FLUID, ConduitKind.ITEM, ConduitKind.ENERGY)) {
            var part = ConduitGeometry.parts(bundle.visualState()).stream().filter(value -> value.kind() == kind && value.role() == ConduitGeometry.Role.HUB).findFirst().orElseThrow();
            AABB box = part.bounds();
            Vec3 local = new Vec3((box.minX + box.maxX) / 2, box.maxY, (box.minZ + box.maxZ) / 2);
            var hit = new BlockHitResult(Vec3.atLowerCornerOf(position).add(local), Direction.UP, position, false);
            player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            helper.assertValueEqual(count(player.getInventory(), AutomationRegistry.conduit(kind)), 3, "Targeted wrench removal returns exactly one " + kind);
            if (kind != ConduitKind.ENERGY) {
                helper.assertTrue(level.getBlockEntity(position) == bundle, "Other lanes keep the same physical BE");
                helper.assertValueEqual(bundle.mode(ConduitKind.ENERGY, Direction.NORTH), ConduitMode.DISABLED, "Other lane settings survive targeted removal");
            }
        }
        helper.assertTrue(level.getBlockState(position).is(Blocks.WATER), "Removing the final lane restores the source water");
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(position).inflate(.3)).isEmpty(), "Returning wrench items does not also run block loot");
        player.setShiftKeyDown(false);
        var dry = conduit(helper, new BlockPos(4, 1, 2), ConduitKind.values());
        level.destroyBlock(dry.getBlockPos(), true, player);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(dry.getBlockPos()).inflate(.3));
        for (ConduitKind kind : ConduitKind.values()) helper.assertValueEqual(drops.stream().filter(item -> item.getItem().is(AutomationRegistry.conduit(kind)))
                .mapToInt(item -> item.getItem().getCount()).sum(), 1, "Survival block destruction drops the installed lane exactly once");
        helper.assertValueEqual(drops.size(), 3, "No synthetic bundle item or duplicate resource drop is added");

        var mined = conduit(helper, new BlockPos(4, 1, 4), ConduitKind.values());
        BlockPos minedPosition = mined.getBlockPos();
        level.setBlock(minedPosition, mined.getBlockState().setValue(ConduitBundleBlock.WATERLOGGED, true), 3);
        mined.setMode(ConduitKind.ENERGY, Direction.NORTH, ConduitMode.DISABLED);
        mined.setRedstone(ConduitKind.ENERGY, Direction.SOUTH, ConduitRedstone.HIGH);
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        aimForMining(helper, player, mined, ConduitKind.FLUID);
        // A rotation packet can arrive before this tick copies body yaw into the living-entity head yaw.
        player.setYHeadRot(player.getYRot() + 180);
        PROTECTED_MINING.add(minedPosition);
        try {
            helper.assertTrue(!player.gameMode.destroyBlock(minedPosition), "A later Fabric protection listener denies lane removal");
            helper.assertValueEqual(mined.installedMask(), 7, "Denied mining cannot mutate the bundle before protection checks");
            helper.assertValueEqual(droppedConduits(level, minedPosition, ConduitKind.FLUID), 0, "Denied mining drops no conduit");
            helper.assertValueEqual(tool.getDamageValue(), 0, "Denied mining does not wear the tool");
        } finally {
            PROTECTED_MINING.remove(minedPosition);
        }
        var miningRay = level.clip(new ClipContext(player.getEyePosition(),
                player.getEyePosition().add(player.getLookAngle().scale(player.blockInteractionRange())),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        helper.assertTrue(mined.current(), "The waterlogged mining fixture remains the registered block entity");
        helper.assertTrue(mined.stillValid(player), "The mining fixture is alive, in range and permitted to interact: " + player.position());
        helper.assertTrue(player.gameMode.destroyBlock(minedPosition), "Native Survival completion follows the latest look despite stale head yaw; ray="
                + miningRay.getType() + "/" + miningRay.getLocation() + "; target=" + minedPosition
                + "; eye=" + player.getEyePosition() + "; range=" + player.canInteractWithBlock(minedPosition, 0)
                + "; spawnProtected=" + level.getServer().isUnderSpawnProtection(level, minedPosition, player));
        helper.assertTrue(level.getBlockEntity(minedPosition) == mined && mined.installedMask() == 5,
                "Normal mining preserves the same bundle and its two untouched strands");
        helper.assertValueEqual(droppedConduits(level, minedPosition, ConduitKind.FLUID), 1, "Only the mined fluid strand drops once");
        helper.assertValueEqual(droppedConduits(level, minedPosition, ConduitKind.ITEM), 0, "The untouched item strand does not drop");
        helper.assertValueEqual(droppedConduits(level, minedPosition, ConduitKind.ENERGY), 0, "The untouched energy strand does not drop");
        helper.assertValueEqual(tool.getDamageValue(), 1, "A partial bundle break applies normal tool wear exactly once");
        helper.assertValueEqual(mined.mode(ConduitKind.ENERGY, Direction.NORTH), ConduitMode.DISABLED, "Partial mining preserves other lane directions");
        helper.assertValueEqual(mined.redstone(ConduitKind.ENERGY, Direction.SOUTH), ConduitRedstone.HIGH, "Partial mining preserves other lane redstone controls");
        player.setYRot(player.getYRot() + 180);
        player.setYHeadRot(player.getYRot());
        helper.assertTrue(!player.gameMode.destroyBlock(minedPosition), "A stale ray cannot fall back to destroying every remaining strand");
        helper.assertValueEqual(mined.installedMask(), 5, "A stale mining completion keeps both remaining strands");
        for (ConduitKind kind : List.of(ConduitKind.ENERGY, ConduitKind.ITEM)) {
            aimForMining(helper, player, mined, kind);
            helper.assertTrue(player.gameMode.destroyBlock(minedPosition), "Each subsequent native completion removes one " + kind);
            helper.assertValueEqual(droppedConduits(level, minedPosition, kind), 1, "Each subsequent strand drops once: " + kind);
        }
        helper.assertTrue(level.getBlockState(minedPosition).is(Blocks.WATER), "Mining the final waterlogged strand restores the water source");
        helper.assertValueEqual(tool.getDamageValue(), 3, "Three mined strands consume three ordinary tool uses");

        var creative = conduit(helper, new BlockPos(2, 1, 4), ConduitKind.values());
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setGameMode(GameType.CREATIVE);
        int expectedMask = 7;
        for (ConduitKind kind : List.of(ConduitKind.ITEM, ConduitKind.ENERGY, ConduitKind.FLUID)) {
            aimForMining(helper, player, creative, kind);
            helper.assertTrue(player.gameMode.destroyBlock(creative.getBlockPos()), "Creative mining still removes only the aimed strand");
            expectedMask &= ~kind.mask();
            if (expectedMask != 0) helper.assertValueEqual(creative.installedMask(), expectedMask, "Creative leaves the unselected strands intact");
            helper.assertValueEqual(droppedConduits(level, creative.getBlockPos(), kind), 0, "Creative partial mining never creates a Survival drop");
        }
        helper.assertTrue(level.isEmptyBlock(creative.getBlockPos()), "The final dry strand removes the block normally");
        helper.succeed();
    }

    public static void independentLanesAndSides(GameTestHelper helper) {
        ServerPlayer miner = player(helper);
        Machine source = machine(helper, new BlockPos(1, 2, 2));
        Machine target = machine(helper, new BlockPos(5, 2, 2));
        enable(source); enable(target);
        source.items.setItem(0, new ItemStack(Items.DIAMOND, 33)); fill(source.tank, mb(1_000)); source.energy.amount = 4_096;
        source.itemSides.clear(); source.itemSides.add(Direction.WEST); // deliberately wrong side until the first checkpoint
        target.itemSides.clear(); target.itemSides.add(Direction.WEST);
        var first = conduit(helper, new BlockPos(2, 2, 2), ConduitKind.values());
        var middle = conduit(helper, new BlockPos(3, 2, 2), ConduitKind.values());
        var last = conduit(helper, new BlockPos(4, 2, 2), ConduitKind.values());
        for (var pipe : List.of(first, middle, last))
            helper.getLevel().setBlockAndUpdate(pipe.getBlockPos().below(), Blocks.STONE.defaultBlockState());
        port(first, Direction.WEST, ConduitMode.EXTRACT); port(last, Direction.EAST, ConduitMode.INSERT);
        helper.runAfterDelay(12, () -> {
            helper.assertValueEqual(count(target.items, Items.DIAMOND), 0, "Wrong-sided item capabilities cannot be bypassed");
            helper.assertTrue(target.tank.amount > 0 && target.energy.amount > 0, "Fluid and energy work independently while item access is blocked");
            helper.assertValueEqual(source.tank.amount + target.tank.amount, mb(1_000), "Fluid accounting is exact");
            aimForMining(helper, miner, middle, ConduitKind.FLUID);
            helper.assertTrue(miner.gameMode.destroyBlock(middle.getBlockPos()), "Native player mining removes the aimed middle fluid strand");
            helper.assertTrue(helper.getLevel().getBlockEntity(middle.getBlockPos()) == middle && middle.installedMask() == 5,
                    "The remaining item and energy lanes keep their physical bundle after mining");
            fill(source.tank, source.tank.amount + mb(300));
            long isolatedWater = source.tank.amount;
            source.itemSides.clear(); source.itemSides.add(Direction.EAST);
            helper.getLevel().updateNeighborsAt(source.getBlockPos(), machineBlock);
            // All fixtures share the world's bounded routing work. Wait for the five
            // item operations within this test's timeout, not a theoretical idle-world deadline.
            helper.startSequence().thenWaitUntil(() -> {
                helper.assertValueEqual(count(target.items, Items.DIAMOND), 33, "The item lane continues through the mixed bundle");
                helper.assertValueEqual(target.energy.amount, 4_096L, "The energy lane continues independently");
            }).thenExecute(() -> {
                helper.assertValueEqual(source.tank.amount, isolatedWater, "Removing only the middle fluid lane breaks only fluid connectivity");
                helper.assertTrue(middle.install(ConduitKind.FLUID), "A removed lane can be reinstalled once");
            }).thenExecuteAfter(25, () -> {
                helper.assertValueEqual(source.tank.amount, 0L, "Rejoining the loaded fluid component resumes transfer");
                helper.assertValueEqual(target.tank.amount, mb(1_300), "No fluid was trapped or lost in the disconnected conduit");
                helper.assertValueEqual(count(source.items, Items.DIAMOND) + count(target.items, Items.DIAMOND), 33, "Item conservation survives removal/rejoin");
            }).thenSucceed();
        });
    }

    public static void branchedRoutingFairness(GameTestHelper helper) {
        Machine first = machine(helper, new BlockPos(1, 2, 2)), second = machine(helper, new BlockPos(4, 2, 3));
        Machine metals = machine(helper, new BlockPos(2, 2, 1)), organics = machine(helper, new BlockPos(3, 2, 4));
        for (Machine value : List.of(first, second, metals, organics)) value.itemEnabled = true;
        first.items.setItem(0, new ItemStack(Items.IRON_INGOT, 9)); first.items.setItem(1, new ItemStack(Items.APPLE, 9));
        second.items.setItem(0, new ItemStack(Items.DIAMOND, 9)); second.items.setItem(1, new ItemStack(Items.STICK, 9));
        metals.items.accepts = item -> item.is(Items.IRON_INGOT) || item.is(Items.DIAMOND);
        organics.items.accepts = item -> item.is(Items.APPLE) || item.is(Items.STICK);
        var a = conduit(helper, new BlockPos(2, 2, 2), ConduitKind.ITEM);
        conduit(helper, new BlockPos(3, 2, 2), ConduitKind.ITEM);
        conduit(helper, new BlockPos(2, 2, 3), ConduitKind.ITEM);
        var d = conduit(helper, new BlockPos(3, 2, 3), ConduitKind.ITEM);
        a.setMode(ConduitKind.ITEM, Direction.WEST, ConduitMode.EXTRACT);
        d.setMode(ConduitKind.ITEM, Direction.EAST, ConduitMode.EXTRACT);
        helper.runAfterDelay(85, () -> {
            helper.assertValueEqual(ConduitNetworks.networkSize(a, ConduitKind.ITEM), 4, "A loop visits each conduit once");
            helper.assertTrue(first.items.isEmpty() && second.items.isEmpty(), "Neither source or resource slot starves behind an incompatible first target");
            for (Item item : List.of(Items.IRON_INGOT, Items.DIAMOND)) helper.assertValueEqual(count(metals.items, item), 9, "Both sources reach the compatible metal sink");
            for (Item item : List.of(Items.APPLE, Items.STICK)) helper.assertValueEqual(count(organics.items, item), 9, "Both source slot cursors reach the second sink");
            helper.succeed();
        });
    }

    public static void transactionAbortAndReplacement(GameTestHelper helper) {
        Machine source = machine(helper, new BlockPos(2, 2, 3)), target = machine(helper, new BlockPos(4, 2, 3));
        enable(source); enable(target);
        source.exportItems = source.exportFluid = source.exportEnergy = false;
        source.items.setItem(0, new ItemStack(Items.DIAMOND, 32)); fill(source.tank, mb(1_000)); source.energy.amount = 1_000;
        var bundle = conduit(helper, new BlockPos(3, 2, 3), ConduitKind.values());
        port(bundle, Direction.WEST, ConduitMode.EXTRACT); port(bundle, Direction.EAST, ConduitMode.INSERT);
        helper.runAfterDelay(6, () -> {
            // Vanilla runs this callback after END_LEVEL_TICK. Empty/non-exporting sources must leave useful work for later API callers.
            EnergyStorage late = EnergyStorage.SIDED.find(helper.getLevel(), bundle.getBlockPos(), Direction.WEST);
            helper.assertTrue(late != null, "A late producer finds the loaded forwarding receiver");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(late.insert(1, transaction), 1L, "A scheduler pass with nothing transferable preserves work for a later producer");
            }
            helper.assertValueEqual(target.energy.amount, 0L, "The late probe still obeys outer abort");
        });
        helper.runAfterDelay(8, () -> onMachineTick(helper, source, () -> {
            var items = ItemStorage.SIDED.find(helper.getLevel(), bundle.getBlockPos(), Direction.WEST);
            var fluids = FluidStorage.SIDED.find(helper.getLevel(), bundle.getBlockPos(), Direction.WEST);
            var energy = EnergyStorage.SIDED.find(helper.getLevel(), bundle.getBlockPos(), Direction.WEST);
            helper.assertTrue(items != null && fluids != null && energy != null, "All real forwarding capabilities are present");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIAMOND), 3, transaction), 3L, "Nested item insertion reaches its endpoint");
                helper.assertValueEqual(source.itemStorage.extract(ItemVariant.of(Items.DIAMOND), 3, transaction), 3L, "The caller supplies exactly the accepted items");
                helper.assertValueEqual(fluids.insert(WATER, mb(50), transaction), mb(50), "Nested fluid insertion reaches its endpoint");
                source.tank.extract(WATER, mb(50), transaction);
                helper.assertValueEqual(energy.insert(73, transaction), 73L, "Nested energy insertion reaches its endpoint");
                source.energy.extract(73, transaction);
            }
            helper.assertValueEqual(count(source.items, Items.DIAMOND), 32, "Outer abort restores items at source");
            helper.assertTrue(target.items.isEmpty(), "Outer abort restores destination item slots");
            helper.assertValueEqual(source.tank.amount, mb(1_000), "Outer abort restores exact fluid source");
            helper.assertValueEqual(target.tank.amount, 0L, "Outer abort removes the uncommitted fluid");
            helper.assertValueEqual(source.energy.amount, 1_000L, "Outer abort restores source energy");
            helper.assertValueEqual(target.energy.amount, 0L, "Outer abort restores destination energy");
            // The same retained capabilities and budgets remain usable after the aborted outer transaction.
            target.items.setItem(0, new ItemStack(Items.DIAMOND, 63));
            for (int slot = 1; slot < 4; slot++) target.items.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            target.tank.capacity = mb(100); fill(target.tank, mb(90)); target.energy.acceptance = 2;
            try (Transaction transaction = Transaction.openOuter()) {
                long movedItems = items.insert(ItemVariant.of(Items.DIAMOND), 8, transaction);
                long movedFluid = fluids.insert(WATER, mb(50), transaction);
                long movedEnergy = energy.insert(73, transaction);
                helper.assertValueEqual(movedItems, 1L, "Only actual item room is accepted");
                helper.assertValueEqual(movedFluid, mb(10), "Only exact droplet headroom is accepted");
                helper.assertValueEqual(movedEnergy, 2L, "The receiver's partial energy acceptance is honored");
                source.itemStorage.extract(ItemVariant.of(Items.DIAMOND), movedItems, transaction);
                source.tank.extract(WATER, movedFluid, transaction); source.energy.extract(movedEnergy, transaction);
                transaction.commit();
            }
            helper.assertValueEqual(count(source.items, Items.DIAMOND) + count(target.items, Items.DIAMOND), 95, "Partial item transfer conserves the preexisting destination stack");
            helper.assertValueEqual(source.tank.amount + target.tank.amount, mb(1_090), "Partial fluid transfer conserves both tanks");
            helper.assertValueEqual(source.energy.amount + target.energy.amount, 1_000L, "Partial energy transfer conserves charge");
            long previousEnergy = target.energy.amount;
            // The endpoint replaces itself during insertion. The real world change is not part of the resource transaction.
            target.energy.afterInsert = () -> helper.getLevel().setBlock(source.getBlockPos(), Blocks.STONE.defaultBlockState(), 3);
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(energy.insert(1, transaction), 0L, "An identity change during insertion rejects the entire route");
                transaction.commit();
            }
            helper.assertValueEqual(target.energy.amount, previousEnergy, "Endpoint replacement cannot leave a duplicate committed insertion");
            target.energy.afterInsert = () -> {};
            helper.getLevel().setBlock(bundle.getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
            var replacement = conduit(helper, new BlockPos(3, 2, 3), ConduitKind.values());
            port(replacement, Direction.WEST, ConduitMode.EXTRACT);
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIAMOND), 1, transaction), 0L, "Retained item capability cannot cross a replaced bundle identity");
                helper.assertValueEqual(fluids.insert(WATER, 1, transaction), 0L, "Retained fluid capability cannot cross a replaced bundle identity");
                helper.assertValueEqual(energy.insert(1, transaction), 0L, "Retained energy capability cannot cross a replaced bundle identity");
                transaction.commit();
            }
            // Completion listeners run on the GameTest scheduler, not inside this machine tick.
            helper.runAfterDelay(1, helper::succeed);
        }));
    }

    public static void forwardingAndSharedBudgets(GameTestHelper helper) {
        Machine source = machine(helper, new BlockPos(2, 2, 2)), target = machine(helper, new BlockPos(1, 2, 0));
        source.energyEnabled = true;
        target.energyEnabled = false;
        source.exportEnergy = false; target.exportEnergy = false; source.energy.amount = 2_000;
        var west = conduit(helper, new BlockPos(1, 2, 2), ConduitKind.ENERGY);
        var north = conduit(helper, new BlockPos(2, 2, 1), ConduitKind.ENERGY);
        var joint = conduit(helper, new BlockPos(1, 2, 1), ConduitKind.ENERGY);
        for (var pipe : List.of(west, north, joint))
            helper.getLevel().setBlockAndUpdate(pipe.getBlockPos().below(), Blocks.STONE.defaultBlockState());
        west.setMode(ConduitKind.ENERGY, Direction.EAST, ConduitMode.EXTRACT);
        north.setMode(ConduitKind.ENERGY, Direction.SOUTH, ConduitMode.EXTRACT);
        joint.setMode(ConduitKind.ENERGY, Direction.NORTH, ConduitMode.INSERT);
        helper.runAfterDelay(12, () -> onMachineTick(helper, source, () -> {
            helper.assertTrue(EnergyStorage.SIDED.find(helper.getLevel(), target.getBlockPos(), Direction.SOUTH) == null,
                    "The existing physical destination initially exposes no energy handler");
            BlockState targetState = target.getBlockState();
            target.energyEnabled = true; // Deliberately no block replacement, mode edit or neighbor notification.
            helper.assertTrue(helper.getLevel().getBlockEntity(target.getBlockPos()) == target && target.getBlockState() == targetState,
                    "The destination capability appears on the same physical block without invalidating its candidates");
            var first = EnergyStorage.SIDED.find(helper.getLevel(), west.getBlockPos(), Direction.EAST);
            var alias = EnergyStorage.SIDED.find(helper.getLevel(), north.getBlockPos(), Direction.SOUTH);
            helper.assertTrue(first != null && alias != null, "Both faces expose forwarding receivers");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(first.insert(96, transaction), 96L, "A push-only generator can use a conduit receiver");
                source.energy.extract(96, transaction);
            }
            helper.assertValueEqual(source.energy.amount, 2_000L, "A simulated generator push refunds its source");
            helper.assertValueEqual(target.energy.amount, 0L, "A simulated push does not leave charge in the network or receiver");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(alias.insert(96, transaction), 96L, "Aborted first-face use did not consume the shared allowance");
                source.energy.extract(96, transaction);
                transaction.commit();
            }
            source.exportEnergy = true;
            long pushedAt = helper.getLevel().getGameTime();
            helper.runAfterDelay(1, () -> {
            helper.assertValueEqual(helper.getLevel().getGameTime(), pushedAt, "Push and observed scheduler pull belong to the exact same server tick");
            long transferred = target.energy.amount;
            helper.assertTrue(transferred > 96, "Scheduled extraction also operates in the same tick as the committed push");
            helper.assertTrue(transferred <= BackpackConfig.get().automation().conduits().energyPerTick(),
                    "Two faces and push plus pull cannot multiply a physical source's allowance");
            helper.assertValueEqual(source.energy.amount + target.energy.amount, 2_000L, "Mixed forwarding and pulling conserves energy");
            long remaining = BackpackConfig.get().automation().conduits().energyPerTick() - transferred;
            try (Transaction transaction = Transaction.openOuter()) {
                long accepted = first.insert(256, transaction);
                helper.assertTrue(accepted <= remaining, "A retained third alias cannot exceed the remaining physical allowance");
                source.energy.extract(accepted, transaction);
                transaction.commit();
            }
            source.exportEnergy = false;
            var leftBag = bag(BackpackTier.IRON, UpgradeKind.BATTERY);
            var rightBag = bag(BackpackTier.IRON, UpgradeKind.BATTERY);
            EnergyStorage charger = ResourceRuntime.energyStorage(leftBag);
            long chargeRate = BackpackConfig.get().upgrades().battery().transfer(leftBag.rows(), leftBag.multiplier());
            try (Transaction transaction = Transaction.openOuter()) {
                // Portable battery calls are rate-limited operations, not an unbounded fixture setter.
                // The placed output tick budget is exercised only after these exact charge operations.
                for (long charged = 0; charged < 1_000; ) {
                    long expected = Math.min(chargeRate, 1_000 - charged);
                    helper.assertTrue(expected > 0, "The configured battery charge operation makes progress");
                    helper.assertValueEqual(charger.insert(expected, transaction), expected,
                            "Each setup charge honors the actual backpack API's configured operation limit");
                    charged += expected;
                }
                transaction.commit();
            }
            helper.assertValueEqual(charger.getAmount(), 1_000L, "The real backpack source begins with exactly the intended charge");
            BlockPos leftPosition = helper.absolutePos(new BlockPos(3, 2, 5));
            BlockPos rightPosition = helper.absolutePos(new BlockPos(5, 2, 5));
            helper.getLevel().setBlock(leftPosition, BackpackRegistry.block(BackpackTier.IRON).defaultBlockState(), 3);
            helper.getLevel().setBlock(rightPosition, BackpackRegistry.block(BackpackTier.IRON).defaultBlockState(), 3);
            var left = (BackpackBlockEntity) helper.getLevel().getBlockEntity(leftPosition);
            var right = (BackpackBlockEntity) helper.getLevel().getBlockEntity(rightPosition);
            left.setStack(leftBag.stack()); right.setStack(rightBag.stack());
            var storageLink = conduit(helper, new BlockPos(4, 2, 5), ConduitKind.ENERGY);
            helper.runAfterDelay(10, () -> {
                helper.assertValueEqual(ResourceRuntime.energyStorage(left.inventory()).getAmount(), 1_000L,
                        "Two output-enabled backpacks do not push charge back and forth through automatic BOTH ports");
                helper.assertValueEqual(ResourceRuntime.energyStorage(right.inventory()).getAmount(), 0L,
                        "Forwarded pushes apply the same automatic storage guard as scheduler pulls");
                storageLink.setMode(ConduitKind.ENERGY, Direction.WEST, ConduitMode.EXTRACT);
                storageLink.setMode(ConduitKind.ENERGY, Direction.EAST, ConduitMode.INSERT);
                helper.runAfterDelay(12, () -> {
                    helper.assertValueEqual(ResourceRuntime.energyStorage(left.inventory()).getAmount(), 0L, "Explicit extraction opts a storage source into the route");
                    helper.assertValueEqual(ResourceRuntime.energyStorage(right.inventory()).getAmount(), 1_000L, "Explicit insertion opts a storage sink in without loss");
                    helper.succeed();
                });
            });
            });
        }));
    }

    public static void splitModesAndChunkBoundaries(GameTestHelper helper) {
        Machine source = machine(helper, new BlockPos(1, 2, 5)), target = machine(helper, new BlockPos(5, 2, 5));
        source.itemEnabled = target.itemEnabled = true;
        source.items.setItem(0, new ItemStack(Items.EMERALD, 40));
        var first = conduit(helper, new BlockPos(2, 2, 5), ConduitKind.ITEM);
        var middle = conduit(helper, new BlockPos(3, 2, 5), ConduitKind.ITEM);
        conduit(helper, new BlockPos(4, 2, 5), ConduitKind.ITEM);
        first.setMode(ConduitKind.ITEM, Direction.WEST, ConduitMode.EXTRACT);
        middle.setMode(ConduitKind.ITEM, Direction.EAST, ConduitMode.DISABLED);
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(target.items.isEmpty(), "A disabled facing edge prevents routing through the other half");
            middle.setMode(ConduitKind.ITEM, Direction.EAST, ConduitMode.INSERT);
            helper.runAfterDelay(18, () -> {
                helper.assertTrue(count(target.items, Items.EMERALD) > 0, "Interior INSERT mode reconnects the lane without directing the graph edge");
                long sourceBefore = count(source.items, Items.EMERALD);
                var held = ItemStorage.SIDED.find(helper.getLevel(), first.getBlockPos(), Direction.WEST);
                var chunk = helper.getLevel().getChunkAt(first.getBlockPos());
                // This models a registration gap in a real loaded chunk. The rendered save/open
                // acceptance separately covers an actual in-progress FULL chunk future.
                helper.assertTrue(chunk.getBlockEntities().remove(first.getBlockPos()) == first,
                        "The identity fixture removes the exact live bundle mapping");
                try {
                    helper.assertFalse(first.current(), "A retained bundle is inactive while its physical mapping is absent");
                    ConduitNetworks.register(first);
                    ConduitNetworks.describe(first);
                    try (Transaction transaction = Transaction.openOuter()) {
                        helper.assertValueEqual(held.insert(ItemVariant.of(Items.EMERALD), 1, transaction), 0L,
                                "A retained port cannot operate through a bundle registration gap");
                        transaction.commit();
                    }
                    helper.assertFalse(chunk.getBlockEntities().containsKey(first.getBlockPos()),
                            "Current checks, registration and visual probing never create a replacement bundle");
                } finally { chunk.addAndRegisterBlockEntity(first); }
                var sourceChunk = helper.getLevel().getChunkSource().getChunkNow(source.getBlockPos().getX() >> 4, source.getBlockPos().getZ() >> 4);
                helper.assertTrue(sourceChunk != null && sourceChunk.getBlockEntities().remove(source.getBlockPos()) == source,
                        "The endpoint fixture removes the exact live machine mapping");
                try {
                    ConduitNetworks.neighborChanged(helper.getLevel(), source.getBlockPos());
                    ConduitNetworks.describe(first);
                    try (Transaction transaction = Transaction.openOuter()) {
                        helper.assertValueEqual(held.insert(ItemVariant.of(Items.EMERALD), 1, transaction), 0L,
                                "A retained port rejects a source whose physical registration is incomplete");
                        transaction.commit();
                    }
                    helper.assertFalse(sourceChunk.getBlockEntities().containsKey(source.getBlockPos()),
                            "Neighbor refresh and capability probing never instantiate a missing machine");
                } finally { sourceChunk.addAndRegisterBlockEntity(source); }
                helper.assertTrue(first.current(), "The same bundle becomes active again when its exact mapping returns");
                helper.assertValueEqual((long) count(source.items, Items.EMERALD), sourceBefore,
                        "Registration gaps preserve all actual source items");
                // This is an event-boundary fixture, not a claim that the GameTest's ticketed chunk actually unloaded.
                ServerChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(helper.getLevel(), chunk);
                try (Transaction transaction = Transaction.openOuter()) {
                    helper.assertValueEqual(held.insert(ItemVariant.of(Items.EMERALD), 1, transaction), 0L, "The unload callback immediately fences a retained network route");
                    transaction.commit();
                } finally { ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(helper.getLevel(), chunk); }
                helper.assertValueEqual((long) count(source.items, Items.EMERALD), sourceBefore, "Lifecycle invalidation cannot withdraw a source item");
                BlockPos unloaded = new BlockPos(20_000_000, 100, 20_000_000);
                helper.assertFalse(helper.getLevel().hasChunkAt(unloaded), "The distant test coordinate is actually unloaded");
                var detached = new ConduitBundleBlockEntity(unloaded, AutomationRegistry.CONDUIT_BUNDLE.defaultBlockState());
                detached.setLevel(helper.getLevel()); detached.install(ConduitKind.ENERGY);
                ConduitNetworks.describe(detached);
                helper.assertFalse(helper.getLevel().hasChunkAt(unloaded), "Registration and visual probing never load an absent chunk");
                helper.runAfterDelay(55, () -> {
                    helper.assertValueEqual(count(target.items, Items.EMERALD), 40, "A fresh loaded component resumes after lifecycle invalidation");
                    helper.assertTrue(source.items.isEmpty(), "Split/rejoin leaves no in-flight item buffer behind");
                    helper.succeed();
                });
            });
        });
    }

    public static void separateSidedEnergyStorage(GameTestHelper helper) {
        Machine left = machine(helper, new BlockPos(2, 2, 2)), right = machine(helper, new BlockPos(4, 2, 2));
        left.energy.amount = 2_000;
        for (Machine battery : List.of(left, right)) {
            battery.energyEnabled = true;
            battery.energySides.clear();
            battery.energySides.addAll(List.of(Direction.NORTH, Direction.SOUTH));
            var sides = new EnumMap<Direction, EnergyStorage>(Direction.class);
            for (Direction side : List.of(Direction.NORTH, Direction.SOUTH)) {
                boolean input = side == Direction.SOUTH;
                sides.put(side, new EnergyStorage() {
                    @Override public boolean supportsInsertion() { return input; }
                    @Override public boolean supportsExtraction() { return !input; }
                    @Override public long getAmount() { return battery.energy.amount; }
                    @Override public long getCapacity() { return battery.energy.capacity; }
                    @Override public long insert(long maximum, TransactionContext transaction) {
                        return input ? battery.energy.insert(maximum, transaction) : 0;
                    }
                    @Override public long extract(long maximum, TransactionContext transaction) {
                        return input ? 0 : battery.energy.extract(maximum, transaction);
                    }
                });
            }
            battery.energyHandler = sides::get;
            EnergyStorage output = EnergyStorage.SIDED.find(helper.getLevel(), battery.getBlockPos(), Direction.NORTH);
            EnergyStorage input = EnergyStorage.SIDED.find(helper.getLevel(), battery.getBlockPos(), Direction.SOUTH);
            helper.assertTrue(output != null && input != null && output != input
                            && output.supportsExtraction() && !output.supportsInsertion()
                            && input.supportsInsertion() && !input.supportsExtraction(),
                    "Each physical battery exposes separate output-only and input-only standard sided handlers");
        }
        // Both faces of both batteries share this H-shaped component; every face starts in native BOTH mode.
        var leftOutput = conduit(helper, new BlockPos(2, 2, 1), ConduitKind.ENERGY);
        conduit(helper, new BlockPos(3, 2, 1), ConduitKind.ENERGY);
        conduit(helper, new BlockPos(4, 2, 1), ConduitKind.ENERGY);
        conduit(helper, new BlockPos(3, 2, 2), ConduitKind.ENERGY);
        conduit(helper, new BlockPos(2, 2, 3), ConduitKind.ENERGY);
        conduit(helper, new BlockPos(3, 2, 3), ConduitKind.ENERGY);
        var rightInput = conduit(helper, new BlockPos(4, 2, 3), ConduitKind.ENERGY);
        int[] phase = {0};
        long[] phaseStarted = {-1}, previousRight = {0};
        helper.onEachTick(() -> {
            helper.assertValueEqual(left.energy.amount + right.energy.amount, 2_000L,
                    "Sided storage routing never creates or loses energy");
            if (phase[0] < 2) {
                helper.assertValueEqual(left.energy.amount, 2_000L, "Automatic BOTH routes leave the charged sided battery unchanged");
                helper.assertValueEqual(right.energy.amount, 0L, "Automatic BOTH routes do not shuttle charge into the second sided battery");
                if (phase[0] == 0 && ConduitNetworks.networkSize(leftOutput, ConduitKind.ENERGY) == 7) {
                    phase[0] = 1;
                    phaseStarted[0] = helper.getTick();
                }
                if (phase[0] != 1 || helper.getTick() - phaseStarted[0] < 20) return;
                helper.assertValueEqual(ConduitNetworks.networkSize(rightInput, ConduitKind.ENERGY), 7,
                        "The no-circulation observation covers a completed connected network, not an unavailable route");
                EnergyStorage forward = EnergyStorage.SIDED.find(helper.getLevel(), leftOutput.getBlockPos(), Direction.SOUTH);
                helper.assertTrue(forward != null && forward.supportsInsertion(), "The automatic source has a real forwarding ingress");
                try (Transaction transaction = Transaction.openOuter()) {
                    helper.assertValueEqual(forward.insert(37, transaction), 0L,
                            "Direct API pushes apply the same physical-storage loop guard as natural scheduled extraction");
                    transaction.commit();
                }
                helper.assertValueEqual(left.energy.amount, 2_000L, "A rejected automatic push cannot debit the source");
                helper.assertValueEqual(right.energy.amount, 0L, "A rejected automatic push cannot credit the receiver");
                leftOutput.setMode(ConduitKind.ENERGY, Direction.SOUTH, ConduitMode.EXTRACT);
                rightInput.setMode(ConduitKind.ENERGY, Direction.NORTH, ConduitMode.INSERT);
                phase[0] = 2;
                return;
            }
            helper.assertTrue(right.energy.amount >= previousRight[0],
                    "The still-automatic reverse faces cannot send received energy back on later ticks");
            previousRight[0] = right.energy.amount;
            if (phase[0] == 2 && left.energy.amount == 0 && right.energy.amount == 2_000) {
                phase[0] = 3;
                phaseStarted[0] = helper.getTick();
            }
            if (phase[0] != 3 || helper.getTick() - phaseStarted[0] < 12) return;
            helper.assertValueEqual(left.energy.amount, 0L, "Explicit extraction drains only the intended source");
            helper.assertValueEqual(right.energy.amount, 2_000L, "Explicit insertion receives the exact original charge and keeps it");
            helper.succeed();
        });
    }

    public static void reboundEndpointViews(GameTestHelper helper) {
        Machine source = machine(helper, new BlockPos(1, 2, 2)), target = machine(helper, new BlockPos(3, 2, 2));
        source.itemEnabled = target.itemEnabled = source.fluidEnabled = target.fluidEnabled = true;
        int firstItems = BackpackConfig.get().automation().conduits().itemsPerOperation();
        long firstWater = mb(BackpackConfig.get().automation().conduits().fluidMbPerTick());
        source.items.setItem(0, new ItemStack(Items.IRON_INGOT, firstItems));
        source.items.setItem(1, new ItemStack(Items.DIAMOND, 3));
        source.itemHandler = () -> source.itemStorage;
        Tank firstTank = new Tank(), hiddenTank = new Tank(), replacementTank = new Tank();
        fill(firstTank, firstWater); fill(hiddenTank, 777); fill(replacementTank, 321);
        var originalFluids = new CombinedStorage<FluidVariant, Tank>(List.of(firstTank, hiddenTank));
        source.fluidHandler = () -> originalFluids;
        var replacementItems = new SimpleContainer(2);
        replacementItems.setItem(0, new ItemStack(Items.EMERALD, 2));
        var replacementItemStorage = InventoryStorage.of(replacementItems, null);
        var bundle = conduit(helper, new BlockPos(2, 2, 2), ConduitKind.ITEM, ConduitKind.FLUID);
        for (ConduitKind kind : bundle.installed()) for (Direction side : Direction.values())
            bundle.setMode(kind, side, side == Direction.WEST ? ConduitMode.EXTRACT
                    : side == Direction.EAST ? ConduitMode.INSERT : ConduitMode.DISABLED);

        Machine freshSource = machine(helper, new BlockPos(1, 2, 5)), freshTarget = machine(helper, new BlockPos(3, 2, 5));
        freshSource.itemEnabled = freshTarget.itemEnabled = true;
        var largeInventory = new SimpleContainer(80);
        largeInventory.setItem(73, new ItemStack(Items.AMETHYST_SHARD, 3));
        var largeStorage = InventoryStorage.of(largeInventory, null);
        int[] freshLookups = {0};
        freshSource.itemHandler = () -> {
            freshLookups[0]++;
            // A valid provider may return a new facade on every lookup; the physical slot API remains indexed.
            return new SlottedStorage<ItemVariant>() {
                @Override public int getSlotCount() { return largeStorage.getSlotCount(); }
                @Override public SingleSlotStorage<ItemVariant> getSlot(int slot) { return largeStorage.getSlot(slot); }
                @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
                    return largeStorage.insert(resource, maximum, transaction);
                }
                @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
                    return largeStorage.extract(resource, maximum, transaction);
                }
                @Override public Iterator<StorageView<ItemVariant>> iterator() { return largeStorage.iterator(); }
            };
        };
        var freshBundle = conduit(helper, new BlockPos(2, 2, 5), ConduitKind.ITEM);
        for (Direction side : Direction.values()) freshBundle.setMode(ConduitKind.ITEM, side,
                side == Direction.WEST ? ConduitMode.EXTRACT : side == Direction.EAST ? ConduitMode.INSERT : ConduitMode.DISABLED);
        var firstFacade = ItemStorage.SIDED.find(helper.getLevel(), freshSource.getBlockPos(), Direction.EAST);
        var secondFacade = ItemStorage.SIDED.find(helper.getLevel(), freshSource.getBlockPos(), Direction.EAST);
        helper.assertTrue(firstFacade != null && secondFacade != null && firstFacade != secondFacade,
                "The high-slot fixture actually returns distinct standard-API facades");

        BlockState sourceState = source.getBlockState();
        boolean[] rebound = {false, false};
        long[] changedAt = {-1};
        helper.onEachTick(() -> {
            // Observe natural END_LEVEL_TICK routing. A full first allowance ensures the hidden second view
            // cannot be consumed before this fixture changes only the provider, without a block/neighbor event.
            if (!rebound[0] && count(target.items, Items.IRON_INGOT) == firstItems) {
                helper.assertValueEqual(count(source.items, Items.DIAMOND), 3, "The original item iterator still has its private second slot");
                source.itemHandler = () -> replacementItemStorage;
                rebound[0] = true;
                changedAt[0] = helper.getTick();
            }
            if (!rebound[1] && target.tank.amount == firstWater) {
                helper.assertValueEqual(hiddenTank.amount, 777L, "The original fluid iterator still has its private second tank");
                source.fluidHandler = () -> replacementTank;
                rebound[1] = true;
                changedAt[0] = helper.getTick();
            }
            if (rebound[0]) {
                helper.assertValueEqual(count(source.items, Items.DIAMOND), 3, "Rebinding the same BE cannot export an old handler's hidden item slot");
                helper.assertValueEqual(count(target.items, Items.DIAMOND), 0, "No item from the no-longer-exposed handler reaches the receiver");
            }
            if (rebound[1]) helper.assertValueEqual(hiddenTank.amount, 777L, "Rebinding the same BE cannot drain the old hidden fluid view");
            helper.assertTrue(helper.getLevel().getBlockEntity(source.getBlockPos()) == source && source.getBlockState() == sourceState,
                    "The handler switch never replaces or reconfigures the physical source block");
            if (!rebound[0] || !rebound[1] || helper.getTick() <= changedAt[0] + 12
                    || !replacementItems.isEmpty() || replacementTank.amount != 0 || !largeInventory.isEmpty()) return;
            helper.assertValueEqual(count(target.items, Items.IRON_INGOT), firstItems, "The first handler's committed item transfer remains exact");
            helper.assertValueEqual(count(target.items, Items.EMERALD), 2, "Routing switches to the new current item handler");
            helper.assertValueEqual(target.tank.amount, firstWater + 321, "Only the old exposed tank and the new current tank reached the fluid sink");
            helper.assertValueEqual(firstTank.amount + hiddenTank.amount + replacementTank.amount + target.tank.amount,
                    firstWater + 777 + 321, "Handler replacement conserves every old and new fluid store");
            helper.assertValueEqual(count(freshTarget.items, Items.AMETHYST_SHARD), 3, "Fresh facades cannot starve a physical item beyond index64");
            helper.assertTrue(freshLookups[0] > 2, "The high-slot transfer exercised real subsequent provider lookups");
            helper.succeed();
        });
    }

    public static void unrelatedTopologyChanges(GameTestHelper helper) {
        Machine source = machine(helper, new BlockPos(0, 2, 3)), target = machine(helper, new BlockPos(7, 2, 3));
        source.itemEnabled = target.itemEnabled = true;
        source.items.setItem(0, new ItemStack(Items.IRON_INGOT, 8));
        ConduitBundleBlockEntity large = null;
        // 210 loaded nodes require several default-budget discovery passes. All remain inside the fixture.
        for (int x = 1; x <= 6; x++) for (int y = 1; y <= 5; y++) for (int z = 1; z <= 7; z++) {
            var node = conduit(helper, new BlockPos(x, y, z), ConduitKind.ITEM);
            if (y == 1) node.setMode(ConduitKind.ITEM, Direction.DOWN, ConduitMode.DISABLED);
            if (x == 1 && y == 2 && z == 3) { large = node; node.setMode(ConduitKind.ITEM, Direction.WEST, ConduitMode.EXTRACT); }
        }
        var entry = large;
        var clock = conduit(helper, new BlockPos(0, 1, 0), ConduitKind.ITEM);
        BlockPos signal = clock.getBlockPos().above();
        for (int tick = 1; tick <= 24; tick++) {
            boolean high = tick % 2 == 0;
            helper.runAfterDelay(tick, () -> {
                helper.getLevel().setBlock(signal, (high ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState(), 3);
                // The physical signal exercises neighbour notifications; this configuration change
                // also invalidates the small component itself while the unrelated frontier is active.
                clock.setRedstone(ConduitKind.ITEM, Direction.UP, high ? ConduitRedstone.HIGH : ConduitRedstone.LOW);
            });
        }
        helper.runAfterDelay(25, () -> {
            helper.assertValueEqual(ConduitNetworks.networkSize(entry, ConduitKind.ITEM), 210,
                    "Unrelated local changes cannot indefinitely reset a large network's bounded discovery");
            helper.assertValueEqual(count(target.items, Items.IRON_INGOT), 8,
                    "The large route keeps making useful progress while the disconnected clock changes every tick");
            helper.assertTrue(source.items.isEmpty(), "Preserved topology progress still conserves the physical source");
            helper.succeed();
        });
    }

    public static void sourceCursorLifecycle(GameTestHelper helper) {
        Machine changing = machine(helper, new BlockPos(2, 2, 3));
        Machine stable = machine(helper, new BlockPos(4, 2, 3));
        Machine north = machine(helper, new BlockPos(3, 2, 2));
        Machine south = machine(helper, new BlockPos(3, 2, 4));
        for (Machine machine : List.of(changing, stable, north, south)) {
            machine.energyEnabled = true;
            machine.exportEnergy = false; // These fixtures push through the actual forwarding API.
        }
        changing.energy.amount = 1;
        stable.energy.amount = 32;
        var bundle = conduit(helper, new BlockPos(3, 2, 3), ConduitKind.ENERGY);
        for (Direction face : Direction.values()) bundle.setMode(ConduitKind.ENERGY, face,
                face == Direction.WEST || face == Direction.EAST ? ConduitMode.EXTRACT
                        : face == Direction.NORTH || face == Direction.SOUTH ? ConduitMode.INSERT : ConduitMode.DISABLED);
        helper.runAfterDelay(6, () -> onMachineTick(helper, stable, () -> {
            helper.assertValueEqual(ConduitNetworks.networkSize(bundle, ConduitKind.ENERGY), 1,
                    "The naturally discovered network is complete before the source-lifecycle regression");
            var stablePort = EnergyStorage.SIDED.find(helper.getLevel(), bundle.getBlockPos(), Direction.EAST);
            var changingPort = EnergyStorage.SIDED.find(helper.getLevel(), bundle.getBlockPos(), Direction.WEST);
            helper.assertTrue(stablePort != null && changingPort != null, "Both sources use real registered forwarding ports");
            pushOne(helper, stable, stablePort);
            helper.assertValueEqual(north.energy.amount + south.energy.amount, 1L, "The live source's first unit reaches exactly one destination");
            boolean firstWasNorth = north.energy.amount == 1;
            Map<?, ?> cursors = sourceCursors(bundle, ConduitKind.ENERGY);
            Object retainedCursor = cursors.get(stable);
            helper.assertTrue(retainedCursor != null && cursors.size() == 1, "The first actual transfer establishes one source fairness cursor");
            Machine current = changing;
            for (int replacement = 0; replacement < 12; replacement++) {
                if (replacement > 0) {
                    current = machine(helper, new BlockPos(2, 2, 3));
                    current.energyEnabled = true;
                    current.exportEnergy = false;
                    current.energy.amount = 1;
                }
                helper.assertTrue(sourceCursors(bundle, ConduitKind.ENERGY) == cursors,
                        "A neighboring machine replacement does not discard the live topology or its cursor map");
                pushOne(helper, current, changingPort);
                helper.assertTrue(cursors.containsKey(current) && cursors.size() == 2,
                        "Only the two currently used physical sources own fairness cursors");
                helper.assertValueEqual(current.energy.amount, 0L, "Every replaced source transfers its one unit before removal");
                helper.getLevel().setBlock(current.getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
                helper.assertFalse(cursors.containsKey(current), "Endpoint refresh promptly releases the removed source identity");
                helper.assertValueEqual(cursors.size(), 1, "Repeated source replacements cannot accumulate obsolete cursor keys");
                helper.assertTrue(cursors.get(stable) == retainedCursor,
                        "Pruning an obsolete source preserves the exact cursor object of the unaffected live source");
            }
            long northBefore = north.energy.amount, southBefore = south.energy.amount;
            pushOne(helper, stable, stablePort);
            helper.assertValueEqual(north.energy.amount - northBefore, firstWasNorth ? 0L : 1L,
                    "The live source resumes at its next destination instead of resetting its round-robin order");
            helper.assertValueEqual(south.energy.amount - southBefore, firstWasNorth ? 1L : 0L,
                    "Both destinations retain their fair turn across another source's repeated replacement");
            helper.assertValueEqual(north.energy.amount + south.energy.amount, 14L, "Both sinks own exactly the fourteen committed units");
            helper.assertValueEqual(stable.energy.amount, 30L, "The stable source pays only its two actual transfers");
            helper.assertValueEqual(stable.energy.amount + north.energy.amount + south.energy.amount, 44L,
                    "Source churn and cursor cleanup conserve all thirty-two initial plus twelve fixture units");
            helper.runAfterDelay(1, helper::succeed);
        }));
    }

    private static void pushOne(GameTestHelper helper, Machine source, EnergyStorage port) {
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(port.insert(1, transaction), 1L, "The physical source forwards one accepted unit");
            helper.assertValueEqual(source.energy.extract(1, transaction), 1L, "The source supplies that same unit in the shared transaction");
            transaction.commit();
        }
    }

    /** Read-only inspection of the real cache, avoiding a production test hook or nondeterministic GC assertions. */
    private static Map<?, ?> sourceCursors(ConduitBundleBlockEntity bundle, ConduitKind kind) {
        try {
            var lookup = ConduitNetworks.class.getDeclaredMethod("component", ConduitBundleBlockEntity.class, ConduitKind.class);
            lookup.setAccessible(true);
            Object component = lookup.invoke(null, bundle, kind);
            if (component == null) throw new IllegalStateException("Expected a naturally discovered conduit component");
            var field = component.getClass().getDeclaredField("destinationsBySource");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(component);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not inspect the actual source cursor cache", failure);
        }
    }

    private record BackpackLink(BackpackBlockEntity source, BackpackBlockEntity destination, ConduitBundleBlockEntity pipe) {}
    private record BackpackSnapshots(ItemStack source, ItemStack destination) {}

    private static BackpackLink backpackLink(GameTestHelper helper) {
        BlockPos sourcePosition = helper.absolutePos(new BlockPos(2, 2, 3));
        BlockPos destinationPosition = helper.absolutePos(new BlockPos(4, 2, 3));
        var sourceBag = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.TANK, UpgradeKind.BATTERY);
        var destinationBag = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.TANK, UpgradeKind.BATTERY);
        destinationBag.updateSettings(upgrade(destinationBag, 2), settings -> settings.putBoolean("external_output", false));
        helper.getLevel().setBlock(sourcePosition, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState(), 3);
        helper.getLevel().setBlock(destinationPosition, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState(), 3);
        var source = (BackpackBlockEntity) helper.getLevel().getBlockEntity(sourcePosition);
        var destination = (BackpackBlockEntity) helper.getLevel().getBlockEntity(destinationPosition);
        source.setStack(sourceBag.stack()); destination.setStack(destinationBag.stack());
        var pipe = conduit(helper, new BlockPos(3, 2, 3), ConduitKind.values());
        port(pipe, Direction.WEST, ConduitMode.EXTRACT);
        port(pipe, Direction.EAST, ConduitMode.INSERT);
        helper.assertFalse(destination.energyStorage(Direction.WEST).supportsExtraction(), "The receiving backpack is explicitly input-only for energy");
        return new BackpackLink(source, destination, pipe);
    }

    private static ItemStack backpackIron() {
        var iron = new ItemStack(Items.IRON_INGOT, 16);
        iron.set(DataComponents.CUSTOM_NAME, Component.literal("Conduit iron sample"));
        return iron;
    }

    private static void backpackSeed(GameTestHelper helper, BackpackLink link) {
        BagInventory source = link.source().inventory();
        // Put the denied variants first: a filtered view must advance to later matching slots.
        source.setItem(0, backpackIron());
        source.setItem(1, new ItemStack(Items.COBBLESTONE, 24));
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(new BackpackTank(source, upgrade(source, 0), false).insert(BACKPACK_LAVA, BACKPACK_LAVA_AMOUNT, transaction),
                    BACKPACK_LAVA_AMOUNT, "The source starts with exact lava including fractional millibuckets");
            helper.assertValueEqual(new BackpackTank(source, upgrade(source, 1), false).insert(BACKPACK_WATER, BACKPACK_WATER_AMOUNT, transaction),
                    BACKPACK_WATER_AMOUNT, "A second physical tank owns a distinct, component-bearing water variant");
            EnergyStorage battery = ResourceRuntime.energyStorage(source);
            for (long charged = 0; charged < 1_000; ) {
                long accepted = battery.insert(1_000 - charged, transaction);
                helper.assertTrue(accepted > 0 && accepted <= 1_000 - charged, "Public battery charging makes bounded progress");
                charged += accepted;
            }
            transaction.commit();
        }
        source.save();
    }

    private static ConduitFilter backpackFilter(ConduitFilterMode mode, String... names) {
        ConduitFilter filter = ConduitFilter.EMPTY.withMode(mode);
        for (int row = 0; row < names.length; row++) filter = filter.withEntry(row, ResourceLocation.fromNamespaceAndPath("minecraft", names[row]));
        return filter;
    }

    private static void backpackFirstFilters(BackpackLink link) {
        link.pipe().setFilter(ConduitKind.ITEM, Direction.WEST, backpackFilter(ConduitFilterMode.ALLOW, "cobblestone"));
        link.pipe().setFilter(ConduitKind.ITEM, Direction.EAST, backpackFilter(ConduitFilterMode.BLOCK, "iron_ingot"));
        link.pipe().setFilter(ConduitKind.FLUID, Direction.WEST, backpackFilter(ConduitFilterMode.ALLOW, "water"));
        link.pipe().setFilter(ConduitKind.FLUID, Direction.EAST, backpackFilter(ConduitFilterMode.BLOCK, "lava"));
    }

    private static BackpackSnapshots backpackSnapshots(BackpackLink link) {
        return new BackpackSnapshots(link.source().inventory().stack().copy(), link.destination().inventory().stack().copy());
    }

    private static void backpackUnchanged(GameTestHelper helper, BackpackLink link, BackpackSnapshots before, String reason) {
        assertStack(helper, link.source().inventory().stack(), before.source(), reason + " (source)");
        assertStack(helper, link.destination().inventory().stack(), before.destination(), reason + " (destination)");
    }

    private static long backpackFluid(BackpackBlockEntity backpack, FluidVariant variant) {
        long amount = 0;
        for (var view : ResourceRuntime.fluidStorage(backpack.inventory())) if (view.getResource().equals(variant)) amount += view.getAmount();
        return amount;
    }

    private static void backpackTotals(GameTestHelper helper, BackpackLink link) {
        helper.assertValueEqual(count(link.source().inventory(), Items.COBBLESTONE) + count(link.destination().inventory(), Items.COBBLESTONE),
                24, "Both actual backpacks conserve every cobblestone item");
        helper.assertValueEqual(count(link.source().inventory(), Items.IRON_INGOT) + count(link.destination().inventory(), Items.IRON_INGOT),
                16, "Both actual backpacks conserve every iron item");
        helper.assertValueEqual(backpackFluid(link.source(), BACKPACK_WATER) + backpackFluid(link.destination(), BACKPACK_WATER),
                BACKPACK_WATER_AMOUNT, "Both actual backpacks conserve the exact water variant and all droplets");
        helper.assertValueEqual(backpackFluid(link.source(), BACKPACK_LAVA) + backpackFluid(link.destination(), BACKPACK_LAVA),
                BACKPACK_LAVA_AMOUNT, "Both actual backpacks conserve the exact lava variant and all droplets");
        helper.assertValueEqual(ResourceRuntime.batteryStored(link.source().inventory(), 2) + ResourceRuntime.batteryStored(link.destination().inventory(), 2),
                1_000L, "Source plus input-only recipient conserve every FE");
        for (var backpack : List.of(link.source(), link.destination())) {
            for (int slot = 0; slot < backpack.inventory().getContainerSize(); slot++) {
                ItemStack stack = backpack.inventory().getItem(slot);
                if (stack.is(Items.IRON_INGOT)) helper.assertTrue(ItemStack.isSameItemSameComponents(stack, backpackIron()),
                        "Registry-ID item filtering preserves the iron's distinct components");
            }
            for (var view : ResourceRuntime.fluidStorage(backpack.inventory())) if (view.getAmount() > 0)
                helper.assertTrue(view.getResource().equals(BACKPACK_WATER) || view.getResource().equals(BACKPACK_LAVA),
                        "Fluid routing never normalizes, mixes or invents a variant");
        }
    }

    public static void backpackFilteredRouting(GameTestHelper helper) {
        BackpackLink link = backpackLink(helper);
        backpackFirstFilters(link);
        backpackSeed(helper, link);
        link.pipe().refreshVisual();
        for (ConduitKind kind : ConduitKind.values()) for (Direction side : List.of(Direction.WEST, Direction.EAST))
            helper.assertTrue(link.pipe().visualState().endpoint(kind, side), "A real three-lane link exposes its installed backpack " + kind + " endpoint on " + side);
        helper.runAfterDelay(45, () -> {
            helper.assertValueEqual(count(link.destination().inventory(), Items.COBBLESTONE), 24, "Natural ticks transfer whitelisted cobble despite the denied first iron slot");
            helper.assertValueEqual(count(link.destination().inventory(), Items.IRON_INGOT), 0, "The iron blacklist/whitelist intersection remains closed");
            helper.assertValueEqual(backpackFluid(link.destination(), BACKPACK_WATER), BACKPACK_WATER_AMOUNT, "Natural ticks transfer whitelisted water despite the denied first lava tank");
            helper.assertValueEqual(backpackFluid(link.destination(), BACKPACK_LAVA), 0L, "The first filter phase leaves every lava droplet at source");
            helper.assertValueEqual(ResourceRuntime.batteryStored(link.destination().inventory(), 2), 1_000L, "Energy moves independently into the input-only receiving backpack");
            backpackTotals(helper, link);
            link.pipe().setFilter(ConduitKind.ITEM, Direction.WEST, backpackFilter(ConduitFilterMode.BLOCK, "cobblestone"));
            link.pipe().setFilter(ConduitKind.ITEM, Direction.EAST, backpackFilter(ConduitFilterMode.ALLOW, "iron_ingot"));
            link.pipe().setFilter(ConduitKind.FLUID, Direction.WEST, backpackFilter(ConduitFilterMode.BLOCK, "water"));
            link.pipe().setFilter(ConduitKind.FLUID, Direction.EAST, backpackFilter(ConduitFilterMode.ALLOW, "lava"));
            helper.runAfterDelay(45, () -> {
                helper.assertValueEqual(count(link.destination().inventory(), Items.IRON_INGOT), 16, "Live mode/list edits admit the opposite item without replacing the conduit");
                helper.assertValueEqual(backpackFluid(link.destination(), BACKPACK_LAVA), BACKPACK_LAVA_AMOUNT, "Live mode/list edits admit lava into the other physical tank");
                helper.assertTrue(link.source().inventory().isEmpty(), "All admitted physical items leave the source exactly once");
                backpackTotals(helper, link);
                helper.runAfterDelay(10, () -> {
                    helper.assertValueEqual(ResourceRuntime.batteryStored(link.destination().inventory(), 2), 1_000L, "The input-only receiving bag does not send energy back after the transfer");
                    helper.assertValueEqual(ResourceRuntime.batteryStored(link.source().inventory(), 2), 0L, "No energy circulates back into the emptied source");
                    backpackTotals(helper, link);
                    helper.succeed();
                });
            });
        });
    }

    public static void backpackHighSlotRouting(GameTestHelper helper) {
        BackpackBlockEntity source = indexedBackpack(helper, new BlockPos(2, 2, 3));
        BackpackBlockEntity destination = indexedBackpack(helper, new BlockPos(4, 2, 3));
        var pipe = conduit(helper, new BlockPos(3, 2, 3), ConduitKind.ITEM);
        port(pipe, Direction.WEST, ConduitMode.EXTRACT);
        port(pipe, Direction.EAST, ConduitMode.INSERT);
        BagInventory inventory = source.inventory();
        int lastSlot = inventory.getContainerSize() - 1;
        helper.assertTrue(lastSlot > 73, "The actual Netherite source has both slot73 and a later final slot");
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 11);
        cobble.set(DataComponents.CUSTOM_NAME, Component.literal("High-slot cobble"));
        ItemStack amethyst = new ItemStack(Items.AMETHYST_SHARD, 5);
        amethyst.set(DataComponents.CUSTOM_NAME, Component.literal("Last-slot amethyst"));
        inventory.setItem(73, cobble.copy());
        inventory.setItem(lastSlot, amethyst.copy());
        SlottedStorage<ItemVariant> first = indexedItems(helper, source);
        SlottedStorage<ItemVariant> second = indexedItems(helper, source);
        helper.assertTrue(first != second, "The real backpack provider returns fresh wrappers, without a test facade");
        helper.assertValueEqual(first.getSlotCount(), inventory.getContainerSize(), "Indexed extent matches every actual physical slot");
        SingleSlotStorage<ItemVariant> retained = first.getSlot(73);
        helper.assertValueEqual(retained.getResource(), ItemVariant.of(cobble), "Slot73 exposes its exact component-bearing resource");
        helper.assertValueEqual(first.getSlot(lastSlot).getResource(), ItemVariant.of(amethyst), "The final physical slot is indexed without truncation");
        ItemStack beforeProbe = inventory.stack().copy();
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(retained.extract(ItemVariant.of(cobble), 2, transaction), 2L, "An indexed view performs a real tentative extraction");
        }
        assertStack(helper, inventory.stack(), beforeProbe, "Indexed extraction abort preserves the complete source snapshot");
        Storage<?>[] previous = {second};
        int[] phase = {0}, lookups = {2};
        helper.onEachTick(() -> {
            SlottedStorage<ItemVariant> current = indexedItems(helper, source);
            helper.assertTrue(current != previous[0], "Subsequent native capability lookups keep returning fresh wrappers");
            previous[0] = current;
            lookups[0]++;
            helper.assertValueEqual(count(inventory, Items.COBBLESTONE) + count(destination.inventory(), Items.COBBLESTONE),
                    phase[0] == 0 ? 11 : 17, "High-slot routing conserves every cobblestone");
            helper.assertValueEqual(count(inventory, Items.AMETHYST_SHARD) + count(destination.inventory(), Items.AMETHYST_SHARD),
                    phase[0] == 0 ? 5 : 8, "Last-slot routing conserves every amethyst shard");
            if (phase[0] == 0) {
                if (count(destination.inventory(), Items.COBBLESTONE) != 11 || count(destination.inventory(), Items.AMETHYST_SHARD) != 5) return;
                helper.assertTrue(inventory.isEmpty(), "OFF filters allow natural routing from slot73 and the final slot");
                var allowed = backpackFilter(ConduitFilterMode.ALLOW, "cobblestone", "amethyst_shard");
                pipe.setFilter(ConduitKind.ITEM, Direction.WEST, allowed);
                pipe.setFilter(ConduitKind.ITEM, Direction.EAST, allowed);
                for (int slot = 0; slot < 64; slot++) inventory.setItem(slot, new ItemStack(Items.IRON_INGOT));
                inventory.setItem(73, cobble.copyWithCount(6));
                inventory.setItem(lastSlot, amethyst.copyWithCount(3));
                helper.assertValueEqual(retained.getAmount(), 6L, "A warmed indexed view reads new content instead of a cached quantity");
                phase[0] = 1;
                return;
            }
            helper.assertValueEqual(count(inventory, Items.IRON_INGOT), 64, "The first64 denied physical slots remain untouched");
            helper.assertValueEqual(count(destination.inventory(), Items.IRON_INGOT), 0, "Neither endpoint's ALLOW filter admits iron");
            if (count(destination.inventory(), Items.COBBLESTONE) != 17 || count(destination.inventory(), Items.AMETHYST_SHARD) != 8) return;
            helper.assertTrue(inventory.getItem(73).isEmpty() && inventory.getItem(lastSlot).isEmpty(),
                    "Natural routing advances past64 denied views to both allowed high slots");
            for (int slot = 0; slot < destination.inventory().getContainerSize(); slot++) {
                ItemStack item = destination.inventory().getItem(slot);
                if (item.isEmpty()) continue;
                helper.assertTrue(ItemStack.isSameItemSameComponents(item, item.is(Items.COBBLESTONE) ? cobble : amethyst),
                        "The actual receiving backpack retains both transferred component variants");
            }
            helper.assertTrue(lookups[0] > 3, "Successful routing spans repeated fresh native provider lookups");
            helper.succeed();
        });
    }

    public static void backpackIndexedViewOwnership(GameTestHelper helper) {
        BackpackBlockEntity entity = indexedBackpack(helper, new BlockPos(3, 2, 3), UpgradeKind.INCEPTION);
        BagInventory root = entity.inventory();
        root.updateSettings(tag -> tag.putBoolean("inception_nested_first", true));
        BagInventory child = bag(BackpackTier.NETHERITE);
        child.setItem(73, new ItemStack(Items.DIAMOND, 5));
        root.setItem(0, child.stack());
        root.setItem(73, new ItemStack(Items.STONE, 7));
        SlottedStorage<ItemVariant> indexed = indexedItems(helper, entity);
        int rootSize = root.getContainerSize(), childSize = child.getContainerSize();
        helper.assertValueEqual(indexed.getSlotCount(), rootSize + childSize, "The native index includes the current ordered child and root extents");
        SingleSlotStorage<ItemVariant> childView = indexed.getSlot(73);
        SingleSlotStorage<ItemVariant> rootView = indexed.getSlot(childSize + 73);
        helper.assertValueEqual(childView.getResource(), ItemVariant.of(Items.DIAMOND), "Child-first indexing addresses the actual child");
        helper.assertValueEqual(rootView.getResource(), ItemVariant.of(Items.STONE), "The root follows the child without shifting its physical slots");
        ItemStack before = root.stack().copy();
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(childView.extract(ItemVariant.of(Items.DIAMOND), 2, transaction), 2L, "Indexed child extraction joins the actual transaction");
        }
        assertStack(helper, root.stack(), before, "Nested indexed extraction abort restores the serialized root and child");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(childView.extract(ItemVariant.of(Items.DIAMOND), 2, transaction), 2L, "A subsequent indexed child extraction can commit");
            transaction.commit();
        }
        BagInventory saved = BagInventory.of(roundTrip(helper.getLevel(), root.stack()));
        assertStack(helper, BagInventory.of(saved.getItem(0)).getItem(73), Items.DIAMOND, 3, "Indexed final commit persists the child into the root codec");
        root.updateSettings(tag -> tag.putBoolean("inception_nested_first", false));
        helper.assertValueEqual(indexed.getSlot(73).getResource(), ItemVariant.of(Items.STONE), "A retained storage rebuilds its index when node ordering changes");
        helper.assertValueEqual(indexed.getSlot(rootSize + 73).getAmount(), 3L, "The reordered child keeps its remaining quantity");
        helper.assertValueEqual(childView.getAmount(), 3L, "A retained slot remains bound to its physical child, not its old ordinal");
        ItemStack detached = root.getItem(0);
        root.setItem(0, ItemStack.EMPTY);
        helper.assertValueEqual(indexed.getSlotCount(), rootSize, "Removing a child shrinks the advertised index");
        helper.assertTrue(childView.isResourceBlank(), "A retained detached child view becomes blank");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(childView.extract(ItemVariant.of(Items.DIAMOND), 1, transaction), 0L, "A retained detached child view cannot extract");
            helper.assertValueEqual(childView.insert(ItemVariant.of(Items.DIAMOND), 1, transaction), 0L, "A retained detached child view cannot insert");
            transaction.commit();
        }
        assertStack(helper, BagInventory.of(detached).getItem(73), Items.DIAMOND, 3, "Detaching and probing the old address conserves the child contents");
        BagInventory replacementChild = bag(BackpackTier.GOLD);
        replacementChild.setItem(73, new ItemStack(Items.EMERALD, 2));
        root.setItem(0, replacementChild.stack());
        helper.assertValueEqual(indexed.getSlotCount(), rootSize + replacementChild.getContainerSize(), "An added child enters an already-retained storage index");
        SingleSlotStorage<ItemVariant> replacementView = indexed.getSlot(rootSize + 73);
        helper.assertValueEqual(replacementView.getResource(), ItemVariant.of(Items.EMERALD), "The new index addresses only the replacement child's resource");
        helper.assertTrue(childView.isResourceBlank(), "An old child view never follows a replacement at the same parent slot");
        root.updateSettings(tag -> tag.putBoolean("inception_outer_inventory", false));
        helper.assertValueEqual(indexed.getSlotCount(), rootSize, "Live outer-inventory policy removes child addresses");
        helper.assertTrue(replacementView.isResourceBlank(), "The prior child view honors that live policy");
        root.updateSettings(tag -> tag.putBoolean("inception_outer_inventory", true));
        helper.assertValueEqual(indexed.getSlotCount(), rootSize + replacementChild.getContainerSize(), "Re-enabled access reconstructs the current child index");
        helper.assertValueEqual(replacementView.getAmount(), 2L, "The same physically attached child becomes accessible again");
        BagInventory replacementRoot = bag(BackpackTier.NETHERITE);
        replacementRoot.setItem(73, new ItemStack(Items.DIRT, 4));
        entity.setStack(replacementRoot.stack());
        helper.assertValueEqual(indexed.getSlotCount(), 0, "Replacing the actual placed bag invalidates its retained indexed storage");
        helper.assertTrue(rootView.isResourceBlank() && replacementView.isResourceBlank(), "Every retained slot keeps the old physical ownership boundary");
        helper.assertValueEqual(indexedItems(helper, entity).getSlot(73).getResource(), ItemVariant.of(Items.DIRT), "A fresh native lookup can address the replacement bag");
        helper.succeed();
    }

    private static BackpackBlockEntity indexedBackpack(GameTestHelper helper, BlockPos relative, UpgradeKind... upgrades) {
        BlockPos position = helper.absolutePos(relative);
        helper.getLevel().setBlock(position, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState(), 3);
        var entity = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        entity.setStack(bag(BackpackTier.NETHERITE, upgrades).stack());
        return entity;
    }

    private static SlottedStorage<ItemVariant> indexedItems(GameTestHelper helper, BackpackBlockEntity entity) {
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(helper.getLevel(), entity.getBlockPos(), Direction.EAST);
        helper.assertTrue(storage instanceof SlottedStorage<?>, "The actual registered backpack item API exposes indexed physical views");
        return (SlottedStorage<ItemVariant>) storage;
    }

    private static <T> long backpackForward(GameTestHelper helper, Storage<T> source, Storage<T> pipe, T resource,
                                           long maximum, TransactionContext transaction) {
        long accepted = pipe.insert(resource, maximum, transaction);
        helper.assertValueEqual(source.extract(resource, accepted, transaction), accepted,
                "A real backpack supplies exactly the resources accepted by the public conduit API");
        return accepted;
    }

    private static long backpackForward(GameTestHelper helper, EnergyStorage source, EnergyStorage pipe,
                                         long maximum, TransactionContext transaction) {
        long accepted = pipe.insert(maximum, transaction);
        helper.assertValueEqual(source.extract(accepted, transaction), accepted,
                "The actual backpack battery supplies exactly the FE accepted by the public conduit API");
        return accepted;
    }

    public static void backpackFilteredTransactions(GameTestHelper helper) {
        BackpackLink link = backpackLink(helper);
        Machine clock = machine(helper, new BlockPos(6, 1, 6));
        helper.runAfterDelay(8, () -> onMachineTick(helper, clock, () -> {
            // Empty bags warm the actual topology first; this native machine-tick callback precedes
            // END_LEVEL_TICK, leaving deterministic allowance for the following public API calls.
            backpackSeed(helper, link);
            var level = helper.getLevel();
            Storage<ItemVariant> sourceItems = ItemStorage.SIDED.find(level, link.source().getBlockPos(), Direction.EAST);
            Storage<FluidVariant> sourceFluids = FluidStorage.SIDED.find(level, link.source().getBlockPos(), Direction.EAST);
            EnergyStorage sourceEnergy = EnergyStorage.SIDED.find(level, link.source().getBlockPos(), Direction.EAST);
            Storage<ItemVariant> items = ItemStorage.SIDED.find(level, link.pipe().getBlockPos(), Direction.WEST);
            Storage<FluidVariant> fluids = FluidStorage.SIDED.find(level, link.pipe().getBlockPos(), Direction.WEST);
            EnergyStorage energy = EnergyStorage.SIDED.find(level, link.pipe().getBlockPos(), Direction.WEST);
            helper.assertTrue(sourceItems != null && sourceFluids != null && sourceEnergy != null
                    && items != null && fluids != null && energy != null, "All six actual bag/conduit APIs are available");
            ItemVariant cobble = ItemVariant.of(Items.COBBLESTONE);
            ItemVariant iron = ItemVariant.of(backpackIron());
            var before = backpackSnapshots(link);

            link.pipe().setFilter(ConduitKind.ITEM, Direction.WEST, backpackFilter(ConduitFilterMode.ALLOW));
            link.pipe().setFilter(ConduitKind.FLUID, Direction.WEST, backpackFilter(ConduitFilterMode.ALLOW));
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(backpackForward(helper, sourceItems, items, cobble, 1, transaction), 0L, "An empty source item whitelist fails closed");
                helper.assertValueEqual(backpackForward(helper, sourceFluids, fluids, BACKPACK_WATER, 1, transaction), 0L, "An empty source fluid whitelist fails closed");
                transaction.commit();
            }
            link.pipe().setFilter(ConduitKind.ITEM, Direction.WEST, backpackFilter(ConduitFilterMode.BLOCK));
            link.pipe().setFilter(ConduitKind.FLUID, Direction.WEST, backpackFilter(ConduitFilterMode.BLOCK));
            link.pipe().setFilter(ConduitKind.ITEM, Direction.EAST, backpackFilter(ConduitFilterMode.ALLOW));
            link.pipe().setFilter(ConduitKind.FLUID, Direction.EAST, backpackFilter(ConduitFilterMode.ALLOW));
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(backpackForward(helper, sourceItems, items, cobble, 1, transaction), 0L, "An empty destination item whitelist fails closed");
                helper.assertValueEqual(backpackForward(helper, sourceFluids, fluids, BACKPACK_WATER, 1, transaction), 0L, "An empty destination fluid whitelist fails closed");
                transaction.commit();
            }
            backpackUnchanged(helper, link, before, "Rejected empty-whitelist requests preserve both exact backpack snapshots");

            link.pipe().setFilter(ConduitKind.ITEM, Direction.EAST, backpackFilter(ConduitFilterMode.BLOCK));
            link.pipe().setFilter(ConduitKind.FLUID, Direction.EAST, backpackFilter(ConduitFilterMode.BLOCK));
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(backpackForward(helper, sourceItems, items, cobble, 8, outer), 8L, "Empty blacklists admit the full item allowance through retained APIs");
                helper.assertValueEqual(backpackForward(helper, sourceFluids, fluids, BACKPACK_WATER, mb(50), outer), mb(50), "Empty blacklists admit exact fluid droplets");
                try (Transaction nested = outer.openNested()) {
                    helper.assertValueEqual(backpackForward(helper, sourceEnergy, energy, 73, nested), 73L, "Energy can commit inside the same outer resource transaction");
                    nested.commit();
                }
            }
            backpackUnchanged(helper, link, before, "Outer abort restores all three resources after nested commits");

            link.pipe().setFilter(ConduitKind.ITEM, Direction.WEST, backpackFilter(ConduitFilterMode.ALLOW, "cobblestone"));
            link.pipe().setFilter(ConduitKind.ITEM, Direction.EAST, backpackFilter(ConduitFilterMode.BLOCK, "cobblestone"));
            link.pipe().setFilter(ConduitKind.FLUID, Direction.WEST, backpackFilter(ConduitFilterMode.ALLOW, "water"));
            link.pipe().setFilter(ConduitKind.FLUID, Direction.EAST, backpackFilter(ConduitFilterMode.BLOCK, "water"));
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(backpackForward(helper, sourceItems, items, cobble, 1, transaction), 0L, "Destination denial intersects source item permission");
                helper.assertValueEqual(backpackForward(helper, sourceItems, items, iron, 1, transaction), 0L, "Source denial intersects destination item permission");
                helper.assertValueEqual(backpackForward(helper, sourceFluids, fluids, BACKPACK_WATER, 1, transaction), 0L, "Destination denial intersects source fluid permission");
                helper.assertValueEqual(backpackForward(helper, sourceFluids, fluids, BACKPACK_LAVA, 1, transaction), 0L, "Source denial intersects destination fluid permission");
                transaction.commit();
            }
            backpackUnchanged(helper, link, before, "Per-endpoint filter intersections cannot leak resources");

            link.pipe().setFilter(ConduitKind.ITEM, Direction.EAST, ConduitFilter.EMPTY);
            var selectedIron = backpackFilter(ConduitFilterMode.ALLOW, "cobblestone")
                    .withEntry(0, ResourceLocation.fromNamespaceAndPath("minecraft", "iron_ingot"));
            link.pipe().setFilter(ConduitKind.ITEM, Direction.WEST, selectedIron);
            link.pipe().setFilter(ConduitKind.ITEM, Direction.NORTH, backpackFilter(ConduitFilterMode.BLOCK, "iron_ingot"));
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(backpackForward(helper, sourceItems, items, iron, 3, transaction), 3L, "A retained API sees edited item rows and ignores an unrelated face's blacklist");
                helper.assertValueEqual(backpackForward(helper, sourceItems, items, cobble, 1, transaction), 0L, "Replacing a row revokes its old item immediately");
                link.pipe().setFilter(ConduitKind.ITEM, Direction.WEST, selectedIron.withoutEntry(0));
                helper.assertValueEqual(backpackForward(helper, sourceItems, items, iron, 1, transaction), 0L, "Clearing the final whitelist row revokes later calls in the same outer transaction");
            }
            backpackUnchanged(helper, link, before, "Aborting after live row edits restores the component-bearing item exactly");
            helper.assertValueEqual(link.pipe().filter(ConduitKind.FLUID, Direction.WEST), backpackFilter(ConduitFilterMode.ALLOW, "water"),
                    "Item-row and other-face edits leave the fluid source policy unchanged");
            helper.assertValueEqual(link.pipe().mode(ConduitKind.ENERGY, Direction.WEST), ConduitMode.EXTRACT, "Item/fluid filters do not alter the energy direction");

            link.pipe().setFilter(ConduitKind.ITEM, Direction.WEST, backpackFilter(ConduitFilterMode.ALLOW, "cobblestone"));
            boolean[] insideInsert = {false}, itemCallback = {false};
            BagInventory destination = link.destination().inventory();
            // The actual backpack change callback runs during tentative destination mutation,
            // not in a fake storage or a final-commit exception. Restore its normal BE dirty callback.
            destination.onChange(() -> {
                link.destination().setChanged();
                if (!itemCallback[0] && count(destination, Items.COBBLESTONE) > 0) {
                    helper.assertTrue(insideInsert[0], "The item policy callback must run before the public insert returns");
                    itemCallback[0] = true;
                    link.pipe().setFilter(ConduitKind.ITEM, Direction.EAST, backpackFilter(ConduitFilterMode.BLOCK, "cobblestone"));
                }
            });
            try (Transaction transaction = Transaction.openOuter()) {
                insideInsert[0] = true;
                long accepted = backpackForward(helper, sourceItems, items, cobble, 8, transaction);
                insideInsert[0] = false;
                helper.assertTrue(itemCallback[0], "A real tentative backpack item insertion triggered the policy change");
                helper.assertValueEqual(accepted, 0L, "A destination filter change during insertion aborts the entire item route");
                transaction.commit();
            } finally { insideInsert[0] = false; destination.onChange(link.destination()::setChanged); }
            backpackUnchanged(helper, link, before, "Callback-rejected item insertion restores exact source and destination state");
            helper.assertValueEqual(link.pipe().filter(ConduitKind.ITEM, Direction.EAST), backpackFilter(ConduitFilterMode.BLOCK, "cobblestone"),
                    "Resource rollback does not undo the independent destination policy edit");

            link.pipe().setFilter(ConduitKind.FLUID, Direction.EAST, ConduitFilter.EMPTY);
            boolean[] fluidCallback = {false};
            destination.onChange(() -> {
                link.destination().setChanged();
                if (!fluidCallback[0] && backpackFluid(link.destination(), BACKPACK_WATER) > 0) {
                    helper.assertTrue(insideInsert[0], "The fluid policy callback must run before the public insert returns");
                    fluidCallback[0] = true;
                    link.pipe().setFilter(ConduitKind.FLUID, Direction.WEST, backpackFilter(ConduitFilterMode.ALLOW, "lava"));
                }
            });
            try (Transaction transaction = Transaction.openOuter()) {
                insideInsert[0] = true;
                long accepted = backpackForward(helper, sourceFluids, fluids, BACKPACK_WATER, mb(50), transaction);
                insideInsert[0] = false;
                helper.assertTrue(fluidCallback[0], "A real tentative backpack tank insertion triggered the policy change");
                helper.assertValueEqual(accepted, 0L, "A source filter change during destination insertion aborts the fluid route");
                transaction.commit();
            } finally { insideInsert[0] = false; destination.onChange(link.destination()::setChanged); }
            backpackUnchanged(helper, link, before, "Callback-rejected fluid insertion preserves exact typed settings and droplets");
            helper.assertValueEqual(link.pipe().filter(ConduitKind.FLUID, Direction.WEST), backpackFilter(ConduitFilterMode.ALLOW, "lava"),
                    "Resource rollback preserves the independent source fluid policy edit");

            link.pipe().setFilter(ConduitKind.ITEM, Direction.EAST, ConduitFilter.EMPTY);
            link.pipe().setFilter(ConduitKind.FLUID, Direction.WEST, backpackFilter(ConduitFilterMode.ALLOW, "water"));
            long energyAmount = Math.min(BackpackConfig.get().automation().conduits().energyPerTick(),
                    BackpackConfig.get().upgrades().battery().transfer(link.source().inventory().rows(), link.source().inventory().multiplier()));
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(backpackForward(helper, sourceItems, items, cobble, 8, transaction), 8L, "Abort and callback rejection refund the complete item allowance for a same-tick retry");
                helper.assertValueEqual(backpackForward(helper, sourceFluids, fluids, BACKPACK_WATER, mb(50), transaction), mb(50), "Fluid retry retains its complete refunded allowance");
                helper.assertValueEqual(backpackForward(helper, sourceEnergy, energy, energyAmount, transaction), energyAmount, "Nested outer abort refunded both conduit and real battery output budgets");
                transaction.commit();
            }
            helper.assertValueEqual(count(destination, Items.COBBLESTONE), 8, "The successful item retry commits exactly once");
            helper.assertValueEqual(backpackFluid(link.destination(), BACKPACK_WATER), mb(50), "The successful fluid retry commits only its requested droplets");
            helper.assertValueEqual(ResourceRuntime.batteryStored(destination, 2), energyAmount, "The successful energy retry commits only its supplied FE");
            backpackTotals(helper, link);
            port(link.pipe(), Direction.WEST, ConduitMode.DISABLED);
            helper.runAfterDelay(1, helper::succeed);
        }));
    }

    private static BlockEntity backpackReload(GameTestHelper helper, BlockEntity original) {
        var level = helper.getLevel();
        var saved = original.saveWithFullMetadata(level.registryAccess());
        BlockEntity decoded = BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), saved, level.registryAccess());
        helper.assertTrue(decoded != null && decoded.getType() == original.getType(), "The actual registered block-entity codec restores the expected machine type");
        level.removeBlockEntity(original.getBlockPos());
        level.setBlockEntity(decoded);
        helper.assertTrue(level.getBlockEntity(original.getBlockPos()) == decoded && original.isRemoved(), "The decoded machine replaces the old physical block-entity identity");
        helper.assertValueEqual(decoded.saveWithFullMetadata(level.registryAccess()), saved, "Registered decode/replace preserves exact persisted machine data");
        return decoded;
    }

    public static void backpackFilterPersistence(GameTestHelper helper) {
        BackpackLink link = backpackLink(helper);
        backpackFirstFilters(link);
        Machine clock = machine(helper, new BlockPos(6, 1, 6));
        helper.runAfterDelay(8, () -> onMachineTick(helper, clock, () -> {
            backpackSeed(helper, link);
            var level = helper.getLevel();
            Storage<ItemVariant> sourceItems = ItemStorage.SIDED.find(level, link.source().getBlockPos(), Direction.EAST);
            Storage<ItemVariant> oldItems = ItemStorage.SIDED.find(level, link.pipe().getBlockPos(), Direction.WEST);
            Storage<FluidVariant> oldFluids = FluidStorage.SIDED.find(level, link.pipe().getBlockPos(), Direction.WEST);
            EnergyStorage oldEnergy = EnergyStorage.SIDED.find(level, link.pipe().getBlockPos(), Direction.WEST);
            helper.assertTrue(sourceItems != null && oldItems != null && oldFluids != null && oldEnergy != null,
                    "The pre-reload source and all three cached conduit APIs are present");
            var before = backpackSnapshots(link);
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(backpackForward(helper, sourceItems, oldItems, ItemVariant.of(Items.COBBLESTONE), 1, transaction), 1L,
                        "The pre-reload cached route is actually usable, not merely a nonnull handler");
            }
            backpackUnchanged(helper, link, before, "The pre-reload route probe aborts without changing saved resources");
            var source = (BackpackBlockEntity) backpackReload(helper, link.source());
            var destination = (BackpackBlockEntity) backpackReload(helper, link.destination());
            var pipe = (ConduitBundleBlockEntity) backpackReload(helper, link.pipe());
            var restored = new BackpackLink(source, destination, pipe);
            backpackUnchanged(helper, restored, before, "Both backpack component snapshots survive real registered BE replacement");
            for (ConduitKind kind : List.of(ConduitKind.ITEM, ConduitKind.FLUID)) for (Direction side : Direction.values())
                helper.assertValueEqual(pipe.filter(kind, side), link.pipe().filter(kind, side), "Every face's saved whitelist/blacklist survives reload: " + kind + " " + side);
            helper.assertFalse(destination.energyStorage(Direction.WEST).supportsExtraction(), "The restored receiving battery remains input-only");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(oldItems.insert(ItemVariant.of(Items.COBBLESTONE), 1, transaction), 0L, "A pre-reload item handler cannot follow the replacement conduit identity");
                helper.assertValueEqual(oldFluids.insert(BACKPACK_WATER, 1, transaction), 0L, "A pre-reload fluid handler cannot follow the replacement conduit identity");
                helper.assertValueEqual(oldEnergy.insert(1, transaction), 0L, "A pre-reload energy handler cannot follow the replacement conduit identity");
                transaction.commit();
            }
            backpackUnchanged(helper, restored, before, "Rejected stale API calls leave both restored backpacks unchanged");
            helper.runAfterDelay(60, () -> {
                helper.assertValueEqual(count(destination.inventory(), Items.COBBLESTONE), 24, "Natural post-reload ticks resume the saved item whitelist");
                helper.assertValueEqual(count(destination.inventory(), Items.IRON_INGOT), 0, "Reload does not clear the saved denial of iron");
                helper.assertValueEqual(backpackFluid(destination, BACKPACK_WATER), BACKPACK_WATER_AMOUNT, "Natural post-reload fluid routing retains every allowed droplet");
                helper.assertValueEqual(backpackFluid(destination, BACKPACK_LAVA), 0L, "Reload does not clear the saved denial of lava");
                helper.assertValueEqual(ResourceRuntime.batteryStored(destination.inventory(), 2), 1_000L, "Post-reload energy routing reaches the input-only receiver");
                backpackTotals(helper, restored);
                helper.succeed();
            });
        }));
    }

    public static void redstoneAndPersistedConfiguration(GameTestHelper helper) {
        Machine source = machine(helper, new BlockPos(1, 2, 2)), target = machine(helper, new BlockPos(3, 2, 2));
        source.itemEnabled = target.itemEnabled = true;
        source.items.setItem(0, new ItemStack(Items.REDSTONE, 16));
        var bundle = conduit(helper, new BlockPos(2, 2, 2), ConduitKind.values());
        bundle.setMode(ConduitKind.ITEM, Direction.WEST, ConduitMode.EXTRACT);
        bundle.setRedstone(ConduitKind.ITEM, Direction.WEST, ConduitRedstone.HIGH);
        bundle.setMode(ConduitKind.ENERGY, Direction.SOUTH, ConduitMode.DISABLED);
        var loaded = (ConduitBundleBlockEntity) BlockEntity.loadStatic(bundle.getBlockPos(), bundle.getBlockState(),
                bundle.saveWithFullMetadata(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        helper.assertTrue(loaded != null && loaded.installedMask() == 7, "Registered BE codec preserves the bundled lane set");
        for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values()) {
            helper.assertValueEqual(loaded.mode(kind, side), bundle.mode(kind, side), "Every per-face mode survives save/load");
            helper.assertValueEqual(loaded.redstone(kind, side), bundle.redstone(kind, side), "Every extraction gate survives save/load");
        }
        ServerPlayer player = player(helper);
        var disabled = conduit(helper, new BlockPos(5, 2, 5), ConduitKind.values());
        for (ConduitKind kind : ConduitKind.values()) for (Direction face : Direction.values())
            disabled.setMode(kind, face, ConduitMode.DISABLED);
        helper.assertValueEqual(disabled.visualState().connectionBits(), 0, "The configuration fixture has no visible connected arms");
        player.setShiftKeyDown(false);
        for (Direction face : Direction.values()) {
            BlockHitResult hubHit = partHit(disabled, ConduitKind.ITEM, ConduitGeometry.Role.HUB, null, face);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.getLevel().getBlockState(disabled.getBlockPos()).useWithoutItem(helper.getLevel(), player, hubHit);
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "Bare use on every hub face opens no configuration screen");
            helper.assertValueEqual(disabled.mode(ConduitKind.ITEM, face), ConduitMode.DISABLED, "Bare hub use does not change a disabled connection");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AutomationRegistry.CONDUIT_WRENCH));
            player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hubHit));
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "Wrench hub repair never opens a configuration screen");
            helper.assertValueEqual(disabled.mode(ConduitKind.ITEM, face), ConduitMode.defaultFor(ConduitKind.ITEM),
                    "Wrench hub repair restores only the targeted direction's default mode");
            helper.assertValueEqual(disabled.mode(ConduitKind.FLUID, face), ConduitMode.DISABLED, "A hub repair leaves other strands unchanged");
            disabled.setMode(ConduitKind.ITEM, face, ConduitMode.DISABLED);
        }
        disabled.setMode(ConduitKind.ITEM, Direction.EAST, ConduitMode.INSERT);
        var internal = conduit(helper, new BlockPos(6, 2, 5), ConduitKind.ITEM);
        disabled.refreshVisual(); internal.refreshVisual();
        BlockHitResult internalTube = partHit(disabled, ConduitKind.ITEM, ConduitGeometry.Role.TUBE, Direction.EAST, Direction.UP);
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, internalTube));
        helper.assertValueEqual(disabled.mode(ConduitKind.ITEM, Direction.EAST), ConduitMode.DISABLED,
                "A wrench on an internal tube cuts its owning branch, not the clicked surface normal");
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "Cutting an internal tube opens no GUI");
        internal.refreshVisual();
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                partHit(internal, ConduitKind.ITEM, ConduitGeometry.Role.HUB, null, Direction.WEST)));
        helper.assertValueEqual(disabled.mode(ConduitKind.ITEM, Direction.EAST), ConduitMode.INSERT,
                "The opposite physical hub can repair an internal cut when both blocks are authorized");
        helper.assertTrue(disabled.visualState().connected(ConduitKind.ITEM, Direction.EAST), "The repaired internal link becomes visible again");
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "Repairing the opposite internal hub opens no GUI");
        helper.assertValueEqual(disabled.installedMask(), 7, "Connection changes never remove an installed strand");
        helper.assertValueEqual(internal.installedMask(), ConduitKind.ITEM.mask(), "The neighbouring strand remains installed exactly once");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "Direct connection actions do not consume the wrench");

        bundle.refreshVisual();
        BlockHitResult endpoint = partHit(bundle, ConduitKind.ITEM, ConduitGeometry.Role.ENDPOINT, Direction.WEST, Direction.UP);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.getLevel().getBlockState(bundle.getBlockPos()).useWithoutItem(helper.getLevel(), player, endpoint);
        helper.assertTrue(player.containerMenu instanceof ConduitMenu, "Bare use on a real external interface opens its configuration");
        var opened = (ConduitMenu) player.containerMenu;
        helper.assertValueEqual(opened.selectedFace(), Direction.WEST, "The interface's branch defines the bound face even when its top surface was clicked");
        var foreign = player(helper);
        foreign.setPos(player.position());
        var beforeForeign = bundle.getUpdateTag(helper.getLevel().registryAccess());
        helper.assertFalse(opened.stillValid(foreign), "A nearby player does not own another player's physical-interface session");
        helper.assertFalse(opened.clickMenuButton(foreign, 10), "A foreign direct mode action cannot use a retained menu reference");
        helper.assertFalse(opened.clickMenuButton(foreign, 20), "A foreign direct redstone action cannot use a retained menu reference");
        helper.assertValueEqual(bundle.getUpdateTag(helper.getLevel().registryAccess()), beforeForeign,
                "Rejected foreign actions preserve every lane, face and redstone preference");
        for (Direction face : Direction.values()) helper.assertFalse(opened.clickMenuButton(player, face.ordinal()),
                "Face-selection payloads cannot retarget an already-open external interface");
        helper.assertValueEqual(opened.selectedFace(), Direction.WEST, "Rejected face actions preserve the opening face");
        for (int step = 0; step < 3; step++) helper.assertTrue(opened.clickMenuButton(player, 10), "The native interface mode control cycles its own face");
        helper.assertValueEqual(bundle.mode(ConduitKind.ITEM, Direction.WEST), ConduitMode.DISABLED, "The interface can disable its own external route");
        helper.assertTrue(opened.stillValid(player) && opened.selectedFace() == Direction.WEST,
                "Disabling an open interface retains that face's configuration session");
        helper.assertTrue(bundle.visualState().connected(ConduitKind.ITEM, Direction.WEST)
                        && !bundle.visualState().endpoint(ConduitKind.ITEM, Direction.WEST),
                "A disabled API-capable external face retains only its wrenchable tube, not the interface plate");
        player.closeContainer();
        var beforeClosed = bundle.getUpdateTag(helper.getLevel().registryAccess());
        helper.assertFalse(opened.clickMenuButton(player, 10), "Closing a menu revokes direct mode actions even while its owner remains nearby");
        helper.assertFalse(opened.clickMenuButton(player, 20), "Closing a menu revokes direct redstone actions too");
        helper.assertValueEqual(bundle.getUpdateTag(helper.getLevel().registryAccess()), beforeClosed,
                "A closed retained menu cannot change the still-present physical interface");
        BlockHitResult stub = partHit(bundle, ConduitKind.ITEM, ConduitGeometry.Role.TUBE, Direction.WEST, Direction.UP);
        helper.getLevel().getBlockState(bundle.getBlockPos()).useWithoutItem(helper.getLevel(), player, stub);
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "A disabled external stub is not a GUI surface");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AutomationRegistry.CONDUIT_WRENCH));
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, stub));
        helper.assertValueEqual(bundle.mode(ConduitKind.ITEM, Direction.WEST), ConduitMode.EXTRACT,
                "Wrenching a disabled external tube cycles that branch directly back to EXTRACT");
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "External tube cycling opens no GUI");
        helper.assertTrue(bundle.visualState().endpoint(ConduitKind.ITEM, Direction.WEST), "Re-enabling restores the external interface plate");
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                partHit(bundle, ConduitKind.ITEM, ConduitGeometry.Role.ENDPOINT, Direction.WEST, Direction.UP)));
        helper.assertTrue(player.containerMenu instanceof ConduitMenu, "An ordinary wrench on the restored interface opens its face configuration");
        var menu = (ConduitMenu) player.containerMenu;
        helper.assertValueEqual(menu.selectedFace(), Direction.WEST, "Wrench interface use binds the same physical endpoint face");
        helper.assertFalse(menu.clickMenuButton(player, 99), "Unknown button IDs are rejected");
        helper.assertTrue(menu.clickMenuButton(player, 12), "Native button cycles only the bound energy face");
        helper.assertValueEqual(bundle.mode(ConduitKind.ENERGY, Direction.WEST), ConduitMode.DISABLED, "BOTH cycles to DISABLED on the bound face");
        helper.assertValueEqual(bundle.mode(ConduitKind.ENERGY, Direction.SOUTH), ConduitMode.DISABLED, "The interface cannot alter another face");
        helper.assertValueEqual(bundle.mode(ConduitKind.ITEM, Direction.WEST), ConduitMode.EXTRACT, "Menu changes cannot overwrite another lane");
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(target.items.isEmpty(), "HIGH extraction remains stopped without a signal");
            helper.getLevel().setBlock(bundle.getBlockPos().above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            helper.runAfterDelay(30, () -> {
                helper.assertValueEqual(count(target.items, Items.REDSTONE), 16, "A real redstone block permits extraction");
                helper.assertTrue(source.items.isEmpty(), "Redstone-gated transfer conserves its source");
                helper.getLevel().setBlock(bundle.getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
                helper.assertFalse(menu.stillValid(player), "The open menu cannot control a replacement block");
                helper.assertFalse(menu.clickMenuButton(player, 10), "Stale menus reject mutations after removal");
                helper.succeed();
            });
        });
    }
}
