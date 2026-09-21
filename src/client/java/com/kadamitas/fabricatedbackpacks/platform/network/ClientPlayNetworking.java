package com.kadamitas.fabricatedbackpacks.platform.network;

import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class ClientPlayNetworking {
    public record Context(LocalPlayer player) { public Minecraft client() { return Minecraft.getInstance(); } }
    private ClientPlayNetworking() {}
    public static void send(CustomPacketPayload packet) { ClientPacketDistributor.sendToServer(packet); }
    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        var connection = Minecraft.getInstance().getConnection(); return connection != null && connection.hasChannel(type);
    }
    @SuppressWarnings("unchecked")
    public static <T extends CustomPacketPayload> void registerGlobalReceiver(CustomPacketPayload.Type<T> type, BiConsumer<T, Context> handler) {
        NativeNetworking.CLIENT.put(type.id(), (packet, context) -> handler.accept((T) packet, new Context((LocalPlayer) context.player())));
    }
}
