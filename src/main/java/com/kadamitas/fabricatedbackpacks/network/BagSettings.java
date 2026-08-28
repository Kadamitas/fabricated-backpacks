package com.kadamitas.fabricatedbackpacks.network;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.component.CustomData;

public record BagSettings(int containerId, CustomData settings, InventorySnapshot memory) implements CustomPacketPayload {
    public static final Type<BagSettings> TYPE = new Type<>(BackpackRegistry.id("bag_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BagSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BagSettings::containerId, CustomData.STREAM_CODEC, BagSettings::settings,
            ByteBufCodecs.fromCodecWithRegistries(InventorySnapshot.CODEC), BagSettings::memory, BagSettings::new);
    @Override public Type<BagSettings> type() { return TYPE; }
}
