package com.kadamitas.fabricatedbackpacks.platform.network;

import java.util.function.BiConsumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerPlayNetworking {
    public record Context(ServerPlayer player) { public MinecraftServer server() { return player.level().getServer(); } }
    private ServerPlayNetworking() {}
    public static void send(ServerPlayer player, CustomPacketPayload packet) { PacketDistributor.sendToPlayer(player, packet); }
    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) { return player.connection.hasChannel(type); }
    @SuppressWarnings("unchecked")
    public static <T extends CustomPacketPayload> void registerGlobalReceiver(CustomPacketPayload.Type<T> type, BiConsumer<T, Context> handler) {
        NativeNetworking.SERVER.put(type.id(), (packet, context) -> handler.accept((T) packet, new Context((ServerPlayer) context.player())));
    }
}
