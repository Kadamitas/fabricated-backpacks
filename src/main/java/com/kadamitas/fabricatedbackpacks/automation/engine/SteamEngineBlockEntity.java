package com.kadamitas.fabricatedbackpacks.automation.engine;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.config.AutomationConfig;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import team.reborn.energy.api.EnergyStorage;

/** A water boiler, vanilla fuel chamber and finite generator buffer in one persistent machine. */
public final class SteamEngineBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer, ExtendedScreenHandlerFactory<BlockPos> {
    public static final int FUEL = 0, WATER_INPUT = 1, FUEL_REMAINDER = 2, WATER_REMAINDER = 3, SLOT_COUNT = 4;
    private static final int[] ALL_SLOTS = {FUEL, WATER_INPUT, FUEL_REMAINDER, WATER_REMAINDER};
    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private SteamEngineState state = SteamEngineState.EMPTY;
    private SteamEngineSides sides = SteamEngineSides.DEFAULT;
    private final SteamEngineStorage storage = new SteamEngineStorage(this);
    private final ResourceTransaction resources = new ResourceTransaction();
    private long lastTick = Long.MIN_VALUE;
    private long outputTick = Long.MIN_VALUE;
    private long outputUsed;
    private long animationTick = Long.MIN_VALUE;
    private float previousAngle, angle;

    public SteamEngineBlockEntity(BlockPos position, BlockState blockState) {
        super(AutomationRegistry.STEAM_ENGINE_ENTITY, position, blockState);
    }

    public SteamEngineState snapshot() { return state; }
    public SteamEngineSides sideConfig() { return sides; }
    public EngineSideMode sideMode(ConduitKind kind, Direction face) { return sides.mode(kind, face); }
    public boolean setSideMode(ConduitKind kind, Direction face, EngineSideMode mode) {
        if (!currentServer() || kind == null || face == null || mode == null
                || kind == ConduitKind.ENERGY && mode.allowsInput()) return false;
        SteamEngineSides changed = sides.with(kind, face, mode);
        if (changed == sides) return false;
        sides = changed;
        synchronizeSides();
        return true;
    }
    private void synchronizeSides() {
        if (!currentServer()) return;
        setChanged();
        level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
    }
    public boolean enabled() { return state.enabled(); }
    public boolean active() { return getBlockState().getValue(SteamEngineBlock.ACTIVE); }
    public long waterCapacityDroplets() { return droplets(rules().waterCapacityMb()); }
    public long energyCapacity() { return rules().energyCapacity(); }
    public Storage<ItemVariant> itemStorage(Direction side) { return storage.items(side); }
    public Storage<FluidVariant> fluidStorage(Direction side) { return storage.water(side); }
    public EnergyStorage energyStorage(Direction side) { return storage.energy(side); }

    public void setEnabled(boolean enabled) {
        if (!currentServer() || state.enabled() == enabled) return;
        state = state.enabled(enabled);
        if (!enabled) setActive(false);
        setChanged();
    }

    static AutomationConfig.Engine rules() { return BackpackConfig.get().automation().engine(); }
    static long droplets(long millibuckets) { return Math.multiplyExact(millibuckets, FluidConstants.BUCKET / 1_000); }
    boolean currentServer() { return level instanceof ServerLevel && current(); }
    boolean current() {
        if (level == null || isRemoved()) return false;
        if (level instanceof ServerLevel server) {
            // A BE load callback can run before its FULL chunk future completes. Never wait on that future.
            var chunk = server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
            return chunk != null && chunk.getBlockEntities().get(worldPosition) == this;
        }
        return level.hasChunkAt(worldPosition) && level.getBlockEntity(worldPosition) == this;
    }

    void updateResources(SteamEngineState next, TransactionContext transaction) {
        resources.updateSnapshots(transaction);
        state = next;
    }

    long outputRemaining() {
        if (!currentServer()) return 0;
        long tick = level.getGameTime();
        if (outputTick != tick) { outputTick = tick; outputUsed = 0; }
        return Math.max(0, rules().energyOutputPerTick() - outputUsed);
    }

    void accountOutput(long amount, TransactionContext transaction) {
        resources.updateSnapshots(transaction);
        outputUsed += amount;
    }

    public static void tick(ServerLevel level, BlockPos position, BlockState blockState, SteamEngineBlockEntity engine) {
        if (!engine.currentServer() || engine.level != level || engine.lastTick == level.getGameTime()) return;
        engine.lastTick = level.getGameTime();
        engine.storage.drainWaterContainer();
        engine.setActive(engine.generate(level));
        engine.storage.pushEnergy(level);
    }

    private boolean generate(ServerLevel level) {
        ItemStack fuel = getItem(FUEL);
        int duration = state.burnRemaining() > 0 || fuel.isEmpty() ? 0 : AbstractFurnaceBlockEntity.getFuel().getOrDefault(fuel.getItem(), 0);
        ItemStack remainder = ItemStack.EMPTY;
        if (duration > 0) remainder = fuel.getRecipeRemainder();
        boolean remainderFits = storage.remainderFits(FUEL_REMAINDER, remainder);
        AutomationConfig.Engine config = rules();
        SteamEngineCycle.Result next = SteamEngineCycle.step(state,
                new SteamEngineCycle.Limits(droplets(config.waterCapacityMb()), config.energyCapacity(),
                        droplets(config.waterMbPerTick()), config.energyPerTick()), duration, remainderFits);
        if (!next.generated()) return false;
        try (Transaction transaction = Transaction.openOuter()) {
            if (next.consumeFuel()) {
                if (storage.internalSlot(FUEL).extract(ItemVariant.of(fuel), 1, transaction) != 1) return false;
                if (!remainder.isEmpty() && storage.internalSlot(FUEL_REMAINDER)
                        .insert(ItemVariant.of(remainder), remainder.getCount(), transaction) != remainder.getCount()) return false;
            }
            updateResources(next.state(), transaction);
            transaction.commit();
        }
        return true;
    }

    private void setActive(boolean active) {
        if (!currentServer() || active() == active) return;
        level.setBlock(worldPosition, getBlockState().setValue(SteamEngineBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
        setChanged();
    }

    /** Client mechanics consume only the public block state, never private water/fuel/energy data. */
    public static void clientTick(Level level, BlockPos position, BlockState blockState, SteamEngineBlockEntity engine) {
        if (!level.isClientSide || engine.animationTick == level.getGameTime()) return;
        engine.animationTick = level.getGameTime();
        engine.previousAngle = engine.angle;
        if (blockState.getValue(SteamEngineBlock.ACTIVE)) engine.angle += (float) (Math.PI / 10);
        if (engine.angle >= Math.PI * 2) {
            engine.angle -= (float) (Math.PI * 2);
            engine.previousAngle -= (float) (Math.PI * 2);
        }
    }

    public float crankAngle(float partialTick) {
        return previousAngle + (angle - previousAngle) * Math.clamp(partialTick, 0, 1);
    }

    @Override public int getContainerSize() { return SLOT_COUNT; }
    @Override public int getMaxStackSize() { return 64; }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> contents) {
        if (contents.size() != SLOT_COUNT) throw new IllegalArgumentException("Steam engine requires four slots");
        items = contents;
    }
    @Override public void clearContent() { items.clear(); setChanged(); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        if (stack.isEmpty()) return false;
        return slot == FUEL ? level instanceof ServerLevel && AbstractFurnaceBlockEntity.isFuel(stack)
                : slot == WATER_INPUT && SteamEngineStorage.containsWater(stack);
    }
    @Override public int[] getSlotsForFace(Direction side) {
        boolean input = sides.allowsInput(ConduitKind.ITEM, side), output = sides.allowsOutput(ConduitKind.ITEM, side);
        return input && output ? ALL_SLOTS.clone() : input ? new int[]{FUEL, WATER_INPUT}
                : output ? new int[]{FUEL_REMAINDER, WATER_REMAINDER} : new int[0];
    }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return currentServer() && sides.allowsInput(ConduitKind.ITEM, side) && canPlaceItem(slot, stack);
    }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return currentServer() && sides.allowsOutput(ConduitKind.ITEM, side)
                && (slot == FUEL_REMAINDER || slot == WATER_REMAINDER);
    }
    @Override public boolean canTakeItem(net.minecraft.world.Container into, int slot, ItemStack stack) {
        return canTakeItemThroughFace(slot, stack, null);
    }

    @Override public boolean stillValid(Player player) {
        return !player.isSpectator() && player.isAlive() && currentServer() && player.level() == level
                && level.mayInteract(player, worldPosition) && super.stillValid(player) && canOpen(player);
    }
    @Override protected Component getDefaultName() { return Component.translatable("block.fabricated_backpacks.steam_engine"); }
    @Override public BlockPos getScreenOpeningData(ServerPlayer player) { return worldPosition; }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new SteamEngineMenu(id, inventory, this);
    }

    public ItemStack dropStack() {
        ItemStack item = new ItemStack(AutomationRegistry.STEAM_ENGINE_ITEM);
        item.applyComponents(collectComponents());
        return item;
    }

    public boolean hasStoredContents() {
        return !isEmpty() || state.waterDroplets() > 0 || state.energy() > 0 || state.burnRemaining() > 0
                || !sides.equals(SteamEngineSides.DEFAULT);
    }

    @Override protected void saveAdditional(CompoundTag output, HolderLookup.Provider registries) {
        super.saveAdditional(output, registries);
        ContainerHelper.saveAllItems(output, items, registries);
        output.put("engine", SteamEngineState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow());
        output.put("ports", SteamEngineSides.CODEC.encodeStart(NbtOps.INSTANCE, sides).getOrThrow());
    }
    @Override protected void loadAdditional(CompoundTag input, HolderLookup.Provider registries) {
        super.loadAdditional(input, registries);
        items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items, registries);
        state = input.contains("engine") ? SteamEngineState.CODEC.parse(NbtOps.INSTANCE, input.get("engine")).result().orElse(SteamEngineState.EMPTY) : SteamEngineState.EMPTY;
        SteamEngineSides previousSides = sides;
        sides = input.contains("ports") ? SteamEngineSides.CODEC.parse(NbtOps.INSTANCE, input.get("ports")).result().orElse(SteamEngineSides.DEFAULT) : SteamEngineSides.DEFAULT;
        lastTick = outputTick = Long.MIN_VALUE;
        outputUsed = 0;
        if (!sides.equals(previousSides)) {
            if (currentServer()) synchronizeSides();
            else if (level != null && level.isClientSide && current())
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }
    @Override protected void applyImplicitComponents(DataComponentInput components) {
        super.applyImplicitComponents(components);
        state = components.getOrDefault(SteamEngineComponents.STATE, SteamEngineState.EMPTY);
        SteamEngineSides previousSides = sides;
        sides = components.getOrDefault(SteamEngineComponents.SIDES, SteamEngineSides.DEFAULT);
        if (!sides.equals(previousSides)) synchronizeSides();
    }
    @Override protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(SteamEngineComponents.STATE, state);
        components.set(SteamEngineComponents.SIDES, sides);
    }
    @Override public void removeComponentsFromTag(CompoundTag output) {
        super.removeComponentsFromTag(output);
        output.remove("engine");
        output.remove("ports");
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("ports", sides.bits());
        return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    ContainerData data() {
        return new ContainerData() {
            @Override public int get(int index) {
                if (index == SteamEngineMenu.ENABLED_DATA) return enabled() ? 1 : 0;
                if (index == SteamEngineMenu.ACTIVE_DATA) return active() ? 1 : 0;
                long value = switch (index / 4) {
                    case 0 -> state.waterDroplets();
                    case 1 -> waterCapacityDroplets();
                    case 2 -> state.energy();
                    case 3 -> energyCapacity();
                    case 4 -> state.burnRemaining();
                    case 5 -> state.burnDuration();
                    default -> 0;
                };
                return SteamEngineWords.word(value, index % 4);
            }
            @Override public void set(int index, int value) { }
            @Override public int getCount() { return SteamEngineMenu.DATA_COUNT; }
        };
    }

    private record ResourceSnapshot(SteamEngineState state, long outputTick, long outputUsed) { }
    private final class ResourceTransaction extends SnapshotParticipant<ResourceSnapshot> {
        @Override protected ResourceSnapshot createSnapshot() { return new ResourceSnapshot(state, outputTick, outputUsed); }
        @Override protected void readSnapshot(ResourceSnapshot previous) {
            state = previous.state(); outputTick = previous.outputTick(); outputUsed = previous.outputUsed();
        }
        @Override protected void onFinalCommit() { if (currentServer()) setChanged(); }
    }
}
