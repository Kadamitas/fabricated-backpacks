package com.kadamitas.fabricatedbackpacks.network;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ServerRules(String json) implements CustomPacketPayload {
    public static final Type<ServerRules> TYPE = new Type<>(BackpackRegistry.id("server_rules"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerRules> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(1_048_576), ServerRules::json, ServerRules::new);
    @Override public Type<ServerRules> type() { return TYPE; }
}
