package com.kadamitas.fabricatedbackpacks.browser;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BrowserCatalogInvalidated() implements CustomPacketPayload {
    public static final BrowserCatalogInvalidated INSTANCE = new BrowserCatalogInvalidated();
    public static final Type<BrowserCatalogInvalidated> TYPE = new Type<>(BackpackRegistry.id("browser_catalog_invalidated"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BrowserCatalogInvalidated> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    @Override public Type<BrowserCatalogInvalidated> type() { return TYPE; }
}
