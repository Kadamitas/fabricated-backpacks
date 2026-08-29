package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;

/** The two public policies of one open face, without any machine inventory or stored amounts. */
public record ConduitFilterState(int containerId, Direction face, ConduitFilter itemFilter,
                                 ConduitFilter fluidFilter) implements CustomPacketPayload {
    public static final Type<ConduitFilterState> TYPE = new Type<>(BackpackRegistry.id("conduit_filter_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConduitFilterState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ConduitFilterState::containerId,
            ByteBufCodecs.VAR_INT.map(ConduitFilterState::decodeFace, Direction::ordinal), ConduitFilterState::face,
            ConduitFilter.STREAM_CODEC, ConduitFilterState::itemFilter,
            ConduitFilter.STREAM_CODEC, ConduitFilterState::fluidFilter,
            ConduitFilterState::new);

    public ConduitFilterState {
        if (containerId < 0) throw new IllegalArgumentException("Invalid conduit menu identity");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(itemFilter, "itemFilter");
        Objects.requireNonNull(fluidFilter, "fluidFilter");
    }
    private static Direction decodeFace(int value) {
        if (value < 0 || value >= Direction.values().length) throw new DecoderException("Invalid conduit filter face");
        return Direction.values()[value];
    }
    @Override public Type<ConduitFilterState> type() { return TYPE; }
}
