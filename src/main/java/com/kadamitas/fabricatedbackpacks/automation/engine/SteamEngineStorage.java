package com.kadamitas.fabricatedbackpacks.automation.engine;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import team.reborn.energy.api.EnergyStorage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Every retained endpoint rechecks physical ownership; internal work uses the same transaction system. */
final class SteamEngineStorage {
    static final FluidVariant WATER = FluidVariant.of(Fluids.WATER);
    private final SteamEngineBlockEntity engine;
    private final ContainerStorage internal;
    private final List<SlottedStorage<ItemVariant>> itemPorts;
    private final List<SingleSlotStorage<FluidVariant>> waterPorts;
    private final List<EnergyStorage> energyPorts;
    private final SingleSlotStorage<FluidVariant> internalWater;

    SteamEngineStorage(SteamEngineBlockEntity engine) {
        this.engine = engine;
        // Internal recipe remainders must be able to enter slots that reject external insertion.
        internal = ContainerStorage.of(new Container() {
            @Override public int getContainerSize() { return SteamEngineBlockEntity.SLOT_COUNT; }
            @Override public boolean isEmpty() { return engine.isEmpty(); }
            @Override public ItemStack getItem(int slot) { return engine.getItem(slot); }
            @Override public ItemStack removeItem(int slot, int count) { return engine.removeItem(slot, count); }
            @Override public ItemStack removeItemNoUpdate(int slot) { return engine.removeItemNoUpdate(slot); }
            @Override public void setItem(int slot, ItemStack stack) { engine.setItem(slot, stack); }
            @Override public int getMaxStackSize() { return engine.getMaxStackSize(); }
            @Override public void setChanged() { engine.setChanged(); }
            @Override public boolean stillValid(Player player) { return engine.stillValid(player); }
            @Override public void clearContent() { engine.clearContent(); }
        }, null);
        List<SlottedStorage<ItemVariant>> items = new ArrayList<>();
        List<SingleSlotStorage<FluidVariant>> fluids = new ArrayList<>();
        List<EnergyStorage> energy = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            Direction side = index < 6 ? Direction.values()[index] : null;
            items.add(createItems(side));
            fluids.add(new WaterPort(side, false));
            energy.add(new EnergyPort(side));
        }
        itemPorts = List.copyOf(items);
        waterPorts = List.copyOf(fluids);
        energyPorts = List.copyOf(energy);
        internalWater = new WaterPort(null, true);
    }

    private SlottedStorage<ItemVariant> createItems(Direction side) {
        List<SingleSlotStorage<ItemVariant>> views = new ArrayList<>();
        for (int slot = 0; slot < SteamEngineBlockEntity.SLOT_COUNT; slot++) views.add(new ItemPort(slot, side));
        List<SingleSlotStorage<ItemVariant>> slots = List.copyOf(views);
        return new SlottedStorage<>() {
            @Override public int getSlotCount() { return slots.size(); }
            @Override public SingleSlotStorage<ItemVariant> getSlot(int slot) { return slots.get(slot); }
            @Override public List<SingleSlotStorage<ItemVariant>> getSlots() { return slots; }
            @Override public boolean supportsInsertion() { return input(ConduitKind.ITEM, side); }
            @Override public boolean supportsExtraction() { return output(ConduitKind.ITEM, side); }
            @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
                StoragePreconditions.notBlankNotNegative(resource, maximum);
                long moved = 0;
                for (var slot : slots) {
                    moved += slot.insert(resource, maximum - moved, transaction);
                    if (moved == maximum) break;
                }
                return moved;
            }
            @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
                StoragePreconditions.notBlankNotNegative(resource, maximum);
                long moved = 0;
                for (var slot : slots) {
                    moved += slot.extract(resource, maximum - moved, transaction);
                    if (moved == maximum) break;
                }
                return moved;
            }
            @Override public Iterator<StorageView<ItemVariant>> iterator() {
                return slots.stream().map(slot -> (StorageView<ItemVariant>) slot).iterator();
            }
        };
    }

    SlottedStorage<ItemVariant> items(Direction side) { return itemPorts.get(sideIndex(side)); }
    SingleSlotStorage<FluidVariant> water(Direction side) { return waterPorts.get(sideIndex(side)); }
    EnergyStorage energy(Direction side) { return energyPorts.get(sideIndex(side)); }
    SingleSlotStorage<ItemVariant> internalSlot(int slot) { return internal.getSlot(slot); }
    private static int sideIndex(Direction side) { return side == null ? 6 : side.ordinal(); }
    private boolean input(ConduitKind kind, Direction side) { return engine.current() && engine.sideConfig().allowsInput(kind, side); }
    private boolean output(ConduitKind kind, Direction side) { return engine.current() && engine.sideConfig().allowsOutput(kind, side); }

    static boolean containsWater(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Storage<FluidVariant> storage = ContainerItemContext.withConstant(stack).find(FluidStorage.ITEM);
        if (storage == null || !storage.supportsExtraction()) return false;
        for (StorageView<FluidVariant> view : storage)
            if (WATER.equals(view.getResource()) && view.getAmount() > 0) return true;
        return false;
    }

    boolean remainderFits(int slot, ItemStack remainder) {
        if (remainder.isEmpty()) return true;
        ItemStack current = engine.getItem(slot);
        return (current.isEmpty() || ItemStack.isSameItemSameComponents(current, remainder))
                && (long) current.getCount() + remainder.getCount() <= engine.getMaxStackSize(remainder);
    }

    void drainWaterContainer() {
        if (!engine.currentServer() || engine.getItem(SteamEngineBlockEntity.WATER_INPUT).isEmpty()) return;
        SingleSlotStorage<ItemVariant> input = internalSlot(SteamEngineBlockEntity.WATER_INPUT);
        SingleSlotStorage<ItemVariant> output = internalSlot(SteamEngineBlockEntity.WATER_REMAINDER);
        ContainerItemContext context = new ContainerItemContext() {
            @Override public SingleSlotStorage<ItemVariant> getMainSlot() { return input; }
            @Override public long insert(ItemVariant variant, long maximum, TransactionContext transaction) {
                return output.insert(variant, maximum, transaction);
            }
            @Override public long insertOverflow(ItemVariant variant, long maximum, TransactionContext transaction) {
                return output.insert(variant, maximum, transaction);
            }
            @Override public List<SingleSlotStorage<ItemVariant>> getAdditionalSlots() { return List.of(output); }
        };
        Storage<FluidVariant> from = context.find(FluidStorage.ITEM);
        if (from == null) return;
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = StorageUtil.move(from, internalWater, WATER::equals,
                    SteamEngineBlockEntity.droplets(SteamEngineBlockEntity.rules().containerTransferMbPerTick()), transaction);
            if (moved > 0) transaction.commit();
        }
    }

    void pushEnergy(ServerLevel level) {
        Direction[] directions = Direction.values();
        int first = Math.floorMod(level.getGameTime(), directions.length);
        for (int offset = 0; offset < directions.length && engine.snapshot().energy() > 0 && engine.outputRemaining() > 0; offset++) {
            Direction direction = directions[(first + offset) % directions.length];
            EnergyStorage energy = energy(direction);
            if (!energy.supportsExtraction()) continue;
            BlockPos neighbor = engine.getBlockPos().relative(direction);
            var targetChunk = level.getChunkSource().getChunkNow(neighbor.getX() >> 4, neighbor.getZ() >> 4);
            if (targetChunk == null) continue;
            BlockState sourceState = engine.getBlockState();
            BlockState targetState = targetChunk.getBlockState(neighbor);
            BlockEntity targetEntity = targetChunk.getBlockEntities().get(neighbor);
            // Fabric otherwise promotes a pending BE when a null entity accompanies a BE-bearing state.
            if (targetState.hasBlockEntity() && targetEntity == null) continue;
            EnergyStorage target = EnergyStorage.SIDED.find(level, neighbor, targetState, targetEntity, direction.getOpposite());
            if (target == null || target == energy || !target.supportsInsertion()) continue;
            try (Transaction transaction = Transaction.openOuter()) {
                long available;
                try (Transaction simulation = transaction.openNested()) {
                    available = energy.extract(engine.outputRemaining(), simulation);
                }
                long inserted = target.insert(available, transaction);
                if (inserted <= 0 || inserted > available || !engine.currentServer()
                        || !endpoint(level, engine.getBlockPos(), sourceState, engine)
                        || !endpoint(level, neighbor, targetState, targetEntity)) continue;
                if (energy.extract(inserted, transaction) == inserted && engine.currentServer()
                        && endpoint(level, engine.getBlockPos(), sourceState, engine)
                        && endpoint(level, neighbor, targetState, targetEntity)) transaction.commit();
            }
        }
    }

    private static boolean endpoint(ServerLevel level, BlockPos position, BlockState state, BlockEntity entity) {
        var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        return chunk != null && chunk.getBlockState(position) == state && chunk.getBlockEntities().get(position) == entity
                && (entity == null || !entity.isRemoved());
    }

    private final class ItemPort implements SingleSlotStorage<ItemVariant> {
        private final int index;
        private final Direction side;
        ItemPort(int index, Direction side) { this.index = index; this.side = side; }
        private SingleSlotStorage<ItemVariant> delegate() { return internal.getSlot(index); }
        @Override public boolean supportsInsertion() {
            return input(ConduitKind.ITEM, side) && (index == SteamEngineBlockEntity.FUEL || index == SteamEngineBlockEntity.WATER_INPUT);
        }
        @Override public boolean supportsExtraction() {
            return output(ConduitKind.ITEM, side) && (index == SteamEngineBlockEntity.FUEL_REMAINDER || index == SteamEngineBlockEntity.WATER_REMAINDER);
        }
        private boolean readable() { return engine.currentServer() && (supportsInsertion() || supportsExtraction()); }
        @Override public boolean isResourceBlank() { return getResource().isBlank(); }
        @Override public ItemVariant getResource() { return readable() ? delegate().getResource() : ItemVariant.blank(); }
        @Override public long getAmount() { return readable() ? delegate().getAmount() : 0; }
        @Override public long getCapacity() { return readable() ? delegate().getCapacity() : 0; }
        @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            return engine.currentServer() && supportsInsertion() && engine.canPlaceItem(index, resource.toStack())
                    ? delegate().insert(resource, maximum, transaction) : 0;
        }
        @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            return engine.currentServer() && supportsExtraction() ? delegate().extract(resource, maximum, transaction) : 0;
        }
    }

    private final class WaterPort implements SingleSlotStorage<FluidVariant> {
        private final Direction side;
        private final boolean internal;
        WaterPort(Direction side, boolean internal) { this.side = side; this.internal = internal; }
        @Override public boolean supportsInsertion() { return internal ? engine.current() : input(ConduitKind.FLUID, side); }
        @Override public boolean supportsExtraction() { return internal ? engine.current() : output(ConduitKind.FLUID, side); }
        private boolean readable() { return engine.currentServer() && (supportsInsertion() || supportsExtraction()); }
        @Override public boolean isResourceBlank() { return getAmount() == 0; }
        @Override public FluidVariant getResource() { return getAmount() == 0 ? FluidVariant.blank() : WATER; }
        @Override public long getAmount() { return readable() ? engine.snapshot().waterDroplets() : 0; }
        @Override public long getCapacity() { return readable() ? Math.max(engine.waterCapacityDroplets(), getAmount()) : 0; }
        @Override public long insert(FluidVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            if (!engine.currentServer() || !supportsInsertion() || !WATER.equals(resource)) return 0;
            long inserted = Math.min(maximum, Math.max(0, engine.waterCapacityDroplets() - getAmount()));
            if (inserted > 0) engine.updateResources(engine.snapshot().resources(getAmount() + inserted, engine.snapshot().energy()), transaction);
            return inserted;
        }
        @Override public long extract(FluidVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            if (!engine.currentServer() || !supportsExtraction() || !WATER.equals(resource)) return 0;
            long extracted = Math.min(maximum, getAmount());
            if (extracted > 0) engine.updateResources(engine.snapshot().resources(getAmount() - extracted, engine.snapshot().energy()), transaction);
            return extracted;
        }
    }

    private final class EnergyPort implements EnergyStorage {
        private final Direction side;
        EnergyPort(Direction side) { this.side = side; }
        @Override public boolean supportsInsertion() { return false; }
        @Override public boolean supportsExtraction() { return output(ConduitKind.ENERGY, side); }
        @Override public long getAmount() { return engine.currentServer() && supportsExtraction() ? engine.snapshot().energy() : 0; }
        @Override public long getCapacity() { return engine.currentServer() && supportsExtraction() ? Math.max(engine.energyCapacity(), getAmount()) : 0; }
        @Override public long insert(long maximum, TransactionContext transaction) {
            StoragePreconditions.notNegative(maximum);
            return 0;
        }
        @Override public long extract(long maximum, TransactionContext transaction) {
            StoragePreconditions.notNegative(maximum);
            if (!engine.currentServer() || !supportsExtraction()) return 0;
            long extracted = Math.min(Math.min(maximum, getAmount()), engine.outputRemaining());
            if (extracted > 0) {
                engine.accountOutput(extracted, transaction);
                engine.updateResources(engine.snapshot().resources(engine.snapshot().waterDroplets(), getAmount() - extracted), transaction);
            }
            return extracted;
        }
    }
}
