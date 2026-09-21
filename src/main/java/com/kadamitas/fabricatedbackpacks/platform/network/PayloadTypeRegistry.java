package com.kadamitas.fabricatedbackpacks.platform.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PayloadTypeRegistry(boolean client) {
    public static PayloadTypeRegistry serverboundPlay() { return new PayloadTypeRegistry(false); }
    public static PayloadTypeRegistry clientboundPlay() { return new PayloadTypeRegistry(true); }
    public <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) { NativeNetworking.codec(type, codec, client); }
}
