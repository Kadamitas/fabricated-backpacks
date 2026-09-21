package com.kadamitas.fabricatedbackpacks.platform.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class NativeNetworking {
    private static final List<Consumer<PayloadRegistrar>> CODECS = new ArrayList<>();
    static final Map<Identifier, BiConsumer<CustomPacketPayload, IPayloadContext>> SERVER = new HashMap<>(), CLIENT = new HashMap<>();
    private NativeNetworking() {}
    public static void initialize(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> CODECS.forEach(register -> register.accept(event.registrar("1.0.0"))));
    }
    static <T extends CustomPacketPayload> void codec(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, boolean client) {
        CODECS.add(registrar -> {
            if (client) registrar.playToClient(type, codec, (packet, context) -> dispatch(CLIENT, packet, context));
            else registrar.playToServer(type, codec, (packet, context) -> dispatch(SERVER, packet, context));
        });
    }
    private static void dispatch(Map<Identifier, BiConsumer<CustomPacketPayload, IPayloadContext>> handlers, CustomPacketPayload packet, IPayloadContext context) {
        var receiver = handlers.get(packet.type().id());
        if (receiver == null) throw new IllegalStateException("No native payload receiver for " + packet.type().id());
        receiver.accept(packet, context);
    }
}
