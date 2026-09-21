package com.kadamitas.fabricatedbackpacks.platform.network;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.payload.PayloadConnection;

public final class NativeNetworking {
    private static final PayloadConnection<CustomPacketPayload> BUILDER = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath("fabricated_backpacks", "main")).networkProtocolVersion(1).payloadChannel();
    private static Channel<CustomPacketPayload> channel;
    static final Map<Identifier, BiConsumer<CustomPacketPayload, CustomPayloadEvent.Context>> SERVER = new HashMap<>(), CLIENT = new HashMap<>();
    private NativeNetworking() {}
    public static void initialize(Object ignored) {}
    public static void finishRegistration() { if (channel == null) channel = BUILDER.play().bidirectional().build(); }
    public static Channel<CustomPacketPayload> channel() { if (channel == null) throw new IllegalStateException("Payload registration is not complete"); return channel; }
    @SuppressWarnings("unchecked")
    static <T extends CustomPacketPayload> void codec(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, boolean client) {
        if (channel != null) throw new IllegalStateException("Late native payload registration: " + type.id());
        BUILDER.play().flow(client ? PacketFlow.CLIENTBOUND : PacketFlow.SERVERBOUND)
                .add(type, (StreamCodec<RegistryFriendlyByteBuf, T>)codec, (packet, context) -> {
                    context.enqueueWork(() -> dispatch(client ? CLIENT : SERVER, packet, context));
                    context.setPacketHandled(true);
                });
    }
    private static void dispatch(Map<Identifier, BiConsumer<CustomPacketPayload, CustomPayloadEvent.Context>> handlers,
            CustomPacketPayload packet, CustomPayloadEvent.Context context) {
        var receiver = handlers.get(packet.type().id());
        if (receiver == null) throw new IllegalStateException("No native payload receiver for " + packet.type().id());
        receiver.accept(packet, context);
    }
}
