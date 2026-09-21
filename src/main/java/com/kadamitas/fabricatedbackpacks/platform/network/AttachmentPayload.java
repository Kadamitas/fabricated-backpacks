package com.kadamitas.fabricatedbackpacks.platform.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-to-client only; private equipped inventory is sent exclusively to its owner. */
public record AttachmentPayload(int entityId, Identifier key, CompoundTag data) implements CustomPacketPayload {
    public static final Type<AttachmentPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fabricated_backpacks", "entity_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AttachmentPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AttachmentPayload::entityId, Identifier.STREAM_CODEC, AttachmentPayload::key,
            ByteBufCodecs.COMPOUND_TAG, AttachmentPayload::data, AttachmentPayload::new);
    @Override public Type<AttachmentPayload> type() { return TYPE; }
}
