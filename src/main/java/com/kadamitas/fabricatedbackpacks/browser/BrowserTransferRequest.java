package com.kadamitas.fabricatedbackpacks.browser;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/** Only identities are requested; the correlation ID never authorizes inventory changes. */
public record BrowserTransferRequest(long epoch, int containerId, Identifier recipe, long requestId, boolean maximum) implements CustomPacketPayload {
    public static final Type<BrowserTransferRequest> TYPE = new Type<>(BackpackRegistry.id("browser_transfer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BrowserTransferRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, BrowserTransferRequest::epoch, ByteBufCodecs.VAR_INT, BrowserTransferRequest::containerId,
            Identifier.STREAM_CODEC, BrowserTransferRequest::recipe, ByteBufCodecs.VAR_LONG, BrowserTransferRequest::requestId,
            ByteBufCodecs.BOOL, BrowserTransferRequest::maximum,
            BrowserTransferRequest::new);
    public BrowserTransferRequest {
        Objects.requireNonNull(recipe);
        if (epoch < 1 || containerId < 0 || requestId < 1) throw new IllegalArgumentException("Invalid browser transfer request");
    }
    public BrowserTransferRequest(long epoch, int containerId, Identifier recipe, long requestId) {
        this(epoch, containerId, recipe, requestId, false);
    }
    @Override public Type<BrowserTransferRequest> type() { return TYPE; }
}
