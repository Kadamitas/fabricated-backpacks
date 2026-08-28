package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.Optional;

/** A bounded intent for the currently open physical face. No position, face, stack or quantity is supplied. */
public record ConduitFilterAction(int containerId, ConduitKind kind, Operation operation,
                                  int index, Optional<Identifier> resource) implements CustomPacketPayload {
    public enum Operation { SET_MODE, SET_ENTRY, CLEAR_ENTRY }
    public static final Type<ConduitFilterAction> TYPE = new Type<>(BackpackRegistry.id("conduit_filter_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConduitFilterAction> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ConduitFilterAction::containerId,
            ByteBufCodecs.VAR_INT.map(ConduitFilterAction::decodeKind, ConduitKind::ordinal), ConduitFilterAction::kind,
            ByteBufCodecs.VAR_INT.map(ConduitFilterAction::decodeOperation, Operation::ordinal), ConduitFilterAction::operation,
            ByteBufCodecs.VAR_INT, ConduitFilterAction::index,
            ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(ConduitFilter.MAX_IDENTIFIER_LENGTH)
                    .map(Identifier::parse, Identifier::toString)), ConduitFilterAction::resource,
            ConduitFilterAction::new);

    public ConduitFilterAction {
        if (containerId < 0) throw new IllegalArgumentException("Invalid conduit menu identity");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(resource, "resource");
        if (kind == ConduitKind.ENERGY) throw new IllegalArgumentException("Energy has no resource filter");
        int bound = operation == Operation.SET_MODE ? ConduitFilterMode.values().length : ConduitFilter.SLOT_COUNT;
        if (index < 0 || index >= bound) throw new IllegalArgumentException("Invalid conduit filter action index");
        if (resource.isPresent() != (operation == Operation.SET_ENTRY))
            throw new IllegalArgumentException("Only a ghost entry supplies an identifier");
        if (resource.isPresent() && resource.orElseThrow().toString().length() > ConduitFilter.MAX_IDENTIFIER_LENGTH)
            throw new IllegalArgumentException("Conduit filter identifier is too long");
    }
    private static ConduitKind decodeKind(int value) {
        if (value < 0 || value >= 2) throw new DecoderException("Invalid filtered conduit kind");
        return ConduitKind.values()[value];
    }
    private static Operation decodeOperation(int value) {
        if (value < 0 || value >= Operation.values().length) throw new DecoderException("Invalid conduit filter operation");
        return Operation.values()[value];
    }
    @Override public Type<ConduitFilterAction> type() { return TYPE; }
}
