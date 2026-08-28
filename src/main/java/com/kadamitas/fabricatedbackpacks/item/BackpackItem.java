package com.kadamitas.fabricatedbackpacks.item;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

public final class BackpackItem extends BlockItem {
    public BackpackItem(Block block, Properties properties) { super(block, properties); }
    @Override public boolean canFitInsideContainerItems() {
        return com.kadamitas.fabricatedbackpacks.config.BackpackConfig.get().storage().allowBagInContainerItems();
    }
    @Override public boolean overrideStackedOnOther(ItemStack stack, net.minecraft.world.inventory.Slot slot,
            net.minecraft.world.inventory.ClickAction action, Player player) {
        return com.kadamitas.fabricatedbackpacks.gameplay.BackpackStashing.fromSlot(stack, slot, action, player);
    }
    @Override public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack carried, net.minecraft.world.inventory.Slot slot,
            net.minecraft.world.inventory.ClickAction action, Player player, net.minecraft.world.entity.SlotAccess cursor) {
        return com.kadamitas.fabricatedbackpacks.gameplay.BackpackStashing.fromCursor(stack, carried, slot, action, player, cursor);
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, net.minecraft.world.entity.LivingEntity target, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer && com.kadamitas.fabricatedbackpacks.gameplay.MobCapture.capture(
                com.kadamitas.fabricatedbackpacks.storage.BagInventory.of(stack), target, serverPlayer)) return InteractionResult.SUCCESS;
        return InteractionResult.PASS;
    }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) BackpackMenus.openHeld(serverPlayer, hand);
        return InteractionResult.SUCCESS;
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        BlockState clicked = context.getLevel().getBlockState(context.getClickedPos());
        var colors = context.getItemInHand().getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                net.minecraft.world.item.component.CustomModelData.EMPTY);
        boolean dyed = colors.getColor(0) != null && colors.getColor(0) != 0xB97843
                || colors.getColor(1) != null && colors.getColor(1) != 0x503B36;
        if (player != null && dyed && clicked.is(net.minecraft.world.level.block.Blocks.WATER_CAULDRON)
                && context.getLevel().mayInteract(player, context.getClickedPos())) {
            if (!context.getLevel().isClientSide()) {
                context.getItemInHand().set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                        new net.minecraft.world.item.component.CustomModelData(colors.floats(), colors.flags(), colors.strings(), java.util.List.of(0xB97843, 0x503B36)));
                net.minecraft.world.level.block.LayeredCauldronBlock.lowerFillLevel(clicked, context.getLevel(), context.getClickedPos());
            }
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer && player.isShiftKeyDown()
                && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof net.minecraft.world.Container target
                && target.stillValid(player) && context.getLevel().mayInteract(player, context.getClickedPos())
                && !com.kadamitas.fabricatedbackpacks.config.RuleMatchers.block(clicked,
                        com.kadamitas.fabricatedbackpacks.config.BackpackConfig.get().storage().blockedInteractions())) {
            var bag = com.kadamitas.fabricatedbackpacks.storage.BagInventory.of(context.getItemInHand());
            boolean deposit = bag.installedUpgrades().stream().anyMatch(upgrade -> upgrade.kind().family().equals("deposit"));
            boolean restock = bag.installedUpgrades().stream().anyMatch(upgrade -> upgrade.kind().family().equals("restock"));
            if (deposit || restock) {
                com.kadamitas.fabricatedbackpacks.world.MobLoot.materialize(bag, serverPlayer.level(), context.getClickedPos(), serverPlayer);
                com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine.transfer(bag, target, deposit);
                return InteractionResult.SUCCESS;
            }
        }
        if (player == null || player.isShiftKeyDown()) return super.useOn(context);
        return use(context.getLevel(), player, context.getHand());
    }
    @Override protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        boolean placed = super.placeBlock(context, state);
        if (placed && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof BackpackBlockEntity backpack) {
            ItemStack placedStack = context.getItemInHand().copyWithCount(1);
            if (context.getPlayer() != null && context.getPlayer().getAbilities().instabuild)
                placedStack = com.kadamitas.fabricatedbackpacks.storage.BackpackCopies.fork(placedStack);
            backpack.setStack(placedStack);
        }
        return placed;
    }
    @Override protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, Player player, ItemStack stack, BlockState state) {
        return false; // The live bag is installed by placeBlock; item commands cannot replace its block entity.
    }
    @Override public java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> getTooltipImage(ItemStack stack) {
        return java.util.Optional.of(BackpackTooltip.from(stack));
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                           Consumer<Component> lines, TooltipFlag flags) {
        BackpackRegistry.tier(stack).ifPresent(tier -> {
            var configured = com.kadamitas.fabricatedbackpacks.config.BackpackConfig.get().capacity(tier);
            int slots = Math.max(configured.slots(), stack.getOrDefault(com.kadamitas.fabricatedbackpacks.storage.BagComponents.CONTENTS,
                    com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot.EMPTY).size());
            int upgrades = Math.max(configured.upgrades(), stack.getOrDefault(com.kadamitas.fabricatedbackpacks.storage.BagComponents.UPGRADES,
                    com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot.EMPTY).size());
            lines.accept(Component.translatable("tooltip.fabricated_backpacks.capacity", slots));
            lines.accept(Component.translatable("tooltip.fabricated_backpacks.upgrade_slots", upgrades));
        });
        lines.accept(Component.translatable("tooltip.fabricated_backpacks.open"));
        lines.accept(Component.translatable("tooltip.fabricated_backpacks.equip"));
    }
}
