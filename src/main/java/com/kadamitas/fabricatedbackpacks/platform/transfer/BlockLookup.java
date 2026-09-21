package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;

public final class BlockLookup<T, N> {
    @FunctionalInterface public interface Provider<T> { T find(Level level, BlockPos position, BlockState state, BlockEntity entity, Direction side); }
    final Capability<N> capability;
    private final Function<N, T> wrap;
    final Function<T, N> unwrap;
    private final Map<BlockEntityType<?>, BiFunction<BlockEntity, Direction, T>> providers = new IdentityHashMap<>();
    private final Map<net.minecraft.world.level.block.Block, Provider<T>> blockProviders = new IdentityHashMap<>();
    public BlockLookup(Capability<N> capability, Function<N, T> wrap, Function<T, N> unwrap) {
        this.capability = capability; this.wrap = wrap; this.unwrap = unwrap; NativeCapabilities.BLOCKS.add(this);
    }
    public T find(Level level, BlockPos pos, Direction side) { return find(level, pos, level.getBlockState(pos), level.getBlockEntity(pos), side); }
    public T find(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction side) {
        var blockProvider = blockProviders.get(state.getBlock());
        if (blockProvider != null) {
            T result = blockProvider.find(level, pos, state, entity, side);
            if (result != null) return result;
        }
        if (entity == null || entity.isRemoved()) return null;
        T local = local(entity, side);
        if (local != null) return local;
        return entity.getCapability(capability, side).map(value -> wrap.apply(value)).orElse(null);
    }
    T local(BlockEntity entity, Direction side) { var provider = providers.get(entity.getType()); return provider == null ? null : provider.apply(entity, side); }
    N exported(BlockEntity entity, Direction side) { T local = local(entity, side); return local == null ? null : unwrap.apply(local); }
    @SuppressWarnings("unchecked") public <BE extends BlockEntity> void registerForBlockEntity(BiFunction<BE, Direction, T> provider, BlockEntityType<BE> type) {
        if (providers.putIfAbsent(type, (entity, side) -> provider.apply((BE) entity, side)) != null) throw new IllegalStateException("Duplicate resource provider");
    }
    public void registerForBlocks(Provider<T> provider, net.minecraft.world.level.block.Block... blocks) {
        for (var block : blocks) if (blockProviders.putIfAbsent(block, provider) != null)
            throw new IllegalStateException("Duplicate block resource provider");
    }
}
