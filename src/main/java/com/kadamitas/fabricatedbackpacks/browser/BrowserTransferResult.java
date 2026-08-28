package com.kadamitas.fabricatedbackpacks.browser;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BrowserTransferResult(long requestId, boolean success, String messageKey) implements CustomPacketPayload {
    public static final Type<BrowserTransferResult> TYPE = new Type<>(BackpackRegistry.id("browser_transfer_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BrowserTransferResult> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, BrowserTransferResult::requestId, ByteBufCodecs.BOOL, BrowserTransferResult::success,
            ByteBufCodecs.stringUtf8(128), BrowserTransferResult::messageKey, BrowserTransferResult::new);
    public BrowserTransferResult {
        if (requestId < 1 || messageKey == null || messageKey.length() > 128) throw new IllegalArgumentException("Invalid browser transfer response");
    }
    @Override public Type<BrowserTransferResult> type() { return TYPE; }
}
