package com.kadamitas.fabricatedbackpacks.automation.conduit;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Runs only at normal player mining completion, after vanilla and Fabric protection checks. */
public final class ConduitMining {
    public enum Result { PASS, REJECTED, REMOVED }

    private ConduitMining() {}

    /** PASS retains vanilla final-lane removal, including water restoration and ordinary block loot. */
    public static Result complete(ServerPlayer player, BlockPos position) {
        var level = player.serverLevel();
        var state = level.getBlockState(position);
        if (!(state.getBlock() instanceof ConduitBundleBlock)) return Result.PASS;
        if (!(level.getBlockEntity(position) instanceof ConduitBundleBlockEntity bundle)
                || !bundle.stillValid(player) || !player.canInteractWithBlock(position, 0)
                || level.getServer().isUnderSpawnProtection(level, position, player)) {
            resync(player, position);
            return Result.REJECTED;
        }

        bundle.refreshVisual();
        // Movement and mining packets can arrive in one tick, before head yaw follows the latest look.
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye,
                eye.add(player.getLookAngle().scale(player.blockInteractionRange())),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(position)) {
            resync(player, position);
            return Result.REJECTED;
        }
        var part = ConduitGeometry.hitPart(bundle.visualState(),
                hit.getLocation().subtract(Vec3.atLowerCornerOf(position)), hit.getDirection()).orElse(null);
        if (part == null || !bundle.has(part.kind())) {
            resync(player, position);
            return Result.REJECTED;
        }
        // A stale hit must not remove a different final lane either. Only a current surface reaches vanilla.
        if (Integer.bitCount(bundle.installedMask()) == 1) return Result.PASS;

        boolean drops = !player.isCreative();
        boolean correctTool = player.hasCorrectToolForDrops(state);
        ItemStack removed = bundle.remove(part.kind());
        if (removed.isEmpty()) {
            resync(player, position);
            return Result.REJECTED;
        }
        if (drops) {
            player.getMainHandItem().mineBlock(level, state, position, player);
            if (correctTool) {
                player.awardStat(Stats.BLOCK_MINED.get(state.getBlock()));
                player.causeFoodExhaustion(.005F);
                // Do not invoke whole-bundle loot, which would drop the surviving lanes.
                Block.popResource(level, position, removed);
            }
        }
        resync(player, position);
        return Result.REMOVED;
    }

    private static void resync(ServerPlayer player, BlockPos position) {
        var level = player.serverLevel();
        level.destroyBlockProgress(player.getId(), position, -1);
        player.connection.send(new ClientboundBlockUpdatePacket(level, position));
        var entity = level.getBlockEntity(position);
        var update = entity == null ? null : entity.getUpdatePacket();
        if (update != null) player.connection.send(update);
    }
}
