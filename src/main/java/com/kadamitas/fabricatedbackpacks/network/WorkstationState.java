package com.kadamitas.fabricatedbackpacks.network;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.component.CustomData;

/** Only the current portable workstation receives its server-owned preferences. */
public record WorkstationState(int containerId, CustomData settings) implements CustomPacketPayload {
    public static final Type<WorkstationState> TYPE = new Type<>(BackpackRegistry.id("workstation_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorkstationState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WorkstationState::containerId,
            ByteBufCodecs.fromCodec(CustomData.CODEC), WorkstationState::settings, WorkstationState::new);
    @Override public Type<WorkstationState> type() { return TYPE; }
}
