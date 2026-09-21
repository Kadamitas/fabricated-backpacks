package com.kadamitas.fabricatedbackpacks.platform.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Lookup handle intentionally refreshes native capabilities so retained routes never outlive provider replacement. */
public final class BlockApiCache<T, C> {
    private final BlockLookup<T, ?> lookup;
    private final ServerLevel level;
    private final BlockPos position;
    private BlockApiCache(BlockLookup<T, ?> lookup, ServerLevel level, BlockPos position) { this.lookup = lookup; this.level = level; this.position = position.immutable(); }
    public static <T> BlockApiCache<T, Direction> create(BlockLookup<T, ?> lookup, ServerLevel level, BlockPos position) { return new BlockApiCache<>(lookup, level, position); }
    public T find(BlockState state, C side) { return lookup.find(level, position, state, level.getBlockEntity(position), (Direction) side); }
}
