package com.kadamitas.fabricatedbackpacks.network;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Native pick intent; the server rechecks reach and derives the item from the live block. */
public record PickBackpackItem(BlockPos pos, boolean includeData) implements CustomPacketPayload {
    public PickBackpackItem { pos = pos.immutable(); }
    public PickBackpackItem(BlockPos pos) { this(pos, false); }
    public static final Type<PickBackpackItem> TYPE = new Type<>(BackpackRegistry.id("pick_backpack_item"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PickBackpackItem> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, PickBackpackItem::pos,
                    net.minecraft.network.codec.ByteBufCodecs.BOOL, PickBackpackItem::includeData, PickBackpackItem::new);
    @Override public Type<PickBackpackItem> type() { return TYPE; }
}
