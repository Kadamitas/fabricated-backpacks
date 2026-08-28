package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

public final class ConduitBundleBlock extends BaseEntityBlock implements SimpleWaterloggedBlock {
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final MapCodec<ConduitBundleBlock> CODEC = simpleCodec(ConduitBundleBlock::new);

    public ConduitBundleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WATERLOGGED, false));
    }
    @Override protected MapCodec<ConduitBundleBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(WATERLOGGED); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).is(Fluids.WATER));
    }
    @Override protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                               LevelAccessor level, BlockPos pos, BlockPos neighbor) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return super.updateShape(state, direction, neighborState, level, pos, neighbor);
    }
    @Override public BlockEntity newBlockEntity(BlockPos position, BlockState state) { return new ConduitBundleBlockEntity(position, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, AutomationRegistry.CONDUIT_BUNDLE_ENTITY, ConduitBundleBlockEntity::tick);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        return ConduitGeometry.shape(level.getBlockEntity(position) instanceof ConduitBundleBlockEntity bundle
                ? bundle.visualState() : ConduitVisualState.EMPTY);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos position,
                                                     Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof ConduitItem || stack.getItem() instanceof ConduitWrenchItem)
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(position) instanceof ConduitBundleBlockEntity bundle)) return InteractionResult.PASS;
        if (level instanceof ServerLevel) bundle.refreshVisual();
        var part = ConduitGeometry.hitPart(bundle.visualState(), hit.getLocation().subtract(Vec3.atLowerCornerOf(position)),
                hit.getDirection()).orElse(null);
        if (part == null || part.role() != ConduitGeometry.Role.ENDPOINT || part.side() == null) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server && bundle.stillValid(player)) ConduitMenus.open(server, bundle, part.side());
        return InteractionResult.SUCCESS;
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof ConduitBundleBlockEntity bundle ? bundle.drops() : List.of();
    }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos position, BlockState state) {
        if (level.getBlockEntity(position) instanceof ConduitBundleBlockEntity bundle)
            for (ConduitKind kind : ConduitKind.values()) if (bundle.has(kind)) return new ItemStack(AutomationRegistry.conduit(kind));
        return new ItemStack(AutomationRegistry.ITEM_CONDUIT);
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos position, Block block, BlockPos sourcePosition, boolean moved) {
        if (level instanceof ServerLevel server && level.getBlockEntity(position) instanceof ConduitBundleBlockEntity bundle) {
            ConduitNetworks.neighborChanged(server, position);
            bundle.refreshVisual();
        }
    }
}
