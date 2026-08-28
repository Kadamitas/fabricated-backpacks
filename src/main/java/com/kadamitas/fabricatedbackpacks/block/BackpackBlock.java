package com.kadamitas.fabricatedbackpacks.block;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public final class BackpackBlock extends BaseEntityBlock implements SimpleWaterloggedBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final MapCodec<BackpackBlock> CODEC = simpleCodec(BackpackBlock::new);
    // Five closed-model groups cover the shell, front pouch, side pouches and
    // handle. The visual lid does not change collision while viewers open it.
    private static final Map<Direction, VoxelShape> SHAPES = rotateHorizontal(Shapes.or(
            Block.box(2.75, .5, 3.75, 13.25, 12.25, 13.125),
            Block.box(3.75, 2.125, 1.5, 12.25, 7.375, 5.125),
            Block.box(.75, 2, 5.5, 3.25, 7.25, 11),
            Block.box(12.75, 2, 5.5, 15.25, 7.25, 11),
            Block.box(6, 12.25, 10.5, 10, 13.5, 11.25)));

    private static Map<Direction, VoxelShape> rotateHorizontal(VoxelShape north) {
        var result = new java.util.EnumMap<Direction, VoxelShape>(Direction.class);
        VoxelShape shape = north;
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            result.put(direction, shape);
            VoxelShape rotated = Shapes.empty();
            for (var box : shape.toAabbs()) rotated = Shapes.or(rotated,
                    Shapes.box(1 - box.maxZ, box.minY, box.minX, 1 - box.minZ, box.maxY, box.maxX));
            shape = rotated;
        }
        return Map.copyOf(result);
    }

    public BackpackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(OPEN, false).setValue(WATERLOGGED, false));
    }
    @Override protected MapCodec<BackpackBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, OPEN, WATERLOGGED); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPES.get(state.getValue(FACING)); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).is(Fluids.WATER));
    }
    @Override protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                              LevelAccessor level, BlockPos pos, BlockPos neighbor) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return super.updateShape(state, direction, neighborState, level, pos, neighbor);
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new BackpackBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? createTickerHelper(type, BackpackRegistry.BLOCK_ENTITY, BackpackBlockEntity::clientTick)
                : createTickerHelper(type, BackpackRegistry.BLOCK_ENTITY, BackpackBlockEntity::tick);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof BackpackBlockEntity entity)) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty() && entity.viewers() == 0) {
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, entity.stack().copy());
                level.removeBlock(pos, false);
            } else BackpackMenus.openPlaced(serverPlayer, entity);
        }
        return InteractionResult.SUCCESS;
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof BackpackBlockEntity entity
                ? List.of(entity.stack().copy()) : List.of(new ItemStack(this));
    }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        // Native Ctrl-pick adds copied contents through BackpackBlockEntity.saveToItem.
        return new ItemStack(this);
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && level.getBlockEntity(pos) instanceof BackpackBlockEntity entity
                && (!entity.inventory().isEmpty() || !entity.inventory().upgrades().isEmpty())) popResource(level, pos, entity.stack().copy());
        return super.playerWillDestroy(level, pos, state, player);
    }
    @Override protected void onExplosionHit(BlockState state, Level level, BlockPos pos, Explosion explosion, BiConsumer<ItemStack, BlockPos> consumer) {
        if (level.getBlockEntity(pos) instanceof BackpackBlockEntity entity && entity.inventory().has(UpgradeKind.EVERLASTING)) return;
        super.onExplosionHit(state, level, pos, explosion, consumer);
    }
    @Override protected boolean hasAnalogOutputSignal(BlockState state) { return true; }
    @Override protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof BackpackBlockEntity entity)) return 0;
        double fullness = 0;
        int usable = 0;
        var bag = entity.inventory();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            if (bag.blocked(slot)) continue;
            usable++;
            ItemStack item = bag.getItem(slot);
            if (!item.isEmpty()) fullness += (double) item.getCount() / bag.capacity(item);
        }
        return usable == 0 || fullness == 0 ? 0 : 1 + (int) Math.floor(14 * fullness / usable);
    }
}
