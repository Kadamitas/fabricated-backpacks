package com.kadamitas.fabricatedbackpacks.automation.engine;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitWrenchItem;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

public final class SteamEngineBlock extends BaseEntityBlock {
    public static final MapCodec<SteamEngineBlock> CODEC = simpleCodec(SteamEngineBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public SteamEngineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, false));
    }
    @Override protected MapCodec<SteamEngineBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, ACTIVE); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos position, BlockState state) { return new SteamEngineBlockEntity(position, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return createTickerHelper(type, AutomationRegistry.STEAM_ENGINE_ENTITY, SteamEngineBlockEntity::clientTick);
        return createTickerHelper(type, AutomationRegistry.STEAM_ENGINE_ENTITY, (world, position, blockState, engine) -> {
            if (world instanceof ServerLevel server) SteamEngineBlockEntity.tick(server, position, blockState, engine);
        });
    }
    @Override protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos position,
                                                    Player player, InteractionHand hand, BlockHitResult hit) {
        return stack.getItem() instanceof ConduitWrenchItem ? InteractionResult.PASS : super.useItemOn(stack, state, level, position, player, hand, hit);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(position) instanceof SteamEngineBlockEntity engine)) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server && !server.isSpectator() && engine.stillValid(server)) server.openMenu(engine);
        return InteractionResult.SUCCESS;
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder parameters) {
        return List.of(parameters.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof SteamEngineBlockEntity engine
                ? engine.dropStack() : new ItemStack(AutomationRegistry.STEAM_ENGINE_ITEM));
    }
    @Override protected ItemStack getCloneItemStack(LevelReader level, BlockPos position, BlockState state, boolean includeData) {
        return includeData && level.getBlockEntity(position) instanceof SteamEngineBlockEntity engine
                ? engine.dropStack() : new ItemStack(AutomationRegistry.STEAM_ENGINE_ITEM);
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos position, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops()
                && level.getBlockEntity(position) instanceof SteamEngineBlockEntity engine && engine.hasStoredContents())
            popResource(level, position, engine.dropStack());
        return super.playerWillDestroy(level, position, state, player);
    }
    @Override protected boolean hasAnalogOutputSignal(BlockState state) { return true; }
    @Override protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos position, Direction side) {
        if (!(level.getBlockEntity(position) instanceof SteamEngineBlockEntity engine) || engine.snapshot().energy() == 0) return 0;
        return 1 + (int) Math.min(14, Math.floor(14.0 * engine.snapshot().energy() / engine.energyCapacity()));
    }
}
