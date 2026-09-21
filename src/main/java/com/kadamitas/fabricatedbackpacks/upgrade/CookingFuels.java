package com.kadamitas.fabricatedbackpacks.upgrade;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

import java.util.Optional;

/** Component-driven fuels shared by portable cooking and the steam engine. */
public final class CookingFuels {
    private CookingFuels() { }

    public static boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.COOKING_FUEL);
    }

    public static int burnDuration(ServerLevel level, ItemStack stack, Container container) {
        var params = new LootParams.Builder(level).withParameter(LootContextParams.CONTAINER, container);
        if (container instanceof net.minecraft.world.level.block.entity.BlockEntity block) {
            params.withParameter(LootContextParams.BLOCK_ENTITY, block)
                    .withParameter(LootContextParams.BLOCK_STATE, block.getBlockState())
                    .withParameter(LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.atCenterOf(block.getBlockPos()));
        }
        var keys = new net.minecraft.util.context.ContextKeySet.Builder()
                .required(LootContextParams.CONTAINER)
                .optional(LootContextParams.BLOCK_ENTITY)
                .optional(LootContextParams.BLOCK_STATE)
                .optional(LootContextParams.ORIGIN).build();
        var context = new LootContext.Builder(params.create(keys)).create(Optional.empty());
        return Math.max(0, ResolvableInt.getFromItem(stack, DataComponents.COOKING_FUEL,
                CookingFuel::burnTime, context, 0));
    }
}
