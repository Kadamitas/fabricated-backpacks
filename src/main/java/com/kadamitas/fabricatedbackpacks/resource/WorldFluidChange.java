package com.kadamitas.fabricatedbackpacks.resource;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Only used for air/liquid blocks: tile entities and dropping blocks are excluded by the caller. */
final class WorldFluidChange extends SnapshotParticipant<BlockState> {
    private final ServerLevel level;
    private final BlockPos position;
    private final BlockState original;

    WorldFluidChange(ServerLevel level, BlockPos position) {
        this.level = level;
        this.position = position.immutable();
        this.original = level.getBlockState(position);
    }

    boolean set(BlockState state, TransactionContext transaction) {
        updateSnapshots(transaction);
        return level.setBlock(position, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
    }

    @Override protected BlockState createSnapshot() { return level.getBlockState(position); }
    @Override protected void readSnapshot(BlockState state) {
        level.setBlock(position, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
    }

    @Override protected void onFinalCommit() {
        BlockState current = level.getBlockState(position);
        level.sendBlockUpdated(position, original, current, Block.UPDATE_ALL);
        level.updateNeighborsAt(position, current.getBlock());
        var fluid = current.getFluidState();
        if (!fluid.isEmpty()) level.scheduleTick(position, fluid.getType(), fluid.getType().getTickDelay(level));
    }
}
