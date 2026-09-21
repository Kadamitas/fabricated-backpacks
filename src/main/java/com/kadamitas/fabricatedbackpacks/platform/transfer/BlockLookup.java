package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;

public final class BlockLookup<T, N> {
    @FunctionalInterface public interface Provider<T> { T find(Level level, BlockPos position, BlockState state, BlockEntity entity, Direction side); }
    private final BlockCapability<N, Direction> capability;
    private final Function<N, T> wrap;
    private final Function<T, N> unwrap;
    public BlockLookup(BlockCapability<N, Direction> capability, Function<N, T> wrap, Function<T, N> unwrap) {
        this.capability = capability; this.wrap = wrap; this.unwrap = unwrap;
    }
    public T find(Level level, BlockPos pos, Direction side) { return find(level, pos, level.getBlockState(pos), level.getBlockEntity(pos), side); }
    public T find(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction side) {
        N result = level.getCapability(capability, pos, state, entity, side);
        return result == null ? null : wrap.apply(result);
    }
    public <BE extends BlockEntity> void registerForBlockEntity(BiFunction<BE, Direction, T> provider, BlockEntityType<BE> type) {
        NativeCapabilities.add(event -> event.registerBlockEntity(capability, type, (entity, side) -> {
            T value = provider.apply(entity, side); return value == null ? null : unwrap.apply(value);
        }));
    }
    public void registerForBlocks(Provider<T> provider, net.minecraft.world.level.block.Block... blocks) {
        NativeCapabilities.add(event -> event.registerBlock(capability, (level, position, state, entity, side) -> {
            T value = provider.find(level, position, state, entity, side); return value == null ? null : unwrap.apply(value);
        }, blocks));
    }
}
