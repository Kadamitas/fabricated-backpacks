package com.kadamitas.fabricatedbackpacks.browser;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BrowserCatalogRequest(long epoch, int offset) implements CustomPacketPayload {
    public static final Type<BrowserCatalogRequest> TYPE = new Type<>(BackpackRegistry.id("browser_catalog_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BrowserCatalogRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, BrowserCatalogRequest::epoch, ByteBufCodecs.VAR_INT, BrowserCatalogRequest::offset, BrowserCatalogRequest::new);
    public BrowserCatalogRequest {
        if (epoch < 0 || offset < 0 || offset > BrowserCatalogPage.MAX_ENTRIES) throw new IllegalArgumentException("Invalid browser catalog cursor");
    }
    @Override public Type<BrowserCatalogRequest> type() { return TYPE; }
}
