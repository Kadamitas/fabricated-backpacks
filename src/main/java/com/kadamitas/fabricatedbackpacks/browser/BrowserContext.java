package com.kadamitas.fabricatedbackpacks.browser;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A UI hint, never a replacement for the server's checks on each transfer. */
public record BrowserContext(int containerId, BrowserWorkstation workstation, boolean limitedCrafting) implements CustomPacketPayload {
    public static final Type<BrowserContext> TYPE = new Type<>(BackpackRegistry.id("browser_context"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BrowserContext> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BrowserContext::containerId,
            ByteBufCodecs.VAR_INT.map(BrowserWorkstation::fromId, BrowserWorkstation::ordinal), BrowserContext::workstation,
            ByteBufCodecs.BOOL, BrowserContext::limitedCrafting, BrowserContext::new);
    public BrowserContext {
        if (containerId < 0) throw new IllegalArgumentException("Invalid browser context menu");
        java.util.Objects.requireNonNull(workstation);
    }
    public BrowserContext(int containerId, boolean craftingTransfer, boolean limitedCrafting) {
        this(containerId, craftingTransfer ? BrowserWorkstation.CRAFTING : BrowserWorkstation.NONE, limitedCrafting);
    }
    public boolean craftingTransfer() { return workstation == BrowserWorkstation.CRAFTING; }
    @Override public Type<BrowserContext> type() { return TYPE; }
}
