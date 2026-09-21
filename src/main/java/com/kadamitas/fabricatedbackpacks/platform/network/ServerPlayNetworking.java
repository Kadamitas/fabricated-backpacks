package com.kadamitas.fabricatedbackpacks.platform.network;

import java.util.function.BiConsumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkContext;
import net.minecraftforge.network.PacketDistributor;

public final class ServerPlayNetworking {
    public record Context(ServerPlayer player) { public MinecraftServer server() { return player.level().getServer(); } }
    private ServerPlayNetworking() {}
    public static void send(ServerPlayer player, CustomPacketPayload packet) { NativeNetworking.channel().send(packet, PacketDistributor.PLAYER.with(player)); }
    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return NetworkContext.get(player.connection.getConnection()).getRemoteChannels().contains(type.id());
    }
    @SuppressWarnings("unchecked")
    public static <T extends CustomPacketPayload> void registerGlobalReceiver(CustomPacketPayload.Type<T> type, BiConsumer<T, Context> handler) {
        NativeNetworking.SERVER.put(type.id(), (packet, context) -> { if (context.getSender() != null) handler.accept((T) packet, new Context(context.getSender())); });
    }
}
