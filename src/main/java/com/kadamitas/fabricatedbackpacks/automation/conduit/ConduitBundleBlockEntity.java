package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import net.fabricmc.fabric.api.blockview.v2.RenderDataBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.TransferVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A bundle stores configuration only. Resource ownership always remains in the attached machines. */
public final class ConduitBundleBlockEntity extends BlockEntity implements RenderDataBlockEntity {
    private int installedMask;
    private final ConduitMode[][] modes = new ConduitMode[3][6];
    private final ConduitRedstone[][] redstone = new ConduitRedstone[3][6];
    private final ConduitFilter[][] filters = new ConduitFilter[2][6];
    private final long[] laneGeneration = new long[3];
    private volatile ConduitVisualState visual = ConduitVisualState.EMPTY;

    public ConduitBundleBlockEntity(BlockPos position, BlockState state) {
        super(AutomationRegistry.CONDUIT_BUNDLE_ENTITY, position, state);
        for (ConduitKind kind : ConduitKind.values()) reset(kind);
    }

    public boolean has(ConduitKind kind) { return (installedMask & kind.mask()) != 0; }
    public int installedMask() { return installedMask; }
    public Set<ConduitKind> installed() {
        EnumSet<ConduitKind> result = EnumSet.noneOf(ConduitKind.class);
        for (ConduitKind kind : ConduitKind.values()) if (has(kind)) result.add(kind);
        return Set.copyOf(result);
    }
    public ConduitMode mode(ConduitKind kind, Direction side) { return modes[kind.ordinal()][side.ordinal()]; }
    public ConduitRedstone redstone(ConduitKind kind, Direction side) { return redstone[kind.ordinal()][side.ordinal()]; }
    public ConduitFilter filter(ConduitKind kind, Direction side) {
        return kind == ConduitKind.ENERGY ? ConduitFilter.EMPTY : filters[kind.ordinal()][side.ordinal()];
    }
    public boolean setFilter(ConduitKind kind, Direction side, ConduitFilter filter) {
        Objects.requireNonNull(filter, "filter");
        if (kind == ConduitKind.ENERGY || !has(kind) || level != null && level.isClientSide
                || filter(kind, side).equals(filter)) return false;
        filters[kind.ordinal()][side.ordinal()] = filter;
        // Policies do not change connectivity. Existing endpoint/view handles must read this live value.
        if (current()) setChanged();
        return true;
    }
    boolean accepts(ConduitKind kind, Direction side, TransferVariant<?> resource) {
        if (!has(kind) || resource.isBlank()) return false;
        if (kind == ConduitKind.ITEM && resource instanceof ItemVariant item) {
            var id = BuiltInRegistries.ITEM.getKey(item.getItem());
            return id != null && filter(kind, side).matches(id);
        }
        if (kind == ConduitKind.FLUID && resource instanceof FluidVariant fluid) {
            var id = BuiltInRegistries.FLUID.getKey(fluid.getFluid());
            return id != null && filter(kind, side).matches(id);
        }
        return false;
    }
    public long laneGeneration(ConduitKind kind) { return laneGeneration[kind.ordinal()]; }
    public ConduitVisualState visualState() { return visual; }
    @Override public Object getRenderData() { return visual; }
    public int connectionMask(ConduitKind kind) { return visual.connectionMask(kind); }

    public boolean install(ConduitKind kind) {
        Objects.requireNonNull(kind);
        if (has(kind) || level != null && level.isClientSide) return false;
        installedMask |= kind.mask();
        laneGeneration[kind.ordinal()]++;
        reset(kind);
        configurationChanged(kind);
        return true;
    }

    /** The caller gives or drops this one item; removing the final lane does not execute block loot. */
    public ItemStack remove(ConduitKind kind) {
        if (!has(kind) || level != null && level.isClientSide) return ItemStack.EMPTY;
        installedMask &= ~kind.mask();
        laneGeneration[kind.ordinal()]++;
        reset(kind);
        configurationChanged(kind);
        return new ItemStack(AutomationRegistry.conduit(kind));
    }

    public boolean setMode(ConduitKind kind, Direction side, ConduitMode mode) {
        Objects.requireNonNull(mode);
        if (!has(kind) || mode(kind, side) == mode || level != null && level.isClientSide) return false;
        modes[kind.ordinal()][side.ordinal()] = mode;
        configurationChanged(kind);
        return true;
    }

    public boolean setRedstone(ConduitKind kind, Direction side, ConduitRedstone control) {
        Objects.requireNonNull(control);
        if (!has(kind) || redstone(kind, side) == control || level != null && level.isClientSide) return false;
        redstone[kind.ordinal()][side.ordinal()] = control;
        configurationChanged(kind);
        return true;
    }

    public boolean extracts(ConduitKind kind, Direction side) {
        return has(kind) && mode(kind, side).extracts() && level != null
                && (redstone(kind, side) == ConduitRedstone.ALWAYS || redstone(kind, side).permits(ConduitNetworks.powered(this)));
    }

    public boolean current() {
        if (isRemoved() || !(level instanceof ServerLevel server)) return false;
        // BE_LOAD also runs inside a chunk's still-incomplete FULL future. Never wait for
        // that same future, or promote/create an entity while checking a retained identity.
        var chunk = server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
        return chunk != null && chunk.getBlockEntities().get(worldPosition) == this;
    }

    public boolean stillValid(Player player) {
        return current() && player.level() == level && !player.isSpectator() && player.isAlive()
                && player.distanceToSqr(Vec3.atCenterOf(worldPosition)) <= 64
                && level.mayInteract(player, worldPosition);
    }

    public List<ItemStack> drops() {
        List<ItemStack> result = new ArrayList<>();
        for (ConduitKind kind : ConduitKind.values()) if (has(kind)) result.add(new ItemStack(AutomationRegistry.conduit(kind)));
        return List.copyOf(result);
    }

    private void reset(ConduitKind kind) {
        java.util.Arrays.fill(modes[kind.ordinal()], ConduitMode.defaultFor(kind));
        java.util.Arrays.fill(redstone[kind.ordinal()], ConduitRedstone.ALWAYS);
        if (kind != ConduitKind.ENERGY) java.util.Arrays.fill(filters[kind.ordinal()], ConduitFilter.EMPTY);
    }

    private void configurationChanged(ConduitKind kind) {
        // Detached/load-time configuration is valid, but it must not notify or load a world chunk.
        if (!current()) return;
        if (level instanceof ServerLevel server) {
            ConduitNetworks.changed(this, kind);
            setChanged();
            refreshVisual();
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public void refreshVisual() {
        if (!(level instanceof ServerLevel) || !current()) return;
        ConduitVisualState next = ConduitNetworks.describe(this);
        if (!next.equals(visual)) {
            visual = next;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public static void tick(Level level, BlockPos position, BlockState state, ConduitBundleBlockEntity entity) {
        if (level instanceof ServerLevel) {
            ConduitNetworks.register(entity);
            if (Math.floorMod(level.getGameTime() + position.asLong(), 20) == 0) entity.refreshVisual();
        }
    }

    @Override public void setRemoved() {
        if (level instanceof ServerLevel) ConduitNetworks.unregister(this);
        super.setRemoved();
    }

    @Override protected void saveAdditional(CompoundTag output, HolderLookup.Provider registries) {
        super.saveAdditional(output, registries);
        output.putInt("installed", installedMask);
        for (ConduitKind kind : ConduitKind.values()) {
            output.putInt(kind.name().toLowerCase(java.util.Locale.ROOT) + "_modes", packedModes(kind));
            output.putInt(kind.name().toLowerCase(java.util.Locale.ROOT) + "_redstone", packedRedstone(kind));
            if (kind != ConduitKind.ENERGY) for (Direction side : Direction.values())
                if (!filter(kind, side).equals(ConduitFilter.EMPTY))
                    output.put(filterKey(kind, side), ConduitFilter.CODEC.encodeStart(NbtOps.INSTANCE, filter(kind, side)).getOrThrow());
        }
    }

    private int packedModes(ConduitKind kind) {
        int result = 0;
        for (Direction face : Direction.values()) result |= mode(kind, face).ordinal() << (face.ordinal() * 2);
        return result;
    }
    private int packedRedstone(ConduitKind kind) {
        int result = 0;
        for (Direction face : Direction.values()) result |= redstone(kind, face).ordinal() << (face.ordinal() * 2);
        return result;
    }

    @Override protected void loadAdditional(CompoundTag input, HolderLookup.Provider registries) {
        super.loadAdditional(input, registries);
        installedMask = NbtAccess.getIntOr(input, "installed", 0) & 7;
        for (ConduitKind kind : ConduitKind.values()) {
            reset(kind);
            String key = kind.name().toLowerCase(java.util.Locale.ROOT);
            int packed = NbtAccess.getIntOr(input, key + "_modes", packedModes(kind));
            int gates = NbtAccess.getIntOr(input, key + "_redstone", 0);
            for (Direction side : Direction.values()) {
                modes[kind.ordinal()][side.ordinal()] = ConduitMode.values()[(packed >>> (side.ordinal() * 2)) & 3];
                int gate = (gates >>> (side.ordinal() * 2)) & 3;
                redstone[kind.ordinal()][side.ordinal()] = gate < 3 ? ConduitRedstone.values()[gate] : ConduitRedstone.ALWAYS;
                if (kind != ConduitKind.ENERGY) {
                    // Preserve missing-mod identities. Missing legacy fields mean OFF, but a damaged
                    // present policy must not accidentally open a previously restricted interface.
                    var encoded = input.get(filterKey(kind, side));
                    filters[kind.ordinal()][side.ordinal()] = encoded == null ? ConduitFilter.EMPTY
                            : ConduitFilter.CODEC.parse(NbtOps.INSTANCE, encoded).result().orElse(ConduitFilter.DENY_ALL);
                }
            }
            laneGeneration[kind.ordinal()]++;
        }
        ConduitVisualState next = new ConduitVisualState(installedMask, NbtAccess.getIntOr(input, "connections", 0),
                NbtAccess.getIntOr(input, "endpoints", 0), NbtAccess.getIntOr(input, "extract", 0), NbtAccess.getIntOr(input, "insert", 0), NbtAccess.getIntOr(input, "neighbors", 0));
        if (!next.equals(visual)) {
            visual = next;
            if (level != null && level.isClientSide && !isRemoved())
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        if (level instanceof ServerLevel) for (ConduitKind kind : ConduitKind.values()) ConduitNetworks.changed(this, kind);
    }
    private static String filterKey(ConduitKind kind, Direction side) {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + "_filter_" + side.getSerializedName();
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("installed", installedMask);
        for (ConduitKind kind : ConduitKind.values()) {
            String key = kind.name().toLowerCase(java.util.Locale.ROOT);
            tag.putInt(key + "_modes", packedModes(kind));
            tag.putInt(key + "_redstone", packedRedstone(kind));
        }
        tag.putInt("connections", visual.connectionBits());
        tag.putInt("endpoints", visual.endpointBits());
        tag.putInt("extract", visual.extractBits());
        tag.putInt("insert", visual.insertBits());
        tag.putInt("neighbors", visual.neighborBits());
        return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void saveToItem(ItemStack picked, HolderLookup.Provider registries) {
        // Pick-block owns one conduit item, not a copy of every installed lane or interface policy.
    }
}
