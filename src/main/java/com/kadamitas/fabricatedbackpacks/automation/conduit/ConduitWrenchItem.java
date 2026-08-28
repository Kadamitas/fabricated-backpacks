package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineMenus;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Physical-part interaction: endpoint settings, direct link changes, or one strand's removal. */
public final class ConduitWrenchItem extends Item {
    public ConduitWrenchItem(Properties properties) { super(properties); }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        super.appendHoverText(stack, context, lines, flags);
        lines.add(Component.translatable("tooltip.fabricated_backpacks.conduit_wrench"));
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        var target = context.getLevel().getBlockEntity(context.getClickedPos());
        if (!(target instanceof ConduitBundleBlockEntity) && !(target instanceof SteamEngineBlockEntity)) return InteractionResult.PASS;
        Player player = context.getPlayer();
        if (player == null || player.isSpectator() || !context.getLevel().mayInteract(player, context.getClickedPos())
                || !player.mayUseItemAt(context.getClickedPos(), context.getClickedFace(), context.getItemInHand())) return InteractionResult.FAIL;
        if (target instanceof SteamEngineBlockEntity engine) {
            if (player instanceof ServerPlayer server) SteamEngineMenus.openSides(server, engine, context.getClickedFace());
            return InteractionResult.SUCCESS;
        }
        var bundle = (ConduitBundleBlockEntity) target;
        if (!context.getLevel().isClientSide) bundle.refreshVisual();
        Vec3 local = context.getClickLocation().subtract(Vec3.atLowerCornerOf(context.getClickedPos()));
        var part = ConduitGeometry.hitPart(bundle.visualState(), local, context.getClickedFace()).orElse(null);
        if (part == null || !bundle.has(part.kind())) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server && bundle.stillValid(player)) {
            if (player.isShiftKeyDown()) {
                boolean water = bundle.getBlockState().getValue(ConduitBundleBlock.WATERLOGGED);
                ItemStack removed = bundle.remove(part.kind());
                if (bundle.installedMask() == 0) context.getLevel().setBlock(context.getClickedPos(),
                        water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                if (!removed.isEmpty() && !player.getInventory().add(removed)) player.drop(removed, false);
            } else if (part.role() == ConduitGeometry.Role.ENDPOINT && part.side() != null) {
                ConduitMenus.open(server, bundle, part.side());
            } else if (part.role() == ConduitGeometry.Role.HUB) {
                repair(bundle, part.kind(), context.getClickedFace(), server, context.getItemInHand());
            } else if (part.side() != null) {
                Direction side = part.side();
                boolean internal = (bundle.visualState().neighborMask(side) & part.kind().mask()) != 0;
                bundle.setMode(part.kind(), side, internal ? ConduitMode.DISABLED : bundle.mode(part.kind(), side).next());
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void repair(ConduitBundleBlockEntity bundle, ConduitKind kind, Direction face,
                               ServerPlayer player, ItemStack wrench) {
        if (bundle.mode(kind, face) == ConduitMode.DISABLED) bundle.setMode(kind, face, ConduitMode.defaultFor(kind));
        var level = player.serverLevel();
        var neighbor = bundle.getBlockPos().relative(face);
        // An internal cut retracts both rendered ends. Either hub may repair it, but never across
        // an unloaded chunk or a neighbour the player cannot configure.
        if (level.hasChunkAt(neighbor) && level.getBlockEntity(neighbor) instanceof ConduitBundleBlockEntity other
                && other.has(kind) && other.stillValid(player)
                && player.mayUseItemAt(neighbor, face.getOpposite(), wrench)
                && other.mode(kind, face.getOpposite()) == ConduitMode.DISABLED) {
            other.setMode(kind, face.getOpposite(), ConduitMode.defaultFor(kind));
        }
        ConduitNetworks.neighborChanged(player.serverLevel(), bundle.getBlockPos());
        bundle.refreshVisual();
    }
}
