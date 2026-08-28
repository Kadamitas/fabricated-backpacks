package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.kadamitas.fabricatedbackpacks.automation.AutomationBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** Installing an additional lane preserves the physical block entity and every other lane's settings. */
public final class ConduitItem extends AutomationBlockItem {
    private final ConduitKind kind;
    public ConduitItem(Block block, Properties properties, ConduitKind kind) {
        super(block, properties);
        this.kind = Objects.requireNonNull(kind);
    }
    public ConduitKind kind() { return kind; }

    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().getBlockEntity(context.getClickedPos()) instanceof ConduitBundleBlockEntity bundle) {
            Player player = context.getPlayer();
            if (player == null || player.isSpectator() || !context.getLevel().mayInteract(player, context.getClickedPos())
                    || !player.mayUseItemAt(context.getClickedPos(), context.getClickedFace(), context.getItemInHand())) return InteractionResult.FAIL;
            if (bundle.has(kind)) return super.useOn(context);
            if (!context.getLevel().isClientSide && bundle.install(kind) && !player.getAbilities().instabuild) context.getItemInHand().shrink(1);
            return InteractionResult.SUCCESS;
        }
        return super.useOn(context);
    }
    @Override protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        if (!super.placeBlock(context, state)) return false;
        if (!context.getLevel().isClientSide && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof ConduitBundleBlockEntity bundle)
            bundle.install(kind);
        return true;
    }
    @Override protected boolean updateCustomBlockEntityTag(BlockPos position, Level level, Player player, ItemStack stack, BlockState state) {
        return false;
    }
}
