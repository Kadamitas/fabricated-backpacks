package com.kadamitas.fabricatedbackpacks.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

public record BagOpeningData(ItemStack stack, int inventorySlot, BlockPos position, int source) {
    public static final int INVENTORY = 0, PLACED = 1, EQUIPPED = 2, LEASED = 3;
    public static final StreamCodec<RegistryFriendlyByteBuf, BagOpeningData> STREAM_CODEC = StreamCodec.composite(
            ItemStack.STREAM_CODEC, BagOpeningData::stack, ByteBufCodecs.VAR_INT, BagOpeningData::inventorySlot,
            BlockPos.STREAM_CODEC, BagOpeningData::position, ByteBufCodecs.VAR_INT, BagOpeningData::source, BagOpeningData::new);
}
