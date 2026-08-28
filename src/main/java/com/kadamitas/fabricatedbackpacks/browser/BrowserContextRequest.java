package com.kadamitas.fabricatedbackpacks.browser;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Asks which transfers the currently open server menu actually supports. */
public record BrowserContextRequest(int containerId) implements CustomPacketPayload {
    public static final Type<BrowserContextRequest> TYPE = new Type<>(BackpackRegistry.id("browser_context_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BrowserContextRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BrowserContextRequest::containerId, BrowserContextRequest::new);
    public BrowserContextRequest {
        if (containerId < 0) throw new IllegalArgumentException("Invalid browser context menu");
    }
    @Override public Type<BrowserContextRequest> type() { return TYPE; }
}
