package com.kadamitas.fabricatedbackpacks.network;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Requests only an intent. Inventory contents and generated results always come from the server. */
public record MenuAction(int containerId, String action, int index, int value, String text) implements CustomPacketPayload {
    public static final Type<MenuAction> TYPE = new Type<>(BackpackRegistry.id("menu_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MenuAction> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MenuAction::containerId, ByteBufCodecs.stringUtf8(64), MenuAction::action,
            ByteBufCodecs.VAR_INT, MenuAction::index, ByteBufCodecs.VAR_INT, MenuAction::value,
            ByteBufCodecs.stringUtf8(256), MenuAction::text, MenuAction::new);
    @Override public Type<MenuAction> type() { return TYPE; }
}
